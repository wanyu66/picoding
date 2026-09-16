# 通用缓存防护

`paicoding-core` 的 `ProtectedCache` 统一提供 TTL 抖动、互斥重建、逻辑过期异步刷新、批量预热和指标埋点。所有 `RedisClient` 命令统一经过进程级熔断器；熔断开启时，`ProtectedCache` 自动回源，原有直接调用会快速失败，由各业务按自身语义降级。

## 普通缓存与热点互斥

```java
ArticleDTO article = protectedCache.get(
        "article", String.valueOf(articleId), ArticleDTO.class,
        Duration.ofMinutes(10), () -> articleRepository.findById(articleId));
```

未命中时只有取得 `SET NX EX` 锁的实例负责重建，释放锁使用 token 校验 Lua 脚本，避免误删其他实例的新锁。缓存写入的物理 TTL 自动增加随机抖动。

## 布隆过滤器与逻辑过期

```java
RedisBloomFilter.Spec articleIds = bloomFilter.spec("article-id", 1_000_000L, 0.01D);
bloomFilter.add(articleIds, articleId);

ArticleDTO article = protectedCache.get(
        "article", String.valueOf(articleId), ArticleDTO.class,
        Duration.ofMinutes(10), loader, bloomFilter, articleIds);

ArticleDTO hotArticle = protectedCache.getLogical(
        "hot-article", String.valueOf(articleId), ArticleDTO.class,
        Duration.ofMinutes(5), loader);
```

逻辑过期命中会立即返回旧值，只有抢到分布式锁的实例提交异步刷新。刷新线程池有固定容量，拒绝任务时会立即释放锁。

## 批量预热

```java
protectedCache.preheat("article", articleMap, Duration.ofMinutes(30));
```

预热按 `preheat-batch-size` 分批并使用 Redis Pipeline，每个 key 独立计算抖动后的 TTL。

## Prometheus 指标

- `paicoding_cache_requests_total{cache,result}`：`hit`、`miss`、`stale`、`bloom_reject`，可据此计算命中率。
- `paicoding_cache_origin_loads_total{cache,result}`：回源成功量与失败量。
- `paicoding_cache_refresh_total{cache,result}`：异步刷新结果。
- `paicoding_cache_hot_key_events_total{cache,result}`：热点阈值触发次数；具体 key 同时写入告警日志。
- `paicoding_cache_preheat_total{cache,result}`：批量预热结果。
- `paicoding_redis_circuit_state`：`0` 关闭、`0.5` 半开、`1` 打开。

所有阈值均位于 `application.yml` 的 `cache.protection` 下。`HotKeyMonitor.hottest(n)` 可供管理端读取当前窗口 Top N，指标标签不直接携带任意 key，以避免 Prometheus 高基数问题。
