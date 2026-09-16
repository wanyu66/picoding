package com.github.paicoding.forum.service.rank.service.impl;

import com.github.paicoding.forum.api.model.enums.rank.ActivityRankTimeEnum;
import com.github.paicoding.forum.api.model.vo.rank.dto.RankItemDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.SimpleUserInfoDTO;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.rank.repository.dao.ActivityRankEventDao;
import com.github.paicoding.forum.service.rank.repository.entity.ActivityRankEventDO;
import com.github.paicoding.forum.service.rank.service.UserActivityRankService;
import com.github.paicoding.forum.service.rank.service.model.ActivityScoreBo;
import com.github.paicoding.forum.service.user.service.UserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** 用户活跃排行榜：MySQL 行为日志是事实源，Redis 是可重建投影。 */
@Slf4j
@Service
public class UserActivityRankServiceImpl implements UserActivityRankService {
    private static final String ACTIVITY_SCORE_KEY = "activity_rank_";
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * KEYS: 事件Hash、日榜ZSet、月榜ZSet、日/月首次达分Hash、日/月投影标记。
     * ARGV: eventId, originalEventId, userId, delta, eventTimeMillis, dayExpireAt, monthExpireAt, eventValue。
     */
    private static final String APPLY_SCORE_LUA =
            "if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then return 0 end\n" +
            "local original = ARGV[2]\n" +
            "if original ~= '' then\n" +
            "  local state = redis.call('HGET', KEYS[1], original)\n" +
            "  if (not state) or string.sub(state,1,1) == 'C' then return -1 end\n" +
            "  redis.call('HSET', KEYS[1], original, 'C|' .. ARGV[1])\n" +
            "end\n" +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[8])\n" +
            "local uid = ARGV[3]\n" +
            "local delta = tonumber(ARGV[4])\n" +
            "local now = ARGV[5]\n" +
            "local function updateRank(rankKey, tieKey, expireAt)\n" +
            "  local current = tonumber(redis.call('ZSCORE', rankKey, uid) or '0')\n" +
            "  local next = current + delta\n" +
            "  if next < 0 then next = 0 end\n" +
            "  if next == 0 then redis.call('ZREM', rankKey, uid)\n" +
            "  else\n" +
            "    redis.call('ZADD', rankKey, next, uid)\n" +
            "    redis.call('HSETNX', tieKey, uid .. ':' .. tostring(next), now)\n" +
            "  end\n" +
            "  redis.call('EXPIREAT', rankKey, expireAt)\n" +
            "  redis.call('EXPIREAT', tieKey, expireAt)\n" +
            "  return next\n" +
            "end\n" +
            "local dayScore = updateRank(KEYS[2], KEYS[4], ARGV[6])\n" +
            "updateRank(KEYS[3], KEYS[5], ARGV[7])\n" +
            "redis.call('SET', KEYS[6], '1') redis.call('EXPIREAT', KEYS[6], ARGV[6])\n" +
            "redis.call('SET', KEYS[7], '1') redis.call('EXPIREAT', KEYS[7], ARGV[7])\n" +
            "redis.call('EXPIREAT', KEYS[1], ARGV[7])\n" +
            "return dayScore";

    private static final String SUMMARIZE_MONTH_LUA =
            "if #KEYS <= 2 then return 0 end\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "local args = {KEYS[1], #KEYS - 2}\n" +
            "for i=3,#KEYS do table.insert(args, KEYS[i]) end\n" +
            "table.insert(args, 'AGGREGATE') table.insert(args, 'SUM')\n" +
            "redis.call('ZUNIONSTORE', unpack(args))\n" +
            "redis.call('EXPIREAT', KEYS[1], ARGV[1])\n" +
            "redis.call('SET', KEYS[2], '1') redis.call('EXPIREAT', KEYS[2], ARGV[1])\n" +
            "return redis.call('ZCARD', KEYS[1])";

    @Autowired
    private UserService userService;

    @Autowired
    private ActivityRankEventDao activityRankEventDao;

    @Value("${rank.day-retention-days:31}")
    private int dayRetentionDays;

    @Value("${rank.month-retention-months:12}")
    private int monthRetentionMonths;

    @Value("${rank.recovery-batch-size:500}")
    private int recoveryBatchSize;

    private final Object rebuildMonitor = new Object();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addActivityScore(Long userId, ActivityScoreBo activityScore) {
        if (userId == null || activityScore == null) {
            return;
        }
        ActionSpec action = actionSpec(activityScore);
        if (action == null) {
            return;
        }
        long eventMillis = activityScore.getEventTime() == null ? System.currentTimeMillis() : activityScore.getEventTime();
        String eventId = StringUtils.defaultIfBlank(activityScore.getEventId(), UUID.randomUUID().toString());
        ActivityRankEventDO existing = activityRankEventDao.findByEventId(eventId);
        if (existing != null) {
            applyPendingEvent(existing);
            return;
        }

        ActivityRankEventDO event = buildEvent(userId, eventId, action, eventMillis);
        if (action.score < 0) {
            ActivityRankEventDO original = StringUtils.isBlank(activityScore.getOriginalEventId())
                    ? activityRankEventDao.findActiveOriginalForUpdate(userId, action.type, action.targetId)
                    : activityRankEventDao.findByEventId(activityScore.getOriginalEventId());
            if (original == null || original.getScoreDelta() <= 0
                    || !Objects.equals(original.getUserId(), userId)
                    || !Objects.equals(original.getActionType(), action.type)
                    || !Objects.equals(original.getTargetId(), action.targetId)
                    || StringUtils.isNotBlank(original.getCancelledByEventId())) {
                log.debug("ignore rank cancellation without active original: eventId={}, userId={}, action={}, target={}",
                        eventId, userId, action.type, action.targetId);
                return;
            }
            event.setOriginalEventId(original.getEventId());
            event.setScoreDelta(-original.getScoreDelta());
            // 撤销必须回写原行为所属周期，不能让取消日/月产生负分。
            event.setDayBucket(original.getDayBucket());
            event.setMonthBucket(original.getMonthBucket());
            original.setCancelledByEventId(eventId);
            activityRankEventDao.updateById(original);
        }
        try {
            activityRankEventDao.save(event);
        } catch (DuplicateKeyException e) {
            event = activityRankEventDao.findByEventId(eventId);
        }
        applyPendingEvent(event);
    }

    private ActivityRankEventDO buildEvent(Long userId, String eventId, ActionSpec action, long eventMillis) {
        LocalDate date = Instant.ofEpochMilli(eventMillis).atZone(ZoneId.systemDefault()).toLocalDate();
        ActivityRankEventDO event = new ActivityRankEventDO();
        event.setEventId(eventId);
        event.setUserId(userId);
        event.setActionType(action.type);
        event.setTargetId(action.targetId);
        event.setScoreDelta(action.score);
        event.setEventTime(new Date(eventMillis));
        event.setDayBucket(DAY_FORMAT.format(date));
        event.setMonthBucket(MONTH_FORMAT.format(date));
        event.setRedisApplied(0);
        return event;
    }

    private void applyPendingEvent(ActivityRankEventDO event) {
        if (event == null || Objects.equals(event.getRedisApplied(), 1)) {
            return;
        }
        try {
            long result;
            synchronized (rebuildMonitor) {
                result = applyEventToRedis(event);
            }
            if (result < 0) {
                rebuildMonth(LocalDate.parse(event.getMonthBucket() + "01", DateTimeFormatter.ofPattern("yyyyMMdd")));
                result = 0;
            }
            if (result >= 0) {
                activityRankEventDao.markRedisApplied(event.getId());
                event.setRedisApplied(1);
            }
        } catch (Exception e) {
            // MySQL 日志保持 redis_applied=0，定时恢复任务会继续重放。
            log.warn("failed to project rank event to Redis: eventId={}", event.getEventId(), e);
        }
    }

    private long applyEventToRedis(ActivityRankEventDO event) {
        LocalDate day = LocalDate.parse(event.getDayBucket(), DAY_FORMAT);
        List<String> keys = new ArrayList<>();
        keys.add(eventLogKey(event.getMonthBucket()));
        keys.add(dayRankKey(event.getDayBucket()));
        keys.add(monthRankKey(event.getMonthBucket()));
        keys.add(dayTieKey(event.getDayBucket()));
        keys.add(monthTieKey(event.getMonthBucket()));
        keys.add(dayMarkerKey(event.getDayBucket()));
        keys.add(monthMarkerKey(event.getMonthBucket()));
        Long result = RedisClient.executeLongScript(APPLY_SCORE_LUA, keys,
                event.getEventId(), StringUtils.defaultString(event.getOriginalEventId()),
                String.valueOf(event.getUserId()), String.valueOf(event.getScoreDelta()),
                String.valueOf(event.getEventTime().getTime()), String.valueOf(dayExpireAt(day)),
                String.valueOf(monthExpireAt(day)), eventValue(event));
        return result == null ? -1L : result;
    }

    private String eventValue(ActivityRankEventDO event) {
        return (StringUtils.isBlank(event.getOriginalEventId()) ? "A" : "R") + "|" + event.getScoreDelta()
                + "|" + event.getUserId() + "|" + event.getActionType() + "|" + event.getTargetId();
    }

    private ActionSpec actionSpec(ActivityScoreBo activityScore) {
        if (activityScore.getPath() != null) {
            return new ActionSpec("path", StringUtils.left(activityScore.getPath(), 128), 1);
        }
        if (activityScore.getArticleId() != null) {
            String target = String.valueOf(activityScore.getArticleId());
            if (activityScore.getPraise() != null) {
                return new ActionSpec("praise", target, BooleanUtils.isTrue(activityScore.getPraise()) ? 2 : -2);
            }
            if (activityScore.getCollect() != null) {
                return new ActionSpec("collect", target, BooleanUtils.isTrue(activityScore.getCollect()) ? 2 : -2);
            }
            if (activityScore.getRate() != null) {
                return new ActionSpec("comment", target, BooleanUtils.isTrue(activityScore.getRate()) ? 3 : -3);
            }
            if (BooleanUtils.isTrue(activityScore.getPublishArticle())) {
                return new ActionSpec("publish", target, 10);
            }
        }
        if (activityScore.getFollowedUserId() != null) {
            return new ActionSpec("follow", String.valueOf(activityScore.getFollowedUserId()),
                    BooleanUtils.isTrue(activityScore.getFollow()) ? 2 : -2);
        }
        return null;
    }

    @Override
    public RankItemDTO queryRankInfo(Long userId, ActivityRankTimeEnum time) {
        List<RankItemDTO> all = queryRankList(time, Integer.MAX_VALUE);
        for (RankItemDTO item : all) {
            if (item.getUser() != null && Objects.equals(item.getUser().getUserId(), userId)) {
                return item;
            }
        }
        return new RankItemDTO().setUser(userService.querySimpleUserInfo(userId)).setRank(0).setScore(0);
    }

    @Override
    public List<RankItemDTO> queryRankList(ActivityRankTimeEnum time, int size) {
        LocalDate now = LocalDate.now();
        String rankKey = time == ActivityRankTimeEnum.DAY ? dayRankKey(DAY_FORMAT.format(now)) : monthRankKey(MONTH_FORMAT.format(now));
        String tieKey = time == ActivityRankTimeEnum.DAY ? dayTieKey(DAY_FORMAT.format(now)) : monthTieKey(MONTH_FORMAT.format(now));
        recoverIfRedisMissing(time, now);
        List<ImmutablePair<String, Double>> scores = RedisClient.zAllScore(rankKey);
        if (scores == null || scores.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Long> tieTimes = RedisClient.hGetAll(tieKey, Long.class);
        List<RankEntry> entries = scores.stream().map(score -> {
            long userId = Long.parseLong(score.getLeft());
            int points = score.getRight().intValue();
            long firstReached = tieTimes.getOrDefault(score.getLeft() + ":" + points, Long.MAX_VALUE);
            return new RankEntry(userId, points, firstReached);
        }).sorted(Comparator.comparingInt(RankEntry::getScore).reversed()
                .thenComparingLong(RankEntry::getFirstReached)
                .thenComparingLong(RankEntry::getUserId))
                .limit(Math.max(0, size)).collect(Collectors.toList());

        Map<Long, SimpleUserInfoDTO> users = userService.batchQuerySimpleUserInfo(
                entries.stream().map(RankEntry::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(SimpleUserInfoDTO::getUserId, user -> user));
        List<RankItemDTO> result = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            RankEntry entry = entries.get(i);
            SimpleUserInfoDTO user = users.get(entry.userId);
            if (user != null) {
                result.add(new RankItemDTO().setUser(user).setScore(entry.score).setRank(i + 1));
            }
        }
        return result;
    }

    @Scheduled(cron = "${rank.pending-recovery-cron:0 */5 * * * ?}")
    public void recoverPendingEvents() {
        List<ActivityRankEventDO> pending = activityRankEventDao.listPending(Math.max(1, recoveryBatchSize));
        for (ActivityRankEventDO event : pending) {
            applyPendingEvent(event);
        }
    }

    /** Redis 被清空或淘汰后，即使没有排行榜查询流量也能自动从 MySQL 恢复。 */
    @Scheduled(cron = "${rank.integrity-recovery-cron:30 */10 * * * ?}")
    public void recoverMissingCurrentRanks() {
        LocalDate now = LocalDate.now();
        recoverIfRedisMissing(ActivityRankTimeEnum.DAY, now);
        recoverIfRedisMissing(ActivityRankTimeEnum.MONTH, now);
    }

    /** 每日汇总所有日榜，覆盖生成月榜；重复执行结果不变。 */
    @Scheduled(cron = "${rank.daily-summary-cron:0 10 0 * * ?}")
    public void summarizeCurrentMonth() {
        summarizeMonth(LocalDate.now().minusDays(1));
        if (LocalDate.now().getDayOfMonth() == 1) {
            summarizeMonth(LocalDate.now());
        }
    }

    private void summarizeMonth(LocalDate dateInMonth) {
        LocalDate first = dateInMonth.withDayOfMonth(1);
        LocalDate last = dateInMonth.with(TemporalAdjusters.lastDayOfMonth());
        List<String> keys = new ArrayList<>();
        keys.add(monthRankKey(MONTH_FORMAT.format(first)));
        keys.add(monthMarkerKey(MONTH_FORMAT.format(first)));
        for (LocalDate day = first; !day.isAfter(last) && !day.isAfter(LocalDate.now()); day = day.plusDays(1)) {
            keys.add(dayRankKey(DAY_FORMAT.format(day)));
        }
        RedisClient.executeLongScript(SUMMARIZE_MONTH_LUA, keys, String.valueOf(monthExpireAt(first)));
    }

    @Override
    public void rebuildRank(ActivityRankTimeEnum time) {
        // 日榜事件也会影响月榜，统一重建整月以保持两个 ZSet 一致。
        rebuildMonth(LocalDate.now());
    }

    private void recoverIfRedisMissing(ActivityRankTimeEnum time, LocalDate date) {
        String dayBucket = DAY_FORMAT.format(date);
        String monthBucket = MONTH_FORMAT.format(date);
        String markerKey = time == ActivityRankTimeEnum.DAY ? dayMarkerKey(dayBucket) : monthMarkerKey(monthBucket);
        long eventCount = time == ActivityRankTimeEnum.DAY
                ? activityRankEventDao.countByDayBucket(dayBucket) : activityRankEventDao.countByMonthBucket(monthBucket);
        if (RedisClient.getStr(markerKey) == null && eventCount > 0) {
            rebuildMonth(date);
        }
    }

    private void rebuildMonth(LocalDate dateInMonth) {
        synchronized (rebuildMonitor) {
            LocalDate first = dateInMonth.withDayOfMonth(1);
            LocalDate next = first.plusMonths(1);
            String month = MONTH_FORMAT.format(first);
            RedisClient.del(eventLogKey(month));
            RedisClient.del(monthRankKey(month));
            RedisClient.del(monthTieKey(month));
            RedisClient.del(monthMarkerKey(month));
            for (LocalDate day = first; day.isBefore(next); day = day.plusDays(1)) {
                RedisClient.del(dayRankKey(DAY_FORMAT.format(day)));
                RedisClient.del(dayTieKey(DAY_FORMAT.format(day)));
                RedisClient.del(dayMarkerKey(DAY_FORMAT.format(day)));
            }
            List<ActivityRankEventDO> events = activityRankEventDao.listByMonthBucket(month);
            for (ActivityRankEventDO event : events) {
                long result = applyEventToRedis(event);
                if (result < 0) {
                    log.warn("failed to replay rank event: eventId={}", event.getEventId());
                }
            }
            log.info("rebuilt activity rank from MySQL event log: month={}, events={}", month, events.size());
        }
    }

    private long dayExpireAt(LocalDate day) {
        return day.plusDays(1L + Math.max(0, dayRetentionDays)).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
    }

    private long monthExpireAt(LocalDate day) {
        return day.withDayOfMonth(1).plusMonths(1L + Math.max(0, monthRetentionMonths))
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
    }

    private String dayRankKey(String bucket) { return ACTIVITY_SCORE_KEY + "day:" + bucket; }
    private String monthRankKey(String bucket) { return ACTIVITY_SCORE_KEY + "month:" + bucket; }
    private String dayTieKey(String bucket) { return ACTIVITY_SCORE_KEY + "day:first:" + bucket; }
    private String monthTieKey(String bucket) { return ACTIVITY_SCORE_KEY + "month:first:" + bucket; }
    private String dayMarkerKey(String bucket) { return ACTIVITY_SCORE_KEY + "day:ready:" + bucket; }
    private String monthMarkerKey(String bucket) { return ACTIVITY_SCORE_KEY + "month:ready:" + bucket; }
    private String eventLogKey(String bucket) { return ACTIVITY_SCORE_KEY + "events:" + bucket; }

    @Data
    @AllArgsConstructor
    private static class ActionSpec {
        private String type;
        private String targetId;
        private int score;
    }

    @Data
    @AllArgsConstructor
    private static class RankEntry {
        private long userId;
        private int score;
        private long firstReached;
    }
}
