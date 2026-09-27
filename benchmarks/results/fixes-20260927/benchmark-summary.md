# 测试问题修复与复核（2026-09-27）

已完成可验证的异常处理、并发状态更新、聊天背压及测试夹具修复；**仍有失败项，不能宣布全部业务通过**。本轮未修改积分值、搜索权重、通过阈值或原矩阵断言，没有操作既有业务数据库，没有调用付费模型。所有既有503份结果文件SHA-256复核一致。

机器可读汇总：[JSON](verification-summary.json)、[CSV](verification-summary.csv)；历史文件保护：[JSON](historical-evidence-check.json)、[CSV](historical-evidence-check.csv)。不同测试层次不合并为一个“业务成功率”。

## 阶段结果

|阶段|修复与原因|同口径基线 → 本次结果|实际规模/时长|剩余与下一步|
|---|---|---|---|---|
|文章/ES离线工具|复核先前已修复的造数、类型比较、基线DSL与统计工具|文章11/11、ES5/5|生成边界覆盖1万/5万/10万；离线，无在线导入；TAP保留|1万篇首次30分钟同步超时仍是历史FAIL；连续同步耗时/在线性能重测N/A|
|ES异常处理|拒绝HTTP200中的超时和失败分片；索引初始化/写删错误传给消费者|JUnit 9/16 → **16/16**；原网络故障3/6 → **5/6**|并发1，16项JUnit、6个故障场景；全轮39.293/42.831秒|真实closed-port注入仍FAIL；本机得到connect timeout，实际DAO回源1次。下一步在隔离网络补真实拒连与消费事务重投|
|排行榜夹具|原夹具清空后复用相同eventId，导致唯一键删除残留上的gap锁死锁；按场景隔离ID|40项30通过 → **32通过/8失败**；本次基线死锁25 → **0**|10/50/100/200并发，共14,760次调用；247.759/239.145秒|4个不同ID重复事件仍多计、4个非原子对照仍失败；保留断言。下一步验证完整生产入口到积分投影|
|点赞/收藏入口|无锁读改写会并发生成重复事件；唯一行初始化及锁定读取在原事务中保护状态变化|**1/7 → 7/7**；384次调用353成功 → **384成功**|32线程，每组64次，共6组+回滚；每组实际时长见CSV|HTTP/Rabbit积分整链路及旧阅读/评论路径混合并发N/A；下一步覆盖混合写入|
|聊天入站背压|默认拒绝策略在队列满时中断连接；保留4/10线程、256队列，饱和时由发送线程执行|固定饱和回归FAIL → PASS；流式207/211 → **211/211**，100并发96/100 → **100/100**|1/10/50/100并发，211正式会话+1预热，最终启动至清理31.317秒|40/40同步路由、UI10/10通过；长期过载、多节点与真实模型N/A。下一步验证慢依赖和持续过载|

点赞入口基线64次重复点赞产生32个事件；修复后产生1个。首次并发插入的31次唯一键冲突降至0；取消、再次点赞、收藏、取消收藏以及回滚保持原状态码与事件契约。这里验证实际Service/MyBatis/Outbox SQL，不冒充HTTP全链路。

聊天缺失期望事件32 → 0；100并发TTFT P95为257.38 → 422.97ms，总耗时P95为576.11 → 787.00ms。旧轮仅成功/可观测样本进入延迟统计，且各轮为短批量，**不声明性能提升**。排行榜死锁修复发生在测试夹具，不能归为生产服务优化。

## 实际修改的文件

业务文件仅5个，修改前已说明原因：

- `paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java`
- `paicoding-service/src/main/java/com/github/paicoding/forum/service/user/repository/mapper/UserFootMapper.java`
- `paicoding-service/src/main/java/com/github/paicoding/forum/service/user/repository/dao/UserFootDao.java`
- `paicoding-service/src/main/java/com/github/paicoding/forum/service/user/service/userfoot/UserFootServiceImpl.java`
- `paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/stomp/WsChatConfig.java`

测试设施均位于 benchmarks：

- ES：`java/es/SearchFailureRegressionTest.java`、`java/es/EsRegressionRunner.java`、`scripts/run-es-fixes.mjs`、`es-operations.md`。
- 排行榜：`java/ranking/RankingHarness.java`、`scripts/prepare-ranking.mjs`、`scripts/run-ranking.mjs`、`scripts/ranking-report.mjs`、`scripts/compare-ranking.mjs`、`ranking-test-plan.md`。
- 点赞：`java/userfoot/UserFootConcurrencyHarness.java`、`config/userfoot-compose.yml`、`scripts/run-userfoot.mjs`、`scripts/compare-userfoot.mjs`、`userfoot-fix.md`。
- 聊天：`java/chat/ChatInboundBackpressureCheck.java`、`java/chat/ChatStreamBenchServer.java`、`scripts/run-chat-backpressure.mjs`、`scripts/chat-stream-report.mjs`、`scripts/compare-chat-fix.mjs`。
- 审计与交付：`scripts/check-fix-evidence.mjs`、`scripts/summarize-fixes.mjs`、`.gitignore`、`README.md` 和本次 `results/fixes-20260927/` 中的原始结果/报告。已有未跟踪benchmark文件保留并按需增量修改，不重置工作区。

新发现并修复的设施错误也保留：ES缓存classpath缺AMQP、Jupiter运行时误加载Spock监听器、Mockito varargs桩未匹配；聊天ready JSON未完整写入就被读取。有效前后对照使用相同最终断言；中间尝试不被改写为通过。

## 实际测试命令与复跑

以下命令从仓库根目录执行；有结果输出的目录必须不存在，复跑更换末尾名称。Java测试强制本地 `.runtime/java8`，不运行Maven clean。完整基线/修复命令、参数与返回码见各阶段报告和manifest。

```powershell
node benchmarks/scripts/check-articles.mjs benchmarks/results/fixes-20260927/article-selftests
node --test --test-reporter=tap benchmarks/scripts/es-benchmark.test.mjs
node benchmarks/scripts/run-es-fixes.mjs benchmarks/results/fixes-20260927/es-after-02
node benchmarks/scripts/prepare-ranking.mjs
node benchmarks/scripts/run-ranking.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01
node benchmarks/scripts/check-ranking.mjs benchmarks/results/fixes-20260927/ranking-fixture-fix-01
node benchmarks/scripts/compare-ranking.mjs benchmarks/results/fixes-20260927/ranking-baseline-01 benchmarks/results/fixes-20260927/ranking-fixture-fix-01
node benchmarks/scripts/run-userfoot.mjs benchmarks/results/fixes-20260927/userfoot-fixed-01 current
node benchmarks/scripts/compare-userfoot.mjs benchmarks/results/fixes-20260927/userfoot-baseline-01 benchmarks/results/fixes-20260927/userfoot-fixed-01 benchmarks/results/fixes-20260927/userfoot-comparison
node benchmarks/scripts/run-chat-backpressure.mjs benchmarks/results/fixes-20260927/chat-backpressure-fixed-02
node benchmarks/scripts/prepare-chat-stream.mjs
node benchmarks/scripts/run-chat-stream.mjs benchmarks/results/fixes-20260927/chat-stream-fixed-03
node benchmarks/scripts/chat-stream-ui.cjs benchmarks/results/fixes-20260927/chat-stream-fixed-03
node benchmarks/scripts/check-chat-stream.mjs benchmarks/results/fixes-20260927/chat-stream-fixed-03
node benchmarks/scripts/check-fix-evidence.mjs verify benchmarks/results/fixes-20260927
node benchmarks/scripts/summarize-fixes.mjs benchmarks/results/fixes-20260927
```

ES整体命令退出1（仍有网络注入失败），排行榜整体退出2（仍有原矩阵失败）；不把证据核对脚本返回0当业务全通过。文章离线、UserFoot修复、聊天最终及比较核对通过。额外执行了脚本语法检查及 `git diff --check`，均通过。

详细阶段记录：[ES](es-fixes.md)、[排行榜](ranking-repair.md)、[点赞/收藏](../../userfoot-fix.md)、[聊天](chat-comparison/report.md)。它们各自列出修改文件、命令、通过/失败、实测数据、剩余问题与下一阶段建议。

## 环境、清理与恢复

实际环境：Windows 10.0.26100 x64，i7-14650HX，24逻辑CPU，16,944,848,896字节内存，Node24.15.0，Java Corretto8u492。MySQL/Redis使用已有Docker镜像；实际版本、镜像ID、连接池、哈希和每轮耗时记录于对应manifest/server/版本文件。不同实验可能同时运行，不合并时长、不据此宣称性能收益。

排行榜/点赞/聊天使用不同端口及随机Compose项目，finally清理仅本次容器和卷；选定基线及最终轮的cleanupExitCode全部为0。ES测试只用回环HTTP桩与内存替身，退出时关闭服务和线程。业务已有MySQL、Redis、ES、RabbitMQ资源未改动；上阶段保留的1万篇文章未删除。源码恢复可按这5个业务文件的差异逐项撤回；本次没有执行任何回退操作或提交。

503份原始历史证据已逐文件核对，没有缺失或改写；原始JSON/CSV/TAP、失败日志、源码哈希和可比基线都保留。真实模型请求0，真实模型性能N/A；自动恢复、长期稳定性、业务整体性能提升仍不作超出证据的声明。
