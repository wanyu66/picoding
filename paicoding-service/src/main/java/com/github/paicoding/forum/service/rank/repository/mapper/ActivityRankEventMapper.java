package com.github.paicoding.forum.service.rank.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.paicoding.forum.service.rank.repository.entity.ActivityRankEventDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ActivityRankEventMapper extends BaseMapper<ActivityRankEventDO> {

    @Select("select * from activity_rank_event where event_id = #{eventId} limit 1")
    ActivityRankEventDO selectByEventId(@Param("eventId") String eventId);

    @Select("select * from activity_rank_event where user_id = #{userId} and action_type = #{actionType} " +
            "and target_id = #{targetId} and score_delta > 0 and cancelled_by_event_id is null " +
            "order by id desc limit 1 for update")
    ActivityRankEventDO selectActiveOriginalForUpdate(@Param("userId") Long userId,
                                                       @Param("actionType") String actionType,
                                                       @Param("targetId") String targetId);

    @Select("select * from activity_rank_event where redis_applied = 0 order by id asc limit #{size}")
    List<ActivityRankEventDO> listPending(@Param("size") int size);

    @Select("select * from activity_rank_event where month_bucket = #{monthBucket} order by event_time asc, id asc")
    List<ActivityRankEventDO> listByMonthBucket(@Param("monthBucket") String monthBucket);

    @Select("select count(*) from activity_rank_event where day_bucket = #{dayBucket}")
    long countByDayBucket(@Param("dayBucket") String dayBucket);

    @Select("select count(*) from activity_rank_event where month_bucket = #{monthBucket}")
    long countByMonthBucket(@Param("monthBucket") String monthBucket);
}
