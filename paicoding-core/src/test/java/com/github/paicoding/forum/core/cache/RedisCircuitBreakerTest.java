package com.github.paicoding.forum.core.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCircuitBreakerTest {
    @Test
    void defaultOpensOnFirstTimeoutAndRejectsFollowingOperations() {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(new CacheProtectionProperties());
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new org.springframework.dao.QueryTimeoutException("command timeout");
        })).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
        assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(() -> breaker.execute(calls::incrementAndGet))
                .isInstanceOf(RedisCircuitBreaker.RedisCircuitOpenException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    void onlyOneRecoveryProbeRunsAndFailedProbeReopensCircuit() throws Exception {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(new CacheProtectionProperties());
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new org.springframework.data.redis.RedisConnectionFailureException("down");
        }));
        org.springframework.test.util.ReflectionTestUtils.setField(breaker, "openUntil", System.currentTimeMillis() - 1);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> probe = executor.submit(() -> breaker.execute(() -> {
                entered.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                throw new org.springframework.dao.QueryTimeoutException("still down");
            }));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> breaker.execute(() -> "must not run"))
                    .isInstanceOf(RedisCircuitBreaker.RedisCircuitOpenException.class);
            release.countDown();
            assertThatThrownBy(() -> probe.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(org.springframework.dao.QueryTimeoutException.class);
            assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.OPEN);
            org.springframework.test.util.ReflectionTestUtils.setField(breaker, "openUntil", System.currentTimeMillis() - 1);
            assertThat(breaker.execute(() -> "recovered")).isEqualTo("recovered");
            assertThat(breaker.getState()).isEqualTo(RedisCircuitBreaker.State.CLOSED);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

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
