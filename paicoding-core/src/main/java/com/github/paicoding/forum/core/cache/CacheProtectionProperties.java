package com.github.paicoding.forum.core.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Production cache protection defaults; all durations are in seconds. */
@ConfigurationProperties(prefix = "cache.protection")
public class CacheProtectionProperties {
    private boolean enabled = true;
    private double ttlJitterRatio = 0.15D;
    private long lockWaitMillis = 80L;
    private long lockLeaseSeconds = 10L;
    private long logicalPhysicalTtlSeconds = 86400L;
    private int refreshThreads = 4;
    private int refreshQueueCapacity = 500;
    private int preheatBatchSize = 200;
    private int breakerFailureThreshold = 5;
    private long breakerOpenSeconds = 30L;
    private int hotKeyThreshold = 100;
    private long hotKeyWindowSeconds = 60L;
    private int hotKeyMaxTracked = 1000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getTtlJitterRatio() { return ttlJitterRatio; }
    public void setTtlJitterRatio(double ttlJitterRatio) { this.ttlJitterRatio = ttlJitterRatio; }
    public long getLockWaitMillis() { return lockWaitMillis; }
    public void setLockWaitMillis(long lockWaitMillis) { this.lockWaitMillis = lockWaitMillis; }
    public long getLockLeaseSeconds() { return lockLeaseSeconds; }
    public void setLockLeaseSeconds(long lockLeaseSeconds) { this.lockLeaseSeconds = lockLeaseSeconds; }
    public long getLogicalPhysicalTtlSeconds() { return logicalPhysicalTtlSeconds; }
    public void setLogicalPhysicalTtlSeconds(long logicalPhysicalTtlSeconds) { this.logicalPhysicalTtlSeconds = logicalPhysicalTtlSeconds; }
    public int getRefreshThreads() { return refreshThreads; }
    public void setRefreshThreads(int refreshThreads) { this.refreshThreads = refreshThreads; }
    public int getRefreshQueueCapacity() { return refreshQueueCapacity; }
    public void setRefreshQueueCapacity(int refreshQueueCapacity) { this.refreshQueueCapacity = refreshQueueCapacity; }
    public int getPreheatBatchSize() { return preheatBatchSize; }
    public void setPreheatBatchSize(int preheatBatchSize) { this.preheatBatchSize = preheatBatchSize; }
    public int getBreakerFailureThreshold() { return breakerFailureThreshold; }
    public void setBreakerFailureThreshold(int breakerFailureThreshold) { this.breakerFailureThreshold = breakerFailureThreshold; }
    public long getBreakerOpenSeconds() { return breakerOpenSeconds; }
    public void setBreakerOpenSeconds(long breakerOpenSeconds) { this.breakerOpenSeconds = breakerOpenSeconds; }
    public int getHotKeyThreshold() { return hotKeyThreshold; }
    public void setHotKeyThreshold(int hotKeyThreshold) { this.hotKeyThreshold = hotKeyThreshold; }
    public long getHotKeyWindowSeconds() { return hotKeyWindowSeconds; }
    public void setHotKeyWindowSeconds(long hotKeyWindowSeconds) { this.hotKeyWindowSeconds = hotKeyWindowSeconds; }
    public int getHotKeyMaxTracked() { return hotKeyMaxTracked; }
    public void setHotKeyMaxTracked(int hotKeyMaxTracked) { this.hotKeyMaxTracked = hotKeyMaxTracked; }
}
