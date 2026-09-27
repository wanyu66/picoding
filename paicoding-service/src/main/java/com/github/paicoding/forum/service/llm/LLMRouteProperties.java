package com.github.paicoding.forum.service.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LLMRouteProperties {
    private Map<LLMScene, Route> routes = new EnumMap<>(LLMScene.class);
    private int maxRetries = 1;
    private long timeoutMs = 10000;
    /** Shared by primary and fallback, including retries. */
    private long totalTimeoutMs = 40000;
    private int maxTotalAttempts = 4;
    private int circuitFailureThreshold = 3;
    private long circuitOpenSeconds = 30;
    private int perMinute = 10;
    private int maxConcurrentPerUser = 2;
    private int dailyQuota = 100;
    private int abnormalDailyUsage = 80;

    public String modelFor(LLMScene scene, String provider) {
        Route route = routes.get(scene);
        if (route == null || StringUtils.isBlank(provider)) return null;
        if (provider.equalsIgnoreCase(route.getPrimaryProvider())) return route.getPrimaryModel();
        if (provider.equalsIgnoreCase(route.getFallbackProvider())) return route.getFallbackModel();
        return null;
    }

    @Data
    public static class Route {
        private String primaryProvider;
        private String primaryModel;
        private String fallbackProvider;
        private String fallbackModel;
        private Long timeoutMs;
        private Integer maxRetries;
        private Long totalTimeoutMs;
        private Integer maxTotalAttempts;
        private Double inputCostPerMillion = 0D;
        private Double outputCostPerMillion = 0D;
    }
}
