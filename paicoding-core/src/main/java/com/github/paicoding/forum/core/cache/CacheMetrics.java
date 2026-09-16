package com.github.paicoding.forum.core.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CacheMetrics {
    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public CacheMetrics(ObjectProvider<MeterRegistry> provider) {
        this.registry = provider.getIfAvailable();
        if (registry != null) {
            Gauge.builder("paicoding.redis.circuit.state", () -> {
                        RedisCircuitBreaker.State state = RedisClient.circuitState();
                        return state == RedisCircuitBreaker.State.CLOSED ? 0D
                                : state == RedisCircuitBreaker.State.OPEN ? 1D : 0.5D;
                    })
                    .description("Redis circuit state: closed=0, half-open=0.5, open=1")
                    .register(registry);
        }
    }

    public void request(String cache, String result) { increment("paicoding.cache.requests", cache, result); }
    public void origin(String cache, String result) { increment("paicoding.cache.origin.loads", cache, result); }
    public void hotKey(String cache) { increment("paicoding.cache.hot.key.events", cache, "threshold"); }
    public void refresh(String cache, String result) { increment("paicoding.cache.refresh", cache, result); }
    public void preheat(String cache, String result) { increment("paicoding.cache.preheat", cache, result); }

    private void increment(String name, String cache, String result) {
        if (registry == null) return;
        String id = name + '|' + cache + '|' + result;
        counters.computeIfAbsent(id, ignored -> Counter.builder(name)
                .tag("cache", sanitize(cache)).tag("result", result).register(registry)).increment();
    }

    private String sanitize(String cache) {
        return cache == null || cache.isEmpty() ? "default" : cache;
    }
}
