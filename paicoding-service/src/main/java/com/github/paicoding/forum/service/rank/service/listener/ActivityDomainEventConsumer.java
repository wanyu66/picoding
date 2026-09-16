package com.github.paicoding.forum.service.rank.service.listener;

import com.github.paicoding.forum.service.notify.config.RabbitMqAutoConfig;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;
import com.github.paicoding.forum.service.notify.service.impl.DomainEventConsumeService;
import com.github.paicoding.forum.service.rank.service.UserActivityRankService;
import com.github.paicoding.forum.service.rank.service.model.ActivityScoreBo;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class ActivityDomainEventConsumer {
    private final UserActivityRankService rankService;
    private final DomainEventConsumeService consumeService;

    public ActivityDomainEventConsumer(UserActivityRankService rankService, DomainEventConsumeService consumeService) {
        this.rankService = rankService;
        this.consumeService = consumeService;
    }

    @RabbitListener(queues = RabbitMqAutoConfig.ACTIVITY_QUEUE, containerFactory = "interactionRabbitListenerContainerFactory")
    public void consume(DomainEvent event) {
        consumeService.consume("activity", event, () -> handle(event));
    }

    private void handle(DomainEvent event) {
        DomainEventType type = DomainEventType.valueOf(event.getEventType());
        ActivityScoreBo score = new ActivityScoreBo().setEventId(event.getEventId())
                .setEventTime(event.getOccurredAt().getTime()).setArticleId(event.getTarget().getArticleId());
        switch (type) {
            case COMMENT: score.setRate(true); break;
            case COLLECT: score.setCollect(true); score.setArticleId(event.getTarget().getId()); break;
            case CANCEL_COLLECT: score.setCollect(false); score.setArticleId(event.getTarget().getId()); break;
            case PRAISE: score.setPraise(true); score.setArticleId(event.getTarget().getId()); break;
            case CANCEL_PRAISE: score.setPraise(false); score.setArticleId(event.getTarget().getId()); break;
            case FOLLOW: score.setFollow(true).setFollowedUserId(event.getTarget().getId()); break;
            case CANCEL_FOLLOW: score.setFollow(false).setFollowedUserId(event.getTarget().getId()); break;
            case ARTICLE_ONLINE: score.setPublishArticle(true).setArticleId(event.getTarget().getId()); break;
            default: return;
        }
        rankService.addActivityScore(event.getOperatorId(), score);
    }
}
