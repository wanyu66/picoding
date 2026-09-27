import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.api.model.enums.*;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.notify.help.MsgNotifyHelper;
import com.github.paicoding.forum.service.notify.service.DomainEventOutboxService;
import com.github.paicoding.forum.service.notify.service.impl.DomainEventOutboxServiceImpl;
import com.github.paicoding.forum.service.user.repository.dao.UserFootDao;
import com.github.paicoding.forum.service.user.repository.mapper.UserFootMapper;
import com.github.paicoding.forum.service.user.service.UserFootService;
import com.github.paicoding.forum.service.user.service.userfoot.UserFootServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real service, mapper, transaction and outbox SQL; only dedicated empty MySQL. */
public class UserFootConcurrencyHarness {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path OUT = Paths.get(System.getenv("BENCH_RESULTS"));
    static JdbcTemplate db;
    static UserFootService service;
    static final List<Map<String, Object>> results = new ArrayList<>();
    @Configuration @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = UserFootMapper.class)
    static class Config {
        @Bean(destroyMethod = "close") HikariDataSource dataSource() {
            HikariDataSource d = new HikariDataSource();
            d.setJdbcUrl("jdbc:mysql://127.0.0.1:13436/bench_userfoot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            d.setUsername("root"); d.setPassword(System.getenv("BENCH_TEST_PASSWORD"));
            d.setMaximumPoolSize(40); d.setMinimumIdle(32); return d;
        }
        @Bean JdbcTemplate jdbc(DataSource d) { return new JdbcTemplate(d); }
        @Bean PlatformTransactionManager tx(DataSource d) { return new DataSourceTransactionManager(d); }
        @Bean SqlSessionFactory factory(DataSource d) throws Exception {
            MybatisSqlSessionFactoryBean f = new MybatisSqlSessionFactoryBean(); f.setDataSource(d);
            MybatisConfiguration c = new MybatisConfiguration(); c.setMapUnderscoreToCamelCase(true);
            f.setConfiguration(c); return f.getObject();
        }
        @Bean UserFootDao dao() { return new UserFootDao(); }
        @Bean UserFootService foot(UserFootDao d) { return new UserFootServiceImpl(d); }
        @Bean DomainEventOutboxService outbox(JdbcTemplate d) { return new DomainEventOutboxServiceImpl(d); }
        @Bean MsgNotifyHelper notifyHelper() { return new MsgNotifyHelper(); }
        @Bean SpringUtil springUtil() { return new SpringUtil(); }
    }
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]); return m;
    }
    static void write(String name, Object value) throws Exception { JSON.writerWithDefaultPrettyPrinter().writeValue(OUT.resolve(name).toFile(), value); }
    static int count(String sql, Object... args) { return db.queryForObject(sql, Integer.class, args); }
    static void scenario(String name, long article, OperateTypeEnum operation, int expectedEvents, int expectedState, String column) throws Exception {
        final int concurrency = 32, calls = 64;
        int before = count("SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", article);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency), go = new CountDownLatch(1);
        List<Map<String, Object>> raw = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        long start = System.nanoTime();
        try {
            for (int t = 0; t < concurrency; t++) {
                final int worker = t;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        for (int i = worker; i < calls; i += concurrency) {
                            long began = System.nanoTime(); Map<String, Object> r = map("index", i);
                            try { service.favorArticleComment(DocumentTypeEnum.ARTICLE, article, 2L, 1L, operation); r.put("ok", true); }
                            catch (Exception e) { r.put("ok", false); r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
                            r.put("ms", (System.nanoTime() - began) / 1e6); raw.add(r);
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("worker start timeout");
            start = System.nanoTime(); go.countDown();
            for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        long successes = raw.stream().filter(r -> Boolean.TRUE.equals(r.get("ok"))).count();
        int events = count("SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=?", article) - before;
        int state = count("SELECT " + column + " FROM user_foot WHERE user_id=1 AND document_id=? AND document_type=1", article);
        Map<String, Object> result = map("scenario", name, "concurrency", concurrency, "calls", calls, "successes", successes,
                "durationMs", (System.nanoTime() - start) / 1e6, "outboxEvents", events, "expectedEvents", expectedEvents,
                "state", state, "expectedState", expectedState, "passed", successes == calls && events == expectedEvents && state == expectedState);
        raw.sort(Comparator.comparingInt(r -> (Integer) r.get("index")));
        write(name + "-requests.json", raw); results.add(result); write("summary.json", results);
    }
    public static void main(String[] args) throws Exception {
        int exit = 1;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            db = context.getBean(JdbcTemplate.class); service = context.getBean(UserFootService.class);
            if (!"bench_userfoot".equals(db.queryForObject("SELECT DATABASE()", String.class)) ||
                    count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()") != 0) throw new IllegalStateException("Dedicated empty schema required");
            String ddl = new String(Files.readAllBytes(Paths.get("paicoding-web/src/main/resources/liquibase/data/init_schema_221209.sql")), StandardCharsets.UTF_8);
            int at = ddl.indexOf("CREATE TABLE `user_foot`"); db.execute(ddl.substring(at, ddl.indexOf(';', at)));
            ddl = new String(Files.readAllBytes(Paths.get("paicoding-web/src/main/resources/liquibase/data/update_schema_260822_2.sql")), StandardCharsets.UTF_8);
            db.execute(ddl.split(";")[0].replace("interaction_event_outbox", "domain_event_outbox"));
            write("server.json", map("mysql", db.queryForObject("SELECT VERSION()", String.class), "java", System.getProperty("java.version"), "poolSize", 40));
            db.update("INSERT INTO user_foot(user_id,document_id,document_type,document_user_id) VALUES(1,42,1,2)");
            scenario("existing-like", 42, OperateTypeEnum.PRAISE, 1, 1, "praise_stat");
            scenario("new-like", 43, OperateTypeEnum.PRAISE, 1, 1, "praise_stat");
            scenario("cancel-like", 43, OperateTypeEnum.CANCEL_PRAISE, 1, 2, "praise_stat");
            scenario("relike", 43, OperateTypeEnum.PRAISE, 1, 1, "praise_stat");
            scenario("collect", 43, OperateTypeEnum.COLLECTION, 1, 1, "collection_stat");
            scenario("cancel-collect", 43, OperateTypeEnum.CANCEL_COLLECTION, 1, 2, "collection_stat");
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(s -> {
                service.favorArticleComment(DocumentTypeEnum.ARTICLE, 44L, 2L, 1L, OperateTypeEnum.PRAISE); s.setRollbackOnly(); return null;
            });
            results.add(map("scenario", "rollback", "passed", count("SELECT COUNT(*) FROM user_foot WHERE document_id=44") == 0 && count("SELECT COUNT(*) FROM domain_event_outbox WHERE aggregate_id=44") == 0));
            write("summary.json", results);
            write("foot-rows.json", db.queryForList("SELECT id,user_id,document_id,document_type,praise_stat,collection_stat FROM user_foot ORDER BY id"));
            write("outbox-rows.json", db.queryForList("SELECT event_id,event_type,aggregate_type,aggregate_id,status FROM domain_event_outbox ORDER BY id"));
            exit = results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))) ? 0 : 2;
        }
        System.exit(exit);
    }
}
