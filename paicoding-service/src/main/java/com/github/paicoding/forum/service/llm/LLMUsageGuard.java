package com.github.paicoding.forum.service.llm;

import com.github.paicoding.forum.core.cache.RedisClient;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class LLMUsageGuard {
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private final ConcurrentHashMap<String, AtomicInteger> concurrency = new ConcurrentHashMap<>();

    @Autowired
    private LLMRouteProperties properties;
    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    public Permit acquire(Long userId, LLMScene scene) {
        String identity = String.valueOf(userId == null ? 0L : userId);
        String concurrentKey = identity;
        AtomicInteger active = concurrency.computeIfAbsent(concurrentKey, key -> new AtomicInteger());
        if (active.incrementAndGet() > Math.max(1, properties.getMaxConcurrentPerUser())) {
            active.decrementAndGet();
            rejected(scene, "concurrency");
            throw new IllegalStateException("LLM user concurrency limit exceeded");
        }
        try {
            long minute = increment("llm:quota:minute:" + LocalDateTime.now().format(MINUTE), identity, 120L);
            if (minute > Math.max(1, properties.getPerMinute())) {
                rejected(scene, "rate");
                throw new IllegalStateException("LLM user rate limit exceeded");
            }
            long daily = increment("llm:quota:daily:" + LocalDate.now(), identity, 172800L);
            if (daily > Math.max(1, properties.getDailyQuota())) {
                rejected(scene, "daily_quota");
                throw new IllegalStateException("LLM user daily quota exceeded");
            }
            if (daily == properties.getAbnormalDailyUsage()) {
                log.warn("abnormal LLM consumption: userHash={}, scene={}, dailyRequests={}", identity.hashCode(), scene, daily);
                MeterRegistry registry = meterRegistryProvider.getIfAvailable();
                if (registry != null) registry.counter("paicoding.llm.abnormal_consumption_total", "scene", scene.name()).increment();
            }
            return new Permit(active);
        } catch (RuntimeException e) {
            active.decrementAndGet();
            throw e;
        }
    }

    private long increment(String key, String field, long ttl) {
        Long count = RedisClient.hIncr(key, field, 1);
        if (count != null && count == 1L) RedisClient.expire(key, ttl);
        return count == null ? 0L : count;
    }

    private void rejected(LLMScene scene, String reason) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) registry.counter("paicoding.llm.guard.rejected_total", "scene", scene.name(), "reason", reason).increment();
    }

    public static final class Permit implements AutoCloseable {
        private final AtomicInteger active;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit(AtomicInteger active) { this.active = active; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) active.decrementAndGet();
        }
    }
}
