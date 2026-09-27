# Java 后端实习简历量化成果报告

汇总日期：2026-09-25。范围：`benchmarks/results/` 现存全部阶段结果；本次只读取、核算和写报告，没有重新压测或修改业务代码。

**证据支持工程实现的验证成果，不支持“整体性能提升”“线上零丢失”“真实模型低延迟”等结论。** 以下简历文案优先使用本次实际完成的“构建测试、验证、定位”表述；业务源码的存在不能单独证明个人开发贡献，若本人确实负责对应实现，可据实际分工调整动词。

## 证据范围与统计规则

已读取并记录 **502 个历史结果文件、143,161,049 字节**（不含本次派生审计文件），核对原始请求、事务批次、数据库快照、STOMP 帧和失败记录。60 项跨原始数据核算检查通过；这是**证据完整性检查**，不是 60 项业务全部通过。[核算脚本](scripts/resume-metrics-audit.mjs)和[文件 SHA-256 清单、27 组对照与核算结果](results/resume-metrics-audit.json)可复查所有数字。

|结果族|采用的证据|结论/取舍|
|---|---|---|
|phase1 审计|phase1-2026-09-24T11-09-20-114Z-01dd0b、phase1-report|22 项结构检查；不算业务性能|
|article-selftests / fixtures / import / verify / cleanup|article-selftests-01；100 篇冒烟与 10k 导入/复查；article-import-summary|离线 11/11；1 万篇最终一致性通过，首次 30 分钟验收失败；100 篇逻辑清理通过|
|ES|run-01、raw-results.csv、cell-summary、故障 02、Alias、relevance、selftests|正式选定的 16,896 次测量；故障 3/6；人工相关性 N/A；离线工具测试 5/5|
|Outbox|run-03 为主；run-01/02 和 setup 失败保留|15/15 场景断言、9/9 数据核对；自动恢复与显式 DLQ 重放分开计算|
|排行榜|run-02；run-01 仅保留设施失败|30/40 通过；10 失败中 4 个为非原子对照，另 6 个为实际服务场景|
|对话|run-03；run-01/02 为设施失败|排除预热后 207/211 会话通过；同步路由 40/40；真实模型调用 0|

4 份历史 JSON 导出不完整：Outbox run-02 的 `bench_effect.json`、`domain_event_consume.json`、`domain_event_outbox.json`，以及 ranking run-01 的 `10-multi_user-mysql-ledger.json`。本次仍保留并记录解析失败；不把这些轮次当作完整成功。ES 早期不等比例混合查询单元也不与正式补跑样本相加；fault-harness-01 未实际注入网络故障，不采用其表面 PASS。

**环境定义：**

- **本机真实依赖集成**：使用实际 MySQL/Redis/RabbitMQ/ES 和部分或全部实际 Java 组件；合成用户、业务夹具或内存 DAO 会明确标注。它不等于生产环境。
- **Mock 故障/模型测试**：实际网络和协议组件对接可控故障桩；只衡量协议、恢复、取消及选路行为，不衡量模型推理性能。
- **真实线上环境**：没有线上流量、多机容量、生产 SLA 或真实付费模型测量，相关指标全部 N/A。

各轮主机为 i7-14650HX、24 逻辑 CPU、约 15.78 GiB 内存、Windows 10.0.26100、Node 24.15.0。Java harness 强制 Java 8；ES 进程为 ES 8.10.0 / JVM 20.0.2 / heap 512 MiB，不应把它写成 Java 应用 heap。具体镜像、源码哈希与环境见各主结果 manifest/provenance；各轮 MySQL 版本也不同，不能混作相同配置的性能对照。

**计算口径：**

```text
延迟下降率 = (基线延迟 - 当前延迟) / 基线延迟 × 100%
吞吐量提升率 = (当前吞吐 - 基线吞吐) / 基线吞吐 × 100%
实测成功率 = 满足该场景业务断言的样本数 / 对应有效尝试数
比例变化“百分点” = 当前比例 - 基线比例
```

负的下降率表示延迟上升；负的提升率表示吞吐下降。先用未舍入原始值计算，再显示两位小数。P95/P99 从逐次记录按 nearest-rank 计算，不平均各轮 P95；QPS 用总请求数/对应测量总秒数，不平均各轮 QPS。小样本百分位与单轮 100% 通过不等于稳定 SLA。不同并发、不同负载、不同测试层次之间不计算“优化收益”。

## A. Java 后端实习简历版：4 条项目亮点

以下可直接作为“项目实践/工程验证”描述，保留本机、Mock 和夹具限定：

1. **全文检索与索引重建：**围绕 Elasticsearch 综合检索、`search_after` 和版本化 Alias 构建可复现验证，覆盖 **1 万/5 万/10 万篇合成文章**；实际 Java 重建方法对接本机 ES、内存 DAO，**1 万篇重建耗时 37.51 秒，重建窗口 215 次查询无错误**。
2. **异步事件可靠性：**基于 Transactional Outbox、RabbitMQ Confirm 与消费事务幂等构建隔离故障测试，覆盖 **96 个已提交事件、80 次额外投递**；在 JDBC 业务夹具中，**40/40 个重复/提交后崩溃事件仅产生一次业务效果，重复写入 0 次**，区分自动补偿与 DLQ 显式重放。
3. **排行榜一致性：**对 Redis ZSet＋Lua＋MySQL 行为日志建立独立预期值测试，覆盖 **10/50/100/200 并发**；**360 条 Redis 停机期间事件全部补偿，恢复积分一致率 100%**，同时定位不同 eventId 重复计分与 MySQL 死锁边界。
4. **流式对话与模型路由：**构建可控 Mock Provider 和真实 WebSocket/STOMP 客户端；本机 **50 会话并发 TTFT P95 274 ms**，**断线回放 10/10 成功、取消响应 P95 9.48 ms**；40 次同步路由故障检查通过，并发现 100 会话突发下的入站队列拒绝问题。

不建议在简历版加入“search_after 提升 6.86%”：该值只是已定位游标后的短测观察，其他规模有退化、吞吐没有一致提升。也不写“排名完全幂等”“消息绝不丢失”“真实模型首字 274ms”。

## B. 项目详细讲解版

### B0. 证据基础与可复现设施

**S｜业务问题：**项目有搜索、消息、排行榜、AI 等实现，但接口存在不等于性能或一致性已经验证，历史失败容易被最终汇总掩盖。

**T｜工程任务：**固定语料、输入、执行参数、源码版本与清理归属，保证指标能从原始记录重算。

**A｜解决方案：**在 benchmarks 下增加固定 seed 造数、批次隔离、原始 JSON/CSV/JSONL、源码 SHA-256、故障注入和清理；本次再次遍历全部结果，重算 ES 分位数/QPS、文章事务吞吐、Outbox 效果、排行榜操作成功率与对话样本分母。

**R｜真实结果：**历史结构检查 22 项、文章离线工具测试 11 项、ES 工具测试 5 项通过；本次 60 项证据核算通过，明确识别 4 份历史不完整 JSON。**业务优化前后基线、线上性能收益：N/A。**

**来源与条件：**[phase1](results/phase1-report.md)、[文章工具测试](results/article-selftests-01/summary.json)、[ES 工具测试](results/es/selftests-final.tap)、[本次审计](results/resume-metrics-audit.json)。本次为离线读取计算，不连接服务、不重新产生负载。

**复现命令：**

```powershell
node benchmarks/scripts/resume-metrics-audit.mjs
```

**面试追问：**为什么要区分功能断言与证据检查？如何避免选择最好一轮？原始 JSON 损坏为什么不能补写为 PASS？HEAD 与工作区源码哈希有什么区别？

### B1. 文章批量导入与异步搜索一致性

**S：**文章写入数据库后需要经过 Outbox/消费者进入搜索索引，SQL 提交或消息 SENT 都不足以证明 ES 可见。

**T：**构建确定性数据，测量导入与逐篇对账，验证超时和清理边界。

**A：**单连接、每事务 200 篇，共 50 批写入 1 万篇合成文章及关联计数/Outbox；独立对比 MySQL 与 ES 的内容、日期、状态、热度；保存同步轮询时间线。业务关联入口为 [ArticleDomainEventOutboxBridge.append](../paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/ArticleDomainEventOutboxBridge.java)、[ArticleSearchDomainEventConsumer.consume](../paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/ArticleSearchDomainEventConsumer.java)。**测试走 SQL 夹具导入，不是发布 Controller，也没有实测 bridge 本身的接口耗时。**

**R：**

|指标|结果|
|---|---:|
|MySQL 批事务总耗时|9.7396264 秒|
|导入吞吐|10,000 / 9.7396264 = **1,026.73 篇/秒**|
|首轮停止观测时 ES 可见|9,242/10,000 = **92.42%**|
|首轮未可见|758 篇；30 分钟时限验收失败|
|次日完整复查|10,000/10,000，缺失 0、字段差异 0，最终一致率 **100%**|
|ES 精确同步耗时|**N/A**；最后不完整观测距末批提交 1,799.353 秒|
|基线→当前提升|**N/A**，没有同条件优化前导入测试|

条件：本机既有开发库中带批次标记的合成数据；MySQL 8.0.45、ES 8.10.0；seed=20260924，固定参考日 2026-01-01；正文长度覆盖 240/1200/6000 字符。导入计时不含预检/字段核对，导入与消费同步重叠。不能写成“1 万篇索引同步仅 9.74 秒”；次日复查 7.90 秒也不是同步完成耗时。5 万/10 万文章仅离线生成，在线 SQL 导入未执行。

**来源：**[逐批耗时](results/import-10k-01/batches.jsonl)、[同步时间线](results/import-10k-01/sync-polls.jsonl)、[首轮 FAIL](results/import-10k-01/summary.json)、[逐篇最终对账](results/verify-10k-01/consistency.jsonl)、[汇总](results/article-import-summary.json)。100 篇清理采用墓碑，保留审计及合成用户/分类；1 万篇仍保留供后续测试，不声称所有测试数据均已删除。

**复现命令：**命令读取本机专用配置；新造数应使用新的批次标识和新的结果目录。下面是原轮次实际命令；已有结果不可覆盖，具体隔离/清理见[文章操作说明](article-import.md)。本次汇总没有执行写库命令。

```powershell
node benchmarks/scripts/articles.mjs generate --config benchmarks/config/articles.local.json --data benchmarks/data/search-10k-v1
node benchmarks/scripts/articles.mjs fixtures --config benchmarks/config/articles.local.json --env-file .env --report benchmarks/results/fixtures-10k-01
node benchmarks/scripts/articles.mjs import --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/import-10k-01
node benchmarks/scripts/articles.mjs verify --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/verify-10k-01
```

**面试追问：**Outbox SENT 为什么不是索引完成？如何定义最终一致性的时限？为什么同步耗时只能给下界？如何只清理本批次、保留删除版本？SQL 导入吞吐与业务发布 QPS 差在哪里？

### B2. Elasticsearch 检索成本与分页对照

**S：**检索既要支持多字段、拼音/错词和热度/时间排序，也要控制查询成本；功能更丰富不能直接等于更快或更相关。

**T：**在相同语料和查询配比下对比基础策略 A 与当前综合策略 B，并单独对比分页方式。

**A：**从实际 [ArticleSearchServiceImpl](../paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java) 导出 DSL；定位 `buildSearchBody/functionScoreQuery`、`fuzzyMatch/pinyinMatch`、游标读写。A 为等权 best_fields/AND，B 为当前召回＋quality/阅读/点赞/时间综合排序；高亮、source、filter、sort 相同。分页使用同一 B 查询，独立核对 from 与 search_after 返回 ID。

**R：**1 万/5 万/10 万篇、并发 1/4 的正式单元累计 **16,896 次测量、948.56 秒测量墙钟总和、请求错误 0**。这是 ES 引擎组件短测，不是业务 HTTP 压测；应用当时仍为 `MYSQL_TITLE_FALLBACK`。

以下均为并发 4、7 查询等比例混合，A→B；**A 不是历史发布版本，B 的召回/排序语义不同，只能解释为策略成本对比。**

|篇数/返回数|样本 A/B|P95 A→B（ms）|P95 下降率|QPS A→B|吞吐提升率|
|---|---:|---:|---:|---:|---:|
|10,000 / 10|2709/224|38.74→616.08|**−1490.43%**|179.34→13.81|**−92.30%**|
|10,000 / 50|770/63|130.75→2427.67|−1756.77%|50.25→3.16|−93.71%|
|50,000 / 10|2625/168|39.97→1178.57|−2848.28%|173.87→9.70|−94.42%|
|50,000 / 50|770/63|128.34→2813.90|−2092.46%|50.09→2.87|−94.27%|
|100,000 / 10|2569/126|42.58→1861.56|**−4272.38%**|170.40→6.76|**−96.03%**|
|100,000 / 50|728/63|138.94→3692.04|−2557.37%|46.82→2.50|−94.65%|

例如 10 万/10 条：`(42.5755−1861.5637)/42.5755 = −4272.38%`，表示 P95 **增加** 4272.38%，绝不是下降。当前策略确实更慢；人工相关性标签未完成，不能用假定的相关性收益抵消性能退化。

**分页限定对照：**并发 4、size=10、offset=9990，B 策略；search_after 已提前定位游标。

|篇数|样本 from/after|P95 from→after（ms）|P95 下降率|P99 下降率|QPS from→after|吞吐提升率|
|---|---:|---:|---:|---:|---:|---:|
|10,000|113/116|637.83→655.42|−2.76%|−2.48%|6.9012→7.1369|+3.42%|
|50,000|60/60|1247.14→1284.98|−3.03%|+0.33%|3.4634→3.4585|−0.14%|
|100,000|37/38|2173.65→2024.63|**+6.86%**|+5.38%|2.0082→2.0018|**−0.32%**|

10 万篇 P95 的下降是本次观察值，但样本仅 37/38，吞吐略降，其他规模 P95 上升；**不能外推稳定的分页优化比例**。游标准备分别耗时 3.35/15.33/27.50 秒，未计入定位后请求。应用最多 50 页，offset=9990 只是 ES API 反事实对照，不是已上线业务分页能力。全并发/返回数/深度的 27 组变化率见本次审计 JSON。

**测试条件：**本机共享 ES 8.10.0、IK/pinyin、1 shard/0 replica、heap 512 MiB；每单元 3 轮，预热 2 秒/轮、计划测量 5 秒/轮，含尾部排空后实际每单元累计约 15 秒或更长。客户端超时 30 秒而非应用 1500ms。1 万/5 万不等配比的初次混合结果被正式完整 7 查询循环补跑替代；分页仍使用原有效单元。50k/100k 直接写独立 ES 索引，不代表 MySQL 有同等在线数据。

**来源：**[原始请求 CSV](results/es/raw-results.csv)、[54 个聚合单元](results/es/cell-summary.json)、[参数与结果](results/es/summary.json)、[分页一致性和轮次数据目录](results/es/run-01)、[相关性状态](results/es/relevance-report.md)。拼音/模糊非字面匹配探针 2/2 通过，仅证明功能分支；MRR/NDCG/Recall 提升均 N/A。heap 峰值约 437.84～442.12 MiB、CPU 记录属于共享 ES 节点，不能归因于单一策略或 Java 应用内存优化。

**复现命令：**新目录顺序执行，使用带 IK/pinyin 的专用或已核对归属的本机 ES；完成后按清单清理测试索引。详细限定见 [ES 操作说明](es-operations.md)。

```powershell
node benchmarks/scripts/prepare-es.mjs
node --test benchmarks/scripts/es-benchmark.test.mjs
node benchmarks/scripts/es-benchmark.mjs init benchmarks/results/es/resume-new-run
node benchmarks/scripts/run-es-suite.mjs benchmarks/results/es/resume-new-run
node benchmarks/scripts/es-report.mjs benchmarks/results/es/resume-new-run
node benchmarks/scripts/es-benchmark.mjs cleanup benchmarks/results/es/resume-new-run
```

**面试追问：**为什么函数评分/模糊召回成本高？QPS 与召回结果集合如何公平比较？为何不平均 P95？search_after 为什么不支持直接任意跳页？没有人工标签怎样避免伪造“召回提升”？共享小 heap/GC 对测试有什么影响？

### B3. 版本化索引重建与 Alias 切换

**S：**全量重建不能直接破坏正在服务的旧索引，需要在构建完成后切换查询入口。

**T：**验证真实 Java 重建流程和 ES Alias 切换，同时观察查询是否失败。

**A：**调用 `ArticleSearchServiceImpl.executeFullRebuild → bulkIndexWithRetry → activateRebuiltIndex → switchAlias`；旧新索引各 1 万篇，独立测试 alias；DAO 为固定内存语料、Redis 为替身，ES 真实读写。

**R：**重建 **37,508.28ms**；严格任务窗口 37.503 秒内 **215/215 次查询成功、错误 0、P95 757.12ms**。完整观察窗口另有 304/304 成功、P95 751.42ms，包含启动与切换后观察，不能混算。旧/新索引均 10,000 篇；全窗口 289 次返回旧索引、15 次返回新索引。无旧重建方案基线，延迟下降/吞吐提升 N/A；不声称生产“零停机 SLA”。

**条件与来源：**并发查询 4，每次后暂停 10ms，无预热；bulk=200、maxRetries=3、1 shard/0 replica。[原始查询](results/es/run-01/alias/queries.jsonl)、[任务结果](results/es/run-01/alias/result.json)、[严格窗口](results/es/alias-task-window.json)。只验证静态夹具重建，真实 MySQL 扫描、并发增量写入和失败批恢复 N/A。

**复现命令：**在 B2 init 后、cleanup 前执行：

```powershell
node benchmarks/scripts/es-benchmark.mjs alias benchmarks/results/es/resume-new-run
```

**面试追问：**Alias 切换原子性与重建期间数据一致性有什么区别？如何处理增量写入跨版本？旧索引何时能删？为什么 215 与 304 都正确但口径不同？

### B4. 搜索故障与降级边界

**S：**ES 网络异常或部分响应失败可能影响搜索；HTTP200 不等于查询完整成功。

**T：**验证现有搜索/读服务是否进入回源路径，保留失败类型。

**A：**使用实际搜索与读服务、loopback HTTP 故障桩；覆盖 socket timeout、端口关闭、`timed_out=true`、failed shard、缺游标、超过最大页。DAO 返回空列表替身，Redis 缓存未命中，覆盖标志设为完成以实际触发网络。

**R：****3/6 用例断言通过**。socket timeout 3 次搜索约 2198.32/2015.89/2029.34ms，读服务 DAO 调用 1 次；端口关闭实际触发连接超时，未获得预期的 connection refused，注入类型断言失败。HTTP200 的 timed_out 和 failed shard 均未触发 DAO，2 项确认失败。缺游标与超页返回空是行为核对通过，不是高可用成功率。真实 MySQL 回源正确率/QPS、线上故障成功率 N/A。

**条件与来源：**Java 8；单线程，每故障 3 次搜索＋1 次读服务；socket 配置 1500ms，总耗时仍可能超过 1500ms。[故障原始 JSON](results/es/fault-harness-02.json)、[说明](results/es/fallback-report.md)。源码入口同 B2 的搜索查询/响应解析和 [ArticleReadServiceImpl](../paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java) 回源流程。无修复后数据，不计算修复收益。

**复现命令：**先 prepare-es；新输出路径：

```powershell
& .runtime/java8/bin/java.exe '-Dfile.encoding=UTF-8' '-Dlogback.configurationFile=benchmarks/config/es-logback.xml' -cp 'benchmarks/build/es;benchmarks/build/es/classpath.jar' SearchFaultHarness benchmarks/results/es/fault-resume-new.json
```

**面试追问：**响应超时标志与 socket 超时有什么区别？分片部分失败如何验收？为什么注入失败不算业务通过？空列表回源替身能证明哪些事情、不能证明哪些事情？

### B5. Transactional Outbox 与 RabbitMQ 可靠性

**S：**业务提交与发消息存在一致性窗口，重投和“提交后未 ACK 崩溃”又可能重复执行业务。

**T：**验证事务回滚、发送确认、失败重试、重复消费和 DLQ 补偿，而不是只统计发布成功。

**A：**实际调用 [DomainEventOutboxServiceImpl](../paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/DomainEventOutboxServiceImpl.java)、[DomainEventOutboxPublisher](../paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/DomainEventOutboxPublisher.java)、[DomainEventConsumeService](../paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/service/impl/DomainEventConsumeService.java) 和 [RabbitMqAutoConfig](../paicoding-service/src/main/java/com/github/paicoding/forum/service/notify/config/RabbitMqAutoConfig.java)。消费令牌与同库 JDBC 业务效果共用真实事务；效果表不设 event_id 唯一约束，避免数据库约束掩盖重复 handler。子进程提交后真实 halt，Broker 负责未 ACK 重投。

**R：**

|指标|实测与分母|
|---|---|
|事件尝试/主动回滚/提交|104 / 8 / 96；回滚事件不计为丢失|
|自动恢复后的业务成功|88/96 = **91.67%**，8 条仍在 DLQ|
|显式 DLQ 重放后|96/96 = **100%**；比补偿前增加 **8.33 个百分点**|
|重复/崩溃事件幂等正确率|40/40 = **100%**|
|Broker 投递/额外投递|176 / 80；不是 exactly-once 投递|
|已提交效果/重复写入|96 / **0**|
|发布重试/消费内部重试|16 / 16|
|发送失败恢复 / Rabbit 重启恢复|2215 / 5077 ms|
|提交后未 ACK 重投去重 / 显式 DLQ 补偿|128 / 165 ms|
|含故障等待的端到端 P50/P95|1286 / 22179 ms，不是正常延迟基线|

**基线说明：**没有同步发送旧版本或无幂等版本的同条件性能基线；吞吐提升、延迟下降 N/A。91.67%→100% 是同轮**增加人工式补偿步骤**的结果，不能称代码优化提升，也不能写“自动恢复 100%”。

**条件与来源：**独立 MySQL/RabbitMQ、4 消费者、prefetch=1、44.032 秒故障矩阵、未预热；JDBC 代表性业务投影，不是文章/通知/排名/ES 全链路。[提交源记录](results/outbox/run-03/bench_source.json)、[效果](results/outbox/run-03/bench_effect.json)、[逐次消费](results/outbox/run-03/bench_attempt.json)、[补偿前检查点](results/outbox/run-03/07-before-manual-compensation.json)、[汇总](results/outbox/summary.json)。恢复直接调用生产补偿方法，不含定时调度等待。15/15 断言和 9/9 核对通过，但 run-02 快照损坏不作为主结果。

**复现命令：**[隔离与清理规则](outbox-test-plan.md)；每次新目录：

```powershell
node benchmarks/scripts/prepare-outbox.mjs
node benchmarks/scripts/run-outbox.mjs benchmarks/results/outbox/resume-new-run
node benchmarks/scripts/outbox-report.mjs benchmarks/results/outbox/resume-new-run
```

**面试追问：**Confirm ACK 与 mandatory return 为什么都要检查？SENT、已消费、业务成功分别代表什么？INSERT IGNORE 与事务回滚如何配合？Redis/ES 副作用为什么不受同库事务保护？DLQ 是否自动重放？如何避免把 at-least-once 描述成 exactly-once？

### B6. Redis ZSet＋Lua 排行榜与 MySQL 日志恢复

**S：**并发积分累加容易丢更新，重复事件容易多计；Redis 不可用后需要恢复可信榜单。

**T：**分别验证原子更新、事件 ID 幂等、业务操作幂等，以及恢复后的积分和排名。

**A：**实际 [UserActivityRankServiceImpl](../paicoding-service/src/main/java/com/github/paicoding/forum/service/rank/service/impl/UserActivityRankServiceImpl.java) 通过 Lua 同步更新事件 Hash、日/月 ZSet、达分时间和标记；[ActivityRankEventDao](../paicoding-service/src/main/java/com/github/paicoding/forum/service/rank/repository/dao/ActivityRankEventDao.java) 保存事实日志，pending 补偿与整月重建重放。独立业务键模型计算正确分值，并检查日/月排名。**这是用户活跃榜，文章 ID 仅是行为目标，不是文章积分榜。**

**R：**40 项中 30 通过、10 失败；其中实际服务场景 22/28 通过、直接 Lua 8/8 通过、非原子演示对照 0/4 通过。

|并发|400 次多用户操作测量 ms|同文章多用户 TPS|P95/P99 ms|同事件重复投递多计|Redis 故障待补偿|补偿耗时 ms|恢复一致率|
|---|---:|---:|---:|---:|---:|---:|---:|
|10|1612.99|247.99|53.48/56.59|0|10|214.95|100%|
|50|760.10|526.25|157.20/204.89|0|50|912.97|100%|
|100|611.13|654.53|257.29/391.78|0|100|1935.32|100%|
|200|681.29|587.12|634.96/670.08|0|200|4471.54|100%|

4 档共 360 条停机事件补偿完成，pending 从 360 降为 0；这仅限停机夹具的积分恢复。停机期间写入阶段分别耗时 1134.94/5445.93/10766.85/21620.57ms，另计上表补偿时间。每档 400 次同 eventId 投递均无多计；不能外推不同 ID 的业务去重。

|失败场景|10 并发|50 并发|100 并发|200 并发|
|---|---:|---:|---:|---:|
|不同 ID 重复点赞，预期均 2 分，实际分值|800|800|774|766|
|多计的 2 分单元数|399|399|386|382|
|该场景 MySQL 死锁失败请求|0|0|13|17|
|热点用户合法积分累加死锁失败请求|0|2|0|25|

热点 200 并发成功返回 375/400 = **93.75%**，实际 750 分而非期望 800 分；50 并发为 398/400 = **99.50%**。全矩阵实际记录 57 次死锁失败。不同 ID 重复点赞和两项热点失败重建后仍错误：MySQL 日志“可重放”不等于业务事实正确或所有输入都已提交。并发重复点赞 API 生产端未端到端验证，不能声称 API 必然发生同等多计。

**基线：**ZSCORE→ZADD 非原子客户端对照全部发生积分差异；实际 Lua 的热点 +2 更新全部达到预期。但 Lua 额外维护去重、日/月和元数据，不是同功能旧版本，**不得以 TPS 比值声称优化提升**。不同并发的 TPS 增减是负载曲线，不是代码优化。真实服务中还有 JVM 实例锁，直接 Lua 吞吐也不能当业务 TPS。

**条件与来源：**Java 8、独立 MySQL 8.0.46/Redis、220 数据库连接池、Redis 超时 100ms、Redis 无 AOF/RDB；常规每项 400 调用，故障每线程 1 事件；100 次预热、每项一次短批量。重建在停止写入后执行，单次重建耗时包含删键、查日志、Lua 重放；原始逐场景耗时见 [metrics.csv](results/ranking/run-02/metrics.csv)、[summary](results/ranking/run-02/summary.json)、[夹具/日志/延迟目录](results/ranking/run-02)、[独立校验](results/ranking/run-02/evidence-check.json)。同分事件时间统一，因此未验证乱序达分时间的排序稳定性。

**复现命令：**[测试说明](ranking-test-plan.md)，新目录，返回码 2 表示存在原样保留的失败：

```powershell
node benchmarks/scripts/prepare-ranking.mjs
node benchmarks/scripts/run-ranking.mjs benchmarks/results/ranking/resume-new-run
node benchmarks/scripts/ranking-report.mjs benchmarks/results/ranking/resume-new-run
node benchmarks/scripts/check-ranking.mjs benchmarks/results/ranking/resume-new-run
```

**面试追问：**Lua 原子性与业务幂等性有什么区别？相同业务操作换 eventId 会怎样？取消为何回写原日/月？MySQL 提交与 Redis 修改之间有哪些窗口？JVM 锁能否保护多实例重建？为什么重建前后一致仍可能是错的？

### B7. 多模型路由与 WebSocket/STOMP 流式可靠性

**S：**流式对话涉及异步输出、用户取消、断线、重复回放、上游超时和模型不可用；只测一次完整回答无法覆盖这些路径。

**T：**用可控模型确定独立答案，在真实 STOMP 网络链路测量首字/完成延迟及可靠性，区分同步路由与流式选路。

**A：**实际 [LLMRouter](../paicoding-service/src/main/java/com/github/paicoding/forum/service/llm/LLMRouter.java)、[ChatFacade](../paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/ChatFacade.java)、[DeepSeekChatServiceImpl](../paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/impl/deepseek/DeepSeekChatServiceImpl.java)、[WsChatConfig](../paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/stomp/WsChatConfig.java)、[ChatRestController](../paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/rest/ChatRestController.java)、[ChatStreamSessionService](../paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/helper/ChatStreamSessionService.java) 与 [ChatGenerationControl](../paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/ChatGenerationControl.java) 对接本机固定 SSE。另执行实际 [网页模板](../paicoding-ui/src/main/resources/templates/views/chat-home/index.html) 的订阅、渲染、resume 和 stop 处理器，验证重复展示。

**R｜Mock 流式链路：**排除预热后 **207/211 = 98.10%** 会话满足独立验收；这是混合用例样本比例，不是线上成功率。

|并发|成功/尝试|TTFT 有效样本|TTFT P95/P99 ms|总耗时 P95/P99 ms|缺失期望事件|模型调用|
|---|---:|---:|---:|---:|---:|---:|
|1|1/1|1|100.83/100.83|450.14/450.14|0|1|
|10|10/10|10|144.74/144.74|458.58/458.58|0|10|
|50|50/50|50|274.03/279.70|598.66/600.90|0|50|
|100|96/100|96|257.38/257.68|576.11/587.23|32|97|

正常 1/10/50/100 并发整批观测分别为 921.28/940.05/1096.67/1143.03ms，包含连接、订阅及尾部检查；它不同于表中单会话 generate→终态耗时。全运行含启动清理约 26.99 秒，时间戳见 manifest/runner-status。

100 并发有 4 个会话收到入站队列 256 满导致的 TaskRejectedException：3 个没有调用 Provider，对应 24 条未生成期望事件；1 个 Provider 已执行但客户端收到 0 条事件，对应 8 条缺失。**32 条不能全称为网络丢包，96 个成功样本较低的 P95 也不能称为 100 并发性能更优。**

|故障能力|结果与条件|
|---|---|
|主动取消|10/10，取消响应 P95/P99 **9.48/9.48ms**；10 个上游 socket 确认断开|
|真实 WebSocket 断开后重连回放|10/10 = **100%**；恢复 P95 **87.09ms**，每会话模型调用仍为 1|
|重复回放|10 会话，共 160 条额外事件全部过滤；实际 DOM 多余答案元素 **0**|
|重复上游 DONE|10/10；各会话历史效果仅 1 条，不重复提交|
|流式超时|10/10；设置 1.2 秒，终态总耗时 P95 **1330.39ms**，保留部分输出并取消上游|
|流式服务不可用|本次 primary 503 返回 error；熔断后下一次请求由 backup 完成|
|同请求中途流式模型切换|**N/A：没有该实现，不能用同步路由成功替代**|

取消实现会发送缓存正常 seq 终态和最大安全整数 seq 确认两个同义终态；测试保留该事实。恢复计时从重建连接前到终态，断网固定等待 500ms 未包含在上述 87.09ms 内；网页自动指数退避计时本轮未验证。首次连接握手时间不计入 TTFT。

**R｜Mock 同步路由，单独统计：**40/40 通过；四场景各 10 次，单次调用预算 80ms、总预算 350ms、最多 3 次尝试、maxRetries=1。timeout/unavailable/template 场景每样本 primary 2 次、backup 1 次；circuit 每样本连续 3 个请求，primary 总共 2 次、backup 3 次。路由总耗时 P95 分别 **196.99 / 15.44 / 5.54 / 28.90ms**，不是流式 TTFT。同步 Mock Provider 共 140 次调用，流式 SSE Mock 共 211 次（含预热和降级），真实付费模型 **0 次**。

**基线与条件：**没有相同模型/参数的旧版本数据，延迟下降、吞吐提升 N/A。每档只跑一批；固定 6 个分片，每片等待 60ms；测试 OkHttp maxRequests/host=256，区别于生产默认调度。实际 Tomcat、Spring simple broker、控制器、SSE 解析和 Redis；身份、历史存储、敏感词为夹具，备用 source 复用同一 SSE 适配器，未验证真实智谱 SDK。正常流超时设 15 秒；并发 100 是突发负载，不能推断持续容量。前端 DOM 检查用捕获事件和合成 STOMP adapter，不等于真实浏览器认证页面全流程。

**来源：**[逐会话 JSON](results/chat-stream/run-03/client-samples.json)、[原始 STOMP 帧](results/chat-stream/run-03/stomp-frames.jsonl)、[Provider 记录](results/chat-stream/run-03/provider-final.json)、[同步路由](results/chat-stream/run-03/router-results.json)、[流式降级](results/chat-stream/run-03/streaming-fallback.json)、[UI](results/chat-stream/run-03/ui-results.json)、[CSV](results/chat-stream/run-03/metrics.csv)、[独立核对](results/chat-stream/run-03/evidence-check.json)。

**复现命令：**新目录，默认强制 Mock，真实模型另行授权和配置；[详细说明](chat-stream-test-plan.md)：

```powershell
node benchmarks/scripts/prepare-chat-stream.mjs
node benchmarks/scripts/run-chat-stream.mjs benchmarks/results/chat-stream/resume-new-run
node benchmarks/scripts/chat-stream-ui.cjs benchmarks/results/chat-stream/resume-new-run
node benchmarks/scripts/chat-stream-report.mjs benchmarks/results/chat-stream/resume-new-run
node benchmarks/scripts/check-chat-stream.mjs benchmarks/results/chat-stream/resume-new-run
```

**面试追问：**同步 fallback 与流式 fallback 差在哪里？取消 UI 确认是否等于上游停止推理？seq 去重遇到乱序会不会丢显示？快照回放与 token 回放的区别？200 事件上限/900 秒 TTL 之后怎么办？STOMP 背压和 broker relay 多实例有什么影响？高并发失败后为什么不能只报成功样本的低延迟？

## C. 尚缺失或证据不足的指标清单

|指标/主张|当前状态|补证据需要什么|
|---|---|---|
|整体延迟下降、吞吐提升百分比|**N/A**；没有同功能旧版→新版的统一对照|冻结两个实现版本、相同依赖/持久化/硬件/数据/并发，多轮交替测量|
|ES 综合检索性能提升|**未测得，当前策略明显更慢**|先定位查询/GC 成本；保留功能语义后重新 A/B，不能删功能只为提高数据|
|search_after 普遍提速|证据不足；10 万定位后 P95 降 6.86%，吞吐降 0.32%，其他规模 P95 退化|扩大样本，计入取游标成本；区分顺序翻页与任意跳页，独占环境多轮验证|
|搜索 MRR/NDCG/Recall 提升|**N/A**；三规模均待人工标签|真实查询、盲审相关性标签、足够候选池和标注一致性；不要自动补评分|
|业务 HTTP 搜索 QPS、正常 ES 模式接口延迟|**N/A**；应用采样为 MYSQL_TITLE_FALLBACK|确认完整索引覆盖和真实 ES_NORMAL，再测 HTTP/回源/缓存全链路|
|1 万文章精确同步完成时长|**N/A**；30 分钟首次失败，仅次日证实一致|连续观测每阶段确认与可见性，保留提交、发布、消费、刷新时刻|
|5 万/10 万篇 MySQL 在线导入|**未执行**；仅离线生成，ES 单独造数|独立库在线导入、对应规模同步和清理验收|
|搜索故障完全降级|**失败**；HTTP200 timed_out/failed shard 未回源|另行最小业务修复后重测；本轮不能当作已修复成果|
|Outbox 自动恢复 100%、exactly-once 投递|**不成立**；自动 91.67%，人工补偿后 100%，实际有重复投递|明确 DLQ 运维/自动补偿流程，验证外部副作用、租约过期、多发布者和分区|
|通知/Redis/ES 所有业务投影端到端成功率|**N/A**；Outbox 用 JDBC 夹具，其他实验分开测|以实际消费者与业务接口串联验收，覆盖跨存储提交/回滚窗口|
|排行榜业务完全幂等|**失败**；不同 ID 重复点赞多计，57 次死锁失败|生产端状态并发保护、稳定事件 ID、重试语义验证；业务修改需单独阶段|
|文章积分排行榜能力|**N/A**；实现是用户活跃榜|先明确业务需求和实际 member 设计，不把目标文章 ID 当榜单 member|
|排行榜多实例在线重建/Redis Cluster|**N/A**；只测单 JVM、静止重建、独立 Redis|跨实例写入与重建、跨日/月取消、乱序事件、脚本运行时错误、Cluster slot 测试|
|真实模型 TTFT/吞吐/成本/质量|**N/A，付费调用 0**|单独确认预算、Provider/模型/区域/版本/输入输出长度；先低并发兼容性测试|
|同一流中途备用模型降级|**未实现**|设计部分输出衔接和重复 token 处理，独立验收，不能引用 complete 的结果|
|STOMP 百并发完全可靠|**失败**；96/100，4 个队列拒绝|验证背压、限流、错误提示与安全重试；不能只扩大队列再声称彻底解决|
|浏览器自动重连、跨节点回放、长流/TTL边界|**N/A**|真实认证浏览器、broker relay、多实例、超过 200 事件与 900 秒 TTL、Redis 故障|
|线上 SLA/长期稳定容量/资源优化|**N/A**；都是本机短测或有限故障样本|独占/可观测环境，多轮长测，置信区间，故障持续时间与恢复分母定义|

## 本次汇总阶段记录

- **实际新增文件：**本报告 `benchmarks/resume-metrics.md`、离线核算脚本 `benchmarks/scripts/resume-metrics-audit.mjs`、派生清单 `benchmarks/results/resume-metrics-audit.json`。历史业务代码与原始测试结果未改。
- **实际执行：**读取源码/报告/原始数据；`node benchmarks/scripts/resume-metrics-audit.mjs`。没有启动容器、数据库写入、付费 API 或新一轮压测。
- **通过/失败：**60 项选定主结果核算通过；4 份历史 JSON 解析失败如实保留。业务失败按 B/C 列示，未修改断言或把失败轮次计入成功。
- **测得/计算的数据：**本次只重算已有数据的百分位、吞吐、比例与 27 组对照，不产生新的性能测量；完整精度见派生 JSON。
- **清理/恢复：**本次只写上述文档和派生产物，无外部状态需要恢复。既有 ES 独立索引、Outbox/排行榜/对话专用容器已有清理记录；文章 1 万篇仍按原阶段约定保留，100 篇冒烟保留墓碑及审计，不虚称全量清理。
- **下一阶段建议：**先补齐已暴露的业务故障验收，再开展相同功能的旧版/新版稳态对照；真实模型压测必须另行确认。
