# Redis 故障复查、ES 固定数据与列表调用分析（2026-09-17）

最终核验：2026-09-18。已完成首因定位、补偿故障验证、ES 固定数据及列表调用次数分析。健康压测正式样本共 5,599 次，业务错误 0；这是一轮短时本地验证，不能宣称生产容量或性能提升百分比。

## 首个触发原因

昨天首次失败的证据位于 `logs/arch/pai-dev.2026-09-16.0.log`，时间 2026-09-16 15:06:26，trace `0a14e47b.1789542386712.035003689`：

```text
RedisConnectionException: Unable to connect to localhost/<unresolved>:6379
StandaloneConnectionProvider.getConnection
AnnotatedSocketException: Address already in use: getsockopt: localhost/127.0.0.1:6379
java.net.BindException: Address already in use: getsockopt
```

`Redis circuit is open` 是随后的保护状态，不是首因。堆栈说明本机建立 Redis 连接失败；未取得当时端口占用快照，不能进一步宣称已经量化临时端口耗尽。旧管道请求使用非池化连接提供器，存在连接频繁创建的问题。

当前日志确认 `DefaultLettucePoolingClientConfiguration` 生效。记录首个可用性异常、熔断状态转换、连接获取耗时，以及 Commons Pool JMX 的活跃/空闲连接、等待者、平均和最大借用等待时间。连接获取耗时不等同于连接池排队时间；池的最大值也可能包含启动阶段。

修复了两处实测发现的采集缺口：手动创建的请求过滤器未注入开关；原 SQL 拦截器只随动态数据源启用。现在构造器传递开关，独立 MyBatis 插件覆盖当前单数据源。开发配置开启时，响应携带 `X-Trace-Id` 和 `X-Request-Io`，异常响应重置后也补回关联信息。计数不包含参数、Redis key 或凭据。故障探测还捕获到两个并发请求的 trace 重复，已把非原子的 volatile 自增改为原子分配，同时消除循环边界重复分配 1000 的问题，并添加完整一轮 9000 次并发分配测试。仍沿用原来的 IP/毫秒/进程/四位序列格式，不适用于单毫秒超过 9000 次分配的场景。

## 阅读计数的持久化与补偿

1. 请求成功前向 MySQL `article_read_outbox` 写入带 UUID、文章/作者、trace 的事件。
2. 后台事务锁定 state=0 事件，按文章累加 `read_count`，同事务标记 state=1；事务失败整体回滚。
3. 用数据库绝对值刷新 Redis，阅读数使用单调最大值更新防止旧刷新覆盖新值。全部刷新成功后删除该批 state=1 事件；失败保留重试，不再累加 MySQL。
4. 原定时 Redis→MySQL 覆盖同步已停用，避免覆盖新计数。首次迁移通过持久化标记执行一次，切换时需停旧写入实例。已有日志证明迁移了 8 篇文章。

Redis 故障时统计读取回退 MySQL。初次现场暂停 Redis，三个详情请求仍然失败，定位为作者白名单查询异常，已增加持久化白名单回退，未授权作者不会因缓存故障被自动放行。现场原始失败记录：`detail-redis-outage-before-2026-09-17.json`。

2026-09-23 补充：针对故障回退仍等待约 4.1 秒的问题，已缩短 Redis 超时并调整首次故障熔断阈值；链路定位、隔离测试结果及限制见 [详情 Redis 故障快速回退](2026-09-23-detail-redis-fallback.md)。本文的历史 HTTP 样本保持原值。

计数最终一致，正常刷新间隔 1 秒，故障期间可以延迟更久。MySQL 本身不可用时事件不能持久化，请求仍会失败。重试幂等范围是同一 outbox 事件，不表示客户端重复 HTTP 请求会被去重。

## 列表逐篇补全：实际调用次数

2026-09-23 补充：已实现列表批量补全，确定性回归的指标对比及复采方法见 [列表批量补全回归](2026-09-23-list-batch.md)。以下保留 9 月 17 日优化前的现场证据。

原始证据：`list-io-profile-2026-09-17.json`，匿名请求，page=1，size=1/4/8，各三次。

| 返回文章数 N | 暖分类缓存后的 SQL | Redis 命令 | 连接获取次数 |
| --- | ---: | ---: | ---: |
| 1 | 5 | 2 | 2 |
| 4 | 17 | 8 | 8 |
| 8 | 33 | 16 | 16 |

当前样本 SQL 是 **1 + 4N**：列表主查询 1 次；标签 `article_tag` N 次；作者 `user_info` N 次；白名单持久配置 `global_conf` N 次；敏感词豁免角色判断再次查询 `user_info` N 次。冷分类缓存可另加 `category` 查询，最终采样前已预热。Redis 是统计 `HGETALL` N 次、白名单 `SISMEMBER` N 次。

只计算请求线程实际执行的 MyBatis query/update 和显式埋点的 outbox insert；不包含异步 PV/UV、后台补偿和 Lua 内部指令。首表名用于分类，不是扫描表数量。当前暖缓存统计无需 SQL；缓存未命中/熔断和不同白名单命中情况会改变次数。

下一步优化依据：标签批量查询；同页作者去重批量读取并复用角色；白名单配置每请求读取一次；统计通过管道减少网络往返（管道不会把 N 条命令变成一条）。本次完成次数分析，没有把这些尚未实现的优化换算成 QPS 提升。

## ES 与固定数据

用户重建后，别名 `paicoding_article` 指向 `paicoding_article_v20260916165124894`，索引 8 篇。真实搜索预热后状态 `ES_NORMAL`，覆盖率检查通过。固定数据文件 `fixed-search-fixture-2026-09-17.json` 保存 8 篇文章的 ID、标题、更新时间及正文摘要 SHA-256，排除变化中的阅读数；关键词固定为 Java、技术派、分布式。

命中 ID 是可复现快照，不是人工相关性标注，不能据此给出搜索准确率提升。昨天搜索是 MySQL 标题回退，不能与今天 ES 搜索直接计算性能提升百分比。

2026-09-18 续跑时，8/8 篇文章指纹、3/3 个关键词的命中顺序均与固定快照一致，搜索保持 `ES_NORMAL`、覆盖检查通过。证据 `fixed-search-fixture-2026-09-18-after.json` 和 `fixture-verification-2026-09-18.json`。这是次日补充内容核验；压测结束当时的搜索健康状态另见 `results/2026-09-17-remediation/after.json`。

## 健康状态压测结果

原始逐请求样本、元信息和汇总：`results/2026-09-17-remediation/`。执行时间 2026-09-17 15:35–15:36（北京时间）。固定 8 篇文章，列表 size=10，详情轮询文章 ID，搜索轮询上述 3 个关键词。每场预热 3 秒、正式采样 10 秒，仅 1 轮；8/32 个闭环并发客户端，HTTP keep-alive，无重试。正式请求总计 5,599 次，全部 HTTP 200 且业务校验通过，搜索 681 次全部 `ES_NORMAL`。

| 接口 | 并发 | 成功请求 | 成功 QPS | P95 (ms) | 错误数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 列表 | 8 | 373 | 36.11 | 396.58 | 0 |
| 详情 | 8 | 1462 | 145.84 | 97.18 | 0 |
| 搜索（ES） | 8 | 256 | 24.17 | 672.82 | 0 |
| 列表 | 32 | 473 | 42.88 | 2219.05 | 0 |
| 详情 | 32 | 2610 | 258.96 | 174.34 | 0 |
| 搜索（ES） | 32 | 425 | 40.30 | 2142.06 | 0 |

延迟为客户端读取完整响应并完成 JSON/业务校验的耗时；QPS 分母含在途请求收尾。客户端与服务、数据库、Redis、ES 共用本机。运行服务为 IDEA Java 17，编译/测试使用 Java 8；开发诊断开启。主机可用内存从约 2.14 GiB 降至 1.10 GiB，存在资源竞争，不能把延迟变化全部归因于代码。32 并发的列表与搜索 P95 已超过 2 秒，因此也不能只凭错误率 0 判断性能达标。

`redis-pool-evidence-2026-09-17.txt` 中采样点显示熔断 CLOSED、池等待者为 0，平均借用等待按毫秒取整为 0、累计最大 191 ms；30 秒采样无法排除采样间隙的瞬时等待，累计最大值也不能当作压测窗口独有最大值。

当前没有同环境、同数据、同持久性语义的修复前对照，故不输出 QPS/延迟“提升百分比”。可以确定的可用性变化是本次相同 Redis 暂停探测的三个详情请求从 0/3 成功变为 3/3 成功，但样本极小，不能外推长期 SLA。

## 构建与回归

Java 8 Maven 全依赖模块构建成功：Redis 熔断 3 项、计数服务 4 项、补偿服务 1 项、事务补偿 2 项、白名单回退 1 项，共 11 项。之后核心模块单独重编译并通过 trace 并发测试 1 项和熔断回归 3 项，累计 **12 个不同测试**通过。构建记录分别在 `.runtime/test-diagnostics-final.log` 与 `.runtime/test-trace-final.log`。

复现时显式设置 Java 8 的 JAVA_HOME/PATH，使用 `mvn -pl paicoding-web -am -Dtest=RedisCircuitBreakerTest,CountServiceImplTest,DurableReadCountServiceTest,DurableReadCountTransactionTest,AuthorWhiteListFallbackTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0 test`；trace 测试使用核心模块和 `-Dtest=RedisCircuitBreakerTest,SelfTraceIdGeneratorTest`。未执行 clean。

## 验证限制

事务补偿测试使用 H2 兼容模式，不能代替真实 MySQL 的多实例锁争用测试。另使用项目 `.env` 中已有配置，对现场 MySQL 做了只读核验；`scripts/load-test/snapshot-read-count.ps1` 不输出凭据。

Redis 暂停后，3/3 个详情请求业务码为 0，耗时约 4.08–4.10 秒（等待缓存超时，尚非快速降级）。证据 `detail-redis-outage-after-2026-09-17.json`。故障前、故障中、恢复后的数据库快照分别为 `read-count-pre-outage-2026-09-17.txt`、`read-count-during-outage-2026-09-17.txt`、`read-count-recovered-2026-09-17.txt`：

| 文章 | 故障前 cnt | 故障中 cnt | 恢复后 cnt |
| --- | ---: | ---: | ---: |
| 100 | 2313 | 2314 | 2314 |
| 101 | 2319 | 2320 | 2320 |
| 102 | 2324 | 2325 | 2325 |

故障中保留 3 条 state=1 事件，恢复后队列清空、计数没有重复增加。熔断日志在 15:32:34 显示 OPEN→HALF_OPEN→CLOSED。这验证了本机本轮 3 个事件的实际补偿，不代表任意时长/规模故障下已经压测完成。

次日完成最终文章指纹读取后再次核对，outbox 仍为空，证据 `read-count-final-2026-09-18.txt`。压测和详情核验会真实增加阅读数；没有删除这些计数，也未把这份末态快照冒充为压测结束瞬间状态。

尝试运行现有 Web 过滤器测试时，测试框架先遇到 Java 8 中文路径代理加载问题；预加载代理后暴露已有 JUnit Platform 版本冲突（`TestPlan.getConfigurationParameters` 缺失），Web 测试未执行，不能计为通过。请求过滤器与 SQL 采集通过实际 HTTP 请求验证。
