package com.github.paicoding.forum.service.rank.service.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @author YiHui
 * @date 2023/8/19
 */
@Data
@Accessors(chain = true)
public class ActivityScoreBo {
    /** 生产方生成并在重试/重投时保持不变的事件 ID。 */
    private String eventId;

    /** 取消事件明确关联的原始加分事件；为空时由行为日志锁定当前有效原行为。 */
    private String originalEventId;

    /** 业务事件发生时间（毫秒）。 */
    private Long eventTime;

    /**
     * 访问页面增加活跃度
     */
    private String path;

    /**
     * 目标文章
     */
    private Long articleId;

    /**
     * 评论增加活跃度
     */
    private Boolean rate;

    /**
     * 点赞增加活跃度
     */
    private Boolean praise;

    /**
     * 收藏增加活跃度
     */
    private Boolean collect;

    /**
     * 发布文章增加活跃度
     */
    private Boolean publishArticle;

    /**
     * 被关注的用户
     */
    private Long followedUserId;

    /**
     * 关注增加活跃度
     */
    private Boolean follow;
}
