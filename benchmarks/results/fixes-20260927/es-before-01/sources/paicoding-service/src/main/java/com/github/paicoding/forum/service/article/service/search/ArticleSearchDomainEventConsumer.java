package com.github.paicoding.forum.service.article.service.search;

import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;
import com.github.paicoding.forum.service.notify.service.impl.DomainEventConsumeService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class ArticleSearchDomainEventConsumer {
    private final ArticleSearchService searchService;
    private final DomainEventConsumeService consumeService;

    public ArticleSearchDomainEventConsumer(ArticleSearchService searchService, DomainEventConsumeService consumeService) {
        this.searchService = searchService;
        this.consumeService = consumeService;
    }

    @RabbitListener(queues = RabbitMqAutoConfig.SEARCH_QUEUE, containerFactory = "interactionRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        if (!event.getEventType().startsWith("ARTICLE_")) return;
        consumeService.consume("article-search", event, () -> {
            DomainEventType type = DomainEventType.valueOf(event.getEventType());
            if (type == DomainEventType.ARTICLE_DELETE) searchService.deleteArticle(event.getTarget().getId());
            else searchService.syncArticle(event.getTarget().getId());
        });
    }
}
