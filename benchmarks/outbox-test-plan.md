# Transactional Outbox / RabbitMQ 故障验证

## 代码定位与实现边界

以下路径均相对项目根目录。标记“已实现”只说明存在代码，不自动等于完整业务链路通过。

|环节|实际源码入口|状态及限制|
|---|---|---|
|业务事务写事件|`paicoding-service/.../user/service/userfoot/UserFootServiceImpl.favorArticleComment`：`@Transactional` 中写 user_foot、调用 `interactionOutboxService.append`|已实现；本次不运行该接口全部依赖，事务测试使用同一真实事务管理器中的专用JDBC业务表。|
|文章事件桥|`paicoding-service/.../article/service/ArticleDomainEventOutboxBridge.append`，`@TransactionalEventListener(BEFORE_COMMIT,fallbackExecution=true)`|已实现；正常有事务时在提交前追加，无事务时fallbackExecution仍可执行，不能无条件声称原子性。|
|Outbox落库|`paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/DomainEventOutboxServiceImpl.java` 的 `append/newEvent`|实际编译并调用；append自身不新建事务，依赖调用者；表 `domain_event_outbox`，event_id唯一。|
|发送与确认|同目录 `DomainEventOutboxPublisher.publishPending/publishOne`|真实调用：抢占PENDING/FAILED→SENDING，持久化消息、messageId、traceId；等待correlated confirm最多5s，同时检查returned message。ACK且未return后写SENT；**不是业务完成标志**。|
|发布重试|同类catch与`publishPending`|FAILED保留，retry_count+1，退避2/4/8...最高实际256s（位移指数被限制为8，即使外层min300）；定时默认1s扫描最多100条。无发布次数上限、无发布端DLQ。测试保留真实next_retry_time，不修改时间加速。|
|租约补偿|同类 `publishPending` 首条UPDATE|SENDING超5分钟改FAILED重试。代码已实现；本轮没有演练5分钟租约超时，不把重试恢复宣称租约测试通过。|
|消费事务幂等|同目录 `DomainEventConsumeService.consume`|真实Spring事务代理+真实MySQL：INSERT IGNORE到 `domain_event_consume`，主键(consumer_name,event_id)，插入成功才handler.run；异常使幂等令牌和同库业务写入回滚。|
|监听确认/重试/DLQ|`paicoding-service/.../notify/config/RabbitMqAutoConfig.listenerContainerFactory`|真实factory：AUTO ACK，defaultRequeueRejected=false，3次总尝试（2次重试），500/1000ms退避；耗尽抛AmqpRejectAndDontRequeueException，经DLX进入对应DLQ。必须核对Broker队列和x-death，不能只看监控counter。|
|通知消费者|`NotificationDomainEventConsumer`→`NotificationDomainEventHandler.consume`|不同于通用消费包装，自己写幂等记录并调用NotifyMsgDao；通知SQL提交后push异常无法回滚已提交幂等记录。通知推送可靠性本轮N/A。|
|其它消费者|`ArticleSearchDomainEventConsumer`、`StatisticDomainEventConsumer`、`ActivityDomainEventConsumer`|ES/Redis不是MySQL事务参与者，通用幂等不保证外部副作用原子性。搜索sync方法吞异常的风险已在前阶段审计，不能把消费记录当作ES业务成功。|
|排行榜补偿|`UserActivityRankServiceImpl.recoverPendingEvents/recoverMissingCurrentRanks`|有独立排行榜事件补偿（默认5分钟/10分钟周期），不是通用DLQ重放；本轮不计入测试成功率。|
|通用DLQ自动重放|搜索notify代码未发现对应任务|未实现/未验证。测试显式执行管理员式重放，单独命名“人工触发补偿”，不能包装成已有自动恢复能力。|

队列与配置：exchange=`paicoding.domain.exchange`、routingKey=`domain.event`；notification/activity/statistics/search 四队列及对应DLQ，DLX=`paicoding.domain.dlx`。测试在独立Broker中只声明notification队列及DLQ，复用真实定义，避免其它消费者的未完成副作用干扰统计。监控：DomainEventMessageMonitor中的confirm/returned/retries/dead_lettered_total；consume_delay在处理前记录，故不作为本轮“业务完成耗时”。

Schema来自 `paicoding-web/src/main/resources/liquibase/data/update_schema_260822_2.sql` 前两个CREATE，按260822_3重命名为domain_event_*。只在新建空测试库执行；绝不执行该迁移后面的通知去重DELETE/ALTER。原有 `OutboxRankFaultTest` 作为设施参考，没有运行其固定项目reset/DROP/FLUSHDB逻辑。

## 场景与断言

|场景|输入/注入|必须检查|
|---|---|---|
|正常|32个事件，同一批真实事务写入业务源记录与Outbox|32条已提交业务效果、消费令牌、SENT；SENT只是附加检查。|
|回滚|同一事务写8个源记录和8个Outbox后setRollbackOnly|两表均无这8个事件；不把主动回滚事件作为丢失消息分母。|
|提交后发送失败|8个已提交事件，临时解除独立队列binding，mandatory return|Broker ACK仍可能出现；Outbox必须FAILED、业务效果0；恢复binding后真实publishPending按到期时间补偿。|
|Rabbit不可用|8个已提交事件，停止本轮专用Rabbit容器|FAILED保留；仅重启本轮容器，等待连接恢复、重启消费者、执行真实publisher补偿。|
|提交后未ACK崩溃|8个事件由子进程basicGet(autoAck=false)，实际事务提交后Runtime.halt(77)|Broker重投redelivered=true，业务效果仍每事件1条；不是通过修改结果模拟崩溃。|
|重复投递|32事件各发送3份，共96次真实消息投递；4消费者|恰好32个持久业务效果。效果表只有自增主键，没有event_id唯一约束，避免约束掩盖重复执行。|
|重试耗尽|8事件，handler插入效果后抛异常|每事件3次处理尝试，同库效果与令牌全部回滚，Broker DLQ实际8条；业务成功0。|
|恢复/补偿|恢复handler，再显式重放8条DLQ消息，确认发布后才ACK原DLQ消息|eventId不变，8业务效果提交，DLQ清空；自动恢复与人工辅助最终成功率分别计算。|

Java8重新编译当前OutboxService/Publisher/ConsumeService/RabbitMqAutoConfig/MDC advice/monitor源码到benchmarks/build/outbox，不改业务target类或业务代码。独立MySQL和Rabbit均使用固定镜像digest；临时随机密码仅在进程环境中。事务效果是**代表性JDBC业务投影**，不冒充文章接口、通知、排行榜、ES四个实际业务投影的端到端验收。

## 指标口径

- 创建事件总数＝实际提交的Outbox唯一event_id；尝试创建含回滚另外列示。
- 真正成功＝该event_id有一条已提交bench_effect且有同consumer的已提交幂等记录；只进入DLQ或只标SENT不算成功。重复效果数＝Σmax(同eventId效果行数−1,0)。
- 发布重试＝Outbox retry_count总和；消费重试＝listener调用数−唯一Broker投递数（同连接/信道/deliveryTag是同一投递），同时对照真实retry counter。Broker重投与客户端重试不能混加。
- 重复投递＝Σmax(每eventId唯一Broker投递数−1,0)，包括重放。幂等正确率专门在duplicate/ack_crash场景的40个事件中按“最终恰好一次效果”计算；DLQ失败后首次成功不冒充幂等抑制。
- DLQ累计进入量取真实8条带x-death的消息，当前残留量另外列示；未处理＝提交事件−业务成功事件。
- 端到端：event创建毫秒时间→首个成功消费事务返回后的记录时间，包含提交和故障等待；不是精确binlog commit时间。每次消费原始起止时间均导出。
- 恢复：开始恢复binding/启动Rabbit/启动替代listener/开始人工DLQ重放→所需效果全部可见（崩溃场景为重投已完成）。包括Broker启动/重连/重试到期等待，publisher由测试显式轮询，不包含生产定时器的随机调度等待。
- 4消费者，prefetch1，单轮固定样本故障验收；无性能预热，不将成功样本比例推广为线上SLA或“永不丢消息”。

## 运行与清理

```powershell
node benchmarks/scripts/prepare-outbox.mjs
node benchmarks/scripts/run-outbox.mjs benchmarks/results/outbox/new-run
node benchmarks/scripts/outbox-report.mjs benchmarks/results/outbox/new-run
```

需要本机Docker权限、Java8、已有模块target/classes及缓存的Maven依赖。prepare优先复用现有model-test-classpath，再按项目Spring Boot2.7.1 BOM补充AMQP依赖；缺文件报错，不修改pom/生产配置。13316/15682旧设施不使用，新测试只用127.0.0.1:13416/15782；占用时不得抢占端口，测试启动失败。每次使用新目录和唯一Compose project，拒绝覆盖历史。

runner的finally只清理本轮新project及卷，失败也保留JSON、日志；禁止运行全局docker stop/prune。若进程被外部强杀，先读取manifest中的run并核对容器的paicoding.benchmark.run标签，再用同一compose文件对该project执行down --volumes。已有业务MySQL3306、Rabbit5672及其队列完全不操作。
