package com.github.paicoding.forum.service.article.service.impl;

import com.github.paicoding.forum.api.model.vo.article.dto.ArticleDTO;
import com.github.paicoding.forum.api.model.vo.article.dto.CategoryDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.ArticleFootCountDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.BaseUserInfoDTO;
import com.github.paicoding.forum.core.cache.*;
import com.github.paicoding.forum.core.senstive.SensitiveService;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.article.repository.dao.ArticleTagDao;
import com.github.paicoding.forum.service.article.service.CategoryService;
import com.github.paicoding.forum.service.chatai.bot.AiBots;
import com.github.paicoding.forum.service.comment.service.CommentReadService;
import com.github.paicoding.forum.service.config.repository.dao.ConfigDao;
import com.github.paicoding.forum.service.config.repository.entity.GlobalConfigDO;
import com.github.paicoding.forum.service.sensitive.service.impl.SensitiveBypassServiceImpl;
import com.github.paicoding.forum.service.statistics.service.impl.CountServiceImpl;
import com.github.paicoding.forum.service.statistics.service.impl.DurableReadCountService;
import com.github.paicoding.forum.service.user.repository.dao.UserFootDao;
import com.github.paicoding.forum.service.user.service.UserFootService;
import com.github.paicoding.forum.service.user.service.UserService;
import com.github.paicoding.forum.service.user.service.whitelist.AuthorWhiteListServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Boot/Lettuce timeouts against a local RESP fixture; no external Redis is paused. */
class ArticleDetailRedisFallbackTest {
    private Object previousTemplate;
    private Object previousBreaker;
    private JdbcTemplate jdbc;
    private ArticleReadServiceImpl articles;
    private RedisCircuitBreaker breaker;

    @BeforeEach
    void setup() {
        previousTemplate = ReflectionTestUtils.getField(RedisClient.class, "template");
        previousBreaker = ReflectionTestUtils.getField(RedisClient.class, "circuitBreaker");
        breaker = new RedisCircuitBreaker(new CacheProtectionProperties());
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(db);
        jdbc.execute("create table article_read_outbox(event_id varchar(36) primary key, article_id bigint, author_id bigint, trace_id varchar, state int, create_time timestamp)");
        jdbc.execute("create table read_count(document_id bigint primary key, cnt int)");
        jdbc.update("insert into read_count values(10,42)");
        DurableReadCountService durable = new DurableReadCountService(jdbc, new DataSourceTransactionManager(db), mock(ObjectProvider.class));
        UserFootDao feet = mock(UserFootDao.class);
        when(feet.countArticleByArticleId(anyLong())).thenAnswer(call -> {
            ArticleFootCountDTO result = new ArticleFootCountDTO();
            result.setReadCount(jdbc.queryForObject("select cnt from read_count where document_id=10", Integer.class));
            return result;
        });
        CountServiceImpl counts = new CountServiceImpl(feet);
        ReflectionTestUtils.setField(counts, "durableReadCountService", durable);
        ReflectionTestUtils.setField(counts, "commentReadService", mock(CommentReadService.class));
        ConfigDao config = mock(ConfigDao.class);
        GlobalConfigDO allow = new GlobalConfigDO();
        allow.setValue("1");
        when(config.getGlobalConfigByKey("auth_article_white_list")).thenReturn(allow);
        AuthorWhiteListServiceImpl whitelist = new AuthorWhiteListServiceImpl();
        ReflectionTestUtils.setField(whitelist, "configDao", config);
        UserService users = mock(UserService.class);
        when(users.queryBasicUserInfo(anyLong())).thenReturn(new BaseUserInfoDTO().setRole("NORMAL"));
        SensitiveBypassServiceImpl bypass = new SensitiveBypassServiceImpl();
        ReflectionTestUtils.setField(bypass, "authorWhiteListService", whitelist);
        ReflectionTestUtils.setField(bypass, "userService", users);
        ReflectionTestUtils.setField(bypass, "aiBots", mock(AiBots.class));
        ArticleDao dao = mock(ArticleDao.class);
        when(dao.queryArticleDetail(anyLong())).thenAnswer(call -> {
            ArticleDTO dto = new ArticleDTO();
            dto.setArticleId(call.getArgument(0));
            dto.setAuthor(((Long) call.getArgument(0)) == 10L ? 1L : 2L);
            dto.setCategory(new CategoryDTO(1L, null));
            dto.setTitle("bad title");
            return dto;
        });
        SensitiveService filter = mock(SensitiveService.class);
        when(filter.replace(anyString())).thenReturn("*** title");
        articles = new ArticleReadServiceImpl();
        ReflectionTestUtils.setField(articles, "articleDao", dao);
        ReflectionTestUtils.setField(articles, "articleTagDao", mock(ArticleTagDao.class));
        ReflectionTestUtils.setField(articles, "categoryService", mock(CategoryService.class));
        ReflectionTestUtils.setField(articles, "userFootService", mock(UserFootService.class));
        ReflectionTestUtils.setField(articles, "countService", counts);
        ReflectionTestUtils.setField(articles, "sensitiveBypassService", bypass);
        ReflectionTestUtils.setField(articles, "sensitiveService", filter);
    }

    @AfterEach
    void cleanup() {
        RequestIoStats.finish();
        ReflectionTestUtils.setField(RedisClient.class, "template", previousTemplate);
        ReflectionTestUtils.setField(RedisClient.class, "circuitBreaker", previousBreaker);
        jdbc.execute("drop all objects");
    }

    private ApplicationContextRunner redis(int port) {
        Path root = Paths.get("paicoding-web/src/main/resources/application.yml");
        if (!Files.exists(root)) root = Paths.get("../paicoding-web/src/main/resources/application.yml");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(root.toFile()));
        Properties properties = yaml.getObject();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources()
                            .addLast(new PropertiesPropertySource("production-defaults", properties));
                    CacheProtectionProperties bound = Binder.get(context.getEnvironment())
                            .bind("cache.protection", CacheProtectionProperties.class).get();
                    assertThat(bound.getBreakerFailureThreshold()).isEqualTo(1);
                    breaker = new RedisCircuitBreaker(bound);
                })
                .withPropertyValues("spring.redis.host=127.0.0.1", "spring.redis.port=" + port,
                        "spring.redis.lettuce.pool.min-idle=0");
    }

    private void register(StringRedisTemplate template) {
        new RedisConnectionDiagnostics().postProcessAfterInitialization(template, "testRedis");
        RedisClient.register(template);
        RedisClient.registerCircuitBreaker(breaker);
    }

    private ArticleDTO detail(long id, String phase, long upperBoundMs) {
        RequestIoStats.begin();
        long start = System.nanoTime();
        ArticleDTO result = articles.queryFullArticleInfo(id, null);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        String io = RequestIoStats.finish();
        System.out.println("DETAIL_REDIS phase=" + phase + " elapsedMs=" + elapsed + " " + io);
        assertThat(elapsed).isLessThan(upperBoundMs);
        assertThat(result.getPraised()).isFalse();
        if (phase.equals("open")) assertThat(io).contains("redisCommands={}, redisAcquisitions=0");
        if (phase.equals("first-command-timeout")) {
            assertThat(io).contains("request/hGetAll=1", "redisAcquisitions=1,").doesNotContain("sIsMember");
        }
        return result;
    }

    @Test
    void firstCommandTimeoutOpensCircuitThenRecoveryUsesCacheAgain() throws Exception {
        try (RespServer server = new RespServer()) {
            redis(server.port()).run(context -> {
                assertThat(context).hasNotFailed();
                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(250));
                io.lettuce.core.SocketOptions socketOptions = factory.getClientConfiguration().getClientOptions().get().getSocketOptions();
                assertThat(socketOptions.getConnectTimeout()).isEqualTo(Duration.ofMillis(250));
                org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration pooling =
                        (org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration) factory.getClientConfiguration();
                assertThat(pooling.getPoolConfig().getMaxWaitMillis()).isEqualTo(100);
                StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
                template.opsForValue().get("warmup"); // Complete the handshake before pausing commands.
                register(template);
                server.paused = true;
                assertThat(detail(10, "first-command-timeout", 2000).getCount().getReadCount()).isEqualTo(42);
                assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
                assertThat(detail(10, "open", 1000).getTitle()).isEqualTo("bad title");
                assertThat(detail(11, "open", 1000).getTitle()).isEqualTo("*** title");
                server.paused = false;
                ReflectionTestUtils.setField(breaker, "openUntil", System.currentTimeMillis() - 1);
                assertThat(detail(10, "recovered", 2000).getCount().getReadCount()).isEqualTo(123);
                assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.CLOSED);
                assertThat(detail(11, "recovered", 2000).getTitle()).isEqualTo("*** title");
                assertThat(jdbc.queryForObject("select count(*) from article_read_outbox where state=0", Integer.class)).isEqualTo(5);
                assertThat(jdbc.queryForObject("select cnt from read_count where document_id=10", Integer.class)).isEqualTo(42);
            });
        }
    }

    @Test
    void firstUnavailableConnectionFallsBackAndStillPersistsReadEvent() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = unused.getLocalPort(); }
        redis(port).run(context -> {
            register(context.getBean(StringRedisTemplate.class));
            assertThat(detail(11, "first-connection-refused", 2000).getTitle()).isEqualTo("*** title");
            assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
            assertThat(detail(10, "open", 1000).getCount().getReadCount()).isEqualTo(42);
            assertThat(jdbc.queryForObject("select count(*) from article_read_outbox", Integer.class)).isEqualTo(2);
        });
    }

    @Test
    void unresponsiveHandshakeIsAlsoBounded() throws Exception {
        try (RespServer server = new RespServer()) {
            server.paused = true;
            redis(server.port()).run(context -> {
                register(context.getBean(StringRedisTemplate.class));
                assertThat(detail(10, "first-handshake-timeout", 2000).getCount().getReadCount()).isEqualTo(42);
                assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
            });
        }
    }

    @Test
    void productionProfileDoesNotRestoreTheOldTwoSecondTimeout() {
        Path path = Paths.get("paicoding-web/src/main/resources-env/prod/application-dal.yml");
        if (!Files.exists(path)) path = Paths.get("../paicoding-web/src/main/resources-env/prod/application-dal.yml");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(path.toFile()));
        Properties properties = yaml.getObject();
        redis(6379).withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new PropertiesPropertySource("prod-profile", properties))).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LettuceConnectionFactory.class).getClientConfiguration().getCommandTimeout())
                    .isEqualTo(Duration.ofMillis(250));
        });
    }

    /** Minimal RESP2 transport fixture: real TCP and Lettuce, controlled response stalls. */
    private static class RespServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "test-redis-resp"); thread.setDaemon(true); return thread;
        });
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        volatile boolean paused;
        volatile boolean closed;
        RespServer() throws IOException {
            listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            workers.submit(() -> {
                while (!closed) {
                    try { Socket socket = listener.accept(); sockets.add(socket); workers.submit(() -> serve(socket)); }
                    catch (IOException error) { if (!closed) throw new UncheckedIOException(error); }
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        private void serve(Socket socket) {
            try {
                InputStream input = new BufferedInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();
                while (!closed) {
                    String head = line(input);
                    if (head == null) return;
                    int size = Integer.parseInt(head.substring(1));
                    List<String> parts = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        int length = Integer.parseInt(line(input).substring(1));
                        byte[] bytes = new byte[length];
                        new DataInputStream(input).readFully(bytes);
                        parts.add(new String(bytes, StandardCharsets.UTF_8));
                        line(input);
                    }
                    while (paused && !closed) Thread.sleep(5);
                    if (closed) return;
                    String command = parts.get(0).toUpperCase(Locale.ROOT);
                    String reply = "HGETALL".equals(command) ? "*2\r\n$9\r\nreadCount\r\n$3\r\n123\r\n"
                            : "SISMEMBER".equals(command) ? ":0\r\n"
                            : "GET".equals(command) ? "$-1\r\n" : "+OK\r\n";
                    if ("PING".equals(command)) reply = "+PONG\r\n";
                    if ("HELLO".equals(command)) reply = "-ERR unknown command 'HELLO'\r\n";
                    output.write(reply.getBytes(StandardCharsets.UTF_8)); output.flush();
                }
            } catch (IOException ignored) {
                // Client shutdown or a timed-out connection is expected.
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int value;
            while ((value = in.read()) != -1) {
                if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
                bytes.write(value);
            }
            return null;
        }
        public void close() throws IOException {
            closed = true; listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow();
        }
    }
}
