package com.github.paicoding.forum.service.notify.service.impl;

import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.core.mdc.SelfTraceIdGenerator;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;
import com.github.paicoding.forum.service.notify.service.DomainEventOutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Service
public class DomainEventOutboxServiceImpl implements DomainEventOutboxService {
    private final JdbcTemplate jdbcTemplate;

    public DomainEventOutboxServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(DomainEvent event) {
        jdbcTemplate.update("INSERT INTO domain_event_outbox " +
                        "(event_id,event_type,aggregate_type,aggregate_id,payload,status,retry_count,next_retry_time,create_time,update_time) " +
                        "VALUES (?,?,?,?,?,'PENDING',0,NOW(),NOW(),NOW())",
                event.getEventId(), event.getEventType(), event.getTarget().getType(), event.getTarget().getId(), JsonUtil.toStr(event));
    }

    @Override
    public DomainEvent newEvent(DomainEventType type, Long operatorId, DomainEvent.Target target, String content) {
        String eventId = UUID.randomUUID().toString();
        String traceId = currentTraceId();
        return DomainEvent.builder()
                .eventId(eventId)
                .eventType(type.name())
                .operatorId(operatorId)
                .target(target)
                .occurredAt(new Date())
                .version(1)
                .traceId(traceId == null ? SelfTraceIdGenerator.generate() : traceId)
                .content(content)
                .build();
    }
}
