package com.github.paicoding.forum.service.notify.service.impl;

import com.github.paicoding.forum.service.notify.model.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DomainEventConsumeService {
    private final JdbcTemplate jdbcTemplate;

    public DomainEventConsumeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public void consume(String consumerName, DomainEvent event, Runnable handler) {
        int acquired = jdbcTemplate.update("INSERT IGNORE INTO domain_event_consume " +
                        "(consumer_name,event_id,event_type,consume_time,create_time) VALUES(?,?,?,?,NOW())",
                consumerName, event.getEventId(), event.getEventType(), new java.util.Date());
        if (acquired == 0) {
            return;
        }
        handler.run();
    }
}
