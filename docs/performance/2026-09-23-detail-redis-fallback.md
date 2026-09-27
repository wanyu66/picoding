# 详情 Redis 故障快速回退（2026-09-23）

## 旧证据与等待位置

`detail-redis-outage-after-2026-09-17.json` 的三个并发详情请求耗时为 4104.30、4075.97、4079.00 ms，每个请求各执行一次 HGETALL 和 SISMEMBER，获取连接两次；连接获取总耗时仅约 0.03–0.045 ms。因此该轮约 4.1 秒主要不是连接池借用等待。

`ArticleReadServiceImpl.queryFullArticleInfo` 的顺序为：查询文章 → 持久化阅读 outbox → 查询统计 HGETALL → 查询点赞用户 → 敏感词豁免判断中的作者白名单 SISMEMBER。统计异常会回退数据库，白名单异常会读取持久配置，不会直接放行未知作者。

旧默认 `spring.redis.timeout=2s`，统计和白名单可以先后各等待一次命令超时，与该轮约 4.1 秒及两条命令的证据吻合。历史采样未记录每条命令的独立耗时，因此不能声称精确分解了旧请求的每一毫秒。

`RedisClient.execute` / `executePipelined` 在执行前取得熔断许可；`RedisCircuitBreaker.execute` 只有在同步调用抛出异常后才记录失败。它本身不计时中断正在执行的命令。旧默认累计 5 次可用性失败才打开，全局并发请求还可能已经取得许可；因此仅有熔断器并不保证首次故障快速回退。

## 改动

| 配置 | 原默认 | 新默认 | 作用 |
| --- | --- | --- | --- |
| `spring.redis.timeout` | 2s | 250ms | Lettuce 命令等待，也参与握手初始化等待 |
| `spring.redis.connect-timeout` | 未显式设置 | 250ms | TCP 连接建立超时 |
| `spring.redis.lettuce.pool.max-wait` | 500ms | 100ms | 连接池借用等待 |
| `cache.protection.breaker-failure-threshold` | 5 | 1 | 第一次可用性故障结束后打开熔断 |

生产 profile 中的命令超时同步更新，避免覆盖主配置后退回 2 秒。环境变量分别为 `PAICODING_REDIS_TIMEOUT`、`PAICODING_REDIS_CONNECT_TIMEOUT`、`PAICODING_REDIS_POOL_MAX_WAIT`、`PAICODING_REDIS_BREAKER_FAILURE_THRESHOLD`；Java 属性默认阈值同步改为 1。

这些是共享 Redis 客户端的默认值，不仅作用于详情。低超时可能在 Redis 短暂抖动时增加数据库回退，因此保留参数覆盖能力。连接、池等待及命令是不同阶段的预算，不等于整个 HTTP 请求 250ms 的硬期限；线程调度、首次客户端初始化和数据库操作另计。

熔断仍只由连接/超时等可用性异常触发，数据类型或参数错误不会打开可用性熔断。保留 30 秒打开窗口、单个半开探测和 generation 防止旧在途成功错误关闭新熔断的机制。首次统计失败打开熔断后，同请求的白名单调用在获取连接前被拒绝，立即读取持久配置；已经在途的并发调用不会被强制取消。

没有改动阅读事件持久化、事务累加、幂等补偿或投影成功后清理事件的顺序，也没有通过吞掉持久化失败换取成功响应。Redis 写命令超时不能证明服务端未执行；现有补偿重试语义保持不变。

## 验证与结果

Java 8 下使用实际 Spring Boot Redis 自动配置、Lettuce、池化连接及独立本机 RESP/TCP 测试服务；不暂停用户运行中的 Redis。详情服务使用真实计数、白名单和豁免逻辑，阅读 outbox 使用 H2 落库，部分文章/用户查询使用测试替身。

最终运行采集值（`detail-redis-fast-fallback-2026-09-23.txt`）：

| 场景 | 详情耗时 | Redis 连接获取 | 结果 |
| --- | ---: | ---: | --- |
| 首次连接拒绝，包含客户端冷初始化 | 1627ms | 1 | 数据库回退，阅读事件落库，打开熔断 |
| TCP 已连接但握手不回应 | 314ms | 1 | 数据库回退并打开熔断 |
| 已建立连接，HGETALL 不回应 | 309ms | 1 | 数据库回退；后续白名单不再访问 Redis |
| 熔断打开后的三个请求 | 2 / 3 / 2ms | 0 | 持久白名单作者保留豁免，未知作者继续过滤 |
| 半开恢复、后续正常读取 | 186 / 3ms | 每次 2 | 重新读取 Redis 统计与白名单，状态 CLOSED |

旧数据来自实际 HTTP/MySQL，本次来自隔离集成测试，不能据此给出线上延迟降低百分比。首次连接拒绝包含约 1.58 秒的获取/初始化开销，不能宣传所有首次不可用请求都小于 250ms。恢复测试人为推进熔断窗口，生产仍等待配置的 30 秒后才能探测。

新增四项详情测试覆盖首次连接拒绝、握手停顿、命令停顿→打开→恢复，以及 prod 配置不退回旧值；断言实际 Lettuce 命令、连接和池等待配置，验证故障期间 outbox 事件保留，统计落库值不被缓存读取覆盖，以及白名单安全语义。新增两项核心测试验证默认首次超时即打开、半开并发只允许一个探测、探测失败重新打开及成功恢复。

本轮共 **24 项测试通过，无失败、错误或跳过**：详情 4、熔断 5、列表批量化 7、计数服务 4、阅读持久化 1、事务补偿 2、白名单回退 1。全依赖模块包含 Web 编译成功。H2 回归不替代真实 MySQL 多实例故障演练。

```powershell
$env:JAVA_HOME = (Resolve-Path .runtime/java8).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -pl paicoding-web -am '-Dtest=ArticleDetailRedisFallbackTest,RedisCircuitBreakerTest,AuthorWhiteListFallbackTest,DurableReadCountServiceTest,DurableReadCountTransactionTest,CountServiceImplTest,ArticleListBatchTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0' test
```

完整日志 `.runtime/detail-redis-test-final.log`。未执行 clean，未重启现有应用，未进行新的真实 Redis 暂停 HTTP 复采。
