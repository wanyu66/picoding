# 模型流五项修复及回归（2026-09-24）

五项已修复。Java 8 Maven 编译成功；独立故障验证主程序 14 个场景全部 PASS，退出 0；Microsoft Edge / Playwright 回放和停止按钮验证 PASS。原始失败结果保留在 [修复前报告](2026-09-24-model-stream-fault.md)，未覆盖。

| 修复 | 最终回归证据 |
|---|---|
| DeepSeek 只有明确完成标记才成功；EOF 保留部分回答并报错 | 第 3 片段 EOF → `error / UPSTREAM_INCOMPLETE`，SQL 0 行 |
| `ChatRecordsVo` 携带 errorCode/errorMessage，clone 保留；WsAnswerHelper 优先发送 error | 非法 JSON → `error / MODEL_ERROR`，SQL 0 行 |
| DeepSeek 终态 CAS 去重，业务模板在副作用前序列化并认领终态 | 重复 DONE SQL 1 行；独立并发 END + 后续 ERROR SQL 1 行 |
| 会话公布前准备取消状态；关闭状态保留到注册结束，迟到 hook 立即取消 | 原确定性竞态通过；100 次并发 + 2 个边界共执行 hook 102 次，残留状态 0 |
| 主备模型共享 totalTimeoutMs/maxTotalAttempts，每次仅分配剩余时间 | 350 ms 预算实际返回 363 ms；次数上限 3 时顺序为 primary、primary、fallback，无第 4 次 |

正常 6 片段生成、seq=4 后重复回放仍通过：SQL 始终 1 行，页面始终 1 个答案。1800 ms 会话超时实际 1890 ms 收到 timeout，上游确认连接关闭；主动取消后 63 ms 上游确认连接关闭，两者 SQL 均为 0 行。浏览器实际按钮发出 cancel 命令。

路由额外验证：主模型两次失败后备用模型成功；各次模型参数为 p-model、p-model、f-model，调用者请求仍为 caller-model。provider 返回后的慢校验和静态模板慢校验分别在 114 ms、102 ms 返回失败（预算均为 100 ms），校验 worker 收到中断。

## 配置与行为

新增 `llm.total-timeout-ms`（默认 40000）、`llm.max-total-attempts`（默认 4），支持在 `llm.routes.<场景>` 内覆盖。后者包含首次尝试，跨主备模型共享。原 `timeout-ms` 是单次上限，`max-retries` 仍是每 provider 的额外重试次数。

路由使用有界工作线程池，饱和时不在调用线程执行；最后一次尝试按剩余纳秒预算等待，超时中断 worker。请求逐次复制，避免未退出的旧 worker 看到后续模型参数。响应 validator 与模板 validator 也纳入剩余预算。预算为非正数时拒绝配置使用。

总预算是停止等待/发起新调用的限制，不是硬实时返回保证：测试对 350 ms 预算允许额外 100 ms 调度、Redis及日志开销，实际为 13 ms；不将 363 ms 写成“严格小于 350 ms”。部分 SDK 可能忽略中断，其物理网络请求可能继续；线程池有界避免无限扩张。本次已实际观察 DeepSeek 流式主动取消的 socket 关闭，但未验证所有模型 SDK 的取消行为。

## 重现与范围

执行 `scripts/load-test/run-model-stream-fault.ps1`，再执行 `scripts/load-test/test-model-stream-ui.cjs`。详见 [运行及配置说明](../../scripts/load-test/MODEL-STREAM-FAULT.md)。隔离测试容器已停止。

证据：[服务端结果](results/2026-09-24-model-stream-fixed/mock-results.json)、[浏览器结果](results/2026-09-24-model-stream-fixed/ui-results.json)、[构建日志](results/2026-09-24-model-stream-fixed/model-compile.log)、[运行日志](results/2026-09-24-model-stream-fixed/model-stream-fault.log)、[代码指纹](results/2026-09-24-model-stream-fixed/manifest.json)。

验证使用真实 HTTP/SSE、生产 DeepSeek 解析器/业务模板/会话服务、Redis 和隔离 MySQL SQL sink。未覆盖整站登录后的 STOMP 物理断网重连、正式历史 DAO/schema 或跨进程副作用去重。新增终态去重的范围是单次生成的进程内回调。

真实模型仍因缺少端点、模型和密钥配置未调用，共 0 次，单独记录为 [BLOCKED](results/2026-09-24-model-stream-fixed/real-results.json)。模拟和真实模型延迟没有混合统计。
