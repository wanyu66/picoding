package com.github.paicoding.forum.core.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCircuitBreakerTest {
    @Test
    void opensAfterConfiguredFailuresAndFailsFast() {
        CacheProtectionProperties properties = new CacheProtectionProperties();
        properties.setBreakerFailureThreshold(2);
        properties.setBreakerOpenSeconds(30);
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(properties);

        assertThatThrownBy(() -> breaker.execute(() -> { throw new org.springframework.data.redis.RedisConnectionFailureException("redis down"); }))
                .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
        assertThatThrownBy(() -> breaker.execute(() -> { throw new org.springframework.data.redis.RedisConnectionFailureException("redis down"); }))
                .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);

        assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> breaker.execute(() -> "never"))
                .isInstanceOf(RedisCircuitBreaker.RedisCircuitOpenException.class);
    }

    @Test
    void commandErrorsDoNotOpenTheAvailabilityCircuit() {
        CacheProtectionProperties properties = new CacheProtectionProperties();
        properties.setBreakerFailureThreshold(1);
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(properties);
        assertThatThrownBy(() -> breaker.execute(() -> { throw new IllegalArgumentException("WRONGTYPE"); }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.CLOSED);
        assertThat(breaker.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void oldInFlightSuccessCannotCloseANewerOpenCircuit() throws Exception {
        CacheProtectionProperties properties = new CacheProtectionProperties();
        properties.setBreakerFailureThreshold(1);
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(properties);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> old = executor.submit(() -> breaker.execute(() -> {
                started.countDown();
                try { finish.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
                return "old";
            }));
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> breaker.execute(() -> { throw new org.springframework.data.redis.RedisConnectionFailureException("down"); }));
            finish.countDown();
            assertThat(old.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("old");
            assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
}
