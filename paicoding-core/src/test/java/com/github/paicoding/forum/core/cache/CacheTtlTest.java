package com.github.paicoding.forum.core.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheTtlTest {
    @Test
    void jitterStaysInsideConfiguredRange() {
        for (int i = 0; i < 100; i++) {
            assertThat(CacheTtl.jitteredSeconds(Duration.ofSeconds(100), 0.15D)).isBetween(85L, 115L);
        }
    }

    @Test
    void ttlNeverBecomesNonPositive() {
        assertThat(CacheTtl.jitteredSeconds(Duration.ZERO, 0.9D)).isGreaterThan(0L);
    }

    @Test
    void bloomSpecCalculatesAUsableShape() {
        RedisBloomFilter.Spec spec = new RedisBloomFilter().spec("article", 100_000L, 0.01D);
        assertThat(spec.getBitSize()).isGreaterThan(100_000L);
        assertThat(spec.getHashFunctions()).isGreaterThan(1);
        assertThat(spec.redisKey()).isEqualTo("bloom:article");
    }
}
