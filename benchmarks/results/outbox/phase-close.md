# Outbox / RabbitMQ 阶段执行记录

日期：2026-09-25。主结果 `run-03`，独立容器项目 `bench-ob-1790336133911`。仅新增或修改 benchmarks 文件；未修改业务代码、生产配置、业务表结构，未连接已有 paicoding 数据库或共享 RabbitMQ。

## 文件

- `benchmarks/outbox-test-plan.md`：实际源码入口、实现限制、故障注入与指标定义。
- `benchmarks/config/outbox-compose.yml`：固定镜像 digest 的独立 MySQL / RabbitMQ，临时密码由 runner 生成。
- `benchmarks/java/outbox/OutboxReliabilityHarness.java`：真实事务、发布确认、消费重试、进程崩溃、DLQ 重放集成测试。
- `benchmarks/scripts/prepare-outbox.mjs`：Java 8 编译真实项目组件和测试设施，保存源码哈希。
- `benchmarks/scripts/run-outbox.mjs`：创建本轮容器、运行测试、导出原始证据、清理专属容器及卷。
- `benchmarks/scripts/outbox-report.mjs`：从数据库快照与 Broker 记录核对并计算 JSON / CSV / Markdown。
- `benchmarks/README.md`：新增入口链接。
- 本目录 `benchmark-summary.md`、`summary.json`、`events.json`、`events.csv`、`attempts.csv`、`scenarios.csv`、`recoveries.csv`：自动生成的汇总与逐事件数据。
- `run-01/`、`run-02/`、`run-03/` 和 `setup/`：成功与失败的原始记录；历史失败不覆盖。

## 执行与验证

项目根目录执行：

```powershell
node benchmarks/scripts/prepare-outbox.mjs
node benchmarks/scripts/run-outbox.mjs benchmarks/results/outbox/run-01
node benchmarks/scripts/run-outbox.mjs benchmarks/results/outbox/run-02
node benchmarks/scripts/run-outbox.mjs benchmarks/results/outbox/run-03
node benchmarks/scripts/outbox-report.mjs benchmarks/results/outbox/run-03
```

以上是实际执行目录，不要原样重用已有目录；复测用新的目录，例如 `run-04`，运行前重新 prepare。报告脚本可从同一份原始快照重复生成汇总。需要 Docker 权限以及 Java 8、本地已构建模块和缓存依赖。

|执行|结果|
|---|---|
|初始编译|失败：旧 classpath 缺少 AMQP 依赖；保留 setup/initial-compile.log。|
|离线 Maven 依赖解析|失败：本地依赖描述元数据未完整缓存；保留 setup/maven-offline.log。|
|修复编译设施后的 prepare|通过：按本项目 BOM 补入已缓存依赖，Java 8 编译成功；未改 pom。|
|run-01|失败：MySQL 初始化阶段的 socket 健康检查早于 TCP 可连接，未进入业务场景。随后仅将测试健康检查改为 TCP SQL 查询。|
|run-02|业务断言通过，但三份日期类型数据库快照导出失败，不能作为完整验收。保留 snapshot-errors.jsonl；旧 runner 返回 0 不代表导出通过。|
|导出设施修复|将 JDBC 日期对象序列化为字符串；快照失败现在导致测试退出失败。没有修改数据库值或业务断言。|
|run-03|八类场景完整执行，15/15 断言、9/9 原始证据核对通过；testExitCode=0，cleanupExitCode=0。|

测试资源使用独立 13416/15782 端口，四个消费者、prefetch=1；故障矩阵 44.032 秒，无性能预热。主机 CPU、内存、系统、Node、源码哈希、Java 版本及容器信息分别保存在 run-03 的 manifest、provenance、java-version 和 containers 文件。并发/连接超时/恢复轮询是测试配置，恢复时间不能直接等同生产调度耗时。

## 实测与边界

- 创建尝试 104 个，主动回滚 8 个，提交 96 个；自动恢复后业务成功 88/96（91.67%），剩余 8 个在 DLQ。
- 显式 DLQ 重放后成功 96/96（100%），最终未处理 0。此 100% 包含管理员式补偿，不代表自动恢复成功率。
- 幂等验收 40/40（100%），已提交业务写入 96 次、重复写入 0；Broker 投递 176 次，额外重复投递 80 次，其中包含 DLQ 重放。
- 发布重试 16 次、消费者内部重试 16 次；实际进入 DLQ 8 条，补偿后残留 0。
- 发送失败恢复 2.215 秒，RabbitMQ 重启至业务完成 5.077 秒；提交后崩溃的重投去重完成 0.128 秒，显式 DLQ 重放至完成 0.165 秒。
- 含故障等待的端到端平均 3377.31 ms，P50 1286 ms，P95 22179 ms；不是正常生产延迟基线。

测试复用真实 Outbox / RabbitMQ / Spring 事务组件，业务处理使用专用 JDBC 投影。文章、通知推送、Redis、ES 全部业务投影的端到端可靠性仍为 N/A；没有用测试成功代替这些业务验收。未测 SENDING 五分钟租约超时、多发布者、网络分区、磁盘故障，单轮数据也不支持线上 SLA 或性能提升百分比。

下一阶段建议：以实际通知或统计消费者为入口，逐一验证外部副作用与消费幂等记录的一致性，再增加多轮、更多样本和租约补偿故障场景。保持当前失败证据和隔离资源清理规则。
