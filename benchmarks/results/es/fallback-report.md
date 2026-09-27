# 降级与故障验证

真实 SearchService/ReadService，Java8，单线程每故障3次搜索+1次读服务；本机随机端口 HTTP 故障桩。索引覆盖标志固定为已完成以触发实际网络查询；Redis模板缓存未命中、DAO空列表替身，syncOnlineKeyword禁用以避免副作用。**真实MySQL回源数据正确性/吞吐、业务HTTP故障下成功率与缓存熔断恢复 = N/A。**

|用例|断言|注入确认|搜索耗时ms（3次）|DAO调用|实际底层原因|
|---|---|---|---|---|---|
|socket_timeout|PASS|是|2198.32/2015.89/2029.34|1|1,500 milliseconds timeout on connection http-outgoing-3 [ACTIVE]|
|connection_refused|FAIL|否|2020.41/2017.07/2013.40|1|Timeout connecting to [/127.0.0.1:63797]|
|es_timed_out|FAIL|是|56.17/4.37/4.33|0|无异常|
|failed_shard|FAIL|是|11.98/3.48/2.50|0|无异常|
|cursor_missing|PASS|是|0.60/0.28/0.36|0|无异常|
|page_over_limit|PASS|是|0.19/0.08/0.08|0|无异常|

3/6断言通过。原始 JSON：fault-harness-02.json；对应日志保留。

客户端 socket1500ms 超时触发DAO降级；实测总延迟含客户端调度，不能直接声称接口1500ms完成。关闭监听端口在本机返回连接超时而非connection refused，严格注入类型断言FAIL，但DAO实际被调用；不伪造Connection refused通过。HTTP200 timed_out=true/failed shards 未被服务检查，DAO调用0，为确认的失败。缺游标/页码>50返回空且无DAO调用，为当前行为核对通过，不等价于高可用通过。

早期 fault-harness-01 的 Redis未初始化导致NPE，stubRequests=0，属于无效故障注入，所有其表面PASS作废；修正测试设施后02才实际发起网络故障。首次 fault-harness.log 为 PowerShell未引用-D参数的启动失败，亦保留。未修改业务断言掩盖 timed_out/failed_shard 失败。
