package com.github.paicoding.forum.service.statistics.service.impl;

import com.github.paicoding.forum.service.statistics.service.CountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class DurableReadCountTransactionTest {
    @Test
    void legacyBaselineMovesOnlyOnceAndNeverReducesPersistedCount() {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE read_count(document_id BIGINT,document_type INT,cnt INT,create_time TIMESTAMP,update_time TIMESTAMP,PRIMARY KEY(document_id,document_type))");
        jdbc.execute("CREATE TABLE article_read_migration(id INT PRIMARY KEY,completed INT)");
        jdbc.update("INSERT INTO article_read_migration VALUES (1,0)");
        jdbc.update("INSERT INTO read_count VALUES (12,1,5,NOW(),NOW())");
        DurableReadCountService service = new DurableReadCountService(jdbc, new DataSourceTransactionManager(db), mock(ObjectProvider.class));
        org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.HashOperations hash = mock(org.springframework.data.redis.core.HashOperations.class);
        org.springframework.data.redis.core.Cursor<String> cursor = mock(org.springframework.data.redis.core.Cursor.class);
        when(redis.scan(any())).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("pai_article_statistic_12");
        doReturn(hash).when(redis).opsForHash();
        when(hash.get("pai_article_statistic_12", "readCount")).thenReturn("7");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        service.initializeBaseline();
        assertThat(jdbc.queryForObject("SELECT cnt FROM read_count", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT completed FROM article_read_migration", Integer.class)).isEqualTo(1);
        service.initializeBaseline();
        verify(redis, times(1)).scan(any());
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void committedEventsSurviveRedisFailureAndRestartWithoutDoubleCounting() {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE read_count(document_id BIGINT,document_type INT,cnt INT,create_time TIMESTAMP,update_time TIMESTAMP,PRIMARY KEY(document_id,document_type))");
        jdbc.execute("CREATE TABLE article_read_outbox(event_id VARCHAR(36) PRIMARY KEY,article_id BIGINT,author_id BIGINT,trace_id VARCHAR(128),state INT,create_time TIMESTAMP)");
        ObjectProvider<CountService> provider = mock(ObjectProvider.class);
        CountService counts = mock(CountService.class);
        when(provider.getObject()).thenReturn(counts);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        DurableReadCountService service = new DurableReadCountService(jdbc, manager, provider);
        service.record(1L, 12L);
        service.record(1L, 12L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_read_outbox WHERE state=0", Integer.class)).isEqualTo(2);
        service.persistBatch();
        assertThat(jdbc.queryForObject("SELECT cnt FROM read_count WHERE document_id=12", Integer.class)).isEqualTo(2);
        doThrow(new IllegalStateException("Redis down")).when(counts).refreshArticleStatisticInfo(12L);
        assertThatThrownBy(service::projectBatch).hasMessage("Redis down");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_read_outbox WHERE state=1", Integer.class)).isEqualTo(2);
        DurableReadCountService restarted = new DurableReadCountService(jdbc, manager, provider);
        restarted.persistBatch();
        doNothing().when(counts).refreshArticleStatisticInfo(12L);
        restarted.projectBatch();
        assertThat(jdbc.queryForObject("SELECT cnt FROM read_count WHERE document_id=12", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_read_outbox", Integer.class)).isZero();

        // A crash/failure between increment and state update rolls the whole batch back.
        service.record(1L, 12L);
        JdbcTemplate failing = spy(jdbc);
        String eventId = jdbc.queryForObject("SELECT event_id FROM article_read_outbox WHERE state=0", String.class);
        doThrow(new IllegalStateException("DB failure before marking applied"))
                .when(failing).update(startsWith("UPDATE article_read_outbox"), eq(eventId));
        DurableReadCountService broken = new DurableReadCountService(failing, manager, provider);
        assertThatThrownBy(broken::persistBatch).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT cnt FROM read_count WHERE document_id=12", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article_read_outbox WHERE state=0", Integer.class)).isEqualTo(1);
        service.persistBatch();
        assertThat(jdbc.queryForObject("SELECT cnt FROM read_count WHERE document_id=12", Integer.class)).isEqualTo(3);
        jdbc.execute("DROP ALL OBJECTS");
    }
}
