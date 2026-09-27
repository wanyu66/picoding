# 聊天入站队列故障修复验证

本轮保留线程池 core=4、max=10、队列=256。入站饱和时在发送线程执行当前 handler，向上游施加背压；线程池关闭后仍明确拒绝。只修改 WsChatConfig 的执行策略，未改业务状态判定、答案断言、模型路由或客户端重试。

旧 run-03 的 4 个失败会话均有 clientInboundChannel 队列满的 STOMP ERROR。最终运行 benchmarks/results/fixes-20260927/chat-stream-fixed-03 的 211 个正式会话全部通过，原 100 并发档从 96/100 变为 100/100，缺失期望事件从 32 变为 0；同步路由 40/40、真实模板 DOM 10/10 通过，重复展示 0。付费调用 0。

固定饱和回归：chat-backpressure-baseline 在 10 个工作线程和 256 个队列任务占满时，三个 handler 接收 0 次并报拒绝，退出 2；chat-backpressure-fixed-02 同条件三个 handler 各接收一次，256 个已接收队列任务全部完成，关闭后仍拒绝，退出 0。两份 checks.json 保留同一组断言。

发现并修复额外测试设施竞态：chat-stream-fixed-02 因 runner 在 server-ready.json 尚未写完时读取而报 JSON 解析失败；该轮保留，不计为业务通过。ChatStreamBenchServer 改为先完整写临时文件、再原子发布文件。final-03 完成全部断言和清理。

实际环境 Intel(R) Core(TM) i7-14650HX、24 逻辑核、15.8 GiB、Windows 10.0.26100、Node v24.15.0；Java 8 和 Redis digest 见对应原始文件。并发档为 1/10/50/100，另有 5 组各 10 个故障会话、1 个预热会话；SSE 每 60ms 输出一片、共 6 片。最终整轮含启动与清理 31.317 秒。每档实际测试时长见 metrics.csv。

100 并发 TTFT P95 257.38 → 422.97ms，总耗时 P95 576.11 → 787.00ms。旧轮延迟只含成功/可观测会话且每档仅短批量，本轮只说明所测故障消失，不声明延迟改善或生产容量。

实际修改：
- paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/stomp/WsChatConfig.java
- benchmarks/java/chat/ChatInboundBackpressureCheck.java（新增）
- benchmarks/scripts/run-chat-backpressure.mjs（新增）
- benchmarks/java/chat/ChatStreamBenchServer.java
- benchmarks/scripts/chat-stream-report.mjs（去除不再适用的“业务代码未改动”硬编码、按实际目录生成命令）
- benchmarks/scripts/compare-chat-fix.mjs（新增）
- benchmarks/results/fixes-20260927/chat-* 原始证据与报告

复跑命令（输出目录必须尚不存在；以下 replay 名称供下一次使用）：

```powershell
node benchmarks/scripts/run-chat-backpressure.mjs benchmarks/results/fixes-20260927/chat-backpressure-baseline-replay --baseline-ref=d2722a30f35a4cddf3304819467ae6d7c9d30f37
node benchmarks/scripts/run-chat-backpressure.mjs benchmarks/results/fixes-20260927/chat-backpressure-fixed-replay
node benchmarks/scripts/prepare-chat-stream.mjs
node benchmarks/scripts/run-chat-stream.mjs benchmarks/results/fixes-20260927/chat-stream-fixed-replay
node benchmarks/scripts/chat-stream-ui.cjs benchmarks/results/fixes-20260927/chat-stream-fixed-replay
node benchmarks/scripts/check-chat-stream.mjs benchmarks/results/fixes-20260927/chat-stream-fixed-replay
node benchmarks/scripts/chat-stream-report.mjs benchmarks/results/fixes-20260927/chat-stream-fixed-replay
node benchmarks/scripts/compare-chat-fix.mjs benchmarks/results/chat-stream/run-03 benchmarks/results/fixes-20260927/chat-stream-fixed-replay benchmarks/results/fixes-20260927/chat-comparison-replay
```

基线固定回归按原断言预期失败（退出 2）。实际已执行的输出目录为 chat-backpressure-baseline、chat-backpressure-fixed、chat-backpressure-fixed-02、chat-stream-fixed-01、chat-stream-fixed-02、chat-stream-fixed-03 及 chat-comparison；中间失败未删除。准备脚本显式使用 .runtime/java8（可用 JAVA8_HOME 指定），无需 mvn clean。

隔离与恢复：流式脚本启动唯一 Compose 项目的本机 16419 端口 Redis，finally 停止 JVM 并 down --volumes 仅清理该项目；最终 serverExitCode=0、cleanupExitCode=0。固定饱和测试仅使用内存线程池并在 finally 关闭。无需业务数据库或生产账号。原业务实现可通过回退本次 WsChatConfig 差异恢复；旧基线直接从固定 Git 提交编译，无需切换工作区。

剩余问题与下一阶段：CallerRuns 会延长发送线程占用，持续过载及慢 Redis 下的稳定态延迟未测。真实认证/模型 API/MySQL 历史落库/长于 200 事件的回放/TTL/乱序/Redis 故障/多节点 relay 仍为 N/A。下一阶段优先测试持续入站过载和慢依赖下的延迟、连接恢复及有界资源使用；真实付费模型仍需另行确认。
