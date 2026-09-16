package com.github.paicoding.forum.service.statistics.listener;

import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.article.repository.dao.ArticleDao;
import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;
import com.github.paicoding.forum.service.notify.service.impl.DomainEventConsumeService;
import com.github.paicoding.forum.service.statistics.constants.CountConstants;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class StatisticDomainEventConsumer {
    private final ArticleDao articleDao;
    private final DomainEventConsumeService consumeService;

    public StatisticDomainEventConsumer(ArticleDao articleDao, DomainEventConsumeService consumeService) {
        this.articleDao = articleDao;
        this.consumeService = consumeService;
    }

    @RabbitListener(queues = RabbitMqAutoConfig.STATISTICS_QUEUE, containerFactory = "interactionRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        consumeService.consume("statistics", event, () -> handle(event));
    }

    private void handle(DomainEvent event) {
        DomainEventType type = DomainEventType.valueOf(event.getEventType());
        Long targetId = event.getTarget().getId();
        Long ownerId = event.getTarget().getOwnerId();
        switch (type) {
            case COMMENT: incrArticle(event.getTarget().getArticleId(), CountConstants.COMMENT_COUNT, 1); break;
            case DELETE_COMMENT: incrArticle(event.getTarget().getArticleId(), CountConstants.COMMENT_COUNT, -1); break;
            case COLLECT: incrUserAndArticle(ownerId, targetId, CountConstants.COLLECTION_COUNT, 1); break;
            case CANCEL_COLLECT: incrUserAndArticle(ownerId, targetId, CountConstants.COLLECTION_COUNT, -1); break;
            case PRAISE: incrUserAndArticle(ownerId, targetId, CountConstants.PRAISE_COUNT, 1); break;
            case CANCEL_PRAISE: incrUserAndArticle(ownerId, targetId, CountConstants.PRAISE_COUNT, -1); break;
            case FOLLOW: incrUser(ownerId, CountConstants.FANS_COUNT, 1); incrUser(event.getOperatorId(), CountConstants.FOLLOW_COUNT, 1); break;
            case CANCEL_FOLLOW: incrUser(ownerId, CountConstants.FANS_COUNT, -1); incrUser(event.getOperatorId(), CountConstants.FOLLOW_COUNT, -1); break;
            case ARTICLE_ONLINE:
            case ARTICLE_OFFLINE:
            case ARTICLE_DELETE:
                RedisClient.hSet(CountConstants.USER_STATISTIC_INFO + ownerId, CountConstants.ARTICLE_COUNT, articleDao.countArticleByUser(ownerId));
                break;
            default:
        }
    }

    private void incrUserAndArticle(Long userId, Long articleId, String field, int delta) {
        incrUser(userId, field, delta); incrArticle(articleId, field, delta);
    }
    private void incrUser(Long id, String field, int delta) { RedisClient.hIncr(CountConstants.USER_STATISTIC_INFO + id, field, delta); }
    private void incrArticle(Long id, String field, int delta) { RedisClient.hIncr(CountConstants.ARTICLE_STATISTIC_INFO + id, field, delta); }
}
