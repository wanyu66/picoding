package com.github.paicoding.forum.service.rank.repository.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.github.paicoding.forum.api.model.entity.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/** 可重放的排行榜行为日志。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_rank_event")
public class ActivityRankEventDO extends BaseDO {
    private String eventId;
    private String originalEventId;
    private String cancelledByEventId;
    private Long userId;
    private String actionType;
    private String targetId;
    private Integer scoreDelta;
    private Date eventTime;
    private String dayBucket;
    private String monthBucket;
    private Integer redisApplied;
}
