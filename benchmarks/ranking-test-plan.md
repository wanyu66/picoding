# 排行榜验证与复现

本工作仅新增 benchmarks 文件，不修改业务逻辑。实际榜单是用户活跃榜，ZSet member=userId，articleId 仅用于行为关联；文章积分榜 N/A。

## 代码分析

- `UserActivityRankServiceImpl.actionSpec`：访问 +1，点赞/收藏/关注 +2，评论 +3，发文 +10；撤销使用原事件实际积分，回写原日/月桶，积分最低为 0，0 分从榜单移除。
- `UserFootServiceImpl.favorArticleComment`：先读当前状态，状态变化才发事件，串行相同操作可返回；源码明确标注缺少并发控制。计分服务自身对不同 ID 的同用户/同文章点赞不去重。
- `DomainEventConsumeService`：以 consumer_name/event_id 消费记录去重；`activity_rank_event` 有 event_id 唯一索引；Lua 用事件 Hash 去重。事件 ID 缺失会随机生成，无法识别语义上相同请求。
- Lua：检查事件 ID、原事件撤销状态，写入事件 Hash，同时更新日/月 ZSet、首次达分时间与 ready 标记和过期时间，单脚本执行避免命令间竞争。它不提供 MySQL/Redis 跨存储事务，也不会识别不同 ID 的同一业务操作；Redis Lua 运行时错误不代表已执行命令自动回滚。
- MySQL 日志包含事件、原事件、撤销事件、用户、目标、积分、时间桶、redis_applied；先写日志，再投影 Redis，异常保留 pending。默认每 5 分钟处理最多 500 条 pending，默认每 10 分钟检查当前日/月 ready；本测试直接调用补偿方法，不等待调度器。
- 重建删除月内相关 Redis 键，按 event_time,id 重放 MySQL 日志。重建和实时投影共用实例内 synchronized 锁；它不锁其他 JVM。排序是积分倒序、首次达到该积分时间升序、用户 ID 升序，不是直接采用 Redis 同分成员字典序。
- 静态风险未在本次验证：MySQL 提交前 Redis 更新后的回滚、跨 JVM 重建与写入竞态、带 originalEventId 的并发撤销、事件时间乱序导致首次达分顺序变化、只丢 ZSet 未丢 ready 标记。不能从本报告推断这些场景正确。

## 隔离、规模与口径

专用 Compose 项目名称由每次运行生成。仅连接 127.0.0.1:13426 的 bench_ranking 和 127.0.0.1:16409 Redis；不读取业务数据库配置。密码随机生成，仅保留在进程环境中。每个用例只清理新建测试库的 activity_rank_event 与专用 Redis 的排行榜前缀，结束删除本次项目容器和卷。

每个业务场景的 eventId/originalEventId 使用该场景前缀，场景内的同 ID 重投仍使用完全相同的 ID。这样避免上一场景 DELETE 后尚未 purge 的同名唯一索引记录影响下一场景；清理不会重新发放已使用的事件 ID。调用出现异常时另保存 `*-innodb-status.json`，用于区分应用异常和测试夹具引入的锁竞争。独立预期、业务输入、并发数和断言不变。

Java 8 编译实际计分服务、DAO、Mapper、实体和 RedisClient 源码，复用已有依赖 classpath。Spring 事务代理、真实 MySQL/Redis；用户资料查询用合成 ID 适配，不测试账号数据和 HTTP/Rabbit/足迹生产链路。当前代码的生产者并发去重能力是 N/A，计分服务面对不同 ID 重复点赞的行为则有实测。

线程数 10/50/100/200；常规每个用例 400 次调用，故障用例每线程一个事件。固定总量、闭环负载、每项一次采样；非长时间稳定态容量结论。每个用例记录实际时长与最大在途调用数；最大在途不等于 Redis 同时执行数。数据库池 220，Redis 命令超时 100ms。开始进行 100 次服务预热；线程启动不计入延迟。等待实例锁/数据库连接/提交计入服务调用延迟；线程池排队时间不计入。日/月重建均在停止写入后执行。

独立预期：输入用户/文章行为产生预期整数，绝不读取 Redis 来计算正确答案。多用户每人 2 分，重复点赞（不同 ID）同用户同文章期望仅 2 分，同 ID 重投期望 2 分，已确认的点赞/收藏全部取消期望空榜，热点用户对 400 篇文章各一次点赞期望 800 分。用统一事件时间消除本套数据的同分时间歧义，因此预期排名为积分倒序、数值用户 ID 升序。重建前后各自与独立预期比较，另记录彼此是否相等，避免“恢复错误数据”算作业务正确。

TPS=调用数/测量墙钟秒；successTPS=无异常返回数/墙钟秒；P50/P95/P99 采用排序后 nearest-rank，包括失败调用。更新成功率表示服务无异常返回，Redis 故障时并不表示已经投影。scoreDifferenceCount 是分值不符用户数；duplicateScoreCount 是超过独立预期的 2 分单元数，仅用于本套全为 +/-2 的夹具；重建一致率=1-不符用户数/预期与实际用户并集数，空榜两边均为空定义为 100%。保存单次延迟、输入、独立预期、MySQL 日志、日/月实际分值与顺序。

非原子对照执行 ZSCORE 后 ZADD，实际 Lua 对照直接调用源代码私有脚本，绕过 JVM 锁。两者提供相同 +2 热点累计负载，但 Lua 还维护日/月/去重元数据，因此仅用于展示丢更新，不用于宣传相同功能的加速百分比。服务 10 并发是同一实现的并发扩展参考，不是优化前版本。

## 命令

在仓库根目录 PowerShell 执行：

```powershell
node benchmarks/scripts/prepare-ranking.mjs
node benchmarks/scripts/run-ranking.mjs benchmarks/results/ranking/run-03
node benchmarks/scripts/ranking-report.mjs benchmarks/results/ranking/run-03
node benchmarks/scripts/check-ranking.mjs benchmarks/results/ranking/run-03
```

依赖已有 `.runtime/java8`（可用 JAVA8_HOME 指定 Java 8）、`paicoding-web/target/model-test-classpath.txt`、相关模块 target/classes 及 Docker 的 mysql:8.0/redis:8.0 镜像。构建脚本拒绝非 Java 8；无需 mvn clean。镜像实际 ID/digest、源码哈希与服务版本保存在结果中。

runner 的 finally 自动清理，仅针对本次生成的 Compose 项目；Redis 停机注入也在 finally 恢复。如果进程被强杀，读取 manifest.json 的 run，设置 BENCH_RUN 为其值、BENCH_TEST_PASSWORD 为任意临时占位值（down 不需连接数据库），执行：

```powershell
docker compose -p $env:BENCH_RUN -f benchmarks/config/ranking-compose.yml down --volumes
```

不得把已有业务 Compose 项目名代入。重新运行必须用新结果目录，原始失败证据不覆盖。返回码 2 表示存在正确性失败（包括非原子对照），不应修改期望让用例通过。

修复验证可在共享结果目录下生成独立报告，并比较同机修复前后原始结果：

```powershell
node benchmarks/scripts/ranking-report.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01 --no-index
node benchmarks/scripts/check-ranking.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01
node benchmarks/scripts/compare-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01 benchmarks/results/fixes-20260927/ranking-fixture-fix-01
```

比较器验证 40 项场景的业务源码哈希、操作量、独立预期，以及排除场景 ID 前缀和墙钟时间后的全部夹具输入相同，输出 `comparison.json` 和 `comparison.csv`。单轮对照用于定位正确性问题，不作为性能改善百分比结论。

每次运行保存编译时的 `RankingHarness.java` 快照。`ranking-baseline-01` 的快照 SHA-256 与其原始 provenance 完全一致，可直接重现旧夹具而无需回改工作区文件：

```powershell
node benchmarks/scripts/prepare-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01/RankingHarness.java
node benchmarks/scripts/run-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-repeat-01
node benchmarks/scripts/prepare-ranking.mjs
node benchmarks/scripts/run-ranking.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-repeat-01
```

这两次重跑仍须使用未存在的新结果目录。死锁发生次数受 InnoDB purge 时序影响，不应断言每次运行出现完全相同的次数。
