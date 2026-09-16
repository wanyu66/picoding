package com.github.paicoding.forum.core.cache;

import com.github.paicoding.forum.core.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Unified cache-aside implementation: jittered TTL, mutex rebuild, stale-while-revalidate,
 * batch preheating, circuit-breaker fallback and bounded-cardinality metrics.
 */
@Component
public class ProtectedCache {
    private static final Logger log = LoggerFactory.getLogger(ProtectedCache.class);
    private static final String NULL_VALUE = "__PAI_CACHE_NULL__";
    private static final String UNLOCK_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final CacheProtectionProperties properties;
    private final CacheMetrics metrics;
    private final HotKeyMonitor hotKeys;
    private final ExecutorService refreshExecutor;

    public ProtectedCache(CacheProtectionProperties properties, CacheMetrics metrics, HotKeyMonitor hotKeys) {
        this.properties = properties;
        this.metrics = metrics;
        this.hotKeys = hotKeys;
        int threads = Math.max(1, properties.getRefreshThreads());
        this.refreshExecutor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getRefreshQueueCapacity())), new CacheThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> T get(String cache, String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        return get(cache, key, type, ttl, loader, null, null);
    }

    public <T> T get(String cache, String key, Class<T> type, Duration ttl, Supplier<T> loader,
                     RedisBloomFilter bloomFilter, RedisBloomFilter.Spec bloomSpec) {
        hotKeys.record(cache, key);
        String redisKey = dataKey(cache, key);
        String cached = safeGet(redisKey);
        if (cached != null) return decodeAndRecord(cache, cached, type);
        if (bloomFilter != null && bloomSpec != null && !safeMightContain(bloomFilter, bloomSpec, key)) {
            metrics.request(cache, "bloom_reject");
            return null;
        }
        metrics.request(cache, "miss");
        String token = tryLock(lockKey(cache, key));
        if (token == null) {
            cached = waitForValue(redisKey);
            if (cached != null) return decodeAndRecord(cache, cached, type);
            return loadOrigin(cache, loader);
        }
        try {
            cached = safeGet(redisKey);
            if (cached != null) return decodeAndRecord(cache, cached, type);
            T value = loadOrigin(cache, loader);
            safeSet(redisKey, value == null ? NULL_VALUE : JsonUtil.toStr(value), ttl);
            return value;
        } finally {
            unlock(lockKey(cache, key), token);
        }
    }

    public <T> T getLogical(String cache, String key, Class<T> type, Duration logicalTtl, Supplier<T> loader) {
        hotKeys.record(cache, key);
        String redisKey = dataKey(cache, key);
        String cached = safeGet(redisKey);
        if (cached == null) {
            metrics.request(cache, "miss");
            return rebuildLogical(cache, key, type, logicalTtl, loader);
        }
        LogicalValue envelope = JsonUtil.toObj(cached, LogicalValue.class);
        T value = envelope.data == null ? null : JsonUtil.toObj(envelope.data, type);
        if (envelope.expireAt > System.currentTimeMillis()) {
            metrics.request(cache, "hit");
            return value;
        }
        metrics.request(cache, "stale");
        String token = tryLock(lockKey(cache, key));
        if (token != null) {
            try {
                refreshExecutor.execute(() -> {
                    try {
                        T fresh = loadOrigin(cache, loader);
                        putLogical(cache, key, fresh, logicalTtl);
                        metrics.refresh(cache, "success");
                    } catch (RuntimeException e) {
                        metrics.refresh(cache, "failure");
                        log.warn("async cache refresh failed: cache={}, key={}", cache, key, e);
                    } finally {
                        unlock(lockKey(cache, key), token);
                    }
                });
            } catch (RejectedExecutionException e) {
                metrics.refresh(cache, "rejected");
                unlock(lockKey(cache, key), token);
            }
        }
        return value;
    }

    private <T> T rebuildLogical(String cache, String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        String token = tryLock(lockKey(cache, key));
        if (token == null) {
            String value = waitForValue(dataKey(cache, key));
            if (value != null) {
                LogicalValue envelope = JsonUtil.toObj(value, LogicalValue.class);
                return envelope.data == null ? null : JsonUtil.toObj(envelope.data, type);
            }
            return loadOrigin(cache, loader);
        }
        try {
            T value = loadOrigin(cache, loader);
            putLogical(cache, key, value, ttl);
            return value;
        } finally { unlock(lockKey(cache, key), token); }
    }

    public <T> void putLogical(String cache, String key, T value, Duration logicalTtl) {
        LogicalValue envelope = new LogicalValue();
        envelope.data = value == null ? null : JsonUtil.toStr(value);
        envelope.expireAt = System.currentTimeMillis() + logicalTtl.toMillis();
        long physicalSeconds = Math.max(properties.getLogicalPhysicalTtlSeconds(), logicalTtl.getSeconds() + 1L);
        safeSet(dataKey(cache, key), JsonUtil.toStr(envelope), Duration.ofSeconds(physicalSeconds));
    }

    public <T> void preheat(String cache, Map<String, T> entries, Duration ttl) {
        if (entries == null || entries.isEmpty()) return;
        int batchSize = Math.max(1, properties.getPreheatBatchSize());
        List<Map.Entry<String, T>> batch = new ArrayList<>(batchSize);
        try {
            for (Map.Entry<String, T> entry : entries.entrySet()) {
                batch.add(entry);
                if (batch.size() == batchSize) { writeBatch(cache, batch, ttl); batch.clear(); }
            }
            if (!batch.isEmpty()) writeBatch(cache, batch, ttl);
            metrics.preheat(cache, "success");
        } catch (RuntimeException e) {
            metrics.preheat(cache, "failure");
            throw e;
        }
    }

    private <T> void writeBatch(String cache, List<Map.Entry<String, T>> batch, Duration ttl) {
        RedisClient.PipelineAction pipeline = RedisClient.pipelineAction();
        for (Map.Entry<String, T> entry : batch) {
            long seconds = CacheTtl.jitteredSeconds(ttl, properties.getTtlJitterRatio());
            byte[] value = RedisClient.valBytes(entry.getValue() == null ? NULL_VALUE : JsonUtil.toStr(entry.getValue()));
            pipeline.add(dataKey(cache, entry.getKey()), (connection, key) -> connection.setEx(key, seconds, value));
        }
        pipeline.execute();
    }

    private <T> T loadOrigin(String cache, Supplier<T> loader) {
        try {
            T value = loader.get(); metrics.origin(cache, "success"); return value;
        } catch (RuntimeException e) { metrics.origin(cache, "failure"); throw e; }
    }

    private String safeGet(String key) {
        if (!properties.isEnabled()) return null;
        try { return RedisClient.getStr(key); }
        catch (RuntimeException e) { return null; }
    }

    private void safeSet(String key, String value, Duration ttl) {
        if (!properties.isEnabled()) return;
        try { RedisClient.setStrWithExpire(key, value, CacheTtl.jitteredSeconds(ttl, properties.getTtlJitterRatio())); }
        catch (RuntimeException e) { log.debug("cache write degraded: key={}", key, e); }
    }

    private boolean safeMightContain(RedisBloomFilter filter, RedisBloomFilter.Spec spec, String key) {
        try { return filter.mightContain(spec, key); }
        catch (RuntimeException e) { return true; }
    }

    private String tryLock(String key) {
        if (!properties.isEnabled()) return null;
        String token = UUID.randomUUID().toString();
        try {
            return Boolean.TRUE.equals(RedisClient.setStrIfAbsentWithExpire(key, token,
                    Math.max(1L, properties.getLockLeaseSeconds()))) ? token : null;
        } catch (RuntimeException e) { return null; }
    }

    private void unlock(String key, String token) {
        if (token == null) return;
        try { RedisClient.executeLongScript(UNLOCK_SCRIPT, java.util.Collections.singletonList(key), token); }
        catch (RuntimeException e) { log.debug("cache lock release failed: key={}", key, e); }
    }

    private String waitForValue(String redisKey) {
        if (!properties.isEnabled()) return null;
        long deadline = System.currentTimeMillis() + Math.max(0L, properties.getLockWaitMillis());
        do {
            String value = safeGet(redisKey);
            if (value != null) return value;
            try { Thread.sleep(10L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private <T> T decodeAndRecord(String cache, String value, Class<T> type) {
        metrics.request(cache, "hit");
        return NULL_VALUE.equals(value) ? null : JsonUtil.toObj(value, type);
    }

    private String dataKey(String cache, String key) { return "cache:" + cache + ':' + key; }
    private String lockKey(String cache, String key) { return "cache:lock:" + cache + ':' + key; }

    @PreDestroy public void close() { refreshExecutor.shutdown(); }

    public static class LogicalValue {
        public String data;
        public long expireAt;
        public LogicalValue() { }
    }

    private static class CacheThreadFactory implements ThreadFactory {
        private int sequence;
        public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "cache-refresh-" + (++sequence));
            thread.setDaemon(true); return thread;
        }
    }
}
