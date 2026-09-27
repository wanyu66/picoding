# 环境、依赖与复现

## 第一阶段实际环境

2026-09-24 本地审计，时区 Asia/Shanghai；仓库在 Windows PowerShell 环境。以下来自本次版本命令与Node os API，而非仓库旧文档：

| 项目 | 实际观察 |
|---|---|
| OS | win32 x64，内核10.0.26100；具体Windows发行版N/A（CIM拒绝访问） |
| CPU | Intel Core i7-14650HX，24逻辑CPU；物理核数N/A |
| 物理内存 | 16,944,848,896 bytes；这是主机内存，不是容器限额 |
| 默认Java | Oracle 25.0.2，禁止直接用于项目构建 |
| 可用Java 8 | `.runtime/java8/bin/java.exe`：Corretto 1.8.0_492-b09 |
| Maven | 命令定位到apache-maven-3.9.16目录；本阶段未执行mvn -version核实运行版本 |
| Node | v24.15.0 |
| Python | PATH中未找到python；不等于全机没有Python |
| Docker CLI / Compose | 29.8.0 / v5.5.1；读取用户Docker配置提示Access denied，daemon和容器可用性未验证 |
| 数据库/Redis/RabbitMQ/ES服务 | 未连接，运行版本、数据量、健康、资源限额均N/A |
| 性能实验 | 未启动；实际并发/持续时间/数据规模=N/A |

AGENTS.md 的Homebrew/macOS命令不适用于当前主机；其“必须Java8、不mvn clean”约束仍遵守。CIM权限失败如实记录；无须为本阶段审计扩大权限。

## 配置与依赖

- 根pom：Boot2.7.1，编译source/target1.8；ES客户端7.17.4。五模块pom已检查；web含JUnit4、Groovy3/Spock2、JMH1.35，core/service含starter-test，service含H2测试依赖。
- `paicoding-web/src/main/resources/application.yml` 为主配置，`resources-env/<env>/application-dal.yml`、`application-rabbitmq.yml`、`application-ai.yml` 为数据源/中间件/AI配置；实际配置受Maven profile、启动参数及动态配置影响，不能按静态文件确定最终连接目标。
- 不导出 `.env` 或完整环境变量，不复制任何密码/Token/API Key。正式运行只保留必要白名单参数和配置hash。
- `scripts/load-test/outbox-rank-compose.yml` 声明 mysql:8.0、rabbitmq:4-management、redis:8.0；端口127.0.0.1:13316、15682、16389。标签非固定digest；正式实验记录实际镜像digest与版本、CPU/内存限额和持久化策略。
- 现有故障脚本固定compose project `paicoding-outbox-rank-fault`，模型测试也复用其中MySQL/Redis；不可同时运行。脚本finally执行stop，**不会自动清空所有数据/匿名卷**。
- 根 `docker-compose.canal-kibana.yml` 包含Canal1.1.7、adapter和Kibana8.10.0-1，连接host上的DB/ES，初始化服务可创建索引。它不是隔离压测全套依赖，不在本阶段启动，也不把Canal链路等同于Outbox链路。
- `start-elasticsearch.sh` 指向本地ES安装（默认macOS路径），不是Docker一键ES部署；IK/分词器和服务端兼容性需要隔离环境核验。
- 本阶段未安装依赖。Python+pymysql、Playwright/Edge及完整隔离ES环境是否就绪均未验证。

## 安全可执行命令（本阶段）

从项目根运行，只有审计脚本会在benchmarks/results新增文件：

```powershell
git status --short
git rev-parse HEAD
& .runtime/java8/bin/java.exe -version
node --version
node benchmarks/scripts/audit.mjs
```

## 下一阶段命令（本阶段未执行）

先在当前PowerShell会话显式Java8。结束恢复进程环境；以下不含mvn clean：

```powershell
$benchOldJavaHome = $env:JAVA_HOME
$benchOldPath = $env:PATH
try {
    $env:JAVA_HOME = (Resolve-Path .runtime/java8).Path
    $env:PATH = "$env:JAVA_HOME\bin;$benchOldPath"
    java -version
    mvn -version
    mvn -pl paicoding-service -am '-Dtest=ArticleSearchServiceImplTest,AbsChatServiceAsyncCallbackTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
} finally {
    $env:JAVA_HOME = $benchOldJavaHome
    $env:PATH = $benchOldPath
}
```

此为已有离线测试的选择性复现建议，需先核验选定测试没有新增外部调用；没有执行结果前不承诺通过。禁止直接全量mvn test，因为BasicTest及AI/数据库集成测试可能使用真实配置。

纯离线相关性工具回归（合成单测数据，不是人工评分证据）：

```powershell
node scripts/search-evaluation/test-search-ranking.js
```

隔离资源归属和清理机制验证完成后，已有故障入口：

```powershell
powershell -NoProfile -File scripts/load-test/run-outbox-rank-fault.ps1
# 模型脚本运行前仅检查下列变量是否存在，不打印值；存在即阻止模拟运行，避免隐式付费。
if ($env:PAI_MODEL_TEST_API_KEY -or $env:PAI_MODEL_TEST_BASE_URL -or $env:PAI_MODEL_TEST_MODEL) {
    throw '模拟实验请使用未注入真实模型配置的独立会话'
}
powershell -NoProfile -File scripts/load-test/run-model-stream-fault.ps1
```

上述脚本写入既有target/.runtime位置，下一阶段benchmarks wrapper应将原始结果、日志、退出码及时复制到唯一run目录；先归档再下一轮，避免覆盖。真实模型请求需单独确认，即使只有一次也不自动授权。

隔离应用已启动并完成合成数据恢复后才能运行HTTP性能测试：

```powershell
$env:BENCH_URL = 'http://127.0.0.1:8080'
$env:BENCH_OUTPUT = Join-Path (Get-Location) ('benchmarks/results/http-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
$env:BENCH_CONCURRENCY = '1,8,32'
$env:BENCH_WARMUP_SECONDS = '30'
$env:BENCH_SECONDS = '60'
$env:BENCH_ROUNDS = '3'
node scripts/load-test/http-benchmark.js
if ($LASTEXITCODE -ne 0) { throw '压测失败，保留原始结果' }
node scripts/load-test/summarize.js $env:BENCH_OUTPUT
```

该脚本自带固定关键词/首页文章，不等于S1的100查询受控数据设计；后续需benchmarks适配器。脚本允许已存在输出目录，必须由wrapper额外拒绝重复run目录。在独立测试shell使用上述变量，退出shell恢复环境。

S3真实同步、完整业务M1、R1规模和W1真实STOMP新驱动尚待实现，本阶段不提供假装存在的执行命令。完成驱动与只清理本次资源的dry-run后才启动这些实验。

## 清理与恢复设计

1. 初始化前保存run资源清单（compose项目、容器ID/卷、端口、专属DB、vhost、Redis实例、ES索引/别名、模拟进程PID），核验不含原有业务资源；无法核验就不执行破坏性测试。
2. 合成数据使用固定seed与run_id、保存生成参数及ID manifest。旧generate_articles.py没有完整恢复流程，先补benchmarks工具，不能直接给业务库批量灌数据。
3. 正常与故障结束都执行finally：恢复本次暂停依赖/网络，停本次客户端/模拟服务，归档原始证据，记录恢复健康与计数核对结果。重跑从同一专属数据快照开始，不复用污染计数。
4. OutboxRankFaultTest会DROP测试表、FLUSHDB及删队列。仅在一次性专属实例允许；原有同名compose环境存在时先判定归属，不能直接运行。归档后再删除本次创建的容器/卷；禁止无范围down -v、FLUSHALL、共享DB清理。
5. W1清理会话/连接、S3恢复本次别名、R2校验重建日/月榜、AI停止本次上游模拟进程。共享依赖一律不通过删业务数据恢复。
6. 第一阶段只生成文档/证据，无业务资源需要恢复。审计重复执行自动新建目录；需要撤销时仅删除已核对的本次benchmarks新增文件，保留用户其他修改。
