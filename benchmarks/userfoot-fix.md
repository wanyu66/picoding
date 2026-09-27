# 点赞/收藏并发状态修复与回归

先读 `UserFootServiceImpl.favorArticleComment`、`UserFootDao`、`UserFootMapper` 和 Liquibase 的 `user_foot` 唯一键。原实现无锁读取后比较状态，多事务会同时读取旧状态并各自写入不同 eventId；首次插入还会发生唯一键冲突。

最小修复为三个业务文件：Mapper 用无副作用的 upsert 初始化或锁住已有的用户/文档唯一行；DAO 使用锁定读取获取最新状态；Service 在原事务内更新状态并追加 Outbox。没有修改积分值、通知类型、点赞/取消状态码或排行榜事件幂等规则，没有新增表或调整业务表结构。无变化请求不产生新事件；取消后再次点赞仍然产生新事件。锁的粒度为同一用户/文档，不是全局锁。

## 本轮证据

- 基线：`results/fixes-20260927/userfoot-baseline-01`，从 Git HEAD 导出的原源码编译；文件哈希见 manifest。
- 修复：`results/fixes-20260927/userfoot-fixed-01`，编译实际工作区源码。
- 独立核对：`results/fixes-20260927/userfoot-comparison/comparison.json` 和 CSV。

每个并发场景 32 线程、64 次调用，共 6 个场景/384 次调用，另加 1 个回滚用例。使用实际 Spring 事务、MyBatis Mapper、UserFoot Service 和 Outbox SQL，独立 MySQL 8.0.46 / 13436 / bench_userfoot；Java 8u492、连接池 40。机器与逐场景时长见 manifest 和 metrics.csv。没有启动 RabbitMQ 消费或 HTTP 接口；下游积分端到端能力 N/A。

基线 1/7 通过；已有足迹重复点赞 64 次产生 32 个事件（期望 1），首次插入 31 次冲突。修复后 7/7 通过，384/384 调用成功，六次状态变化共 6 个事件；回滚后足迹及 Outbox 都不存在。单轮功能验证不代表长期 SLA 或性能收益。没有调整期望或覆盖旧失败。

## 可重复命令

从仓库根目录运行，结果目录必须不存在，Java 固定为 `.runtime/java8`。baseline 模式支持最后一个参数指定固定 commit，默认 HEAD；复现旧版本须核对原基线 manifest 的源码哈希，不能把未来 HEAD 自动当作旧实现。本次原业务源码与 `d2722a30f35a4cddf3304819467ae6d7c9d30f37` 一致。

```powershell
node benchmarks/scripts/run-userfoot.mjs benchmarks/results/fixes-20260927/userfoot-baseline-01 baseline d2722a30f35a4cddf3304819467ae6d7c9d30f37
node benchmarks/scripts/run-userfoot.mjs benchmarks/results/fixes-20260927/userfoot-fixed-01 current
node benchmarks/scripts/compare-userfoot.mjs benchmarks/results/fixes-20260927/userfoot-baseline-01 benchmarks/results/fixes-20260927/userfoot-fixed-01 benchmarks/results/fixes-20260927/userfoot-comparison
```

脚本复用已有 classpath 缓存，重新编译所测业务源码及 harness；不运行 Maven clean。基线返回码 2 表示原实现未满足同一断言；修复和比较均返回 0。原始请求、SQL快照、JSON/CSV、环境、源码哈希、编译和退出日志全部保留。

## 清理与恢复

Runner 只创建随机 `bench-foot-*` Compose 项目，最终执行该项目的 `down --volumes`；两轮清理退出码均为 0。Harness 要求数据库名称精确为 bench_userfoot 且启动时为空，不连接或删除已有业务数据。异常中断时，从 manifest 读取精确 run 值后对 `benchmarks/config/userfoot-compose.yml` 执行相同项目的清理；Compose 所需临时密码只从进程环境提供，不写报告。再次运行使用新结果目录，即可重建同一 schema/夹具。

本阶段修改：上述三个业务文件及 `config/userfoot-compose.yml`、`java/userfoot/UserFootConcurrencyHarness.java`、`scripts/run-userfoot.mjs`、`scripts/compare-userfoot.mjs`、本说明和新结果目录。

剩余问题：直接向积分服务注入不同 eventId 仍被视为不同事件，本修复不改该契约；实际 HTTP 身份验证、Rabbit 消费、排行榜结果整链路 N/A。旧 `saveOrUpdateUserFoot` 阅读/评论写路径与 favor 混合并发未覆盖，7/7 只证明 favor 自身同键竞争。下一阶段可将实际入口、Outbox 和真实投影接入同一隔离环境做端到端验证，并补充混合写入场景。
