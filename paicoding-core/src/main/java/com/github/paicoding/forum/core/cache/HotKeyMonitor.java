package com.github.paicoding.forum.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class HotKeyMonitor {
    private static final Logger log = LoggerFactory.getLogger(HotKeyMonitor.class);
    private final CacheProtectionProperties properties;
    private final CacheMetrics metrics;
    private final ConcurrentMap<String, WindowCounter> keys = new ConcurrentHashMap<>();

    public HotKeyMonitor(CacheProtectionProperties properties, CacheMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public void record(String cache, String key) {
        long window = System.currentTimeMillis() / (Math.max(1L, properties.getHotKeyWindowSeconds()) * 1000L);
        String id = cache + ':' + key;
        WindowCounter counter = keys.get(id);
        if (counter == null) {
            if (keys.size() >= properties.getHotKeyMaxTracked()) {
                keys.entrySet().removeIf(entry -> entry.getValue().window != window);
                if (keys.size() >= properties.getHotKeyMaxTracked()) return;
            }
            WindowCounter fresh = new WindowCounter(window);
            WindowCounter old = keys.putIfAbsent(id, fresh);
            counter = old == null ? fresh : old;
        }
        long count = counter.increment(window);
        if (count == properties.getHotKeyThreshold()) {
            metrics.hotKey(cache);
            log.warn("cache hot key detected: cache={}, key={}, accesses={}, windowSeconds={}",
                    cache, key, count, properties.getHotKeyWindowSeconds());
        }
    }

    public Map<String, Long> hottest(int limit) {
        List<Map.Entry<String, WindowCounter>> entries = new ArrayList<>(keys.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, WindowCounter> e) -> e.getValue().count.get()).reversed());
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(Math.max(0, limit), entries.size()); i++) {
            result.put(entries.get(i).getKey(), entries.get(i).getValue().count.get());
        }
        return result;
    }

    private static class WindowCounter {
        private volatile long window;
        private final AtomicLong count = new AtomicLong();
        private WindowCounter(long window) { this.window = window; }
        private synchronized long increment(long currentWindow) {
            if (window != currentWindow) { window = currentWindow; count.set(0L); }
            return count.incrementAndGet();
        }
    }
}
