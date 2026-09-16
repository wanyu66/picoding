package com.github.paicoding.forum.core.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request-local counts; never records SQL parameters, Redis keys or credentials. */
public final class RequestIoStats {
    private static final ThreadLocal<RequestIoStats> CURRENT = new ThreadLocal<>();
    private final Map<String, Integer> sql = new LinkedHashMap<>();
    private final Map<String, Integer> redis = new LinkedHashMap<>();
    private int acquisitions;
    private long acquisitionNanos;
    private String stage = "request";
    public static <T> T stage(String name, java.util.function.Supplier<T> action) {
        RequestIoStats stats = CURRENT.get();
        if (stats == null) return action.get();
        String previous = stats.stage;
        stats.stage = name;
        try { return action.get(); } finally { stats.stage = previous; }
    }
    public static void begin() { CURRENT.set(new RequestIoStats()); }
    public static void sql(String table) { record(table, true); }
    public static void redis(String command) { record(command, false); }
    private static void record(String name, boolean database) {
        RequestIoStats stats = CURRENT.get();
        if (stats == null) return;
        Map<String, Integer> counts = database ? stats.sql : stats.redis;
        name = stats.stage + "/" + name;
        String key = counts.size() >= 64 && !counts.containsKey(name) ? "other" : name;
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }
    public static void acquired(long nanos) {
        RequestIoStats stats = CURRENT.get();
        if (stats != null) { stats.acquisitions++; stats.acquisitionNanos += nanos; }
    }
    public static String finish() {
        RequestIoStats stats = CURRENT.get();
        CURRENT.remove();
        if (stats == null) return "";
        return "sql=" + stats.sql + ", redisCommands=" + stats.redis + ", redisAcquisitions="
                + stats.acquisitions + ", redisAcquisitionMs=" + stats.acquisitionNanos / 1_000_000D;
    }
    private RequestIoStats() { }
}
