package com.github.paicoding.forum.core.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class CacheTtl {
    private CacheTtl() { }

    public static long jitteredSeconds(Duration ttl, double ratio) {
        long seconds = Math.max(1L, ttl.getSeconds());
        double safeRatio = Math.max(0D, Math.min(0.9D, ratio));
        long range = (long) (seconds * safeRatio);
        if (range == 0L) return seconds;
        return Math.max(1L, seconds + ThreadLocalRandom.current().nextLong(-range, range + 1L));
    }
}
