# 多模型路由与 WebSocket/STOMP 流式对话基准报告

**仅 Mock 数据，真实模型性能 N/A，付费调用为 0。**

运行 bench-chat-1790338725299：2026-09-25T12:18:45.299Z 至 2026-09-25T12:19:12.284Z。业务代码未改动，未操作生产数据库。

排除 1 个预热会话，流式测试 211 个会话：207 通过、4 失败。同步路由 40/40 通过；后续请求熔断降级 通过；UI PASS。测试退出码 2，服务退出码 0，容器清理退出码 0。

## 关键发现

- 失败会话：normal-100-10157、normal-100-10160、normal-100-10161、normal-100-10162。原始 STOMP ERROR 显示 clientInboundChannel 队列满（256）导致 TaskRejectedException；没有通过修改队列或重试让结果变绿。
- 缺失期望事件共 32 条，其中 24 条属于请求没有调用 Provider，8 条属于 Provider 已执行但连接没有收到事件。因此不能把全部缺失称为“网络丢包”。
- 真实断开 WebSocket、重新 CONNECT/SUBSCRIBE 并回放：10/10 成功，恢复率 100.00%，恢复 P95 87.09ms，每会话仅一次 Provider 调用。断网注入等待固定 500ms，不是网页自动重连退避时间。
- 取消响应 P95/P99 9.48/9.48ms；10/10 观察到上游 socket 断开。取消会收到两类同义终态：缓存中的正常 seq 和额外最大安全整数 seq 确认；原始事件保留，未强行当成一个消息。
- 重复回放注入 160 条重复事件，客户端丢弃 160 条；实际网页模板渲染 10 个会话，重复答案元素 0。重复 [DONE] 与已提交历史条数另由 evidence-check.json 核对。
- 同步 complete() 支持本次请求内的超时重试、备用 Provider 和模板。流式路径只在请求开始时选路，本次 primary 503 后发 error，熔断后的下一次请求由 backup 完成。**同一条流中途自动模型降级 N/A，不能将同步降级结果套用到流式请求。**

## 环境与测试边界

Intel(R) Core(TM) i7-14650HX，逻辑核 24，主机内存 15.8 GiB，Windows 10.0.26100，Node v24.15.0；Java 8 实际版本见 java-version.txt，Redis 镜像 digest 见 redis-image.txt。

真实嵌入式 Tomcat/Spring simple broker、原控制器/服务/SSE 解析和独立 Redis 回放。测试身份与历史存储为夹具，未启动生产应用，不涵盖真实认证、MySQL 历史表或 Rabbit relay。SSE 每 60ms 输出一片，共 6 片；OkHttp 测试调度上限 256/host=256，不能外推生产默认调度容量。备用 source 复用 SSE 解析适配器，未验证真实智谱 SDK。

每档一次短批量，1 次预热；无稳定态长压测或优化前版本，因此不声明性能改善。100 并发是同时发送突发负载，连接/订阅先完成。所有失败保留。

## Mock 流式指标

TTFT 从发送 generate 到首个非空 delta，总耗时到首个终态，单位 ms。无终态/无首字记 N/A，百分位只统计实际可观测样本；失败数和样本数并列，避免幸存者延迟被误认为全量成功。

|场景|会话/失败|TTFT 样本|TTFT P50/P95/P99|总耗时 P95/P99|缺失事件|模型调用|
|---|---:|---:|---:|---:|---:|---:|
|normal-1|1/0|1|100.83 / 100.83 / 100.83|450.14 / 450.14|0|1|
|normal-10|10/0|10|131.96 / 144.74 / 144.74|458.58 / 458.58|0|10|
|normal-50|50/0|50|177.43 / 274.03 / 279.70|598.66 / 600.90|0|50|
|normal-100|100/4|96|167.85 / 257.38 / 257.68|576.11 / 587.23|32|97|
|cancel|10/0|10|94.53 / 95.41 / 95.41|239.50 / 239.50|0|10|
|reconnect|10/0|10|86.39 / 87.20 / 87.20|742.24 / 742.24|0|10|
|duplicate_replay|10/0|10|88.55 / 89.27 / 89.27|445.27 / 445.27|0|10|
|duplicate_done|10/0|10|97.18 / 99.48 / 99.48|423.11 / 423.11|0|10|
|timeout|10/0|10|99.04 / 102.88 / 102.88|1330.39 / 1330.39|0|10|

完整精度、总时长、客户端与 Provider 最大在途数、取消和恢复指标见 metrics.csv。client-samples.json 包含每个会话原始耗时与 STOMP 事件。

## Mock 同步路由指标（不是流式 TTFT）

每次路由单次超时 80ms、总预算 350ms、最多 3 次 Provider 尝试。circuit 每个样本连续发 3 个请求，验证首请求后 primary 被跳过。P95/P99 为整段路由耗时，样本仅 10 个，不作为容量结论。

|场景|通过/样本|primary 调用|backup 调用|P95 ms|P99 ms|
|---|---:|---:|---:|---:|---:|
|timeout|10/10|20|10|196.99|196.99|
|unavailable|10/10|20|10|15.44|15.44|
|template|10/10|20|10|5.54|5.54|
|circuit|10/10|20|30|28.90|28.90|

流式 SSE Provider 总调用 211 次（包括预热与降级用例）；同步 Mock Provider 总调用 140 次，均为本机夹具；真实模型调用 0。模板 fallback 没有额外模型调用。

## 原始证据与复现

- 配置：benchmarks/config/chat-stream-compose.yml
- 服务：benchmarks/java/chat/ChatStreamBenchServer.java
- 脚本：prepare-chat-stream.mjs、run-chat-stream.mjs、chat-stream-client.mjs、chat-stream-ui.cjs、chat-stream-report.mjs、check-chat-stream.mjs，均位于 benchmarks/scripts/
- 说明：benchmarks/chat-stream-test-plan.md
- 输出：本目录 JSON/CSV/JSONL、原始服务器日志、调用记录、源码哈希、启动/清理状态；前两轮失败也保留。

实际命令（复跑需使用新目录，例如 run-04）：

```powershell
node benchmarks/scripts/prepare-chat-stream.mjs
node benchmarks/scripts/run-chat-stream.mjs benchmarks/results/chat-stream/run-03
node benchmarks/scripts/chat-stream-ui.cjs benchmarks/results/chat-stream/run-03
node benchmarks/scripts/chat-stream-report.mjs benchmarks/results/chat-stream/run-03
node benchmarks/scripts/check-chat-stream.mjs benchmarks/results/chat-stream/run-03
```

run-01 为测试端 mock bean 被自动注入无关依赖导致启动失败，已改为注册预构造 singleton；run-02 的测试客户端发送 application/json 导致 String 控制器转换失败，改为与网页相同的无 content-type 头。均为测试夹具修正，没有修改业务代码、预期结果或高并发失败判定。

## 剩余问题与下一阶段

优先复现并界定 STOMP 入站背压/拒绝时的客户端恢复，验证重发会不会导致重复生成；然后测试长流超过 200 事件的回放、TTL 过期、乱序、Redis 故障和多节点 broker relay。当前未测项和真实模型指标均 N/A。任何真实模型测试须另行确认预算和单独配置，默认不得做付费高并发。
