# ES 异常处理修复验证（2026-09-27）

本阶段只修复真实异常处理缺陷；未改搜索权重、分页判定、排序或业务数据。保留全部历史证据及失败尝试。

## 实际修改

- `paicoding-service/.../search/impl/ArticleSearchServiceImpl.java`：`timed_out=true` 或 `_shards.failed>0` 时抛出 IOException，交给原有查询失败/DAO 回源路径，避免把部分结果缓存为成功。索引初始化失败、单文档同步失败、非404删除失败向事件消费者传播异常，避免静默提交消费标记。404幂等、禁用ES、null参数行为保留。未新增409特殊忽略规则。
- 新增 `benchmarks/java/es/SearchFailureRegressionTest.java`、`EsRegressionRunner.java` 与 `benchmarks/scripts/run-es-fixes.mjs`；使用项目缓存依赖和真实服务源码，运行原有4项Jupiter测试与12项隔离回归。
- 在 `benchmarks/es-operations.md` 补充复现入口。新增本报告及 `es-before-*`、`es-after-*` 原始JSON、日志、命令清单、源码快照与SHA-256。
- 原 `SearchFaultHarness.java`、原4项业务测试及历史 `benchmarks/results/es/` 没有改动。

## 可复现命令

在项目根目录执行；每次使用不存在的结果目录。默认强制 `.runtime/java8`，支持 `JAVA8_HOME`；不下载依赖，不使用 `mvn clean`。

```powershell
# 相同的当前测试验证保存的修复前源码；预期退出码1。
node benchmarks/scripts/run-es-fixes.mjs benchmarks/results/fixes-20260927/es-before-new benchmarks/results/fixes-20260927/es-before-03/sources/paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java

# 验证当前源码；本机真实closed-port注入不满足refused断言，仍返回退出码1，不能当成整体通过。
node benchmarks/scripts/run-es-fixes.mjs benchmarks/results/fixes-20260927/es-after-new
```

本次实际完整命令见 `es-before-04/manifest.json` 与 `es-after-02/manifest.json`，含每个 javac/java 参数、退出码与耗时。固定Jupiter引擎避免缓存classpath里的Spock监听器自动装载；没有修改原测试断言。

## 通过与失败

| 验证 | 修复前 es-before-04 | 修复后 es-after-02 |
|---|---:|---:|
| JUnit（原4项 + 新12项） | 9通过 / 7失败 / 0跳过 | 16通过 / 0失败 / 0跳过 |
| 原网络故障harness | 3通过 / 3失败 | 5通过 / 1失败 |
| timed_out DAO调用次数 | 0 | 1 |
| failed_shard DAO调用次数 | 0 | 1 |
| 独立mock ConnectException | 通过 | 通过 |
| 真实closed-port严格refused注入 | 失败（实际connect timeout） | 失败（实际connect timeout） |

JUnit原始数据：`es-before-04/junit-results.json`、`es-after-02/junit-results.json`；网络原始数据：同目录 `fault-results.json`，详细异常堆栈见 `junit.log` 和 `fault.log`。

消费边界测试实际调用 `ArticleSearchDomainEventConsumer` 和 `DomainEventConsumeService`，仅JDBC替身；它验证异常确实传到消费调用方，真实数据库事务回滚/Rabbit重投结果为 **N/A**。

## 实际环境、数据与耗时

- Windows_NT 10.0.26100 x64，Intel Core i7-14650HX；Corretto OpenJDK 1.8.0_492；Node版本和完整环境见manifest。
- 每个测试进程并发1；JUnit 16项，原网络harness 6场景，每场景3次搜索 + 1次读服务调用。解析测试使用1条模拟命中文档；DAO返回固定空列表；真实数据库、Redis、ES文档规模均为0。
- 修复前完整运行39,293ms，JUnit 8,013ms；修复后完整运行42,831ms，JUnit 8,859ms。此处是功能/故障验证耗时，**不作为性能改善结论**。修复后编译启动与前一进程收尾短暂重叠，两个实际测试阶段未并行。
- 修复后 socket timeout 3次搜索：2,415.12 / 2,017.42 / 2,029.82ms；服务配置1500ms，实际总耗时含客户端调度，不宣称接口1500ms完成。
- 修复后 ES `timed_out`：88.57 / 5.77 / 4.43ms；failed shard：32.03 / 6.42 / 7.97ms。两者4次请求均走失败路径，读服务各回源DAO1次。
- closed-port 实际异常 `Timeout connecting to [/127.0.0.1:62484]`；搜索2,063.23 / 2,024.57 / 2,024.62ms，DAO调用1次，但拒绝连接类型未注入成功，保留FAIL。

## 失败尝试保留说明

- `es-before-01`：缓存classpath缺少AMQP，编译失败。修复复用现有outbox脚本的缓存BOM解析，无联网下载。
- `es-before-02`：JUnit自动发现Spock监听器导致agent装载失败；网络harness正常留下原始故障数据。
- `es-before-03`、`es-after-01`：新增消费边界测试的Mockito varargs桩未匹配，消费提前返回。这两个用例不能作为有效消费传播结论；其余用例原始结果仍保留。
- 修正桩后，使用保存的原业务源码重跑 `es-before-04`，与 `es-after-02` 使用相同测试与同一原网络harness；不删除先前失败，也未降低断言。

## 清理、剩余问题与下一阶段

HTTP桩仅绑定127.0.0.1随机端口；每项测试在finally/try-with-resources关闭HTTP服务、执行器和客户端；Mockito注册只存在于退出的独立JVM。没有创建数据库、正式ES索引或Redis键，无外部恢复操作。编译产物仅在各轮 `build/`，日志、JSON和源码快照留存；重复执行更换结果目录即可。

剩余1项网络注入失败属于本机closed-port表现，不等价于DAO降级失败，也不能用mock结果替代真实拒连证据。真实MySQL回源数据正确性/吞吐、真实拒连、真实事件事务回滚与重投均为N/A。`activeRebuildTarget()`原有重建目标探测吞错路径未覆盖，本阶段未调整重建行为。

下一阶段建议在隔离网络环境稳定注入真实TCP拒绝连接，并在隔离Outbox事务设施中补测真实ES写失败的消费回滚/重投及重建期间双写故障；保留现有排序/性能基线，不将本轮功能通过推导为吞吐收益。
