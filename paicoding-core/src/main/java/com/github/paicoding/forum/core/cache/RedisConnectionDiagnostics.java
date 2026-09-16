package com.github.paicoding.forum.core.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import javax.management.ObjectName;

/** Decorates templates, leaving the provider bean and its lifecycle intact. */
@Slf4j
@Component
public class RedisConnectionDiagnostics implements BeanPostProcessor {
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${observability.redis.pool-log-interval-ms:30000}")
    public void logPoolState() {
        log.info("redis_pool_state circuit={} pools={}", RedisClient.circuitState(), poolSnapshot());
    }
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof RedisTemplate)) return bean;
        RedisTemplate<?, ?> template = (RedisTemplate<?, ?>) bean;
        RedisConnectionFactory delegate = template.getConnectionFactory();
        if (delegate == null) return bean;
        RedisConnectionFactory monitored = (RedisConnectionFactory) Proxy.newProxyInstance(
                RedisConnectionFactory.class.getClassLoader(), new Class<?>[]{RedisConnectionFactory.class},
                (proxy, method, args) -> {
                    boolean acquire = method.getName().equals("getConnection") || method.getName().equals("getClusterConnection");
                    long start = System.nanoTime();
                    try {
                        Object result = invoke(delegate, method, args);
                        return acquire ? commands(result, method.getReturnType()) : result;
                    } catch (Throwable error) {
                        if (acquire) log.error("redis_connection_acquire_failed provider={} elapsedMs={} pools={}",
                                delegate.getClass().getSimpleName(), (System.nanoTime() - start) / 1_000_000D, poolSnapshot(), error);
                        throw error;
                    } finally {
                        if (acquire) RequestIoStats.acquired(System.nanoTime() - start);
                    }
                });
        template.setConnectionFactory(monitored);
        String configuration = delegate instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
                ? ((org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) delegate).getClientConfiguration().getClass().getSimpleName()
                : "unknown";
        log.info("redis_connection_diagnostics provider={} clientConfiguration={} pools={}", delegate.getClass().getName(), configuration, poolSnapshot());
        return bean;
    }

    private Object commands(Object target, Class<?> api) {
        if (target == null || !api.isInterface()) return target;
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            String name = method.getName();
            boolean nested = name.endsWith("Commands") && method.getReturnType().isInterface();
            if (method.getDeclaringClass() != Object.class && !nested && !name.startsWith("is")
                    && !name.equals("close") && !name.equals("openPipeline") && !name.equals("closePipeline")
                    && !name.equals("getNativeConnection")) RequestIoStats.redis(name);
            Object result = invoke(target, method, args);
            return nested ? commands(result, method.getReturnType()) : result;
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    /** Actual Commons Pool borrow wait, distinct from provider acquisition latency. */
    public static String poolSnapshot() {
        try {
            StringBuilder result = new StringBuilder();
            for (ObjectName name : ManagementFactory.getPlatformMBeanServer().queryNames(
                    new ObjectName("org.apache.commons.pool2:type=GenericObjectPool,*"), null)) {
                result.append(name).append(' ');
                for (String attribute : new String[]{"NumActive", "NumIdle", "NumWaiters", "MeanBorrowWaitTimeMillis", "MaxBorrowWaitTimeMillis"}) {
                    result.append(attribute).append('=').append(ManagementFactory.getPlatformMBeanServer().getAttribute(name, attribute)).append(' ');
                }
            }
            return result.length() == 0 ? "unavailable:no_registered_pool(shared_connection_or_JMX_disabled)" : result.toString();
        } catch (Exception error) { return "unavailable:" + error.getClass().getSimpleName(); }
    }
}
