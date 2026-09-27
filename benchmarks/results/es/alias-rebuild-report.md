# 重建与 Alias 切换

独立 bench_es_* alias；原有业务索引/alias不变。SearchRebuildHarness调用当前源码 executeFullRebuild → bulkIndexWithRetry → activateRebuiltIndex → switchAlias，真实ES读写；DAO提供固定10000篇内存语料、Redis模板替身，bulk=200、maxRetries=3、1shard/0replica。并发查询4，无预热（此处统计故障窗口全部请求）；每请求后暂停10ms。

严格重建任务窗口为37.50s，按响应完成时间筛选，共215次请求、0次错误，成功率100.00%。完整计算见 alias-task-window.json。下面的全观察窗口另包含Java进程启动和切换后观察，不能混称全部都发生在重建任务期间。

|观测|实测|
|---|---|
|总请求|304|
|成功/错误|304/0|
|查询成功率|100.00%|
|窗口秒|51.65|
|查询P95ms|751.42|
|旧/新索引返回次数|289/15|
|旧/新文档数|10000/10000|
|生产重建任务|SUCCEEDED|
|重建耗时ms|37508.28|
|任务成功文档|10000|

生产任务细节/失败批次见 run-01/alias/harness.json；原始查询见 queries.jsonl，fixture-manifest.json含输入哈希，aliasAfter为实际服务端返回。

这是实际Java重建方法+ES的组件集成，未验证真实MySQL扫描、Outbox双写并发更新、HTTP渲染/Redis跨进程失效或失败批重试恢复。不停止共享ES，不在现有业务alias上执行重建。清理命令只删除manifest精确命名且meta所有权匹配的测试索引。
