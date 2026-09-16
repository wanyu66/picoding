package com.github.paicoding.forum.service.rank.repository.dao;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.paicoding.forum.service.rank.repository.entity.ActivityRankEventDO;
import com.github.paicoding.forum.service.rank.repository.mapper.ActivityRankEventMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ActivityRankEventDao extends ServiceImpl<ActivityRankEventMapper, ActivityRankEventDO> {
    public ActivityRankEventDO findByEventId(String eventId) {
        return baseMapper.selectByEventId(eventId);
    }

    public ActivityRankEventDO findActiveOriginalForUpdate(Long userId, String actionType, String targetId) {
        return baseMapper.selectActiveOriginalForUpdate(userId, actionType, targetId);
    }

    public List<ActivityRankEventDO> listPending(int size) {
        return baseMapper.listPending(size);
    }

    public List<ActivityRankEventDO> listByMonthBucket(String monthBucket) {
        return baseMapper.listByMonthBucket(monthBucket);
    }

    public long countByDayBucket(String dayBucket) {
        return baseMapper.countByDayBucket(dayBucket);
    }

    public long countByMonthBucket(String monthBucket) {
        return baseMapper.countByMonthBucket(monthBucket);
    }

    public void markRedisApplied(Long id) {
        ActivityRankEventDO update = new ActivityRankEventDO();
        update.setId(id);
        update.setRedisApplied(1);
        updateById(update);
    }
}
