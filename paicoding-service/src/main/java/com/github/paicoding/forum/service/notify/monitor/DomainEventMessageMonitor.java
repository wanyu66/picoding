package com.github.paicoding.forum.service.notify.monitor;

import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class DomainEventMessageMonitor {
    private final RabbitAdmin rabbitAdmin;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();
    private final Counter retries;
    private final Counter deadLettered;
    private final Counter pushSuccess;
    private final Counter pushFailure;
    private final Counter publisherAck;
    private final Counter publisherNack;
    private final Counter returned;
    private final Timer consumeDelay;

    public DomainEventMessageMonitor(MeterRegistry registry, RabbitAdmin rabbitAdmin) {
        this.rabbitAdmin = rabbitAdmin;
        Gauge.builder("paicoding.rabbitmq.domain.backlog", backlog, AtomicLong::get).register(registry);
        Gauge.builder("paicoding.rabbitmq.domain.dead_letters", deadLetters, AtomicLong::get).register(registry);
        retries = Counter.builder("paicoding.rabbitmq.domain.retries").register(registry);
        deadLettered = Counter.builder("paicoding.rabbitmq.domain.dead_lettered_total").register(registry);
        pushSuccess = Counter.builder("paicoding.notification.push").tag("result", "success").register(registry);
        pushFailure = Counter.builder("paicoding.notification.push").tag("result", "failure").register(registry);
        publisherAck = Counter.builder("paicoding.rabbitmq.publisher.confirm").tag("result", "ack").register(registry);
        publisherNack = Counter.builder("paicoding.rabbitmq.publisher.confirm").tag("result", "nack").register(registry);
        returned = Counter.builder("paicoding.rabbitmq.publisher.returned").register(registry);
        consumeDelay = Timer.builder("paicoding.rabbitmq.domain.consume_delay").publishPercentileHistogram().register(registry);
        Gauge.builder("paicoding.notification.push.success_ratio", this, DomainEventMessageMonitor::successRatio).register(registry);
    }

    public void recordConsumed(DomainEvent event) {
        Date occurredAt = event.getOccurredAt();
        if (occurredAt != null) {
            consumeDelay.record(Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - occurredAt.getTime())));
        }
    }

    public void recordRetries(int count) { retries.increment(count); }
    public void recordDeadLettered() { deadLettered.increment(); }
    public void recordPush(boolean success) { (success ? pushSuccess : pushFailure).increment(); }
    public void recordPublisherConfirm(boolean ack) { (ack ? publisherAck : publisherNack).increment(); }
    public void recordReturned() { returned.increment(); }

    @Scheduled(fixedDelayString = "${rabbitmq.monitor.interval-ms:15000}")
    public void refreshQueueMetrics() {
        backlog.set(messageCount(RabbitMqAutoConfig.QUEUE) + messageCount(RabbitMqAutoConfig.ACTIVITY_QUEUE)
                + messageCount(RabbitMqAutoConfig.STATISTICS_QUEUE) + messageCount(RabbitMqAutoConfig.SEARCH_QUEUE));
        deadLetters.set(messageCount(RabbitMqAutoConfig.DLQ) + messageCount(RabbitMqAutoConfig.ACTIVITY_DLQ)
                + messageCount(RabbitMqAutoConfig.STATISTICS_DLQ) + messageCount(RabbitMqAutoConfig.SEARCH_DLQ));
    }

    private long messageCount(String queue) {
        Properties properties = rabbitAdmin.getQueueProperties(queue);
        Object value = properties == null ? null : properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private double successRatio() {
        double total = pushSuccess.count() + pushFailure.count();
        return total == 0D ? 1D : pushSuccess.count() / total;
    }
}
