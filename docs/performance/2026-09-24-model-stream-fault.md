# 模型流故障验证记录（2026-09-24）

后续状态：五项问题已修复，见 [修复及回归报告](2026-09-24-model-stream-fixed.md)。以下保留修复前结果。

已执行可控模拟服务测试：Java 8 编译通过，8 个服务端场景中 3 个通过、5 个失败；独立浏览器验证通过。真实模型请求 **0 次**：缺少指定端点、模型及密钥环境配置，状态为 BLOCKED。测试没有修改生产实现来掩盖失败。

| 场景 | 结果 | 实际证据 |
|---|---|---|
| 正常 6 片段，第 3 片段后游标回放两次 | PASS | seq=4 后返回 4 个事件；SQL 行数始终 1；越权回放 0 个事件 |
| 上游第 3 片段后 EOF，无 DONE | FAIL | 只收到 3 片段，终态 completed，残缺答案写入 1 行 |
| 第 3 片段后非法 JSON | FAIL | 解析器报错，SQL 0 行，但对外终态仍是 completed |
| 第 3 片段后不再输出 token | PASS | 1800 ms 会话上限，1857 ms 收到 timeout；上游观察到连接关闭，SQL 0 行 |
| 第 3 片段后停止 | PASS | cancelled；64 ms 后上游观察到连接关闭；SQL 0 行 |
| 重复 DONE | FAIL | 对外虽仅完成一次，但 SQL 实际新增 2 行 |
| 先取消、后注册上游取消 hook | FAIL | hook 未调用 |
| 两模型重试与总预算 | FAIL（预算） | primary、primary、fallback、fallback 共 4 次，次数符合现有配置；总耗时 695 ms，超过测试目标 350 ms |

浏览器使用 Microsoft Edge / Playwright，执行当前生产模板中提取的实际处理函数。从第 3 片段恢复到完整 6 片段后，再重复及倒回旧事件，DOM 始终只有 1 个答案，无重复显示。实际点击“停止生成”按钮发出正确 generationId 的 cancel 命令。最终回放及重复事件渲染耗时约 18.54 ms；Java 两次 Redis 回放及 SQL 复查共 22 ms。这些不是整站重连恢复时延。

## 根因与边界

1. `DeepSeekChatServiceImpl.onClosed` 只要已有内容，就通过 END 完成，不能区分缺少 DONE 的提前 EOF。
2. `WsAnswerHelper` 按 answerType 判断终态，ERROR 回调也会走 `streamSessions.complete`，丢失错误语义。
3. `DeepSeekChatServiceImpl` 的完成回调没有重复终态保护；`AbsChatService.asyncChat` 对每个 END 都执行计数和持久化，只有观测指标做了原子去重。
4. `ChatGenerationControl.cancel` 移除当前 hook 列表，未保存取消状态；后注册的 hook 不会收到之前的取消。
5. `LLMRouter` 只有每次尝试的 timeout 与每 provider 重试次数，没有总 deadline/总次数预算字段。350 ms 是本测试明确设置的验收目标，不是声称项目已有该配置。测试证明缺少总预算，而非单次超时配置被违反。

真实组件包括 DeepSeek HTTP 集成、SDK 流解析器、业务异步模板、会话/回放/取消服务、Redis 和 MySQL。Facade 选择、敏感词服务、历史查询和遥测使用测试替身；正式历史存储入口 `recordChatItem` 替换为专用 MySQL SQL sink。因此这里的“SQL 2 行”证明生产异步模板重复触发落库，不代表已经覆盖正式历史 DAO 的完整配置和 schema。

回放模拟的是客户端在第 3 片段后漏收事件，再携带游标恢复。没有启动整站登录鉴权、网关和真实 STOMP 链路，浏览器的 STOMP 对象为命令捕获替身。不能宣称已通过整站物理断网重连。EOF 为 HTTP 响应提前正常关闭，不是 TCP RST。stall 保持 SSE 注释流通，用于检验 token 停止后的会话超时，不是 socket read timeout。路由的 provider 为测试 HTTP 适配器，不代表所有真实模型 SDK 都已验证。

## 时延来源

模拟服务每片段前固定等待 80 ms。正常场景首片段 1394 ms、终态 1774 ms，包含首次调用的 JVM/类加载开销；其余场景首片段 175–225 ms。每场景一次，不能据此计算有意义的 p95/p99 或推断真实模型性能。

模拟数据在 [mock-results.json](results/2026-09-24-model-stream/mock-results.json)，真实模型状态单独保存到 [real-results.json](results/2026-09-24-model-stream/real-results.json)，未混合统计。真实模型时延暂无数据。

## 重现与剩余工作

参见 [运行说明](../../scripts/load-test/MODEL-STREAM-FAULT.md)。最终 Java 编译成功；故障主程序按设计返回非零，保留所有验收失败；浏览器测试退出 0。隔离 MySQL/Redis 容器已停止，未停止常用服务。基准提交 `318e5d08e149f2da80fb51af04890441bc497284`，运行于有既有修改的工作区。

证据目录：[results/2026-09-24-model-stream](results/2026-09-24-model-stream)。包含编译日志、运行日志、原始服务端结果、回放事件与 UI 结果。

仍待完成：上述缺陷修复及回归、整站 STOMP/正式历史 DAO 验收、真实模型兼容性小样本。真实验证入口已准备，配置 `PAI_MODEL_TEST_BASE_URL`、`PAI_MODEL_TEST_MODEL`、`PAI_MODEL_TEST_API_KEY` 后最多额外调用 1 次；当前没有可用配置，不能声称验证通过。
