# 文章列表批量补全回归（2026-09-23）

依据 `2026-09-17-remediation.md` 和 `list-io-profile-2026-09-17.json` 的逐篇补全采样，列表改为一次批量标签查询、一次去重作者查询，并使用这批作者的角色判断敏感词豁免。白名单成员按作者去重，持久配置仅在 Redis 未命中或异常时懒加载一次；结果只存在于本次列表补全的局部变量中。统计通过一次管道读取，仍然执行每篇一条 HGETALL。

## 指标对比

以下为 `ArticleListBatchTest` 的确定性测试采集，**不是更新后的真实 HTTP 压测**。使用真实 MyBatis SQL 执行及 `RequestSqlDiagnostics`、真实 StringRedisTemplate 及 `RedisConnectionDiagnostics`，底层为 H2 和可控 RedisConnection。主列表使用测试夹具 SELECT，标签、用户和配置使用生产 Mapper/DAO；分类模拟暖缓存。旧流程在同一夹具中逐篇补全，新流程调用生产列表服务，并逐字段比较完整 PageListVo。原始输出见 `list-batch-regression-2026-09-23.txt`。

同页同一普通作者、白名单不命中、统计全部命中缓存：

| 返回 N | 旧 SQL | 新 SQL | 旧 Redis 命令 | 新 Redis 命令 | 旧连接获取 | 新连接获取 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 5 | 4 | 2 | 2 | 2 | 2 |
| 4 | 17 | 4 | 8 | 5 | 8 | 2 |
| 8 | 33 | 4 | 16 | 9 | 16 | 2 |

旧流程重现现场的 `1+4N` SQL 和 `2N` Redis 调用形态。新流程 SQL 为列表、标签、作者、白名单配置各一次。若所有候选作者都命中 Redis 白名单或都是机器人，则不读取持久白名单配置。

令 U 为本页需要白名单检查的不同作者数（机器人跳过），普通列表的 Redis 命令为 `N+U`，连接获取为 `1+U`。另测 8 篇、8 位不同普通作者：SQL 4 次，Redis 16 条命令，连接获取 9 次。因此不能声称管道将 N 条命令变成一条，也不能声称不同作者场景减少了 Redis 命令数。重复文章 ID 会去重统计读取，但输出仍保留原列表顺序及重复项。

分类冷缓存、统计缺失或 Redis 故障会增加 SQL；这些场景不套用暖缓存的 4 次上限。缓存缺失仍按文章查询原数据库统计，管道失败则回退该批文章；熔断开启时不获取 Redis 连接。连接获取耗时来自测试替身，不用于推导网络延迟或性能提升百分比。

## 返回语义与回归

新增 7 项测试覆盖：

- N=1/4/8 的指标断言、完整返回对象前后比较。
- 不同作者、普通角色、管理员、运营、机器人、Redis 白名单、持久白名单，以及下一页重新读取配置。
- 标签左连接结果、已删除关联、停用/删除标签、孤立标签、无标签文章。保持原查询条件，不额外过滤标签状态；内部批量关联字段不会进入公开 TagDTO。
- 空页无补全 I/O、满页/不满页 hasMore、部分及全部作者缺失时的原业务异常。
- 非零数据库统计回退、缓存缺失、Redis 异常；未知作者不会因白名单缓存异常获得豁免。
- 搜索命中顺序、高亮、缺失文章、重复 ID、置顶列表。
- Redis 熔断后数据库回退且连接获取为 0。

生产主列表查询、分页规则和详情补全未改。标签查询延续旧 SQL 的无 ORDER BY 行为，不新增排序规则。测试覆盖固定数据下的标签顺序，不承诺数据库原本未保证的物理行顺序。

Java 8 构建及测试命令（PowerShell；不执行 clean）：

```powershell
$env:JAVA_HOME = (Resolve-Path .runtime/java8).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -pl paicoding-web -am '-Dtest=ArticleListBatchTest,AuthorWhiteListFallbackTest,CountServiceImplTest,RedisCircuitBreakerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0' test
```

最终构建成功，15 项测试全部通过（新增列表回归 7 项、计数服务 4 项、白名单回退 1 项、Redis 熔断 3 项），无失败、错误或跳过。完整日志：`.runtime/list-batch-test-final.log`。测试夹具不启动 Spring Web 测试上下文，未使用此前存在版本冲突的 Web 测试框架路径。

## 真实 HTTP 复采

部署本次构建并启用现有请求 I/O 诊断后执行：

```powershell
node scripts/load-test/profile-list.js docs/performance/list-io-profile-2026-09-23-after.json
```

采集脚本先预热，再对 size=1/4/8 各取三次，保留原始 `X-Request-Io`、trace，同时输出 SQL/Redis/连接获取总量、不同作者数、文章 ID 顺序和 hasMore。可通过 BASE_URL 指定实例。本文未重启用户的运行实例，也未将旧进程采样冒充新代码的 HTTP 结果。
