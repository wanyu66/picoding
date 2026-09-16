package com.github.paicoding.forum.service.statistics.service.impl;

import com.github.paicoding.forum.service.statistics.service.CountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class DurableReadCountServiceTest {
    @Test
    void redisFailureRetainsAppliedEventsAndRetryDoesNotIncrementMysqlAgain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ObjectProvider<CountService> provider = mock(ObjectProvider.class);
        CountService counts = mock(CountService.class);
        when(provider.getObject()).thenReturn(counts);
        Map<String, Object> event = new HashMap<>();
        event.put("event_id", "event-1"); event.put("article_id", 12L); event.put("author_id", 3L);
        when(jdbc.queryForList(contains("state=0"))).thenReturn(Collections.singletonList(event), Collections.emptyList());
        when(jdbc.queryForList(contains("state=1"))).thenReturn(Collections.singletonList(event));
        DurableReadCountService service = new DurableReadCountService(jdbc, manager, provider);
        doThrow(new IllegalStateException("redis down")).doNothing().when(counts).refreshArticleStatisticInfo(12L);
        service.persistBatch();
        assertThatThrownBy(service::projectBatch).hasMessage("redis down");
        verify(jdbc, never()).update(startsWith("DELETE"), eq("event-1"));
        service.persistBatch();
        service.projectBatch();
        verify(jdbc, times(1)).update(startsWith("INSERT INTO read_count"), eq(12L), eq(1));
        verify(jdbc, times(1)).update(startsWith("DELETE"), eq("event-1"));
        verify(counts).refreshUserStatisticInfo(3L);
    }
}
