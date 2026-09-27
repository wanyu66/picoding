# 指标定义与证据规则

本阶段所有业务性能/可靠性数值均为 **N/A（未运行）**。实现存在、配置开关打开、HTTP 200、单测通过都不能替代端到端测量。

## 通用性能口径

| 指标 | 定义 / 单位 | 数据源与边界 | 基线 |
|---|---|---|---|
| 总QPS / 成功QPS | 完成请求数 / 实际测量秒数；成功请求数 / 同一秒数 | 原始逐请求样本；成功同时满足 HTTP、业务码和内容断言；排空时间计入实际耗时 | 所有“吞吐提升”必须 A/B |
| P50/P95/P99 | 升序第 ceil(p×N) 个；ms | 分别输出成功延迟、所有完成请求延迟、错误和超时数；现有HTTP脚本分位只针对成功，不能隐瞒失败 | 性能改善必须同数据/并发对照 |
| 错误率 | 失败数/总完成数 | 分类网络/超时/HTTP/业务/内容错误；未完成请求单列 | 故障前/中/后；零样本=N/A |
| 饱和点 | 达到预先声明错误率/延迟阈值时的并发/到达率 | 闭环脚本只测固定并发容量，存在协调遗漏，不能冒充恒定到达率SLA | 需要相同负载模型 |
| 资源 | 服务端CPU、RSS/堆、GC次数/时间、连接池、线程、队列、磁盘/网络 | 服务进程/容器独立采样；Node客户端CPU/事件循环延迟单列 | 同采样周期与资源限额 |
| SQL/Redis次数 | 每请求实际统计次数 | RequestIoStats、SqlStateInterceptor、RequestSqlDiagnostics、X-Request-Io；线程局部统计不自动覆盖异步链路 | 批量/缓存改善需要同列表规模/路径对照 |

至少3轮、预热剔除，保存各轮结果和离散程度。不要平均各轮 P99 冒充总体 P99；总体分位从合并样本重算，并保留单轮分位。吞吐提升=(B−A)/A，延迟下降=(A−B)/A；A=0 为 N/A。基线不能通过修改业务正确性、丢弃错误或不同数据规模制造。

## 搜索

- 端到端耗时从客户端发起到响应体完整；ES调用耗时来自服务内 timer，两者不可混用。
- 每轮记录 ES_NORMAL / 回退 / 重建状态分布及缓存状态。回退率=发生回退的有效搜索请求/有效搜索请求，需确认埋点是否覆盖一次HTTP中的所有查询，不能直接拼不一致分母。
- 索引延迟=业务提交到该版本在检索侧可见；记录刷新策略、时钟同步和轮询间隔。采样 verify 不能证明全量正确；S3 用全部夹具ID核对。
- NDCG@K 用分级相关性0..3、增益2^rel−1及log2位置折扣；MRR@K/Recall相关阈值为2。Recall仅在已标注全集/候选池内解释，必须写分母范围。
- 有答案查询和无答案查询分开；后者报告误召回比例，前者报告空结果率。有效查询无结果不同于请求失败/缺失run。
- 排名改善必须冻结相同 corpus/queries/judgments，比较 A/B/C，人工标签未完成则 N/A。按意图族 bootstrap，不能把同意图改写视为完全独立样本。
- CTR需要真实曝光与点击窗口及去重口径。现有 query_count/click_count 不是已验证曝光系统，离线CTR=N/A。

## 消息与排行榜可靠性

| 指标 | 定义 |
|---|---|
| 投递延迟 | published_at−业务提交时刻；occurredAt 若在提交前生成不可直接当提交时刻 |
| 业务收敛延迟 | 最后一次必需业务投影成功−提交时刻；逐消费者和eventId计算 |
| 未收敛/丢失 | 截止观察时限仍缺预期结果的唯一eventId/预期eventId；先报告未收敛，不能仅凭超时断言永久丢失 |
| 重复业务效果 | 同一eventId的实际额外增分/额外通知/额外持久化次数；区别于broker重复投递次数 |
| 积压 | Outbox按PENDING/FAILED/SENDING分组；Rabbit各队列ready/unacked分开；DLQ单列 |
| 恢复时间 | 依赖恢复可用到积压/投影满足验收条件；注明轮询误差和定时任务周期 |
| 排名误差 | 独立SQL事件oracle与Redis对应用户分数差、TopK集合/次序差；同分、撤销、时区边界遵循源码规则 |

健康/故障实验必须同事件集、同并发、同恢复窗口。broker ack不等于消费者成功，consume表有记录不等于ES/Redis投影正确，队列ready=0不等于没有unacked或Outbox未发事件。有限样本0丢失只能写“本次N事件未观察到丢失”，不能写100%绝对可靠。

## AI / STOMP

- TTFT：发送开始到首个有效内容delta，不以started/心跳/占位文案为首token。原始事件统一记录 requestId/generationId/seq；HTTP provider、helper、浏览器各自时钟与起点分开。
- 完成耗时：请求到成功completed终态；error/timeout/cancelled分别计数，不能算完成成功。
- 取消延迟：用户cancel到上游连接关闭、到客户端终态分别统计。仅停止渲染不证明停止付费生成。
- 持久化重复率：多终态回调下同generation的持久化次数；现有模拟SQL sink结果不能替代正式user_ai_history端到端验证。
- 重连恢复：TTL内可恢复会话中完整补齐且无重复展示的比例；TTL外、缓存截断、进程消失、跨节点分别报告，不剔除失败美化成功率。
- tokens与费用：以provider返回的usage/账单为准；估算单列。模拟tokens/延迟不得写成真实模型表现。真实模型性能/质量/成本本阶段 N/A。

## 当前观测能力（源码证据）

`paicoding-web/pom.xml` 已引入 Actuator 和 micrometer-registry-prometheus。`application.yml` 管理地址127.0.0.1:8999，web exposure="*"；未访问在线端点，`/actuator/prometheus` 是否可抓取未验证。ES health 检查被关闭，health=UP也不能证明ES可用。本次未发现配套独立 Prometheus 抓取/告警部署证据，不能把导出依赖称为已运行监控平台。

| 实现 | 指标/日志 | 范围限制 |
|---|---|---|
| ArticleSearchServiceImpl | paicoding.search.elasticsearch.latency / requests_total / slow_total / circuit_open_total；paicoding.search.fallback_total | 记录ES调用/回退，不等于完整HTTP耗时；timer未必有可用P99桶 |
| DomainEventMessageMonitor | paicoding.rabbitmq.domain.backlog / dead_letters / retries / dead_lettered_total / consume_delay；publisher.confirm / returned；notification.push | backlog汇总四队列ready，不含unacked；recordConsumed目前由通知consumer调用，不能代表搜索/排行延迟；无样本push.success_ratio返回1，必须结合计数 |
| CacheMetrics | paicoding.redis.circuit.state及缓存计数器 | 注册依赖MeterRegistry及路径；不直接等于业务缓存命中率 |
| LLMRouter / LLMUsageGuard | paicoding.llm.requests_total、latency、first_token_latency、tokens_total、estimated_cost_total、guard.rejected_total | mock/真实分组；无usage不把0当实际零成本；provider流路径覆盖需逐一确认 |
| logback-spring.xml / ReqRecordFilter / RabbitMdcTraceAdvice | traceId、bizCode、请求和MQ日志；SQL/IO诊断 | 原始SQL、prompt、用户信息不能直接归档；仅保留脱敏事件ID/计数/耗时 |

Micrometer名到Prometheus名会发生转换，以实际导出为准。未配置histogram/quantile的timer不能凭空导出P95/P99，改用客户端原始样本。不引入生产监控改动。

## 每轮证据最小集合

`manifest.json`：run_id、UTC起止/时区、source hash/dirty状态、工具/依赖版本与镜像digest、脱敏配置hash、数据规模/seed、并发/负载模型、预热/测量/排空时长、基线ID、模型来源、退出码。

`requests.jsonl`或CSV：逐请求耗时/状态/场景；`events.jsonl`：事件事实、期望、时序及故障注入；`metrics-before/after`：脱敏监控快照；`summary.json`：重算汇总；`cleanup.json`：资源归属、清理/恢复和检查结果。没有采集的数据用null及reason，不填0。
