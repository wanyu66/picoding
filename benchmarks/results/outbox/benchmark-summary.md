# Outbox / RabbitMQ 故障测试结果

主结果：run-03（bench-ob-1790336133911）。真实独立MySQL/RabbitMQ、当前源码编译、4消费者/prefetch1，44.03秒故障矩阵，未预热；功能断言15/15、原始数据核对9/9。不是容量测试或线上SLA。

范围：真实Outbox写入、发布确认、状态/重试、Spring事务消费包装、Rabbit监听工厂及DLQ；业务效果为无event_id唯一约束的专用JDBC表。**没有证明文章HTTP接口、通知推送、排行榜Redis、统计Redis、ES投影全部业务成功。**

|指标|实测|
|---|---|
|尝试创建/主动回滚/实际提交事件|104/8/96|
|自动恢复后业务成功率|88/96 = 91.67%|
|显式DLQ补偿后最终业务成功率|96/96 = 100.00%|
|幂等正确率（重复+崩溃事件）|40/40 = 100.00%|
|发布重试/消费重试|16/16|
|Broker投递/额外重复投递|176/80|
|Broker redelivered标记投递/幂等跳过调用|8/72|
|已提交业务效果/重复业务写入|96/0|
|累计进入DLQ/最终DLQ残留|8/0|
|人工补偿前/后未处理|8/0|
|端到端平均/P50/P95/最大(ms)|3377.31/1286.00/22179.00/22326.00|

**DLQ不是成功：**补偿前已提交事件96个，业务成功88个，DLQ待处理8个；Outbox各状态保留在07检查点。现有项目没有通用自动DLQ重放；最终100.00%包含本次测试明确触发的管理员式重放，不能写成“现有系统自动100%恢复”。

|故障|恢复方式|实测恢复ms|
|---|---|---|
|send_failure|production publishPending; natural retry due time|2215|
|rabbit_outage|container restart + production publisher compensation|5077|
|ack_crash|new listener receives broker redeliveries|128|
|dlq|explicit benchmark DLQ replay; NOT existing automatic compensation|165|

恢复计时始于恢复操作，包含重连/自然退避到期；显式调用生产publishPending，不包含其定时调度等待。端到端起点为event创建时间，终点为首个成功消费事务返回后的审计时间。重复投递按连接/信道/deliveryTag去重计算，stateless listener重试不是新的Broker投递。

|场景|尝试/提交|最终成功|Broker投递|重复投递|重复写入|DLQ累计|端到端P95ms|
|---|---|---|---|---|---|---|---|
|normal|32/32|32|32|0|0|0|1602.00|
|rollback|8/0|0|0|0|0|0|N/A|
|send_failure|8/8|8|8|0|0|0|2492.00|
|rabbit_outage|8/8|8|8|0|0|0|22326.00|
|ack_crash|8/8|8|16|8|0|0|4007.00|
|duplicate|32/32|32|96|64|0|0|1534.00|
|dlq|8/8|8|16|8|0|8|4706.00|

DLQ一行的“最终成功”发生在第8步显式重放之后；第7步该场景成功数为0。SQL效果插入后失败的24次尝试全部回滚，不属于已提交业务写入。

证据：events.csv/attempts.csv/recoveries.csv由数据库逐行快照计算；run-03/ 下包含Outbox/consume/source/effect/attempt全量JSON、8个检查点、真实子进程崩溃日志、Broker x-death、Micrometer读数、镜像/构建哈希和清理日志。所有初轮记录保留，未修改结果以制造成功率。

运行命令见 [计划与操作](../../outbox-test-plan.md)。主机=Intel(R) Core(TM) i7-14650HX、逻辑CPU=24、Node=v24.15.0；数据库13416和Broker15782均为临时独立容器，固定镜像digest见config/outbox-compose.yml。生产配置/接口/表结构未改。

历史尝试说明见 [阶段执行记录](phase-close.md)；主结果以本报告开头标明的目录和原始状态为准。

适合简历的限定表述：

> 构建8类Outbox/RabbitMQ隔离故障测试，覆盖96个已提交事件和80次额外重复投递；40/40个重复/提交后崩溃事件仅产生一次业务效果，重复写入0次。验证Broker恢复补偿，并区分88个自动完成事件与8个需显式DLQ重放的事件。

剩余：真实业务各投影端到端成功率、5分钟SENDING租约过期、多发布者争抢、Broker节点/磁盘崩溃和网络分区、长时间多轮统计N/A。下一阶段应先补齐真实通知/Redis/ES失败状态与补偿验收，再增加独立重复轮次；本轮不声称exactly-once消息投递。
