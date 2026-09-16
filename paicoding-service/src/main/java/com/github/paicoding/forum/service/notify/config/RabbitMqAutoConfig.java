package com.github.paicoding.forum.service.notify.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.paicoding.forum.core.config.RabbitmqProperties;
import com.github.paicoding.forum.service.notify.monitor.DomainEventMessageMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class RabbitMqAutoConfig {
    public static final String EXCHANGE = "paicoding.domain.exchange";
    public static final String QUEUE = "paicoding.domain.notification.queue";
    public static final String ROUTING_KEY = "domain.event";
    public static final String DLX = "paicoding.domain.dlx";
    public static final String DLQ = "paicoding.domain.notification.dlq";
    public static final String DLQ_ROUTING_KEY = "domain.notification.dead";
    public static final String ACTIVITY_QUEUE = "paicoding.domain.activity.queue";
    public static final String STATISTICS_QUEUE = "paicoding.domain.statistics.queue";
    public static final String SEARCH_QUEUE = "paicoding.domain.search.queue";
    public static final String ACTIVITY_DLQ = "paicoding.domain.activity.dlq";
    public static final String STATISTICS_DLQ = "paicoding.domain.statistics.dlq";
    public static final String SEARCH_DLQ = "paicoding.domain.search.dlq";

    @Bean
    public ConnectionFactory rabbitConnectionFactory(RabbitmqProperties properties) {
        CachingConnectionFactory factory = new CachingConnectionFactory(properties.getHost(), properties.getPort());
        factory.setUsername(properties.getUsername());
        factory.setPassword(properties.getPassport());
        factory.setVirtualHost(properties.getVirtualhost());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean
    public Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, ObjectMapper objectMapper,
                                         DomainEventMessageMonitor monitor) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(rabbitMessageConverter(objectMapper));
        template.setMandatory(true);
        template.setConfirmCallback((data, ack, cause) -> {
            monitor.recordPublisherConfirm(ack);
            if (!ack) {
                log.error("RabbitMQ publisher nack, eventId={}, cause={}", data == null ? null : data.getId(), cause);
            }
        });
        template.setReturnsCallback(returned -> {
            monitor.recordReturned();
            log.error("RabbitMQ message returned, exchange={}, routingKey={}, reply={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });
        return template;
    }

    @Bean
    public DirectExchange interactionExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange interactionDeadLetterExchange() {
        return ExchangeBuilder.directExchange(DLX).durable(true).build();
    }

    @Bean
    public Queue interactionQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DLQ_ROUTING_KEY).build();
    }

    @Bean
    public Queue interactionDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Queue activityQueue() { return domainQueue(ACTIVITY_QUEUE, "domain.activity.dead"); }
    @Bean
    public Queue statisticsQueue() { return domainQueue(STATISTICS_QUEUE, "domain.statistics.dead"); }
    @Bean
    public Queue searchQueue() { return domainQueue(SEARCH_QUEUE, "domain.search.dead"); }
    @Bean
    public Queue activityDeadLetterQueue() { return QueueBuilder.durable(ACTIVITY_DLQ).build(); }
    @Bean
    public Queue statisticsDeadLetterQueue() { return QueueBuilder.durable(STATISTICS_DLQ).build(); }
    @Bean
    public Queue searchDeadLetterQueue() { return QueueBuilder.durable(SEARCH_DLQ).build(); }

    @Bean
    public Binding interactionBinding() {
        return BindingBuilder.bind(interactionQueue()).to(interactionExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding interactionDeadLetterBinding() {
        return BindingBuilder.bind(interactionDeadLetterQueue()).to(interactionDeadLetterExchange()).with(DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding activityBinding() { return BindingBuilder.bind(activityQueue()).to(interactionExchange()).with(ROUTING_KEY); }
    @Bean
    public Binding statisticsBinding() { return BindingBuilder.bind(statisticsQueue()).to(interactionExchange()).with(ROUTING_KEY); }
    @Bean
    public Binding searchBinding() { return BindingBuilder.bind(searchQueue()).to(interactionExchange()).with(ROUTING_KEY); }
    @Bean
    public Binding activityDeadLetterBinding() { return BindingBuilder.bind(activityDeadLetterQueue()).to(interactionDeadLetterExchange()).with("domain.activity.dead"); }
    @Bean
    public Binding statisticsDeadLetterBinding() { return BindingBuilder.bind(statisticsDeadLetterQueue()).to(interactionDeadLetterExchange()).with("domain.statistics.dead"); }
    @Bean
    public Binding searchDeadLetterBinding() { return BindingBuilder.bind(searchDeadLetterQueue()).to(interactionDeadLetterExchange()).with("domain.search.dead"); }

    private Queue domainQueue(String name, String deadRoutingKey) {
        return QueueBuilder.durable(name).deadLetterExchange(DLX).deadLetterRoutingKey(deadRoutingKey).build();
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public RabbitMdcTraceAdvice rabbitMdcTraceAdvice() {
        return new RabbitMdcTraceAdvice();
    }

    @Bean("interactionRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory listenerContainerFactory(ConnectionFactory connectionFactory,
                                                                         ObjectMapper objectMapper,
                                                                         DomainEventMessageMonitor monitor,
                                                                         RabbitMdcTraceAdvice rabbitMdcTraceAdvice) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter(objectMapper));
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(3));
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0D);
        backOff.setMaxInterval(5000L);
        retryTemplate.setBackOffPolicy(backOff);
        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                if (context.getRetryCount() < 3) {
                    monitor.recordRetries(1);
                }
            }
        });
        factory.setAdviceChain(rabbitMdcTraceAdvice, RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer((message, cause) -> {
                    monitor.recordDeadLettered();
                    throw new AmqpRejectAndDontRequeueException("interaction message retries exhausted", cause);
                }).build());
        return factory;
    }
}
