package com.github.paicoding.forum.core.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.QueryTimeoutException;
import java.util.function.Supplier;

/** Process-wide Redis circuit breaker with a single half-open probe. */
@Slf4j
public class RedisCircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final CacheProtectionProperties properties;
    private int failures;
    private long openUntil;
    private long generation;
    private boolean probeRunning;
    private long lastCommandErrorLog;

    public RedisCircuitBreaker(CacheProtectionProperties properties) {
        this.properties = properties;
    }

    public <T> T execute(Supplier<T> operation) {
        long ticket = acquirePermission();
        long started = System.nanoTime();
        try {
            T result = operation.get();
            succeeded(ticket);
            return result;
        } catch (RuntimeException e) {
            failed(ticket, e, (System.nanoTime() - started) / 1_000_000D);
            throw e;
        }
    }

    private synchronized long acquirePermission() {
        if (openUntil == 0L) {
            return generation;
        }
        if (System.currentTimeMillis() < openUntil) {
            throw new RedisCircuitOpenException("Redis circuit is open");
        }
        if (probeRunning) {
            throw new RedisCircuitOpenException("Redis circuit is half-open");
        }
        probeRunning = true;
        log.info("redis_circuit_transition from=OPEN to=HALF_OPEN generation={}", generation);
        return generation;
    }

    private synchronized void succeeded(long ticket) {
        // A success already in flight before opening cannot close a new circuit generation.
        if (ticket != generation) return;
        if (openUntil != 0) log.info("redis_circuit_transition from=HALF_OPEN to=CLOSED generation={}", generation);
        failures = 0;
        openUntil = 0;
        probeRunning = false;
    }

    private synchronized void failed(long ticket, RuntimeException error, double elapsedMs) {
        if (ticket != generation) return;
        if (!isAvailabilityFailure(error)) {
            if (System.currentTimeMillis() - lastCommandErrorLog > 10000) {
                lastCommandErrorLog = System.currentTimeMillis();
                log.error("redis_command_failed circuitUnaffected=true elapsedMs={}", elapsedMs, error);
            }
            // Data/type/serialization errors do not prove Redis is unavailable.
            succeeded(ticket);
            return;
        }
        failures++;
        if (failures == 1 || probeRunning) {
            log.error("redis_first_availability_failure failures={} elapsedMs={} pools={}", failures,
                    elapsedMs, RedisConnectionDiagnostics.poolSnapshot(), error);
        }
        if (probeRunning || failures >= Math.max(1, properties.getBreakerFailureThreshold())) {
            openUntil = System.currentTimeMillis() + Math.max(1L, properties.getBreakerOpenSeconds()) * 1000L;
            generation++;
            probeRunning = false;
            log.error("redis_circuit_transition to=OPEN failures={} openUntil={} generation={}", failures, openUntil, generation);
        }
    }

    static boolean isAvailabilityFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException || cause instanceof QueryTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.SocketException
                    || cause.getClass().getSimpleName().equals("RedisCommandTimeoutException")) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    public synchronized State getState() {
        if (openUntil == 0L) return State.CLOSED;
        return System.currentTimeMillis() < openUntil ? State.OPEN : State.HALF_OPEN;
    }

    public static class RedisCircuitOpenException extends RuntimeException {
        public RedisCircuitOpenException(String message) { super(message); }
    }
}
