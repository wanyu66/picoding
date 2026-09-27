package com.github.paicoding.forum.service.rank;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.config.RabbitMdcTraceAdvice;
import com.github.paicoding.forum.service.notify.monitor.DomainEventMessageMonitor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.service.impl.*;
import com.github.paicoding.forum.service.rank.repository.dao.ActivityRankEventDao;
import com.github.paicoding.forum.service.rank.repository.mapper.ActivityRankEventMapper;
import com.github.paicoding.forum.service.rank.service.UserActivityRankService;
import com.github.paicoding.forum.service.rank.service.impl.UserActivityRankServiceImpl;
import com.github.paicoding.forum.service.rank.service.listener.ActivityDomainEventConsumer;
import com.github.paicoding.forum.service.user.service.UserService;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.jar.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, destructive ONLY to the dedicated compose project/database. No mocked transport or rank DAO. */
@EnabledIfSystemProperty(named = "outbox.rank.fault", matches = "true")
public class OutboxRankFaultTest {
    private static final String QUEUE = RabbitMqAutoConfig.ACTIVITY_QUEUE;
    private static final Path ROOT = Paths.get("..").toAbsolutePath().normalize();
    private static final Path RESULTS = Paths.get("target/outbox-rank-fault").toAbsolutePath();
    private static final int EVENTS = 1000;
    private static final int COPIES = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    static {
        // Keep this opt-in load test's I/O from being dominated by per-command DEBUG output.
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.ERROR);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        return Stream.of(8, 32).filter(c -> System.getProperty("fault.concurrency", c.toString()).equals(c.toString()))
                .flatMap(c -> Stream.of("mixed", "rabbit-outage", "ack-crash", "redis-outage", "cancel-before-praise")
                .filter(f -> System.getProperty("fault.scenario", f).equals(f))
                .map(f -> org.junit.jupiter.params.provider.Arguments.of(c, f)));
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @MapperScan(basePackageClasses = ActivityRankEventMapper.class)
    static class Config {
        @Bean(destroyMethod = "close") HikariDataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:mysql://127.0.0.1:13316/outbox_rank_fault?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
            ds.setUsername("root"); ds.setPassword("fault-test-only"); ds.setMaximumPoolSize(40);
            return ds;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true); factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean ActivityRankEventDao rankDao() { return new ActivityRankEventDao(); }
        // Not called: assertions read the real Redis projection directly; user-profile enrichment is outside this test.
        @Bean UserService userService() {
            return (UserService) java.lang.reflect.Proxy.newProxyInstance(UserService.class.getClassLoader(),
                    new Class<?>[]{UserService.class}, (proxy, method, args) -> {
                        if (method.getName().equals("toString")) return "UnusedUserEnrichment";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) return proxy == args[0];
                        throw new AssertionError("Unexpected user enrichment: " + method);
                    });
        }
        @Bean UserActivityRankService rankService() { return new UserActivityRankServiceImpl(); }
        @Bean DomainEventConsumeService consumeService(JdbcTemplate jdbc) { return new DomainEventConsumeService(jdbc); }
        @Bean ActivityDomainEventConsumer consumer(UserActivityRankService rank, DomainEventConsumeService consume) {
            return new ActivityDomainEventConsumer(rank, consume);
        }
        @Bean DomainEventOutboxServiceImpl outbox(JdbcTemplate jdbc) { return new DomainEventOutboxServiceImpl(jdbc); }
        @Bean(destroyMethod = "destroy") LettuceConnectionFactory redisFactory() {
            return new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 16389),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).shutdownTimeout(Duration.ZERO).build());
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory factory) {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            RedisClient.register(template); RedisClient.registerCircuitBreaker(null); return template;
        }
        @Bean(destroyMethod = "destroy") CachingConnectionFactory rabbit() {
            CachingConnectionFactory factory = new CachingConnectionFactory("127.0.0.1", 15682);
            factory.setUsername("guest"); factory.setPassword("guest"); factory.setConnectionTimeout(1000);
            factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true);
            return factory;
        }
        @Bean RabbitTemplate template(CachingConnectionFactory factory) {
            RabbitTemplate template = new RabbitTemplate(factory);
            template.setMandatory(true); template.setMessageConverter(new Jackson2JsonMessageConverter(JSON)); return template;
        }
        @Bean DomainEventOutboxPublisher publisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
            return new DomainEventOutboxPublisher(jdbc, rabbit);
        }
    }

    static final class Fixture {
        final List<DomainEvent> originals = new ArrayList<>();
        final List<DomainEvent> remaining = new ArrayList<>();
        final List<DomainEvent> all = new ArrayList<>();
        final Map<String, Integer> expectedUsers = new TreeMap<>();
        final Map<String, Integer> expectedDeltas = new TreeMap<>();
        final Map<String, String> expectedOriginals = new TreeMap<>();
        final Date time = new Date();
        Fixture() {
            // Independent business oracle: 400 cancelled pairs and 200 retained likes. No DAO/Lua reuse.
            for (int i = 0; i < 600; i++) {
                long uid = 1 + (i * 17L % 53);
                DomainEvent like = event("like-" + i, "PRAISE", uid, i + 1, time);
                if (i < 400) {
                    originals.add(like);
                    DomainEvent cancel = event("cancel-" + i, "CANCEL_PRAISE", uid, i + 1, new Date(time.getTime() + 1));
                    remaining.add(cancel); expectedDeltas.put(cancel.getEventId(), -2);
                    expectedOriginals.put(cancel.getEventId(), like.getEventId());
                } else { remaining.add(like); expectedUsers.merge(String.valueOf(uid), 2, Integer::sum); }
                expectedDeltas.put(like.getEventId(), 2);
            }
            all.addAll(originals); all.addAll(remaining);
            assertEquals(EVENTS, all.size());
            assertEquals(400, expectedUsers.values().stream().mapToInt(Integer::intValue).sum());
        }
        private DomainEvent event(String id, String type, long uid, long target, Date when) {
            return DomainEvent.builder().eventId(id).eventType(type).operatorId(uid).occurredAt(when).version(1)
                    .traceId("fault-" + id).target(DomainEvent.Target.builder().type("ARTICLE").id(target).articleId(target).ownerId(999L).build()).build();
        }
    }

    @ParameterizedTest(name = "{0} consumers / {1}")
    @MethodSource("cases")
    void jointFaultMatrix(int concurrency, String fault) throws Exception {
        Files.createDirectories(RESULTS);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("concurrency", concurrency); report.put("scenario", fault);
        report.put("uniqueEvents", EVENTS); report.put("copiesPerEvent", COPIES);
        report.put("startedAt", Instant.now().toString());
        report.put("recoveryMode", "explicit invocation of production recovery method; excludes cron wait");
        long started = System.nanoTime();
        SimpleMessageListenerContainer listener = null;
        boolean rabbitStopped = false, redisStopped = false;
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class)) {
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            StringRedisTemplate redis = ctx.getBean(StringRedisTemplate.class);
            CachingConnectionFactory connection = ctx.getBean(CachingConnectionFactory.class);
            RabbitTemplate template = ctx.getBean(RabbitTemplate.class);
            DomainEventOutboxPublisher publisher = ctx.getBean(DomainEventOutboxPublisher.class);
            DomainEventOutboxServiceImpl outbox = ctx.getBean(DomainEventOutboxServiceImpl.class);
            UserActivityRankServiceImpl rank = ctx.getBean(UserActivityRankServiceImpl.class);
            TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            reset(jdbc, redis, connection);
            Fixture fixture = new Fixture();
            JSON.writerWithDefaultPrettyPrinter().writeValue(RESULTS.resolve(concurrency + "-" + fault + "-fixture.json").toFile(), fixture.all);
            report.put("expectedScores", fixture.expectedUsers);
            AtomicInteger deliveries = new AtomicInteger(), redeliveries = new AtomicInteger();
            AtomicInteger inFlight = new AtomicInteger(), peakInFlight = new AtomicInteger();
            List<String> errors = new CopyOnWriteArrayList<>();
            listener = listener(ctx, concurrency, deliveries, redeliveries, inFlight, peakInFlight, errors);
            listener.start();
            final SimpleMessageListenerContainer activeListener = listener;
            await("requested consumers started", () -> activeListener.getActiveConsumerCount() == concurrency, 30);
            report.put("activeConsumers", listener.getActiveConsumerCount());
            List<DomainEvent> first = fixture.originals;
            List<DomainEvent> second = fixture.remaining;
            if (fault.equals("cancel-before-praise")) {
                first = fixture.remaining.stream().filter(e -> e.getEventType().startsWith("CANCEL")).collect(Collectors.toList());
                second = fixture.all.stream().filter(e -> e.getEventType().equals("PRAISE")).collect(Collectors.toList());
            }
            if (fault.equals("rabbit-outage")) {
                // One failed publish proves the outage; retain the rest pending without waiting for 100 socket timeouts.
                append(tx, outbox, first.subList(0, 1));
                listener.stop(); compose("stop", "rabbit"); rabbitStopped = true;
                publisher.publishPending();
                int failed = count(jdbc, "select count(*) from domain_event_outbox where status='FAILED'");
                report.put("failedOutboxDuringOutage", failed); assertTrue(failed > 0);
                append(tx, outbox, first.subList(1, first.size()));
                assertEquals(first.size(), count(jdbc, "select count(*) from domain_event_outbox where status<>'SENT'"));
                long recovery = System.nanoTime();
                compose("start", "rabbit"); rabbitStopped = false;
                await("Rabbit recovery", () -> rabbitReady(connection), 90);
                listener.start();
                publishUntilSent(publisher, jdbc); duplicates(template, first);
                await("first wave committed", () -> count(jdbc, "select count(*) from domain_event_consume") == 400 && deliveries.get() >= 1200, 120);
                report.put("rabbitRecoveryMs", elapsed(recovery));
            } else {
                append(tx, outbox, first);
                publishUntilSent(publisher, jdbc); duplicates(template, first);
                await("first wave committed", () -> count(jdbc, "select count(*) from domain_event_consume") == 400 && deliveries.get() >= 1200, 120);
            }
            if (fault.equals("redis-outage")) { compose("stop", "redis"); redisStopped = true; }
            Collections.shuffle(second, new Random(20260923));
            if (fault.equals("ack-crash")) {
                listener.stop();
                // Publish exactly one event for the child to commit and leave unacknowledged.
                DomainEvent crashEvent = second.remove(0);
                append(tx, outbox, Collections.singletonList(crashEvent)); publishUntilSent(publisher, jdbc);
                crashAfterCommit();
                assertEquals(1, jdbc.queryForObject("select count(*) from domain_event_consume where event_id=?", Integer.class, crashEvent.getEventId()));
                assertEquals(1, jdbc.queryForObject("select count(*) from rank_effect_audit where event_id=?", Integer.class, crashEvent.getEventId()));
                report.put("committedBeforeCrashEventId", crashEvent.getEventId());
                long recovery = System.nanoTime();
                listener.start(); duplicates(template, Collections.singletonList(crashEvent));
                await("unacked event redelivered", () -> redeliveries.get() > 0, 60);
                report.put("ackCrashRedeliveryMs", elapsed(recovery));
            }
            append(tx, outbox, second); publishUntilSent(publisher, jdbc); duplicates(template, second);
            await("all events committed and all copies delivered", () -> count(jdbc, "select count(*) from domain_event_consume") == EVENTS && deliveries.get() >= EVENTS * COPIES, 180);
            if (fault.equals("redis-outage")) {
                report.put("pendingProjectionDuringOutage", count(jdbc, "select count(*) from activity_rank_event where redis_applied=0"));
                assertTrue(count(jdbc, "select count(*) from activity_rank_event where redis_applied=0") > 0);
                long recovery = System.nanoTime();
                compose("start", "redis"); redisStopped = false;
                await("Redis recovery", () -> redisReady(redis), 60);
                await("projection compensation", () -> { rank.recoverPendingEvents(); return count(jdbc, "select count(*) from activity_rank_event where redis_applied=0") == 0; }, 120);
                report.put("redisCompensationRecoveryMs", elapsed(recovery));
            }
            listener.stop();
            Map<String, Integer> ledger = new TreeMap<>();
            jdbc.query("select event_id,score_delta from activity_rank_event", rs -> { ledger.put(rs.getString(1), rs.getInt(2)); });
            Map<String, String> links = new TreeMap<>();
            jdbc.query("select event_id,original_event_id from activity_rank_event where original_event_id is not null", rs -> { links.put(rs.getString(1), rs.getString(2)); });
            Set<String> expectedIds = fixture.expectedDeltas.keySet();
            Set<String> consumed = new TreeSet<>(jdbc.queryForList("select event_id from domain_event_consume where consumer_name='activity'", String.class));
            Set<String> sent = new TreeSet<>(jdbc.queryForList("select event_id from domain_event_outbox where status='SENT'", String.class));
            Set<String> missing = new TreeSet<>(expectedIds); missing.removeAll(ledger.keySet());
            int duplicates = count(jdbc, "select coalesce(sum(n-1),0) from (select count(*) n from rank_effect_audit group by event_id having count(*)>1) x");
            String day = DateTimeFormatter.ofPattern("yyyyMMdd").format(fixture.time.toInstant().atZone(ZoneId.systemDefault()));
            String month = day.substring(0, 6);
            Map<String, Integer> dayScores = scores(redis, "pai_activity_rank_day:" + day);
            Map<String, Integer> monthScores = scores(redis, "pai_activity_rank_month:" + month);
            report.put("receivedDeliveries", deliveries.get()); report.put("redeliveries", redeliveries.get());
            report.put("peakConcurrentCallbacks", peakInFlight.get());
            report.put("consumerErrors", errors); report.put("outboxSent", sent.size()); report.put("consumedUnique", consumed.size());
            report.put("businessEffects", ledger.size()); report.put("missingBusinessEventIds", missing);
            report.put("expectedEventDeltas", fixture.expectedDeltas); report.put("actualEventDeltas", ledger);
            report.put("actualCancellationLinks", links);
            report.put("duplicateBusinessSideEffects", duplicates);
            report.put("pendingProjections", count(jdbc, "select count(*) from activity_rank_event where redis_applied=0"));
            report.put("dayScores", dayScores); report.put("monthScores", monthScores);
            report.put("dayAbsoluteScoreDeviation", deviation(fixture.expectedUsers, dayScores));
            report.put("monthAbsoluteScoreDeviation", deviation(fixture.expectedUsers, monthScores));
            report.put("queueReady", new RabbitAdmin(connection).getQueueInfo(QUEUE).getMessageCount());
            int deadLetters = new RabbitAdmin(connection).getQueueInfo(RabbitMqAutoConfig.ACTIVITY_DLQ).getMessageCount();
            report.put("deadLetters", deadLetters);
            boolean passed = sent.equals(expectedIds) && consumed.equals(expectedIds) && ledger.equals(fixture.expectedDeltas)
                    && links.equals(fixture.expectedOriginals) && duplicates == 0 && dayScores.equals(fixture.expectedUsers)
                    && monthScores.equals(fixture.expectedUsers) && deadLetters == 0
                    && peakInFlight.get() == concurrency
                    && new RabbitAdmin(connection).getQueueInfo(QUEUE).getMessageCount() == 0
                    && count(jdbc, "select count(*) from activity_rank_event where redis_applied=0") == 0;
            report.put("passed", passed);
            assertTrue(passed, "Business completeness / deduplication / score reconciliation failed; see " + concurrency + "-" + fault + ".json");
        } catch (Throwable e) {
            report.put("passed", false); report.put("failure", e.toString()); throw e;
        } finally {
            if (listener != null) listener.destroy();
            if (rabbitStopped) compose("start", "rabbit");
            if (redisStopped) compose("start", "redis");
            report.put("elapsedMs", elapsed(started));
            JSON.writerWithDefaultPrettyPrinter().writeValue(RESULTS.resolve(concurrency + "-" + fault + ".json").toFile(), report);
        }
    }

    private static SimpleMessageListenerContainer listener(AnnotationConfigApplicationContext ctx, int concurrency,
                                                          AtomicInteger deliveries, AtomicInteger redeliveries,
                                                          AtomicInteger inFlight, AtomicInteger peakInFlight, List<String> errors) {
        CachingConnectionFactory connection = ctx.getBean(CachingConnectionFactory.class);
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId("outbox-rank-fault"); endpoint.setQueueNames(QUEUE);
        CountDownLatch simultaneousStart = new CountDownLatch(concurrency);
        endpoint.setMessageListener((ChannelAwareMessageListener) (message, channel) -> {
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            if (Boolean.TRUE.equals(message.getMessageProperties().getRedelivered())) redeliveries.incrementAndGet();
            try {
                simultaneousStart.countDown();
                if (!simultaneousStart.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Consumers did not start concurrently");
                ctx.getBean(ActivityDomainEventConsumer.class).consume(JsonUtil.toObj(new String(message.getBody(), StandardCharsets.UTF_8), DomainEvent.class));
            } catch (Exception e) {
                errors.add(e.toString()); throw e;
            } finally { inFlight.decrementAndGet(); deliveries.incrementAndGet(); }
        });
        SimpleMessageListenerContainer listener = new RabbitMqAutoConfig().listenerContainerFactory(connection, JSON,
                new DomainEventMessageMonitor(new SimpleMeterRegistry(), new RabbitAdmin(connection)), new RabbitMdcTraceAdvice())
                .createListenerContainer(endpoint);
        listener.setConcurrentConsumers(concurrency); listener.setPrefetchCount(1);
        return listener;
    }

    private static void reset(JdbcTemplate jdbc, StringRedisTemplate redis, CachingConnectionFactory rabbit) throws Exception {
        assertEquals("outbox_rank_fault", jdbc.queryForObject("select database()", String.class));
        for (String table : Arrays.asList("rank_effect_audit", "activity_rank_event", "domain_event_consume", "domain_event_outbox")) jdbc.execute("DROP TABLE IF EXISTS " + table);
        String rankSql = new String(Files.readAllBytes(ROOT.resolve("paicoding-web/src/main/resources/liquibase/data/update_schema_260822.sql")), StandardCharsets.UTF_8);
        jdbc.execute(rankSql.trim());
        String sql = new String(Files.readAllBytes(ROOT.resolve("paicoding-web/src/main/resources/liquibase/data/update_schema_260822_2.sql")), StandardCharsets.UTF_8);
        String[] statements = sql.split(";");
        jdbc.execute(statements[0].replace("interaction_event_outbox", "domain_event_outbox"));
        jdbc.execute(statements[1].replace("interaction_event_consume", "domain_event_consume"));
        jdbc.execute("CREATE TABLE rank_effect_audit(id bigint auto_increment primary key,event_id varchar(128),score_delta int)");
        jdbc.execute("CREATE TRIGGER rank_effect_insert AFTER INSERT ON activity_rank_event FOR EACH ROW INSERT INTO rank_effect_audit(event_id,score_delta) VALUES(NEW.event_id,NEW.score_delta)");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        RabbitAdmin admin = new RabbitAdmin(rabbit);
        admin.deleteQueue(QUEUE); admin.deleteQueue(RabbitMqAutoConfig.ACTIVITY_DLQ);
        admin.declareExchange(new DirectExchange(RabbitMqAutoConfig.EXCHANGE));
        admin.declareExchange(new DirectExchange(RabbitMqAutoConfig.DLX));
        admin.declareQueue(QueueBuilder.durable(RabbitMqAutoConfig.ACTIVITY_DLQ).build());
        admin.declareBinding(new Binding(RabbitMqAutoConfig.ACTIVITY_DLQ, Binding.DestinationType.QUEUE, RabbitMqAutoConfig.DLX, "domain.activity.dead", null));
        admin.declareQueue(QueueBuilder.durable(QUEUE).deadLetterExchange(RabbitMqAutoConfig.DLX).deadLetterRoutingKey("domain.activity.dead").build());
        admin.declareBinding(new Binding(QUEUE, Binding.DestinationType.QUEUE, RabbitMqAutoConfig.EXCHANGE, RabbitMqAutoConfig.ROUTING_KEY, null));
    }

    private static void append(TransactionTemplate tx, DomainEventOutboxServiceImpl outbox, List<DomainEvent> events) {
        tx.execute(status -> { events.forEach(outbox::append); return null; });
    }
    private static void publishUntilSent(DomainEventOutboxPublisher publisher, JdbcTemplate jdbc) throws Exception {
        await("outbox drain", () -> { publisher.publishPending(); return count(jdbc, "select count(*) from domain_event_outbox where status<>'SENT'") == 0; }, 120);
    }
    private static void duplicates(RabbitTemplate template, List<DomainEvent> events) throws Exception {
        List<DomainEvent> copies = new ArrayList<>();
        for (int i = 1; i < COPIES; i++) copies.addAll(events);
        Collections.shuffle(copies, new Random(731));
        for (DomainEvent event : copies) {
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            template.convertAndSend(RabbitMqAutoConfig.EXCHANGE, RabbitMqAutoConfig.ROUTING_KEY, event, correlation);
            assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()); assertNull(correlation.getReturnedMessage());
        }
    }
    private static Map<String, Integer> scores(StringRedisTemplate redis, String key) {
        Map<String, Integer> scores = new TreeMap<>();
        redis.opsForZSet().rangeWithScores(key, 0, -1).forEach(t -> scores.put(t.getValue(), t.getScore().intValue()));
        return scores;
    }
    private static int deviation(Map<String, Integer> expected, Map<String, Integer> actual) {
        Set<String> users = new HashSet<>(expected.keySet()); users.addAll(actual.keySet());
        return users.stream().mapToInt(u -> Math.abs(expected.getOrDefault(u, 0) - actual.getOrDefault(u, 0))).sum();
    }
    private static int count(JdbcTemplate jdbc, String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static void await(String name, BooleanSupplier condition, int seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < end);
        fail("Timed out: " + name);
    }
    private static boolean rabbitReady(CachingConnectionFactory factory) {
        try { return factory.createConnection().isOpen(); } catch (Exception e) { return false; }
    }
    private static boolean redisReady(StringRedisTemplate redis) {
        try { return "PONG".equals(redis.getConnectionFactory().getConnection().ping()); } catch (Exception e) { return false; }
    }
    private static void compose(String action, String service) throws Exception {
        Process p = new ProcessBuilder("docker", "compose", "-p", "paicoding-outbox-rank-fault", "-f",
                ROOT.resolve("scripts/load-test/outbox-rank-compose.yml").toString(), action, service)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(RESULTS.resolve("docker.log").toFile())).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("docker timed out: " + action + " " + service); }
        assertEquals(0, p.exitValue(), "docker " + action + " " + service);
    }

    private static void crashAfterCommit() throws Exception {
        // Manifest avoids Windows command-line length limits for the full Maven test classpath.
        Manifest manifest = new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, Arrays.stream(cp.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(s -> new File(s).toURI().toString()).collect(Collectors.joining(" ")));
        Path jar = RESULTS.resolve("child-classpath.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        Process p = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(), "-cp", jar.toString(), OutboxRankFaultTest.class.getName())
                .redirectErrorStream(true).redirectOutput(RESULTS.resolve("crash-child.log").toFile()).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("crash child timed out"); }
        assertEquals(77, p.exitValue(), "child must halt after database commit and before broker ACK");
    }

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class);
        Channel channel = ctx.getBean(CachingConnectionFactory.class).createConnection().createChannel(false);
        GetResponse message = channel.basicGet(QUEUE, false);
        if (message == null) throw new IllegalStateException("No crash event");
        DomainEvent event = JsonUtil.toObj(new String(message.getBody(), StandardCharsets.UTF_8), DomainEvent.class);
        ctx.getBean(ActivityDomainEventConsumer.class).consume(event);
        System.out.println("COMMITTED_BEFORE_ACK " + event.getEventId()); System.out.flush();
        Runtime.getRuntime().halt(77);
    }
}
