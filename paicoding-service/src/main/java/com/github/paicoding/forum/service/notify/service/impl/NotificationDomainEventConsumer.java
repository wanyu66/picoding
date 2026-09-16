package com.github.paicoding.forum.service.notify.service.impl;

import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.monitor.DomainEventMessageMonitor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class NotificationDomainEventConsumer {
    private final NotificationDomainEventHandler consumer;
    private final DomainEventMessageMonitor monitor;

    public NotificationDomainEventConsumer(NotificationDomainEventHandler consumer, DomainEventMessageMonitor monitor) {
        this.consumer = consumer;
        this.monitor = monitor;
    }

    @RabbitListener(queues = RabbitMqAutoConfig.QUEUE, containerFactory = "interactionRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        if (event.getEventType().startsWith("ARTICLE_") || event.getEventType().startsWith("DELETE_")) {
            return;
        }
        monitor.recordConsumed(event);
        consumer.consume(event); // 异常必须交给监听容器重试，禁止 catch 后吞掉
    }
}
