# 本机 HTTP 压测

需要先启动 PaiCoding，并确保首页存在公开文章。使用 Node.js（本次实测 v24.15.0），无需安装 npm 依赖。

```powershell
node scripts/load-test/http-benchmark.js
node scripts/load-test/summarize.js docs/performance/results/<本次输出目录>
```

默认逐个测试文章列表、文章详情和搜索列表，1/8/32 并发，每档三轮，预热 3 秒、测量 10 秒。详情轮询首页文章，搜索均匀轮询 Java、技术派、分布式。测试会增加正常阅读计数、查询统计，不会造文章、点赞、下单或清空缓存。

可配置环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| BENCH_URL | http://127.0.0.1:8080 | 仅允许本机 HTTP |
| BENCH_CONCURRENCY | 1,8,32 | 并发档位 |
| BENCH_SECONDS | 10 | 每轮测量秒数 |
| BENCH_WARMUP_SECONDS | 3 | 每轮预热秒数 |
| BENCH_ROUNDS | 3 | 重复轮数 |
| BENCH_OUTPUT | 带时间戳的新目录 | 必须使用新的目录，避免混合不同实验 |
| BENCH_SERVER_RUNTIME | 空 | 人工确认的服务端 JDK 等信息 |

HTTP 200 不等于成功：脚本同时校验业务码和结果结构。某阶段错误率超过 5% 时自动停止，保存已完成计时阶段和 failure.json；不要通过删除失败样本或禁用校验获得更高 QPS。

测量使用闭环、无思考时间、keep-alive、无重试、10 秒请求截止时间。每个样本包含完整响应接收及 JSON 校验时间。QPS 分母包含最后一批请求的完成时间。分位数按合并原始成功样本计算，失败次数单独报告。预热失败会停止，但预热样本不计入计时结果。

本脚本是短时基线工具，不是生产容量认证。真实优化对比应固定数据快照、机器、JVM 参数、缓存策略、请求分布和成功口径，延长测量窗口，最好使用独立压测机和交替 A/B 多轮测试。提高并发带来的吞吐变化不能写成代码优化提升。搜索准确率另需真实人工相关性标注集，不能使用 search-evaluation 的模板数据声称准确率改善。
