# ES 测试操作

2026-09-27 异常处理回归可独立运行：`node benchmarks/scripts/run-es-fixes.mjs benchmarks/results/fixes-20260927/es-after-new`（必须新目录）。它使用Java8和缓存依赖，运行原4项搜索单测、新12项隔离回归及原6项网络故障验证；只访问临时loopback HTTP桩和mock DAO/JDBC。完整修复前后对照、失败注入限制与清理说明见 `benchmarks/results/fixes-20260927/es-fixes.md`。真实closed-port在本机返回connect timeout而非refused，严格断言仍FAIL，脚本退出码1；不得把它改写为全部通过。

在项目根目录执行。仅连接本机 ES；配置 `benchmarks/config/es-benchmark.json`，密码/API Key 不写入文件。默认 Java8=`.runtime/java8`，可设置 JAVA8_HOME。已有 Maven 测试依赖路径列表 `paicoding-web/target/model-test-classpath.txt`；其他机器先用 Java8 构建模块/生成依赖列表，并通过 PAI_BENCH_CLASSPATH_FILE 指定它。脚本不自动下载依赖、不启动业务应用。需要 IK 与 pinyin 插件，缺失时索引创建失败，不能自动降级成另一套 Mapping。

```powershell
node benchmarks/scripts/prepare-es.mjs
node --test benchmarks/scripts/es-benchmark.test.mjs

# 每次必须使用新目录；manifest 中保存 runId、集群UUID、索引和源码/配置哈希。
node benchmarks/scripts/es-benchmark.mjs init benchmarks/results/es/my-run
node benchmarks/scripts/es-benchmark.mjs load benchmarks/results/es/my-run 10000
node benchmarks/scripts/es-benchmark.mjs performance benchmarks/results/es/my-run 10000
node benchmarks/scripts/es-benchmark.mjs relevance benchmarks/results/es/my-run 10000
# 顺序执行，不能边造数/重建边做性能对照。
node benchmarks/scripts/es-benchmark.mjs load benchmarks/results/es/my-run 50000
node benchmarks/scripts/es-benchmark.mjs performance benchmarks/results/es/my-run 50000
node benchmarks/scripts/es-benchmark.mjs load benchmarks/results/es/my-run 100000
node benchmarks/scripts/es-benchmark.mjs performance benchmarks/results/es/my-run 100000
node benchmarks/scripts/es-benchmark.mjs relevance benchmarks/results/es/my-run 50000
node benchmarks/scripts/es-benchmark.mjs relevance benchmarks/results/es/my-run 100000
node benchmarks/scripts/es-benchmark.mjs alias benchmarks/results/es/my-run
node benchmarks/scripts/es-benchmark.mjs probe benchmarks/results/es/my-run 10000

# Java -D 参数在 PowerShell 中需要引号。失败退出码应保留，不是强制改成通过。
& .runtime/java8/bin/java.exe '-Dfile.encoding=UTF-8' '-Dlogback.configurationFile=benchmarks/config/es-logback.xml' -cp 'benchmarks/build/es;benchmarks/build/es/classpath.jar' SearchFaultHarness benchmarks/results/es/fault-new.json

node benchmarks/scripts/es-report.mjs benchmarks/results/es/my-run
# 删除前校验全部索引 _meta.benchmarkRun 与集群UUID，只删除清单中精确命名的测试索引。
node benchmarks/scripts/es-benchmark.mjs cleanup benchmarks/results/es/my-run
```

init/load/performance/relevance/alias 拒绝覆盖已有产物。性能中断后保留旧目录；可设置 `$env:PAI_BENCH_ATTEMPT='-retry1'` 再运行 performance，并用 `Remove-Item Env:PAI_BENCH_ATTEMPT` 恢复。清理不会删除报告、原有 alias、业务 ES 索引或 MySQL 文章；重复创建需换新 run 目录。中断重建可能留下 manifest 中 exact-name 的测试新索引；若未写入 owner meta，清理会拒绝，先核对 harness 日志及创建证据，不放宽检查。

初次 init 后也可用 `node benchmarks/scripts/run-es-suite.mjs benchmarks/results/es/my-run` 顺序编排全部规模；发现不完整尝试即停止，不能在其他测试仍运行时启动它。`ranking <run目录> <规模>` 专门补跑完整七查询循环的排序对照，本轮1万/5万的早期探索记录由此补齐，汇总时排除原来不等比例的混合记录。

本轮没有改动 MySQL。已有文章造数清理仍使用 article-import.md 的批次命令，不能混用 ES component 索引与应用 Outbox 的正式 alias。

## 人工评测

打开 `relevance-10000/review.html`，对每条查询池化候选打分并明确确认。热门/新发布查询要按意图检查元数据；评分前保留候选顺序盲测。导出真实人工 JSONL 后：

```powershell
# 复用项目既有 evaluate-search-ranking.js；本工具提供固定接口：
node benchmarks/scripts/es-report.mjs relevance benchmarks/results/es/my-run/relevance-10000 <人工导出的JSONL路径>
```

不把待评分 null 改成0，不把自动生成规则声称人工标签。脚本校验人审状态、审阅者、时间、语料快照和每个结果的评分后才出指标；只报告 judged Recall，7查询不足以推断真实社区相关性收益。

原始数据：raw-results.csv、各轮cells/per-query JSONL、nodes-stats JSONL、cursor-preparation 与一致性检查、原始A/B结果与候选、故障日志、重建任务结果。汇总报告由 es-report.mjs 生成；新增结果后重新生成，保留历史 run 目录。
