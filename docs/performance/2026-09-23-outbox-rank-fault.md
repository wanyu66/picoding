# Outbox 与排行榜联合故障测试（2026-09-23）

## 实测结果

Java 8 实测完成，JUnit **10 项：8 项通过、2 项失败、0 错误、0 跳过**，测试矩阵耗时约 770 秒。失败是两种并发下的乱序取消缺陷，不能宣称全场景业务最终完整。测试结束后专用容器均已停止。

| 场景 | 并发 | Outbox SENT / 消费去重 | 实际积分事件 | 重复积分副作用 | 日／月积分绝对偏差 | 结果 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 混合点赞、取消、重复 | 8 / 32 | 各 1000 / 1000 | 各 1000 | 0 | 0 / 0 | 通过 |
| RabbitMQ 中断恢复 | 8 / 32 | 各 1000 / 1000 | 各 1000 | 0 | 0 / 0 | 通过 |
| 提交后 ACK 前退出 | 8 / 32 | 各 1000 / 1000 | 各 1000 | 0 | 0 / 0 | 通过 |
| Redis 更新失败后补偿 | 8 / 32 | 各 1000 / 1000 | 各 1000 | 0 | 0 / 0 | 通过 |
| 取消先于点赞投递 | 8 / 32 | 各 1000 / 1000 | 各 600 | 0 | 800 / 800 | **失败** |

每个场景都达到了所声明的 8／32 并发回调峰值。两次 Redis 故障各留下 600 条 `redis_applied=0` 的记录，恢复后均清零。所有场景最终消息队列与死信队列均为空；乱序用例仍然失败，说明空队列不是业务完整的证据。

| 并发 | RabbitMQ 第一波恢复 | ACK 缺口消息重投 | Redis 启动至补偿清零 |
| --- | ---: | ---: | ---: |
| 8 | 23,177 ms | 22 ms | 20,010 ms |
| 32 | 23,244 ms | 29 ms | 20,039 ms |

恢复计时边界见下文；这些时间不包含生产定时器等待。

归档证据：[汇总 JSON](results/2026-09-23-outbox-rank/summary.json)、[JUnit 原始结果](results/2026-09-23-outbox-rank/junit.txt)、[32 并发乱序逐事件对账](results/2026-09-23-outbox-rank/32-cancel-before-praise.json)、[乱序原始夹具](results/2026-09-23-outbox-rank/32-cancel-before-praise-fixture.json)、[被测源码哈希](results/2026-09-23-outbox-rank/provenance.json)。同目录保留全部 10 份逐场景 JSON，包括独立预期增量、实际增量、取消关联、逐用户日／月榜及失败事件 ID。仓库已有未提交修改，源码哈希用于确认本次测试版本。

## 测试口径

每个用例 1,000 个不同领域事件，每个事件总投递 3 次（Outbox 首发一次，额外重复两次），分别启动 8、32 个真实 RabbitMQ 消费者。每次测试独立重建测试表、清空专用 Redis、重建专用消息队列。测试数据不进入现有应用环境。

使用生产 `DomainEventOutboxServiceImpl`、`DomainEventOutboxPublisher`、`ActivityDomainEventConsumer`、`DomainEventConsumeService`、`UserActivityRankServiceImpl` 和 MyBatis Mapper；事务由 Spring 代理提交。监听容器使用生产 `RabbitMqAutoConfig` 的 AUTO ACK、重试和死信配置，prefetch 设置为 1。首次消费用门闩同步起跑，并断言实际并发回调峰值达到 8／32。MySQL 8、RabbitMQ 4、Redis 8 都是真实服务，使用独立 Docker Compose 项目 `paicoding-outbox-rank-fault`。

独立预期值来自夹具的业务规则：600 次点赞（每次 +2），其中 400 次被取消（每次 -2），53 位合成用户最终合计 400 分。日榜、月榜分别按每位用户核对，偏差为逐用户绝对误差之和，不能用总分相互抵消。另按 eventId 核对 1,000 条积分增量和 400 条取消关联；数据库触发器审计积分记录插入，检测重复业务副作用。

成功必须同时满足：Outbox SENT 的事件集合完整、activity 消费幂等集合完整、业务积分事件及取消关联完全匹配、重复积分副作用为 0、待补偿记录为 0、日/月榜偏差为 0、队列积压与死信均为 0。收到消息次数仅作辅助指标。

## 场景

| 场景 | 注入方式及验证点 |
| --- | --- |
| mixed | 400 个原始点赞先提交；随后随机混合 400 个取消、200 个保留点赞及重复投递。保留同一点赞与取消的因果顺序。 |
| rabbit-outage | 停止专用 RabbitMQ，验证实际发布失败且 Outbox 留存；中断期间继续写入待发送事件，启动 broker 后由生产 publisher 重试。 |
| ack-crash | 子 JVM 从 RabbitMQ 手动领取一条消息，调用生产消费服务；确认事务提交后 `Runtime.halt(77)`，不发送 ACK。父 JVM 核实数据库业务已提交，随后验证 broker 的 redelivered 标记和最终幂等结果。 |
| redis-outage | 先完成 400 个原始点赞；停止专用 Redis，继续处理另外 600 个事件；必须看到待投影记录，再恢复 Redis 并调用生产补偿方法直到清零。 |
| cancel-before-praise | 额外边界：400 个取消先到，之后 600 个点赞；事件自身时间仍为点赞早于取消。预期最终业务结果不因投递顺序改变，用于识别“消费成功但取消被丢弃”。 |

恢复时间使用单调时钟。RabbitMQ 计时从发起容器启动到第一波 400 个事件及其重复投递处理完成；ACK 计时从启动替代消费者到观察到未 ACK 消息重投；Redis 计时从发起容器启动到生产补偿将待投影清零。包括服务启动时间，属于本机单次样本。最终是否正确还必须通过完整业务对账。

测试主动调用发布和补偿入口，**不包含生产调度等待**（Outbox 默认 1 秒轮询，积分待补偿默认每 5 分钟）。这些数字不能当作生产恢复 SLA。

## 复现与证据

在仓库根目录，用 PowerShell 执行：

```powershell
./scripts/load-test/run-outbox-rank-fault.ps1
# 也可指定其他 Java 8 安装目录：
./scripts/load-test/run-outbox-rank-fault.ps1 -Java8Home 'D:/jdk8'
```

需要 Docker Desktop，首次运行下载 MySQL 镜像。端口固定为 MySQL 13316、RabbitMQ 15682、Redis 16389，仅绑定 localhost。脚本显式使用 Java 8，设置仓库内临时目录以避免 Windows 中文路径问题，不执行 `mvn clean`。结束后停止专用容器，保留测试数据卷便于调查。任一业务不变量失败均使 Maven 返回非零。

常规单元测试默认跳过本测试。单独运行时需加 `-Doutbox.rank.fault=true`，可用 `-Dfault.scenario=redis-outage` 和 `-Dfault.concurrency=32` 缩小范围；这些参数用于 Java/Maven 直接调用。

每个场景输出夹具和 JSON 对账到 `paicoding-service/target/outbox-rank-fault/`，另有 `docker.log`、ACK 故障子进程日志；Maven 完整日志位于 `.runtime/outbox-rank-fault.log`。

本次范围是 activity 消费者的积分副作用，不覆盖 notification、statistics、search 消费者，也不覆盖用户点赞 HTTP 事务、跨日/月、多个应用实例同时重建或同一目标的多轮点赞/取消。用户资料补全未调用，测试直接读取真实 Redis 榜单。以前的 3 条阅读计数补偿事件不属于本次数据或证据。

## 已复现的问题：乱序取消被永久消费

`UserActivityRankServiceImpl.addActivityScore` 在找不到有效原始点赞时直接返回（当前第 135–142 行）。`DomainEventConsumeService.consume` 已在同一事务插入消费幂等记录，处理函数正常返回使该记录提交。后续重复投递命中幂等记录而直接退出，稍后到达的原始点赞加分也不会补回先前丢失的取消。

因此“所有消息已收到／所有消费记录已落库／没有重复积分”都不能证明业务完整。乱序夹具最终应有 1,000 个积分事件、400 条取消关联、400 总分；缺陷表现为仅 600 个积分事件、取消关联缺失、1,200 总分（日／月榜逐用户绝对偏差均为 800）。这是未修复的生产行为，本次保留红色测试和逐事件证据，没有降低断言来让构建通过。

后续修复需要明确领域事件的因果关联或业务版本，让未找到原行为的取消可持久重试／补偿，避免直接当作已完成。仅增加消费线程、重复投递次数或重建 Redis，不能恢复数据库中根本没有保留的取消事实。
