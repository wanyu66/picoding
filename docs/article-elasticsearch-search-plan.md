# 文章全文搜索 Elasticsearch 接入计划

## 目标

- admin 文章管理支持按正文全文检索，解决只能按标题、作者等字段筛选的问题。
- 用户端首页搜索接口继续复用现有 `/search/api/hint` 和 `/search/api/list`，在 ES 开启后自动走全文检索。
- 和 PaiSmart 共用同一套 Elasticsearch 服务时，通过独立索引、轻量 mapping、分页限制和失败回退降低互相影响。

## 索引设计

- 索引名：`paicoding_article_v1`
- 文档 ID：文章 ID
- 主要字段：
  - `articleId`
  - `title`
  - `shortTitle`
  - `summary`
  - `content`
  - `authorId`
  - `authorName`
  - `status`
  - `deleted`
  - `columnIds`
  - `updateTime`

正文和标题使用 IK 分词，标题权重大于摘要，摘要权重大于正文。当前不引入向量字段，避免和 PaiSmart RAG 的 embedding / dense_vector 资源竞争。

## 搜索分析与排序（v2）

- ES 节点必须安装与 ES 版本一致的 `analysis-ik` 和 `analysis-pinyin` 插件；索引创建会注册 IK 中文分词、停用词、同义词图和拼音子字段。插件变更后必须重启 ES。

### 本机 Docker 插件排查

若创建索引返回 `Unknown filter type [pinyin] for [pai_pinyin]`，先检查
`GET /_cat/plugins?format=json`。仅安装 `analysis-ik` 不满足当前索引配置。
本机 ES 8.10.0、容器 `paicoding-es` 的修复命令如下（其他版本须替换版本号）：

```powershell
docker exec paicoding-es /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-pinyin/8.10.0
docker restart paicoding-es
curl.exe http://127.0.0.1:9200/_cat/plugins?format=json
```

安装地址来自 [analysis-pinyin 官方说明](https://github.com/infinilabs/analysis-pinyin)。
重启后应同时看到 `analysis-ik` 与 `analysis-pinyin`，版本均与 ES 一致。
插件也应纳入实际使用的 ES 镜像构建或插件持久化方案，避免重建容器后丢失。

安装成功只解决索引创建问题，不会自动完成历史文章的全量导入。
若 `/search/api/status` 变为 `MYSQL_TITLE_FALLBACK` 且
`indexCoverageComplete=false`，需登录后台执行“重建文章搜索索引”，
等待任务完成后再检查 `ES_NORMAL`。不要直接把搜索别名指向其他结构的 Canal 索引。

### 字段与查询策略

- 字段权重为标题 `8`、短标题 `5`、摘要 `2`、正文 `1`。召回同时使用精确短语、短语前缀、`AUTO` 拼写容错、拼音和低权重 wildcard 兜底。
- 排序使用 `function_score`，叠加精品/官方/置顶质量分、阅读量、点赞量和 30 天时间衰减。
- 已存在的 v1 索引不会原地获得 analyzer。部署插件与新代码后，调用后台“重建文章搜索索引”接口，新索引构建完成后再原子切换 alias。
- 列表分页使用 `_score, createTime, articleId` 稳定排序和 `search_after` 游标；默认最多 50 页。
- 高亮先使用不可执行的占位标记，服务端 HTML 转义后只恢复受控 `<em class="search-highlight">`，模板不得直接输出未经该流程处理的 ES `_source`。
- 离线评测见 `scripts/search-evaluation`，发布前固定评测集并比较 NDCG@10、MRR、零结果率与点击率。

## 降级状态、超时与熔断

`GET /search/api/status` 返回当前状态，搜索提示与 HTML 分页响应也携带 `searchState`：

| 状态 | 含义 | 用户请求路径 |
| --- | --- | --- |
| `ES_NORMAL` | ES 可用且索引覆盖完整 | ES 全文检索 |
| `MYSQL_TITLE_FALLBACK` | 索引覆盖不足 | MySQL 仅对标题、短标题做 `LIKE` |
| `INDEX_REBUILDING` | 版本化索引正在重建 | 旧 alias 完整时继续 ES；否则 MySQL 标题降级 |
| `ES_UNAVAILABLE` | ES 关闭、超时、连接失败或熔断 | MySQL 标题降级 |

Web 搜索的 ES 请求默认连接/socket 超时为 1500ms，连接池等待为 200ms；全局 ES socket 超时为 3000ms。连续 5 次失败后熔断 30 秒，随后只允许一个半开探测请求。随机深分页缺少已缓存的 `search_after` 游标时直接停止，不同步遍历前面所有页面。

Actuator 指标包括：

- `paicoding.search.elasticsearch.latency`：按 success/failure 区分的 ES 延迟。
- `paicoding.search.elasticsearch.requests_total`：ES 请求数。
- `paicoding.search.elasticsearch.slow_total`：默认超过 500ms 的慢查询数。
- `paicoding.search.elasticsearch.circuit_open_total`：熔断次数。
- `paicoding.search.fallback_total`：按不可用、索引不足、重建、游标缺失等原因统计的降级次数。

慢查询日志只记录耗时、查询哈希和结果状态，不输出用户原始搜索词。

## 查询路径

1. 用户端搜索建议 `/search/api/hint`
   - ES 开启：只查 `title`、`shortTitle`。
   - ES 未开启或异常：回退原 MySQL 标题模糊查询。

2. 用户端搜索列表 `/search/api/list`
   - ES 开启：查 `title`、`shortTitle`、`summary`、`content`，过滤已发布且未删除文章。
   - ES 未开启或异常：回退原 MySQL 标题、短标题、摘要模糊查询。

3. admin 文章列表 `/admin/article/list`
   - 新增 `keyword` 参数。
   - `keyword` 非空且 ES 开启：走 ES 全文检索，同时保留状态、推荐、置顶、专栏、作者、标题等筛选条件。
   - ES 未开启或异常：回退 MySQL，`keyword` 退化为标题搜索，保证页面可用。

## 索引同步

- 新增文章、更新文章、状态变更：监听 `ArticleMsgEvent` 后异步 upsert 文档。
- 删除文章：监听删除事件后删除 ES 文档。
- 历史数据：提供 admin 重建接口 `/admin/article/search/rebuild`，按 ID 批量扫描 MySQL 后写入 ES。

## 服务压力控制

- Paicoding 使用独立索引 `paicoding_article_v1`，不复用 PaiSmart 的知识库索引。
- 不写入向量字段，不调用 embedding 服务，只做普通 BM25 文本检索。
- 搜索接口沿用现有分页大小，默认只取当前页。
- 批量重建按小批次滚动写入，避免一次性大 bulk。
- ES 查询失败时降级 MySQL，避免 ES 短时抖动拖垮核心页面。

## 上线步骤

1. 部署代码但保持 `elasticsearch.open=false`，验证 MySQL 搜索不受影响。
2. 配置 ES 连接信息和索引名，开启 `elasticsearch.open=true`。
3. 调用 `/admin/article/search/rebuild` 初始化索引。
4. 验证 admin 关键词搜索、首页搜索建议、首页搜索列表。
5. 观察 ES CPU、heap、search latency；如 PaiSmart 高峰期压力明显，再单独限流或拆独立 ES 节点。
