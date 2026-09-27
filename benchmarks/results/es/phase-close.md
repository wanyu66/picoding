# 本阶段交付与验收

仅修改 benchmarks 文件，没有修改业务逻辑、生产配置、接口和数据库结构。本轮没有向 MySQL 写入数据；在本机 ES 上创建独立所有权标记的测试索引，完成后删除了本轮5个索引。原业务 alias 和其文档数量在清理前后保持一致（10008），上一阶段的1万篇文章保留。

## 修改文件

- 文档：`benchmarks/README.md`、`.gitignore`、`es-test-plan.md`、`es-operations.md`。
- 配置：`config/es-benchmark.json`、`config/es-logback.xml`。
- Java 测试设施：`java/SearchContract.java`、`SearchFaultHarness.java`、`SearchRebuildHarness.java`。
- 脚本：`scripts/prepare-es.mjs`、`es-benchmark.mjs`、`es-benchmark.test.mjs`、`run-es-suite.mjs`、`es-report.mjs`、`check-es-results.mjs`、`cleanup-es.mjs`、`es-review-intents.mjs`、`refresh-es-review.mjs`、`es-inventory.mjs`。
- 报告与原始证据：`results/es/`。完整文件与哈希见 `artifact-inventory.json`；编译中间产物位于被忽略的 `benchmarks/build/es/`。

## 实际执行命令

在项目根目录使用 Node24.15.0 和 Java8 执行，关键命令如下；详细参数和新的运行目录示例见 ../../es-operations.md。

```powershell
node benchmarks/scripts/prepare-es.mjs
node --test --test-reporter=tap benchmarks/scripts/es-benchmark.test.mjs
node benchmarks/scripts/es-benchmark.mjs init benchmarks/results/es/run-01
node benchmarks/scripts/es-benchmark.mjs load benchmarks/results/es/run-01 10000
node benchmarks/scripts/es-benchmark.mjs performance benchmarks/results/es/run-01 10000
# 初轮停在高亮游标准备阶段；保留原目录，用 -retry1 重跑。
$env:PAI_BENCH_ATTEMPT='-retry1'
node benchmarks/scripts/es-benchmark.mjs performance benchmarks/results/es/run-01 10000
Remove-Item Env:PAI_BENCH_ATTEMPT
node benchmarks/scripts/es-benchmark.mjs relevance benchmarks/results/es/run-01 10000
node benchmarks/scripts/es-benchmark.mjs load benchmarks/results/es/run-01 50000
node benchmarks/scripts/es-benchmark.mjs performance benchmarks/results/es/run-01 50000
node benchmarks/scripts/run-es-suite.mjs benchmarks/results/es/run-01
# 编排依次完成100000的导入/性能、10000/50000排序补测、50000/100000相关性候选、真实重建方法测试。
& .runtime/java8/bin/java.exe '-Dfile.encoding=UTF-8' -cp 'benchmarks/build/es;benchmarks/build/es/classpath.jar' SearchFaultHarness benchmarks/results/es/fault-harness-02.json
node benchmarks/scripts/es-benchmark.mjs probe benchmarks/results/es/run-01 10000
node benchmarks/scripts/refresh-es-review.mjs benchmarks/results/es/run-01/relevance-10000
node benchmarks/scripts/es-report.mjs benchmarks/results/es/run-01
node benchmarks/scripts/check-es-results.mjs benchmarks/results/es/run-01
node benchmarks/scripts/cleanup-es.mjs benchmarks/results/es/run-01
```

这些是已执行命令的记录，不要直接在已完成的 run-01 上重新造数；新实验需用新目录。故障 harness 的非零退出码来自保留的失败断言，不应忽略。报告生成和结果完整性检查在清理后再次执行，属于对已保存原始证据的验证，不是对已删除索引重新测试。

## 通过、失败与测量

- 工具单测5/5通过；数据与报告完整性21/21通过。实际Java源码编译与DSL导出通过，编译版本与文件哈希见 build-provenance.json。
- 三规模分别核对10000/50000/100000个文档；15个分页位置的返回ID一致；正式混合单元中7个关键词次数严格相等。
- 54个实验单元（每规模18个）、每单元3轮，计划每轮预热2秒、测量5秒。正式样本16896条，实际测量总时长948.56秒，直连ES错误0；慢请求完整查询循环和排空会延长窗口。原始文件包含预热，汇总过滤measurement。
- 10万篇、单分片、4并发、返回10条、7查询等比例混合：综合策略126次请求，QPS6.76，P95=1861.56ms；基础全文2569次请求，QPS170.40，P95=42.58ms。策略召回不同，此结果表明成本差异，不证明相关性或业务接口优化收益。
- 故障断言3/6通过。客户端超时进入DAO降级；HTTP200的 timed_out/失败分片没有触发降级，为确认缺陷。关闭监听端口得到连接超时而非connection refused，严格故障类型断言失败，但实际触发DAO。DAO为受控替身，真实MySQL回源吞吐/HTTP故障成功率N/A。
- 实际Java重建方法成功构建10000文档并切换独立alias。全观察窗口304次查询零错误；严格任务窗口37.503秒内完成215次查询零错误，Top10和文档总数校验通过。数据扫描使用内存DAO，不能写成完整MySQL/Outbox故障切换通过。
- 中文原文拼音召回、正常英文词的拼写容错探针均通过；不作为人工相关性评分。用户明确选择相关性MRR/Recall/NDCG为N/A，三个规模均保留人工标注入口。

## 保留的失败和限制

首次游标准备返回500条高亮文档，准备成本过高；中断记录保留，改为仅取排序游标，正式查询没有删减高亮。1万/5万初轮混合负载因时间截断导致关键词比例不齐，原始记录保留并由完整七查询循环的补测替代；分页记录不受影响。

fault-harness.log 保留 PowerShell 参数引用错误；fault-harness-01 因未初始化Redis而在发网络请求前NPE，无效的表面PASS不计入结果。修正测试设施后的02确认实际故障注入，业务失败断言原样保留。最初直接使用classpath JAR进行javac编译失败，改用Java8参数文件后编译通过。

环境为共享本机Docker ES，heap512MiB、ES JVM20.0.2；测试客户端与ES同机。短窗口、合成重复语料和少量查询不足以推断线上容量/SLA；不报告“提升X%”。ES客户端30秒超时与应用默认1.5秒不同。应用仍处于MySQL标题降级状态；真实HTTP、Redis命中、MySQL和Outbox并发更新的端到端验证未完成。

## 下一阶段

先在另一个获准的业务修复阶段处理超时标志/失败分片与缺失游标行为，再基于相同基线复测；人工标注后计算相关性；最后用独立完整依赖环境运行长时间HTTP和真实MySQL回源故障实验。此次未修改业务判定逻辑以改善指标。
