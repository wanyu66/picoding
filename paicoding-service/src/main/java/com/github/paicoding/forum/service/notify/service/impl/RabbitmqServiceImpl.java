package com.github.paicoding.forum.service.notify.service.impl;

import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.notify.service.RabbitmqService;
import com.rabbitmq.client.BuiltinExchangeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 兼容旧接口的 Spring AMQP 适配器。新交互消息通过 Transactional Outbox 发布。
 */
@Slf4j
@Service
public class RabbitmqServiceImpl implements RabbitmqService {
    private final RabbitTemplate rabbitTemplate;

    public RabbitmqServiceImpl(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean enabled() {
        return "true".equalsIgnoreCase(SpringUtil.getConfig("rabbitmq.switchFlag"));
    }

    @Override
    public void publishMsg(String exchange, BuiltinExchangeType exchangeType, String routingKey, String message) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return msg;
        });
    }

    @Override
    public void consumerMsg(String exchange, String queueName, String routingKey) {
        log.info("Consumer is managed by @RabbitListener, queue={}", queueName);
    }

    @Override
    public void processConsumerMsg() {
        log.info("RabbitMQ consumers are managed by Spring listener containers");
    }
}
