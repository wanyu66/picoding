package com.github.paicoding.forum.service.notify.service.impl;

import com.github.paicoding.forum.api.model.enums.DocumentTypeEnum;
import com.github.paicoding.forum.api.model.enums.NotifyStatEnum;
import com.github.paicoding.forum.api.model.enums.NotifyTypeEnum;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import com.github.paicoding.forum.service.comment.repository.entity.CommentDO;
import com.github.paicoding.forum.service.comment.service.CommentReadService;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;
import com.github.paicoding.forum.service.notify.monitor.DomainEventMessageMonitor;
import com.github.paicoding.forum.service.notify.repository.dao.NotifyMsgDao;
import com.github.paicoding.forum.service.notify.repository.entity.NotifyMsgDO;
import com.github.paicoding.forum.service.notify.service.NotifyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Service
@ConditionalOnProperty(value = "rabbitmq.switchFlag", havingValue = "true")
public class NotificationDomainEventHandler {
    private static final String CONSUMER = "notification";
    private final JdbcTemplate jdbcTemplate;
    private final NotifyMsgDao notifyMsgDao;
    private final NotifyService notifyService;
    private final ArticleReadService articleReadService;
    private final CommentReadService commentReadService;
    private final DomainEventMessageMonitor monitor;

    public NotificationDomainEventHandler(JdbcTemplate jdbcTemplate, NotifyMsgDao notifyMsgDao, NotifyService notifyService,
                                    ArticleReadService articleReadService, CommentReadService commentReadService,
                                    DomainEventMessageMonitor monitor) {
        this.jdbcTemplate = jdbcTemplate;
        this.notifyMsgDao = notifyMsgDao;
        this.notifyService = notifyService;
        this.articleReadService = articleReadService;
        this.commentReadService = commentReadService;
        this.monitor = monitor;
    }

    @Transactional(rollbackFor = Exception.class)
    public void consume(DomainEvent event) {
        int acquired = jdbcTemplate.update("INSERT IGNORE INTO domain_event_consume " +
                        "(consumer_name,event_id,event_type,consume_time,create_time) VALUES(?,?,?,?,NOW())",
                CONSUMER, event.getEventId(), event.getEventType(), new java.util.Date());
        if (acquired == 0) {
            return;
        }
        DomainEventType type = DomainEventType.valueOf(event.getEventType());
        switch (type) {
            case CANCEL_PRAISE:
                notifyMsgDao.removeByBusinessKey(businessKey(event, DomainEventType.PRAISE));
                return;
            case CANCEL_COLLECT:
                notifyMsgDao.removeByBusinessKey(businessKey(event, DomainEventType.COLLECT));
                return;
            case CANCEL_FOLLOW:
                notifyMsgDao.removeByBusinessKey(businessKey(event, DomainEventType.FOLLOW));
                return;
            default:
                saveNotification(event, type);
        }
    }

    private void saveNotification(DomainEvent event, DomainEventType type) {
        DomainEvent.Target target = event.getTarget();
        NotifyTypeEnum notifyType = NotifyTypeEnum.valueOf(type.name());
        NotifyMsgDO msg = new NotifyMsgDO().setEventId(event.getEventId()).setBusinessKey(businessKey(event, type))
                .setRelatedId(target.getArticleId() == null ? target.getId() : target.getArticleId())
                .setCommentId(target.getCommentId()).setNotifyUserId(target.getOwnerId())
                .setOperateUserId(event.getOperatorId()).setType(notifyType.getType())
                .setState(NotifyStatEnum.UNREAD.getStat()).setMsg(event.getContent() == null ? "" : event.getContent());
        String pushText;
        if (type == DomainEventType.COMMENT) {
            ArticleDO article = articleReadService.queryBasicArticle(target.getId());
            pushText = String.format("您的文章《%s》收到一个新的评论，快去看看吧", article.getTitle());
        } else if (type == DomainEventType.REPLY) {
            CommentDO parent = commentReadService.queryComment(target.getParentId());
            pushText = String.format("您的评价《%s》收到一个新的回复，快去看看吧", parent.getContent());
        } else if (type == DomainEventType.FOLLOW) {
            msg.setRelatedId(0L);
            pushText = "恭喜您获得一枚新粉丝~";
        } else {
            if ("COMMENT".equals(target.getType())) {
                CommentDO comment = commentReadService.queryComment(target.getId());
                ArticleDO article = articleReadService.queryBasicArticle(comment.getArticleId());
                msg.setRelatedId(target.getId()).setMsg(String.format("%s了您在文章 <a href=\"/article/detail/%d\">%s</a> 下的评论 <span>%s</span>",
                        type == DomainEventType.PRAISE ? "赞" : "收藏", article.getId(), article.getTitle(), comment.getContent()));
            }
            pushText = String.format("太棒了，您的%s %s数+1!!!",
                    "ARTICLE".equals(target.getType()) ? "文章" : "评论", notifyType.getMsg());
        }
        if (notifyMsgDao.saveIdempotently(msg)) {
            pushAfterCommit(msg.getNotifyUserId(), notifyType, pushText);
        }
    }

    private String businessKey(DomainEvent event, DomainEventType effectiveType) {
        DomainEvent.Target target = event.getTarget();
        if (effectiveType == DomainEventType.COMMENT || effectiveType == DomainEventType.REPLY) {
            return effectiveType.name() + ":" + event.getEventId();
        }
        return effectiveType.name() + ":" + event.getOperatorId() + ":" + target.getId() + ":" + target.getOwnerId();
    }

    private void pushAfterCommit(Long userId, NotifyTypeEnum type, String text) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notifyService.notifyToUser(userId, type, text);
                    monitor.recordPush(true);
                } catch (RuntimeException e) {
                    monitor.recordPush(false);
                    throw e;
                }
            }
        });
    }
}
