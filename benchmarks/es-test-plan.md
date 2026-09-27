# Elasticsearch 性能、相关性与可靠性验证

本阶段仅新增 benchmarks 文件。先审计当前源码，再通过 Java 8 编译当前 SearchService / ReadService 到 benchmarks/build，并反射导出实际 Mapping、DSL；不替换生产类文件、配置、接口或表结构。已有 1 万篇 MySQL/Outbox 导入记录见 article-import.md。本轮 1万/5万/10万为独立 ES 合成语料实验，不代表又向 MySQL 导入了 15 万篇。

## 实际入口与实现状态

代码路径均相对项目根目录；“已实现”仅说明代码存在，不等于本地完整链路验证通过。

|能力|真实入口/方法|实现与验证边界|
|---|---|---|
|用户检索|`paicoding-web/.../front/search/rest/SearchRestController.java` 的 GET `/search/api/list?key=...&page=...`；`ArticleReadServiceImpl.queryArticlesBySearchKey`|已实现。当前运行实例 `/search/api/status` 返回 MYSQL_TITLE_FALLBACK、coverage=false，不能将其吞吐标为 ES 吞吐。|
|ES DSL|`paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/search/impl/ArticleSearchServiceImpl.java` 的 `articleKeywordQuery`、`buildSearchBody`、`functionScoreQuery`|已实现。SearchContract.java 调用真实私有方法导出；源码、配置、契约哈希随结果保存。|
|字段权重|同类 `fieldBoost`|title=8，shortTitle=5，summary=2，content=1。phrase×2、phrase_prefix×1.4、fuzzy×1、pinyin×0.8、wildcard×0.35。部分 builder 仅在 boost>1 时写出，未写时按 ES 默认值，不能把标称乘积都当作实际 DSL 值。|
|召回|`articleKeywordQuery`；`analysisSettings`|已实现 phrase、前缀、AUTO fuzziness（prefix_length=1/max_expansions=50）、pinyin 子字段与 Exact wildcard。ik_max_word 索引、ik_smart 查询，查询 synonym_graph。数据本身含拼音/错词，命中这些字面值不能单独证明纠错有效。|
|综合排序|`functionScoreQuery`、`relevanceSort`|已实现 quality sqrt×2、阅读 log1p(factor .08)×.8、点赞 log1p(factor .3)×1.2、发布时间 gauss(now,30d,offset7d,decay .5)×1.5，sum/sum/max_boost20；score/createTime/articleId 均降序。|
|分页|`executeSearchPage`；控制器校验|部分实现：search_after + Redis 游标，默认最大50页；`buildSearchBody` 忽略 offset。缺游标返回空结果，不触发 DAO；无 PIT，实时 now 会改变时间分数，不能保证跨请求静态快照。|
|缓存|`searchResultCacheKey`、`cursorKey`|第一页缓存120s、游标300s；`pai_article_search:result:<generation>:...` / `pai_article_search:cursor:<generation>:...:<page>`。直连 ES 性能测试不包含 Redis 命中收益。|
|超时/降级|`searchRequestOptions`、`search`、`parseSearchResponse`；`ArticleReadServiceImpl.queryArticlesBySearchKey`|部分实现：socket/connect默认1500ms、连接池等待200ms，异常返回null后进入 MySQL 标题匹配；连续失败5次熔断30s。HTTP200里的 timed_out / failed shards 未检查，故障测试保留失败。|
|MySQL查询|`ArticleDao.listArticlesByTitleSearchKey`|实际降级 DAO 为标题匹配，不等价于 ES 正文/拼音/排序。组件故障测试 DAO 使用空列表替身；真实数据库故障切换端到端结果 N/A。|
|重建与切换|`rebuildArticleIndex` → `executeFullRebuild` → `bulkIndexWithRetry` → `activateRebuiltIndex` → `switchAlias`|已实现版本化索引、按 ID 批量扫描、external_gte 更新时间版本、重试和失败批次、刷新后原子切换 alias。基准 harness 调用真实 executeFullRebuild，输入为内存 DAO，真实 ES；并发 MySQL 写入/Outbox 双写一致性不是本次验证范围。|
|覆盖检测|`isFullIndexAvailable`|只比较索引数量与数据库可搜索数量；数量够不代表文章集合/内容正确。现有混合索引不执行全量重建或切换。|
|观测|同类 `recordEsQuery`、`recordFallback`；dev/application-dal.yml|Micrometer `paicoding.search.elasticsearch.latency/requests_total/slow_total/circuit_open_total` 与 `paicoding.search.fallback_total`。本轮测 ES `_nodes/stats` 与响应 took；应用 JVM 因未承担该负载不作为性能数据。|

主要类完整路径：

- `paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java`
- `paicoding-service/src/main/java/com/github/paicoding/forum/service/article/repository/dao/ArticleDao.java`
- `paicoding-web/src/main/java/com/github/paicoding/forum/web/front/search/rest/SearchRestController.java`
- `paicoding-web/src/main/resources-env/dev/application-dal.yml`
- `paicoding-service/src/main/resources/mapper/ArticleMapper.xml`

## 可隔离基线

|编号|配置|对照范围|
|---|---|---|
|A|四字段等权 multi_match / best_fields / AND，deleted=0、status=1|基础全文召回，保留相同分词器/同义词、排序 tie-break、source、高亮；不是标准分词器/LIKE基线。|
|B|当前 Java 导出的全部召回及四个 function_score 因子|与 A 对比的是召回+加权+热度+时间的整体成本，不能拆成单一排序因子的收益。唯一受控修改是 origin 固定为生成语料的参考日期2026-01-01。|
|C/D|B，from=0/90/990/4990/9990、size=10|同一高命中正文查询（validate recovery），引擎浅/深分页对照。应用没有直接from路径，且超过50页被拒绝，不标为业务接口能力。|
|E|B，相同位置 search_after|预先顺序获取游标；排序不变，分别核对 C/D/E 返回 ID。定位成本保存在 cursor-preparation.jsonl，正式数字仅指已定位后的请求，不能声称随机跳页或端到端遍历加速。|

三种规模使用相同固定种子、标题/内容前缀分布、1 shard / 0 replicas、同一个本地 ES 节点。各规模内 A/B 使用同一份不可变索引；不执行 force merge 或清缓存。每轮用固定种子的随机顺序调度实验单元，避免固定先后顺序偏差。现有旧 `scripts/search-evaluation/search-variants.js` C 不含 qualityScore，不能充当本次 B。

## 负载与统计

- 每规模：7个关键词（精确标题、正文、拼音、错词、热门意图、最新意图、无结果）；混合轮询。返回10/50条、并发1/4，A/B共8个单元；分页5个深度×2路径、并发4共10个单元。每单元3轮，每轮预热2s、测量5s；每规模54段测量。
- 闭环客户端：并发连接等待响应后再发请求。正式混合负载在截止时间后完成整个七查询循环，严格保证七个关键词次数相等（客户端断言）。QPS=完成请求数/实际墙钟时间（包含完整循环收尾和排空尾部），成功QPS另外计算；不是开放到达率容量压测，不修正 coordinated omission。慢请求会延长窗口。
- P50/P95/P99 对原始测量请求按 nearest-rank 计算，包含失败耗时；不平均各轮百分位。均值、错误率、响应 took、返回数量逐请求保留。按查询拆分，避免总体数掩盖无结果/大结果的差异。短窗口下慢请求样本较少，尾部分位不用于 SLA。
- 客户端超时30s，为观察实际成本而设，**不是应用默认1500ms超时**。部分分片失败、ES timed_out、非2xx全部按失败记录。
- 每秒 `_nodes/stats`：ES process CPU、JVM heap、GC计数/耗时、cgroup内存。进程 virtual memory 不是 RSS；未提供的 RSS 标 N/A。资源为共享 ES 节点，含其他索引活动；主机与 Docker 同机，不能声称独占硬件。
- QPS包含客户端JSON解析和同步CSV日志开销；不是ES最大容量。先前规模的独立索引在后续规模测试时仍存在，跨规模除了目标索引文档数还受共享节点缓存/分片常驻状态影响；严格因果“数据量增长成本”需要每轮重置独立集群。相同规模内部A/B共用同一环境。
- 这是短时本机探索测试；可靠容量需增加持续时间/轮数、独占机器与客户端、真实流量查询分布、稳定 JVM/缓存状态。

## 相关性与故障

相关性复用项目严格的 evaluate-search-ranking.js 和人工审阅页面。7条查询为合成语料的小型待审集，不是人工搜索日志。Top20(A∪B)池化候选评分0..3，>=2为相关；人工评分未完成时 MRR/NDCG/Recall 全部 N/A。池化只能称 judged Recall@10，不能称全语料 Recall；热门/最新需结合页面显式元数据与查询意图判断，不自动以热度替代相关性。

故障 harness 只向随机本机端口的 HTTP 测试服务注入故障，使用真实 SearchService 与 ReadService；Redis模板/DAO受控替身，禁止停止共享 ES。验证客户端超时、连接不可达、HTTP200超时标志、失败分片、缺游标和超最大页。严格断言失败如实报告；真实 MySQL 回源性能、线上 HTTP 缓存/熔断/恢复、真实故障切换全链路未验证。

重建使用独立 owned alias，旧/新各10000篇相同输入；读压并发4贯穿实际 Java 重建与切换。DAO为确定性内存输入，Redis模板为替身；实际 bulk、refresh、alias、external version 调用生产方法。未覆盖真实 MySQL/Outbox 并发更新窗口、控制器权限、跨进程任务恢复和失败批重试演练。
