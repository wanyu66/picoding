# 代码审计与测试计划

状态只描述静态代码：**已实现**=找到可追踪入口和主要实现；**部分实现**=有机制但存在覆盖/可靠性边界；**未实现/未发现**=本次扫描未找到对应实现或测试。所有运行状态均为未验证，数值 N/A。下表“计划规模”不是实测数据。

## 源码定位规则与模块

下文类名均为实际源码名；运行 `scripts/audit.mjs` 生成的 `source-manifest.csv` 包含完整仓库相对路径及 SHA-256，可按文件名定位。Java 根包为 `com.github.paicoding.forum`。

| 模块 | 实际职责与位置 |
|---|---|
| api | `paicoding-api/src/main/java/.../api/model`：VO、枚举与 DTO；并非所有数据库实体都在 api |
| core | `paicoding-core/src/main/java/.../core`：RedisClient、CacheMetrics、SQL 拦截、MDC |
| service | `paicoding-service/src/main/java/.../service`：业务、repository/entity、mapper、MQ 消费 |
| web | `paicoding-web/src/main/java/.../web/QuickForumApplication.java` 启动；front/admin 控制器、STOMP |
| ui | `paicoding-ui/src/main/resources`：Thymeleaf、浏览器聊天流渲染 |
| 构建 | 根 pom 聚合五模块；web → service/ui，service → core → api；Boot 2.7.1，Java 8；dev/test/pre/prod Maven profile 选择 resources-env，不能只切换 Spring profile 就假定环境已隔离 |

## 1. Elasticsearch 文章检索

**已实现**：`web/front/search/rest/SearchRestController.java`：GET `/search/api/hint?key=...`、`/search/api/list?key=...&page=1&size=10`、`status`、`hot`、`suggestions`、`zero-result`；POST `click`。页面搜索另有 `SearchViewController`。

调用链：`ArticleReadServiceImpl.querySimpleArticleBySearchKey/queryArticlesBySearchKey` → `article/service/search/impl/ArticleSearchServiceImpl.searchHintArticleIds/searchOnlineArticleIds` → ES 召回 ID → ArticleDao/MySQL 回源与展示。无可用 ES 结果时走 MySQL 标题查询；`searchState` 区分 ES_NORMAL、MYSQL_TITLE_FALLBACK、ES_UNAVAILABLE、INDEX_REBUILDING，不能将回退成功计为 ES 成功。

`ArticleSearchServiceImpl` 包含全文召回/排序、在线/删除过滤、高亮、结果缓存、分页游标、熔断、版本化写入、批量重建与别名切换。配置：`service/config/es/ElasticsearchConfig.java`；`resources-env/<env>/application-dal.yml` 的 `elasticsearch.*`。代码默认物理索引 `paicoding_article_v1`，别名 `paicoding_article`。客户端依赖 7.17.4 不代表服务端版本。

管理员入口：`web/admin/rest/ArticleSettingRestController.java`，POST `/api/admin/article/search/rebuild`，GET 同路径 `/{taskId}`，POST `/{taskId}/retry`、`/{taskId}/abort`，GET `/api/admin/article/search/verify`（另有 `/admin/article/` 映射）。重建必须在隔离索引运行；校验采样一致不代表全量一致。

数据库来源：`ArticleDao`、`ArticleMapper.xml` 的文章文档查询；`article`、`article_detail` 等关联表，以 mapper 实际 SQL 为准。初始化必须覆盖作者、分类、标签及统计依赖，避免只插 article 导致假失败。

**部分实现：异步索引可靠性**。`ArticleDomainEventOutboxBridge.append` 在 BEFORE_COMMIT 接收 ArticleMsgEvent（有 fallbackExecution）；`ArticleSearchDomainEventConsumer.consume` 接收 ARTICLE_*，调用 syncArticle/deleteArticle。两方法会 catch 后仅记日志，disabled/ensureIndex 失败也可直接返回；外层消费幂等记录可能提交，不能保证异常进入 Rabbit 重试/DLQ。列为故障验收项，不在本阶段修复。无事务时 fallbackExecution 也不证明业务写入与 Outbox 原子性。

## 2. RabbitMQ / Outbox

**已实现**的业务入口与生产路径：

| 入口 | Service / 生产点 | 主要表 |
|---|---|---|
| `/article/api/favor`、`/comment/api/favor` | `UserFootServiceImpl.favorArticleComment` 事务内追加互动事件 | user_foot |
| POST `/comment/api/post`，`/comment/api/delete` | `CommentWriteServiceImpl.saveComment/deleteComment` | comment、user_foot |
| POST `/user/api/saveUserRelation` | `UserRelationServiceImpl.saveUserRelation` | user_relation |
| POST `/article/api/post`，`/article/api/delete` | `ArticleWriteServiceImpl.saveArticle` 使用 TransactionTemplate；deleteArticle 有事务；ArticleMsgEvent → ArticleDomainEventOutboxBridge | article、article_detail |

统一链路：`DomainEventOutboxServiceImpl.append` → `domain_event_outbox` → `DomainEventOutboxPublisher.publishPending` → `RabbitMqAutoConfig` → 各消费者。

- publisher 每轮默认延迟 1000ms，选取最多 100 条到期记录；条件更新认领 SENDING，等待 confirm 最多 5s，检查 returned，成功 SENT；异常 FAILED + 指数退避；5 分钟过期租约重新待发。串行发送及 confirm 等待影响容量，不假定无限吞吐。
- exchange `paicoding.domain.exchange`，routing key `domain.event`，持久消息及队列、correlated confirm、mandatory return。
- `paicoding.domain.notification.queue` → `NotificationDomainEventConsumer` → `NotificationDomainEventHandler` → notify_msg/通知推送。
- `paicoding.domain.activity.queue` → `ActivityDomainEventConsumer` → UserActivityRankService。
- `paicoding.domain.statistics.queue` → `StatisticDomainEventConsumer` → 统计更新。
- `paicoding.domain.search.queue` → ArticleSearchDomainEventConsumer。
- 各队列对应 `paicoding.domain.{notification|activity|statistics|search}.dlq`，DLX `paicoding.domain.dlx`。AUTO ack；默认不重新入队，容器最多尝试 3 次，间隔从 500ms 倍增；耗尽拒绝进入死信。
- `DomainEventConsumeService.consume` 在事务内 INSERT IGNORE `domain_event_consume` 后执行 handler；主键 `(consumer_name,event_id)`。SQL 回滚不撤销 Redis/ES 外部副作用，因此只能逐消费者验证幂等，不能声称 exactly-once。
- `rabbitmq.switchFlag` 控制 publisher/配置/消费者 Bean；dev 文件写 true 不是在线健康证据。旧 `RabbitmqServiceImpl`、`RabbitmqUtil` 与 `RabbitmqTest` 不等价于统一领域事件验收。

迁移：`liquibase/data/update_schema_260822.sql` 建 activity_rank_event；`260822_2.sql` 建 interaction_event_outbox/consume 并修改 notify_msg；`260822_3.sql` 重命名为 domain_event_*。其中旧迁移含通知去重 DELETE，绝不能拿已有业务库执行测试初始化。`260916.sql`/`260916_2.sql` 为阅读计数 durable outbox/migration，是另一条机制，不混入 Rabbit Outbox 指标。

统计消费者直接对 `user_statistic_<id>` / `article_statistic_<id>` 的 Hash 做 HINCR；若一个增量成功后后续Redis操作或SQL提交失败，重试可能重复应用已成功的增量。M1必须分别核对这些字段，不能用排行榜Lua去重结果替代统计消费者验收。通知处理器则自己在事务中执行消费去重，依赖 notify_msg 的事件/业务唯一键，与通用consumeService路径分开审计。

## 3. Redis 排行榜

**已实现**：`web/front/rank/RankController.rank` → `/rank/{time}`（页面，返回前 30 名）→ `rank/service/impl/UserActivityRankServiceImpl.queryRankList`。写入入口为 ActivityDomainEventConsumer 和 `addActivityScore`；`activity_rank_event` 是持久事实源（event_id 唯一），Lua 负责事件去重、加减分及日/月投影，失败保持 redis_applied=0，定时恢复。

规则实际为路径 +1、点赞/收藏/关注 ±2、评论 ±3、发布 +10；无原始正向事件的撤销会被忽略。不能为构造漂亮结果修改这些规则；乱序撤销须先明确与产品期望是否一致。

**部分实现/边界**：`queryRankList` 使用 zAllScore、hGetAll 后 Java 排序，再截取；Top30 不是只从 Redis 读取 30 项。重建锁为 JVM 内 synchronized，多节点写入/重建互斥尚未证明。Lua 更新与 MySQL 提交不是同一事务。恢复入口 `recoverPendingEvents`、`recoverMissingCurrentRanks`、`summarizeCurrentMonth`；管理员 POST `/api/admin/rank/rebuild`（RankAdminRestController）。重建删除整月投影，只可用于独立实例。

## 4. AI 对话与 WebSocket/STOMP

**已实现**：`web/front/chat/stomp/WsChatConfig` 注册 `/gpt/{id}/{aiType}`、`/notify`，应用前缀 `/app`。`ChatRestController` 的 MessageMapping `/chat/{session}` 与 `/chat/{session}/{chatId}` 接收发送、cancel/resume/heartbeat。认证通过 AuthHandshakeInterceptor/Handler、AuthInChannelInterceptor，不能用路径 session 代替已认证身份。HTTP 历史接口 `/chat/api/listSession`、`syncHistory`、`updateSession`。

链路：`WsAnswerHelper` → `ChatFacade` → `ChatServiceFactory` → `AbsChatService.asyncChat` → provider（例如 DeepSeekChatServiceImpl/DeepSeekIntegration）→ 回调 → `ChatStreamSessionService` → 用户推送。`ChatGenerationControl` 处理上游取消；`ChatHistoryServiceImpl.saveRecord` → `UserAiServiceImpl`/`user_ai_history`，同时维护 Redis 历史和会话；配额有 `user_ai`。不是所有 provider 都必然经过 LLMRouter 的请求重试：ChatFacade 还直接分派 ChatService，必须区分 LLMRouter 路由测试与 provider 流式测试。

`LLMRouter`/`LLMRouteProperties`/`LLMUsageGuard` 有路由、主备、尝试次数和总预算、配额、指标；`ChatContextBudgeter` 处理上下文预算。配置为 `application-ai.yml`、`llm.*`、`chat.websocket.*`，只记录脱敏配置。

**部分实现**：ChatStreamSessionService 有 generationId/seq、限长重放、TTL、终态/取消/超时；运行中 Generation 保存在本机 Map，Redis 重放不等于迁移活跃模型请求。WsChatConfig 支持 simple broker 和条件 broker relay；多实例恢复、真网络断连、慢客户端和鉴权隔离尚需端到端验证。SimpleWsConfig 的配置注解被注释，旧 `/chatgpt` 入口未启用。

WsAnswerHelper 通过用户目的地 `/chat/rsp` 发送；真实客户端需按用户目的地协议订阅。WsChatProperties 默认每用户3连接、重放200事件/900秒、生成上限120秒、发送缓冲512KiB、消息64KiB、队列256；这些只是代码默认值，运行时需记录生效值。broker relay 默认关闭，配置支持不等于已部署多节点broker。

**未实现/未发现的测试能力**：本次未发现统一可执行的真实 STOMP 压测/跨节点恢复验收工具；现有模型测试不覆盖登录和真实 STOMP socket。不能将 UI 替身测试写成整站断网重连成功。

## Redis Key 清单

以下是逻辑 key；`core/cache/RedisClient.keyBytes` 统一加 `pai_` 前缀，采样和清理应以实际物理 key 为准，直接 RedisTemplate 路径需另核验。

| 用途 | 逻辑 key / 类型 |
|---|---|
| 搜索统计 | `article_search:queries:hot`、`:zero`（ZSet）；`article_search:metrics:query_count`、`click_count`、`click_articles` |
| 搜索缓存 | `article_search:result:<generation>:<signature>`；`article_search:cursor:<generation>:<signature>:<page>`，默认 TTL 120/300s |
| 索引代次/引导 | `article_search:index_generation:<index>`；`article_search:keyword_bootstrap:done:...` 及锁 |
| 日/月榜 | `activity_rank_day:yyyyMMdd`、`activity_rank_month:yyyyMM`（ZSet） |
| 达分时间 | `activity_rank_day:first:<bucket>`、`activity_rank_month:first:<bucket>`（Hash） |
| 投影标记/事件 | `activity_rank_day:ready:<bucket>`、`activity_rank_month:ready:<bucket>`；`activity_rank_events:yyyyMM`（Hash） |
| 聊天配额与历史 | ChatConstants：`chat.rates.<source>-<date>`、`chat.list.<source>.<user>`、`chat.history.<source>.<user>[:<chatId>]` |
| 路由配额 | LLMUsageGuard：`llm:quota:minute:<minute>` / `llm:quota:daily:<date>`，Hash，TTL120/172800秒 |
| 互动统计 | CountConstants：`user_statistic_<id>` / `article_statistic_<id>`，Hash字段含praiseCount/collectionCount/commentCount/followCount/fansCount |
| 流会话 | `chat:stream:events:<generation>`（List）、`latest:<session>:<chatId>`、`cancelled:<generation>`、`owner:<generation>`；前缀均为 `chat:stream:` |

排行榜 TTL 按事件日/月及配置保留期计算（默认 31 天/12 个月），固定时区为必要条件。聊天重放 TTL/容量见 WsChatProperties；重放过期结果应单独统计，不按永久可恢复承诺。

## 已有设施与复用边界

| 设施 | 能证明什么 / 不能证明什么 |
|---|---|
| core/service 的 JUnit 测试、Spring Boot starter-test；service H2 | Redis 熔断、TTL、列表批量、阅读计数事务等；mock/H2 不替代实际 Redis/MySQL |
| ArticleSearchServiceImplTest | 高亮、查询定义、关闭状态、超时参数；不代表真实 ES 检索成功 |
| web JUnit4、Jupiter、Spock/Groovy、JMH 1.35 依赖 | `BasicTest` 会启动完整应用；不可全量盲跑。JMH 依赖存在不代表五条链路已有微基准 |
| scripts/load-test/http-benchmark.js + summarize.js | 本机闭环 HTTP；默认 1/8/32 并发、3 轮、预热 3s/测量 10s；保存请求样本 JSON；校验 HTTP+业务码+内容；CPU 是压测客户端指标。GET 详情会写阅读计数，搜索会写统计 |
| scripts/search-evaluation/* | 冻结语料、查询、A/B/C run、manifest、人工盲标页与 NDCG/MRR/Recall；当前未完成人工标签，不能报告相关性提升或 CTR |
| OutboxRankFaultTest + run-outbox-rank-fault.ps1 | opt-in；真实隔离 MySQL/Redis/Rabbit；1000 原始事件、3 份投递、8/32 并发，mixed/rabbit-outage/ack-crash/redis-outage/cancel-before-praise；有 DROP/FLUSHDB/删队列，不覆盖所有业务 HTTP/消费者 |
| ModelStreamFaultHarness + run-model-stream-fault.ps1 | 本地 HTTP/SSE 模拟 → 真 DeepSeek 解析/模板/helper + Redis/MySQL；部分服务替身，历史为隔离 SQL sink；不是正式 DAO/STOMP 全链路 |
| test-model-stream-ui.cjs | Playwright/Edge 检验生产模板渲染、补齐和 cancel 命令；STOMP 是替身 |
| scripts/es-test/generate_articles.py | pymysql；默认 10000 条、200 一批，要求 CONFIRM_TEST_DB；有运行标识但未提供完整清理/恢复闭环，正式复用前补充 manifest 和隔离重建工具 |
| Liquibase master.xml / changelog / data | 应用 schema/历史初始化；仅新建一次性库可用于实验，不用于重置共享业务库 |

## 后续测试矩阵（均未执行）

| ID / 代码入口 | 计划规模、并发、时长 | 对照与断言 | 原始证据 / 清理恢复 |
|---|---|---|---|
| S1 搜索 SearchRestController → ArticleReadServiceImpl | 合成 1千/1万/10万文章，固定 100 查询，C=1/8/32；预热30s、测量60s、3轮 | 同快照 MySQL 标题回退/ES，冷暖缓存分开；成功率、QPS、P95/99，逐轮实际 searchState；两算法语义不同，性能与效果分报 | 请求 JSON/CSV、查询/索引哈希、ES状态；销毁本次专属库/索引实例并重建 |
| S2 相关性 ArticleSearchServiceImpl 查询构造 + search-variants.js | 冻结语料、同一查询集和人工分级标签；顺序运行，耗时只作执行记录 | A全文/B召回/C热度衰减；NDCG@10/MRR@10/Recall；缺标签 FAIL/BLOCKED，非 NDCG=0 | corpus/queries/judgments/runs/requests/manifest JSONL；离线输入只读，无业务清理 |
| S3 索引同步 ArticleSearchDomainEventConsumer → syncArticle/deleteArticle；管理 rebuild/verify | 独立库 1000 合成文章、100次更改/删除；C=1/8，故障30s，恢复观察最多10min | ES健康/断开/恢复及重建并发写；逐ID比 MySQL 可见性与ES；消息已确认而索引缺失应明确 FAIL | eventId、outbox/consume/ES文档快照；独立实例重建，检查别名恢复 |
| M1 OutboxPublisher/ConsumeService/各 Consumer | 复用1000事件×3副本、C=8/32；每场景最多10min；故障30s、3轮；现有 harness 实际参数另存 | 健康与broker故障、投递后未标SENT、消费后未ack、毒消息；检查丢失、重复业务效果、积压/DLQ、SQL回滚；新增各消费者验收后才能覆盖全链路 | 按eventId保存期望/事实/投递次数/时间线；归档后仅重置专属compose资源 |
| R1 排行榜 RankController → queryRankList | 1千/1万/10万合成用户、每人10事件；C=1/8/32；30s预热+60s×3 | 日/月榜分开；SQL事件独立计算分数/同分规则；现有实现为基线，未做优化不能称提升 | 分数/榜单快照、请求样本、CPU/内存；独立Redis/MySQL恢复相同快照 |
| R2 ActivityDomainEventConsumer → addActivityScore/recoverPendingEvents/rebuildRank | 1000事件×3、C=8/32；含撤销、跨日/月、Redis故障30s，恢复10min | 健康对照；重复增分=0、日/月误差=0、pending最终收敛；乱序按现有语义记录，产品期望冲突单列；增加Redis成功后DB回滚场景 | event流水、Lua结果、redis_applied与投影；仅本次实例清空重放 |
| A1 AbsChatService.asyncChat/LLMRouter/ChatGenerationControl | 确定性模拟正常/半流错误/重复终态/超时/取消；C=1/8/32各100次，10min上限 | 正常对照与故障；终态一次、持久化触发一次、取消释放、主备预算不超预定义容差；模拟TTFT不代表模型性能 | 事件seq、调用尝试、模拟延迟、SQL sink快照；关闭模拟进程，清本次run_id数据 |
| W1 WsChatConfig/AuthInChannelInterceptor/ChatRestController/WsAnswerHelper/replay | 待建真实STOMP客户端；合成用户10/100/500连接、300s×3；第3片段断连，TTL内/外恢复 | simple broker单节点基线；无权限订阅拒绝、重放去重、终态、慢消费者；relay双实例作为独立实验，未部署则N/A | 帧时间戳/seq/重连/鉴权判定（脱敏）；关闭客户端、停止本次节点，删除本次会话 |

以上时长/规模为拟定配置。现有脚本参数不同，必须在后续运行 wrapper 中显式实现或记录实际值，不能声称矩阵已可一键执行。新工具只放 benchmarks，不修改既有业务判定。M1/S3/W1 等缺少测试驱动的子场景标为待实现，不使用虚构运行命令。

## 静态发现与优先顺序

1. 优先验证 ES 同步吞异常造成消费成功/索引失败，以及 Redis 投影成功后 SQL 回滚的一致性；这些是从代码识别的风险，尚非现场故障结论。
2. 完善实例隔离、数据 manifest 和恢复验证。现有故障脚本共用 compose project，不能并行运行。
3. 验证源码级单测与模拟故障，再补真实 STOMP；最后测稳态性能。单机、同机压测、mock 测试分别写清边界。
