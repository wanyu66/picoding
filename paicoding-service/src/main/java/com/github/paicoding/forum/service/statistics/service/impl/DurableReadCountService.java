package com.github.paicoding.forum.service.statistics.service.impl;

import com.github.paicoding.forum.service.statistics.service.CountService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/** MySQL is authoritative. Redis is an eventually consistent, retryable projection. */
@Slf4j
@Service
@DependsOnDatabaseInitialization
public class DurableReadCountService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectProvider<CountService> counts;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public DurableReadCountService(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectProvider<CountService> counts) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.counts = counts;
    }

    /** One-time cutover; run with old application writers stopped. Failure rolls back marker. */
    @PostConstruct
    public void initializeBaseline() {
        transaction.execute(status -> {
            Integer completed = jdbc.queryForObject("SELECT completed FROM article_read_migration WHERE id=1 FOR UPDATE", Integer.class);
            if (Integer.valueOf(1).equals(completed)) return null;
            String prefix = "pai_article_statistic_";
            int migrated = 0;
            try (Cursor<String> keys = stringRedisTemplate.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build())) {
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!key.substring(prefix.length()).matches("[0-9]+")) continue;
                    Object count = stringRedisTemplate.opsForHash().get(key,
                            com.github.paicoding.forum.service.statistics.constants.CountConstants.READ_COUNT);
                    if (count == null) continue;
                    long value = Long.parseLong(count.toString());
                    if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalStateException("Invalid legacy read total");
                    jdbc.update("INSERT INTO read_count(document_id,document_type,cnt,create_time,update_time) VALUES (?,1,?,NOW(),NOW()) "
                            + "ON DUPLICATE KEY UPDATE cnt=GREATEST(cnt,VALUES(cnt)),update_time=NOW()", Long.parseLong(key.substring(prefix.length())), value);
                    migrated++;
                }
            }
            jdbc.update("UPDATE article_read_migration SET completed=1 WHERE id=1");
            log.info("read_count_legacy_baseline_migrated articles={}", migrated);
            return null;
        });
    }

    public void record(Long authorId, Long articleId) {
        com.github.paicoding.forum.core.cache.RequestIoStats.sql("article_read_outbox.insert");
        // Persist before returning success: no process-local queue and no Redis dependency.
        jdbc.update("INSERT INTO article_read_outbox(event_id,article_id,author_id,trace_id,state,create_time) VALUES (?,?,?,?,0,NOW())",
                UUID.randomUUID().toString(), articleId, authorId, MDC.get("traceId"));
    }

    @Scheduled(fixedDelayString = "${read-count.drain-interval-ms:1000}")
    public void drain() {
        try {
            persistBatch();
            projectBatch();
        } catch (RuntimeException error) {
            log.error("read_count_drain_failed eventsRetained=true", error);
        }
    }

    void persistBatch() {
        transaction.execute(status -> {
            // Concurrent instances serialize claims on the same oldest pending rows.
            List<Map<String, Object>> events = jdbc.queryForList(
                    "SELECT event_id,article_id FROM article_read_outbox WHERE state=0 ORDER BY create_time,event_id LIMIT 500 FOR UPDATE");
            Map<Long, Integer> deltas = new TreeMap<>();
            for (Map<String, Object> event : events) {
                Long articleId = ((Number) event.get("article_id")).longValue();
                deltas.put(articleId, deltas.getOrDefault(articleId, 0) + 1);
            }
            for (Map.Entry<Long, Integer> delta : deltas.entrySet()) {
                jdbc.update("INSERT INTO read_count(document_id,document_type,cnt,create_time,update_time) VALUES (?,1,?,NOW(),NOW()) "
                        + "ON DUPLICATE KEY UPDATE cnt=cnt+VALUES(cnt),update_time=NOW()", delta.getKey(), delta.getValue());
            }
            if (!events.isEmpty()) jdbc.update("UPDATE article_read_outbox SET state=1 WHERE state=0 AND event_id IN ("
                    + placeholders(events.size()) + ")", events.stream().map(event -> event.get("event_id")).toArray());
            return null;
        });
    }

    void projectBatch() {
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT event_id,article_id,author_id FROM article_read_outbox WHERE state=1 ORDER BY create_time,event_id LIMIT 500");
        if (events.isEmpty()) return;
        Set<Long> articles = new LinkedHashSet<>();
        Set<Long> authors = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            articles.add(((Number) event.get("article_id")).longValue());
            authors.add(((Number) event.get("author_id")).longValue());
        }
        // Absolute refresh is idempotent. Failure/timeout leaves rows pending, never re-increments MySQL.
        for (Long article : articles) counts.getObject().refreshArticleStatisticInfo(article);
        for (Long author : authors) counts.getObject().refreshUserStatisticInfo(author);
        jdbc.update("DELETE FROM article_read_outbox WHERE state=1 AND event_id IN (" + placeholders(events.size()) + ")",
                events.stream().map(event -> event.get("event_id")).toArray());
        log.debug("read_count_projection_complete events={} articles={}", events.size(), articles.size());
    }

    private String placeholders(int size) { return String.join(",", Collections.nCopies(size, "?")); }
}
