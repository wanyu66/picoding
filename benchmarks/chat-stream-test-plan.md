# 多模型与 STOMP 流式对话验证

仅新增 benchmarks 文件；不修改业务代码、判定或生产配置，不使用业务数据库。真实模型测试 N/A，默认禁止，当前 harness 不提供真实模型 URL 参数；若要测真实模型必须另行明确配置并确认付费范围和低并发上限。

## 已定位的实际实现

|能力|源码|行为与边界|
|---|---|---|
|Provider 路由|service/llm/LLMRouter.java、LLMRouteProperties.java|complete 按 primary/fallback 顺序执行；单次超时、总超时和总尝试次数共享预算；模板兜底；每 Provider 熔断；最多 32 个同步调用 worker|
|流式 Provider 选择|service/chatai/ChatFacade.java、service/ChatServiceFactory.java|先 routeChatSource，再调用相应 ChatService；不同于 complete，当前流失败后不会在同一请求中调用备用 Provider|
|流式协议|DeepSeekIntegration.java、DeepSeekChatServiceImpl.java、AbsChatService.java|实际 OkHttp SSE、[DONE] 结束；EOF 不视为成功；终态回调去重；流式成功/失败回传路由熔断统计|
|STOMP|WsChatConfig.java、ChatRestController.java、WsAnswerHelper.java|/app/chat/me/{chatId} → 用户 principal → /user/chat/rsp；默认 simple broker；入/出站线程 core=4、max=10、queue=256；测试继承这些配置|
|停止生成|ChatGenerationControl.java、ChatStreamSessionService.cancel、WsAnswerHelper.cancel|cancel hook 关闭上游 SSE；Redis 取消标志＋本机状态；缓存取消终态外还发送一个 seq=9007199254740991 的确认，客户端可能收到两个取消终态|
|回放|ChatStreamSessionService.java|Redis 保存带 generationId/seq 的快照，默认最多 200 个事件、TTL 900 秒；owner/session 校验；lastSeq 之后回放，不重新生成|
|重连/去重|paicoding-ui/.../chat-home/index.html|1 秒起指数退避至 30 秒；sessionStorage 保存游标；同 generationId 且 seq 不高于游标则忽略；输出为累计快照|
|超时|ChatStreamSessionService.timeout|默认流式总超时 120 秒，触发 timeout 终态并取消上游；测试正常场景设 15 秒，超时场景设 1.2 秒以缩短可控故障周期|

service 路径前缀为 `paicoding-service/src/main/java/com/github/paicoding/forum/`；Web 类位于 `paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/`。

## 隔离与测试设施

- 独立 Docker Compose Redis，固定 loopback 端口 16419，项目名 bench-chat-时间戳；不用 MySQL，不触碰任何既有数据。
- 本机嵌入式 Tomcat 随机端口，真实 Spring STOMP controller、broker、in/out 拦截器、WsAnswerHelper、Redis replay、ChatFacade/Factory、AbsChatService、SSE parser 和 LLMRouter 源码均重新以 Java 8 编译。
- 仅握手登录身份、敏感词、历史查询/存储、业务次数计数为合成夹具；User usage guard 本身仍使用真实 Redis 实现。测试每分钟/每天配额为 1000/10000，每用户并发仍默认 2。熔断阈值设 1（同步路由用例为 2），用于明确观察后续请求选路。
- SSE Provider 固定 6 个分片，每片等待 60ms，答案精确为 `chunk01|chunk02|chunk03|chunk04|chunk05|chunk06|`。通过真实 loopback HTTP 输出标准 SSE，不调用任何 SDK 远端服务。备用 source 使用同一 SSE 解析适配器，不代表已验证智谱真实 SDK。
- OkHttp fixture 调度上限 256/host=256，用于给协议层提供可控并发上游；**不同于生产 OkHttp 默认队列行为**，不能把本结果直接当线上容量。正常 1/10/50/100 会话，每个用户发一次；故障场景每项 10 会话。
- Provider URL 固定 127.0.0.1，HTTP 拦截器拒绝外部 host；不启动应用主程序、不载入应用 YAML/真实 Provider bean，runner 不把常见模型凭据环境变量传给 JVM。

## 场景和独立预期

1. 正常生成：8 条协议事件（started＋6 delta＋completed），固定完整答案，每会话 1 次模型调用。
2. 多用户并发：同上，独立用户/会话；同时开始生成，记录真实拒绝而不重试掩盖。
3. 主动停止：第三个 delta 后发送 cancel，保留前三片，观察 cancelled 终态、上游 socket 断开，不再调用模型。
4. 断开与重连回放：第二片后真正关闭 WebSocket；间隔 500ms 后建立新 WebSocket、CONNECT、SUBSCRIBE，再以原 generationId/lastSeq 请求回放。生成在断网期间继续；期望 8 个唯一事件齐全，最终答案精确匹配，调用次数仍为 1。
5. 重复投递：完整生成后从 seq=0 重复回放两遍，每会话收到额外 16 个事件但显示不应增加；另发送两次上游 [DONE] 验证终态与历史落库回调不重复。
6. 超时：第三片后只发送 SSE 注释保活，不发 token 或 DONE；期待 1.2 秒流式总超时、timeout 终态和上游取消，不误记成功。
7. 服务不可用：primary 返回 503，当前流应 error；熔断后下一次 generation 由备用 source 完成。同一次流中途重试/降级 N/A。
8. 同步路由另外 40 次测试：timeout/unavailable/template/circuit 每项 10 次；调用预算 per-attempt=80ms、total=350ms、maxAttempts=3、maxRetries=1；独立期望 primary 2 次，backup 1 次；circuit 连续 3 个请求 primary 总共 2 次、backup 3 次；模板结果精确为 MOCK_TEMPLATE。

模型内容预期来自固定夹具，消息数预期来自协议，不从 Redis 或客户端实际答案推导正确结果。客户端按生产高水位序号规则统计去重，另用 Edge/Playwright 执行实际网页模板的 subscriber、渲染、resume 和 stop 处理器，向 DOM 投递网络原始快照验证 10 个重复回放会话没有重复答案元素。UI 测试采用合成 STOMP adapter，真实网络测试采用 Node 客户端；浏览器自动重连定时器与登录链路 N/A。

## 指标口径

- TTFT：客户端发送 generate 到收到第一个非空 delta；不含建连与订阅。未产生首字为 null/N/A，绝不写 0。总生成耗时：发送到第一终态，失败无终态为 null；P95/P99 nearest-rank，同时报告有效样本数和失败数。
- 并发：已建立连接后同时发请求，peakClientInFlight 为等待会话数；Provider peak 从 server 日志区间独立计算，二者不同。
- 消息缺失：期望 seq（正常 1..8，取消/超时 1..5）未到达数，**包含未获服务的请求，不全是传输丢包**。报告另区分 Provider 已执行但未送达的事件与根本未生成的事件。
- 重复接受：按 generationId/seq 在客户端去重后仍重复的事件数；重复展示：实际 DOM 中多出的答案元素数，独立于前者。两个取消终态会单独记录，不伪装成只发一个终态。
- 取消响应：发送 cancel 到接收首个终态；另验证 Provider 对端确实检测断开，不能推断客户端 UI 响应就等于模型服务器立刻停止计算。
- 恢复成功：完整独立预期、无消息缺失、模型仅调用一次；恢复延迟从重建连接前到收到终态（另保留总断线时长）。
- 同步路由耗时单独报告，不作为流式 TTFT。所有延迟属于 Mock 协议链路，真实模型 TTFT/吞吐/成本均为 N/A。

## 运行与清理

从仓库根目录运行，复跑选新目录：

```powershell
node benchmarks/scripts/prepare-chat-stream.mjs
node benchmarks/scripts/run-chat-stream.mjs benchmarks/results/chat-stream/run-04
node benchmarks/scripts/chat-stream-ui.cjs benchmarks/results/chat-stream/run-04
node benchmarks/scripts/chat-stream-report.mjs benchmarks/results/chat-stream/run-04
node benchmarks/scripts/check-chat-stream.mjs benchmarks/results/chat-stream/run-04
```

依赖 `.runtime/java8`（可用 JAVA8_HOME 指定），现有 reactor target/classes、paicoding-web/target/model-test-classpath.txt、Docker redis:8.0。本地 Edge、Playwright 用于 UI 检查；可用 BENCH_NODE_MODULES 指向其 node_modules。无需 mvn clean。

runner 在 finally 通过 stdin 停止本次测试 JVM，再执行该次唯一 Compose 项目的 down --volumes。进程被强杀时，从 manifest.json 读取 run 并设置 BENCH_RUN，再执行 `docker compose -p $env:BENCH_RUN -f benchmarks/config/chat-stream-compose.yml down --volumes`；仅能使用该 manifest 中的测试项目名。所有 Redis 数据都属于新建测试容器，不需要恢复业务数据。Java 临时 Tomcat 文件在本次结果目录内保留。不得使用全局 docker prune/flushall。

测试不通过保持退出码 2；启动/设施故障为 1。报告和证据校验脚本只验证证据完整性，不会把业务失败改成通过。保留失败轮次、原始 STOMP JSONL、每会话 JSON、模型调用记录、CSV、源码哈希和清理状态。

## 未覆盖与下一阶段

真实账号鉴权/续期、生产历史数据库、外部 Rabbit broker relay、多节点跨机回放、TTL 过期/200 事件截断、慢消费者与长文本、上游乱序重复 token、同 requestId 重发的业务幂等性、半开熔断恢复、真实模型输出质量与性能 N/A。后续优先用相同 Mock 参数验证突发背压和断线恢复边界，再讨论有明确预算的单并发真实 Provider 兼容性验证。
