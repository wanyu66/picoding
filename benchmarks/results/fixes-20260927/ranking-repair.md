# 排行榜测试修复记录

本阶段只修改 benchmarks，未修改排行榜业务判定、计分逻辑或数据库结构。修复的是不同测试场景复用已经 DELETE 的事件 ID 所造成的夹具相互影响；不同 ID 重复点赞的业务失败保留。

## 证据与最小修复

同机重跑基线 [ranking-baseline-01](ranking-baseline-01/report.md) 出现 25 次 MySQL 死锁。服务器 [50-repeat_like-innodb-status.json](ranking-baseline-01/50-repeat_like-innodb-status.json) 显示两个 INSERT 在 `uk_activity_rank_event_id` 上持有 S gap 锁，同时等待 X insert intention。原夹具在每次 DELETE 后继续使用 `event-0..399`，重复使用刚被删除的唯一索引键；生产 `DomainEventOutboxServiceImpl.newEvent` 使用 UUID。

修复为每个场景给 eventId 和 originalEventId 加场景前缀。场景内的同 ID 重投仍使用完全相同的 ID，取消事件仍引用正确的原事件。计分预期、并发数、数据规模、Lua 和服务源码、断言均未改变。新增服务器诊断和源码快照，方便追溯。

比较器已断言：两轮业务源码哈希完全一致；40 项场景的操作量和独立预期一致；全部夹具输入除了事件 ID 前缀和实际墙钟时间完全一致；修复后跨场景不复用事件 ID。[comparison.json](ranking-fixture-fix-01/comparison.json) 与 [comparison.csv](ranking-fixture-fix-01/comparison.csv) 保留全部数据。

## 修改文件

- `benchmarks/java/ranking/RankingHarness.java`：场景 ID 隔离，异常时保存 InnoDB 原始诊断。
- `benchmarks/scripts/prepare-ranking.mjs`：支持指定历史 harness 源码，保存编译源码快照。
- `benchmarks/scripts/run-ranking.mjs`：把编译源码快照复制到每轮结果目录。
- `benchmarks/scripts/ranking-report.mjs`：复跑命令采用实际目录；新增 `--no-index` 防止覆盖共享结果目录的总索引。
- `benchmarks/scripts/compare-ranking.mjs`：校验基线可比性，输出原始比较 JSON/CSV。
- `benchmarks/ranking-test-plan.md`：补充故障原因、复现和快照说明。
- 本记录及两个结果目录：保留各项延迟、输入、预期、ledger、分值/排名、环境、源码哈希、服务诊断与清理日志。

## 执行命令与复现

以下编译、runner、报告、证据核验及比较命令均已执行；编译历史快照后已重新执行默认 prepare 恢复为修复版构建。复跑时必须把结果目录替换成全新目录，runner 不会覆盖已有结果。

```powershell
node benchmarks/scripts/prepare-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01/RankingHarness.java
node benchmarks/scripts/run-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01
node benchmarks/scripts/ranking-report.mjs benchmarks/results/fixes-20260927/ranking-baseline-01 --no-index
node benchmarks/scripts/check-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01
node benchmarks/scripts/prepare-ranking.mjs
node benchmarks/scripts/run-ranking.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01
node benchmarks/scripts/ranking-report.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01 --no-index
node benchmarks/scripts/check-ranking.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01
node benchmarks/scripts/compare-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01 benchmarks/results/fixes-20260927/ranking-fixture-fix-01
```

旧源码快照哈希为 `5832c706d66fa4561fe2e1c54510bef475e83234b18b12bcdba5fbac903bab4b`，已验证与基线编译时 provenance 完全一致。运行前无需修改业务配置；仅连接本轮独立 Compose 测试库和 Redis，finally 删除本轮容器和卷。

## 实测结果

环境：Windows 10.0.26100，i7-14650HX，24 逻辑核，15.8 GiB 内存，Node v24.15.0，Java 8；MySQL/Redis 版本和镜像摘要保留于各轮 JSON/TXT。并发 10/50/100/200，通常每项 400 次调用、停机场景每线程一次，每轮共 14,760 次计时调用；另有 100 次预热和取消场景预置。数据库池 220、Redis 命令超时 100 ms，Redis AOF/RDB 关闭。

| 项目 | 同机基线 | 修复夹具后 |
|---|---:|---:|
| 总通过/总用例 | 30/40 | 32/40 |
| 非对照通过/用例 | 30/36 | 32/36 |
| 调用异常/死锁次数 | 25/25 | 0/0 |
| 4 档热点场景通过 | 2/4 | 4/4 |
| 完整运行耗时（含启动、预置、重建、清理） | 247.759 秒 | 239.145 秒 |
| 测试退出码 | 2 | 2 |
| 容器清理退出码 | 0 | 0 |

修复后四档热点场景均为 400 条 ledger、800 分、调用成功率 100%。Redis 停机时 pending 分别为 10/50/100/200，补偿后均为 0，补偿时间分别为 191.50/918.49/1952.99/3500.75 ms。全部 28 个独立业务夹具预期校验及结果一致性核验通过；证据检查脚本成功不表示所有业务场景通过。

仍失败 8 项：4 个 `repeat_like` 每项实际 800 分，独立预期仍为 2 分；4 个非原子 `client_rmw_baseline` 仍丢更新。没有修改期望或把失败改为跳过。修复后完整结果见 [report.md](ranking-fixture-fix-01/report.md)、[summary.json](ranking-fixture-fix-01/summary.json) 和 [metrics.csv](ranking-fixture-fix-01/metrics.csv)。

## 剩余问题与下一阶段

本轮结果说明场景 ID 隔离后未再观察到该夹具诱发的死锁，不代表生产数据库永远不会死锁；次数受 InnoDB purge 和并发时序影响。没有业务性能优化，不能把本轮 TPS 或总耗时变化写成生产性能提升。

计分层只按 eventId 幂等，不同 ID 的同一行为重复计分仍失败。完整生产者状态变更链路需独立验证，本排行榜 harness 对 HTTP、足迹并发、Rabbit 链路结论为 N/A。数据库提交前 Redis 投影的回滚窗口、多 JVM 在线重建等原有未覆盖风险继续为 N/A；本阶段未为了消除测试失败引入 REQUIRES_NEW 或改变业务幂等语义。

下一阶段建议在实际生产者入口验证重复操作的状态转换与稳定事件发送，再沿真实事件链路集成验证；排行榜历史 4 项不同 ID 重复点赞失败继续保留，直到有明确契约和相应实现变更证据。
