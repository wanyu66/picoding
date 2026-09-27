# 可控模型流故障验证

入口：`run-model-stream-fault.ps1`。使用 Java 8；不启动整站、不读取生产模型密钥，不执行 `mvn clean`。

```powershell
./scripts/load-test/run-model-stream-fault.ps1
# 已编译且类路径文件存在时：
./scripts/load-test/run-model-stream-fault.ps1 -SkipBuild
```

脚本复用 `paicoding-outbox-rank-fault` 隔离项目的 MySQL（13316）和 Redis（16389），结束后停止这两个容器。MySQL 仅创建/追加专用表 `outbox_rank_fault.model_stream_fault_history`，按每次运行 UUID 隔离；Redis 使用随机会话及用户 ID 和生产代码的过期机制。不清空数据库。不要和 Outbox 测试同时运行。

Java 主程序：`paicoding-web/src/test/java/com/github/paicoding/forum/test/chat/ModelStreamFaultHarness.java`。
编译命令使用 Maven，验证本身是 opt-in Java 主程序，不是 JUnit 绿灯。任何 FAIL/ERROR 返回非零，且继续收集其余场景。

模拟服务绑定随机 loopback 端口，使用真实 HTTP/SSE 连接。固定 6 个片段 `chunk01|` 至 `chunk06|`，每片段前等待 80 ms。故障位置为第 3 个片段之后：

- `eof`：关闭响应，不发送 `[DONE]`。
- `error`：发送非法 JSON SSE 帧。
- `stall`：不再输出 token，以 SSE 注释保持传输；检验会话的 1800 ms 总超时。
- `cancel`：同样停止 token，服务器定期写 SSE 注释，以写失败确认客户端确实关闭上游连接。
- `duplicate_done`：重复发送 `[DONE]`，查 SQL 写入次数。
- 单独验证取消先发生、取消 hook 后注册的确定性竞态。
- 路由使用两个测试 HTTP provider 适配器接真实 `LLMRouter`，各请求 700 ms 后响应、单次超时 150 ms、每 provider 重试 1 次。分别验证 350 ms 总预算（允许 100 ms 调度/记录开销）及跨主备模型最多 3 次尝试。
- 独立向业务模板并发投递 END，随后再投递 ERROR，断言只落库一次；执行 100 次并发取消/注册及 2 个确定性边界，断言 hook 不丢失、不重复，状态释放。
- 验证主模型失败后备用模型能成功，并且每次调用的模型参数互相隔离，不改变调用者请求。

链路边界：使用真实 DeepSeek HTTP 集成、解析器、`AbsChatService.asyncChat`、会话服务、`WsAnswerHelper`、Redis、MySQL。Facade 选择、敏感词服务、历史查询和遥测使用测试替身。`recordChatItem` 替换为写入隔离 MySQL 表的 SQL sink，因此能验证业务模板是否重复触发持久化，但不能替代正式历史 DAO/schema 的端到端验收。未接入登录鉴权或真实 STOMP 连接。

浏览器测试需要已安装 Playwright 与 Microsoft Edge，设置 `NODE_PATH` 指向其依赖目录后执行：

```powershell
node scripts/load-test/test-model-stream-ui.cjs
```

它读取上一轮 `replay-events.json`，执行从当前生产 HTML 模板提取的订阅、渲染、恢复和停止按钮处理代码。以第 3 个片段后的 `seq=4` 为游标，验证补齐、重复及旧事件回放后 DOM 仍只有一个答案，并实际点击按钮验证 cancel 命令。STOMP 传输使用命令捕获替身；服务端取消及上游关闭由 Java 用例独立验证。不能把两者描述成已完成整站断网重连测试。

结果目录：`paicoding-web/target/model-stream-fault/`；运行日志：`.runtime/model-stream-fault.log`。`firstTokenMs` 是 helper 发起请求到收到首个 delta，`terminalMs` 到收到终态，包含本地应用/网络耗时；首场景包含 JVM 冷启动影响。`cancelToDisconnectMs` 到模拟服务写入失败，不是页面按钮复位耗时。模拟延迟不能解释为真实模型延迟或性能分位数。

## 重试预算配置

`LLMRouteProperties` 支持全局及逐场景覆盖：

```yaml
llm:
  timeout-ms: 10000
  max-retries: 1
  total-timeout-ms: 40000
  max-total-attempts: 4
  routes:
    CHAT:
      total-timeout-ms: 350
      max-total-attempts: 3
```

`max-retries` 仍是每个 provider 的重试次数；`max-total-attempts` 包括首次调用，跨主备模型共享。`total-timeout-ms` 是单次路由调用的共享预算，最后一次尝试只分配剩余时间；provider 返回内容的校验也计入预算。预算必须为正。静态模板不发起额外模型调用，有 validator 时其校验同样受剩余预算约束；预算耗尽后不再执行未经校验的模板。

底层使用最多 32 个线程的有界执行器，饱和时拒绝提交，不会在调用线程执行并绕过 deadline。超时会中断 worker，但不保证所有第三方 SDK 都响应中断或立即关闭物理网络连接；本测试已单独确认 DeepSeek 流式主动取消的 socket 关闭。记录/线程调度有少量开销，所以实际返回耗时不等于精确的硬实时截止点。

真实模型兼容性入口默认不运行。由调用者在进程环境中配置以下变量后，同一脚本最多额外发起 **1 次** 请求：

- `PAI_MODEL_TEST_BASE_URL`：兼容 OpenAI Chat Completions 的服务 base URL，不带 `/chat/completions`。
- `PAI_MODEL_TEST_MODEL`：模型名。
- `PAI_MODEL_TEST_API_KEY`：通过环境注入，不要提交到仓库。

固定短提示 `Reply with only OK.`，无工具调用和自动重试，读超时 30 秒、会话上限 40 秒。结果使用 `latencySource=real` 单独标记；缺失配置时记 BLOCKED，不能宣称真实兼容性通过。测试不会在结果 JSON 输出真实答案或密钥；隔离 SQL 表会保存该短答案。兼容性判据是流成功完成且非空、一次持久化，不以回答是否严格等于 OK 评价质量。
