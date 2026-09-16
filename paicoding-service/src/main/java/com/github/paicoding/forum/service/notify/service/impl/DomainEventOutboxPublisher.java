package com.github.paicoding.forum.service.notify.service.impl;

import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class DomainEventOutboxPublisher {
    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;

    public DomainEventOutboxPublisher(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${rabbitmq.outbox.interval-ms:1000}")
    public void publishPending() {
        jdbcTemplate.update("UPDATE domain_event_outbox SET status='FAILED', next_retry_time=NOW(), " +
                "last_error='publisher lease expired', update_time=NOW() WHERE status='SENDING' AND update_time < DATE_SUB(NOW(), INTERVAL 5 MINUTE)");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,event_id,payload,retry_count FROM domain_event_outbox " +
                "WHERE status IN ('PENDING','FAILED') AND next_retry_time<=NOW() ORDER BY id LIMIT 100");
        for (Map<String, Object> row : rows) {
            publishOne(row);
        }
    }

    private void publishOne(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        String eventId = String.valueOf(row.get("event_id"));
        int claimed = jdbcTemplate.update("UPDATE domain_event_outbox SET status='SENDING',update_time=NOW() " +
                "WHERE id=? AND status IN ('PENDING','FAILED')", id);
        if (claimed == 0) {
            return;
        }
        try {
            DomainEvent event = JsonUtil.toObj(String.valueOf(row.get("payload")), DomainEvent.class);
            CorrelationData correlation = new CorrelationData(eventId);
            rabbitTemplate.convertAndSend(RabbitMqAutoConfig.EXCHANGE, RabbitMqAutoConfig.ROUTING_KEY, event, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setMessageId(eventId);
                message.getMessageProperties().setHeader("traceId", event.getTraceId());
                return message;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturnedMessage() != null) {
                throw new IllegalStateException(confirm.getReason() == null ? "message was returned" : confirm.getReason());
            }
            jdbcTemplate.update("UPDATE domain_event_outbox SET status='SENT',published_at=NOW(),last_error=NULL,update_time=NOW() WHERE id=?", id);
        } catch (Exception e) {
            int retry = ((Number) row.get("retry_count")).intValue() + 1;
            long delaySeconds = Math.min(300L, 1L << Math.min(retry, 8));
            jdbcTemplate.update("UPDATE domain_event_outbox SET status='FAILED',retry_count=?,next_retry_time=DATE_ADD(NOW(), INTERVAL ? SECOND)," +
                    "last_error=?,update_time=NOW() WHERE id=?", retry, delaySeconds, abbreviate(e.getMessage()), id);
            log.error("Publish outbox event failed, eventId={}, retry={}", eventId, retry, e);
        }
    }

    private String abbreviate(String value) {
        if (value == null) return "unknown error";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
