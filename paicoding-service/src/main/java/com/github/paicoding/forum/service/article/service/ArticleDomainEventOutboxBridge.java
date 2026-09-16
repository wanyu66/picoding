package com.github.paicoding.forum.service.article.service;

import com.github.paicoding.forum.api.model.event.ArticleMsgEvent;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;
import com.github.paicoding.forum.service.notify.service.DomainEventOutboxService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 将现有文章领域事件在原业务事务提交前写入统一 Outbox。 */
@Component
public class ArticleDomainEventOutboxBridge {
    private final DomainEventOutboxService outboxService;

    public ArticleDomainEventOutboxBridge(DomainEventOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @TransactionalEventListener(classes = ArticleMsgEvent.class,
            phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void append(ArticleMsgEvent<?> source) {
        if (!(source.getContent() instanceof ArticleDO)) return;
        ArticleDO article = (ArticleDO) source.getContent();
        DomainEventType type = DomainEventType.valueOf("ARTICLE_" + source.getType().name());
        DomainEvent.Target target = DomainEvent.Target.builder().type("ARTICLE").id(article.getId())
                .articleId(article.getId()).ownerId(article.getUserId()).build();
        outboxService.append(outboxService.newEvent(type, article.getUserId(), target, null));
    }
}
