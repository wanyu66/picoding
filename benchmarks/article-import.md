# 可复现文章数据集：生成、导入、同步和清理

工具只在 benchmarks 新增文件。用户指定复用本机既有项目库；只读连接发现 `paicoding` 名称不存在（MySQL1049），实际文章表位于 `127.0.0.1:3306/pai_coding`，本次使用后者。所有写入都带批次所有权标识，不执行业务DDL、全表清空、索引删除或全量重建。另保留 `bench_` 独立环境配置。实际连接前仍必须确认本机目标不是生产库。

## 源码分析与实现选择

| 实际文件 / 方法 | 对工具的约束 |
|---|---|
| `paicoding-web/src/main/resources/liquibase/data/init_schema_221209.sql`；后续 `update_schema_251110.sql` | article标题/短标题120、摘要300，slug后加为200；article_detail唯一(article_id,version)；read_count唯一(document_id,document_type)；user_foot唯一(user_id,document_id,document_type)。工具使用现有迁移后的schema，不自建表或改schema |
| `paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleWriteServiceImpl.java#saveArticle/insertArticle` | 发布事务保存文章/正文/标签、阅读足迹，再发CREATE/ONLINE/REVIEW事件；包含审核和可能的AI slug生成。数据夹具直接SQL批量导入，不冒充发布API吞吐，不改变审核规则、不调用付费API |
| `.../service/article/service/ArticleDomainEventOutboxBridge.java#append` | 正常Spring事件在BEFORE_COMMIT进入domain_event_outbox；裸SQL不会触发。工具在同一MySQL事务显式构造同契约ARTICLE_CREATE，使用统一生产publisher/consumer同步 |
| `.../service/notify/service/impl/DomainEventOutboxPublisher.java#publishPending` | 默认每秒取100条、逐条确认；1万到10万篇的同步时间受真实队列消费能力限制，不能拿造数速度代表索引速度 |
| `.../service/article/service/search/ArticleSearchDomainEventConsumer.java#consume` | ARTICLE_CREATE走syncArticle；ARTICLE_DELETE走deleteArticle。ARTICLE_CREATE不触发排行榜发布+10，避免夹具冒充真实发布活动 |
| `paicoding-service/src/main/resources/mapper/ArticleMapper.xml#articleSearchDocumentSelect` | 关联user_info、最新未删除article_detail、column_article、read_count(type=1)、user_foot(type=1,praise_stat=1)。阅读量和点赞数必须落这些真实表，不添加虚构article热度列 |
| `.../service/article/service/search/impl/ArticleSearchServiceImpl.java#indexDefinition/analysisSettings/toIndexDocument` | 单分片/0副本为代码默认；IK、pinyin、synonym_graph；title/shortTitle/summary/content及pinyin子字段、exact字段，urlSlug为keyword；readCount/praiseCount/qualityScore与日期字段参与排序 |
| 同上 `indexOne/documentVersion/deleteDocument` | 文档ID=articleId，external_gte版本=update_time毫秒。先硬删MySQL会使删除事件取不到旧版本，退回1并可能发生版本冲突。清理必须先更新删除墓碑、发送DELETE并确认ES消失，再删本批次正文/计数/足迹；保留article墓碑 |

**现有机制边界**：syncArticle/deleteArticle会吞异常记日志，consume表成功不证明ES写入成功。工具以逐ID `_mget`、可搜索 `_count` 和字段核对为准，失败退出非0；`sync`会重新产生带新eventId的CREATE事件，不改消费判定。缺失的IK/pinyin插件、错索引或不存在Mapping直接失败，不创建简化Mapping掩盖问题。

分类通过真实category_id区分五类，但当前ES文档没有categoryId，不能声称支持按分类ES过滤。标签/专栏非检索夹具必要条件，不插article_tag/column_article；文章类型1，专栏列表为空。造数具备同义词/拼音/拼写错误样本，并不证明召回正确；queries.jsonl相关性始终null，待人工标签。

## 文件与参数

- `scripts/articles.mjs`：generate / fixtures / import / sync / verify / cleanup入口。
- `scripts/cleanup-articles.mjs`：独立清理入口，等价于cleanup。
- `scripts/articles/*.mjs`：确定性生成、MySQL客户端、事务SQL、同步核验。
- `config/articles.local.json`：实际本机pai_coding库；root用户通过授权的本地.env连接，ES本机9200/paicoding_article已只读核对。配置不含密码。
- `config/articles.json`：默认禁止连接的独立测试环境示例。
- 数据目录：articles.jsonl、manifest.json、queries.jsonl；结果目录每次必须新建。

默认10000篇，支持50000/100000篇；100篇仅用于冒烟。变更规模时修改count及batch，例如 `search-s20260924-n50000-v1`。固定seed=20260924、referenceTime=2026-01-01 UTC；没有依赖当前时间的内容或排序字段。批次相同、参数相同生成字节一致，manifest保存SHA-256。不同批次slug不同；跨规模性能对比需控制语料子集及其他参数。

覆盖Java/Spring/Redis/MySQL/Elasticsearch；六种关键词变体中文、英文、混合、同义词、拼音、错拼；标题三档长度，正文240/1200/6000字符；发布时间跨固定参考时刻之前约两年；阅读量0到约百万、点赞0/1/4/16，收藏为点赞人数一半；推荐标记有固定分布。热度由真实关联记录产生。数据为合成技术文章，不是真实用户文章或真实流量样本。

批次标记：article.url_slug=`bench-<batch>-000001`；source_url=`urn:paicoding:bench:<batch>`。清理必须同时匹配这两个条件和本批次manifest中的slug，绝不依赖自增ID范围或宽泛LIKE。ES批次count使用完整slug正则+固定6位后缀，避免批次名互为前缀时误计数。导入自动生成ID，不假定auto_increment连续；恢复通过slug回查。

## 前提

Node22+（需要util.parseEnv，本机已验证24.15.0）、mysql客户端（本机8.0.45）。无npm/Python依赖。复用mysql持久连接，以stdin传SQL，不经shell；数据用UTF-8十六进制字面量，密码仅放子进程环境，报告不输出SQL或密码。

数据库已经包含项目所需迁移、表为InnoDB。运行中的Paicoding确实连接该库、rabbitmq.switchFlag=true、Outbox publisher及搜索consumer正常，ES索引/别名与config一致、Mapping由项目已有实现创建。工具不负责启动整站或套用生产配置。不运行Liquibase到既有业务库，不调用重建API。

工具的MySQL会话用UTC。测试应用读取timestamp和序列化Date也应与UTC一致（JDBC serverTimezone及Jackson时区）；工具支持ES epoch/ISO时间，若ES输出无时区时间按UTC核对。不同设置导致日期不一致应报告失败，不能跳过日期断言。**不要为本次导入修改正在服务的业务实例配置**；必要时将该项留作环境问题，或使用明确配置的测试实例。

## 执行顺序（项目根目录，PowerShell）

1. 先离线生成，可重复到不同新目录比较manifest.sha256：

```powershell
node benchmarks/scripts/articles.mjs generate --config benchmarks/config/articles.local.json --data benchmarks/data/search-10k-v1
node --test benchmarks/scripts/articles.test.mjs
# 保留离线原始TAP和JSON报告：
node benchmarks/scripts/check-articles.mjs benchmarks/results/article-selftests-01
```

2. 密码通过当前进程环境注入，不写配置或命令行。可使用交互输入：

```powershell
$benchSecret = Read-Host 'Local MySQL password' -AsSecureString
$env:PAI_BENCH_MYSQL_PASSWORD = [System.Net.NetworkCredential]::new('', $benchSecret).Password
```

配置文件可改用户名，不能写密码。用完关闭该测试shell或移除该变量。ES需要API key时同样注入PAI_BENCH_ES_API_KEY；不在报告显示值。

本次用户明确授权复用根目录 `.env`。在线命令加 `--env-file .env` 即可：工具只提取PAICODING_DB_PASSWORD（或配置的密码变量）和可选ES API key，不加载AI模型密钥、不复制文件内容。下方所有在线命令在本机运行时加此参数；也可继续使用进程环境方式。

3. 创建本批次17个合成引用用户（作者1+点赞用户16）及5个分类，并输出非敏感ready配置。仅追加带批次标记的user/user_info/category；已有同名但标识不匹配则失败，不复用真实用户。重复运行可恢复已建引用数据：

```powershell
node benchmarks/scripts/articles.mjs fixtures --config benchmarks/config/articles.local.json --env-file .env --report benchmarks/results/fixtures-10k-01
```

检查summary.json的PASS后使用该目录config.ready.json。命令失败则不要继续。此前生成数据不含数据库ID，不需要重生成。

现有user_info迁移后有无默认值的JSON字段ip，工具检测该列并写空JSON对象，不填真实IP。引用数据事务失败会回滚；恢复时检查稳定的批次标识。

4. 批处理MySQL导入、现有Outbox同步、完整检查：

```powershell
node benchmarks/scripts/articles.mjs import --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/import-10k-01
```

每事务默认200篇，批量写入临时staging表，再INSERT SELECT写article/detail/read_count/user_foot/outbox，一次COMMIT；临时表仅当前连接可见，不修改永久schema。数据库级GET_LOCK防止本工具并发导入/清理；不阻塞业务代码。用户选择现有库意味着搜索结果/统计会包含测试文章，须安排合适测试窗口。

每批提交保存batches.jsonl。中断后同配置/数据重跑import，已提交批次必须逐字段完全一致才跳过；未提交批次自动回滚。不使用INSERT IGNORE吞掉文章或关联表错误。新的报告目录不可覆盖。数据库导入成功但ES同步失败时保留文章及原始记录供排查，不能自动删除数据掩盖失败。

5. 单独检查或修复本批次同步（修复仅重发领域事件）：

```powershell
node benchmarks/scripts/articles.mjs verify --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/verify-10k-01
node benchmarks/scripts/articles.mjs sync --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/sync-10k-01
```

默认同步等待上限1800s，可调整syncTimeoutSeconds（最多7200）；持续看不到本批次文档可能是consumer未运行、索引配置不一致或写入异常，不应放宽断言。新文档默认ES refresh产生可搜索延迟，工具不强制refresh现有索引。

6. 按批次清理：

```powershell
node benchmarks/scripts/cleanup-articles.mjs --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/cleanup-10k-01
```

先逐批设置仅本批次article.deleted=1/status=0/update_time=NOW，并原子追加ARTICLE_DELETE；等待本批次ES可搜索数量0且逐ID不存在，再物理删除这些测试文章关联的正文、read_count与user_foot。**保留article墓碑、Outbox/consume审计行及合成用户/分类**，报告明确记录保留项；这是可重试的逻辑清理，不是全库物理擦除。恢复同batch导入会失败（墓碑不当成已导入成功），新一轮用新batch并保存原seed。永久清理墓碑需要消费全部排空和独立维护方案，本工具不执行。

清理失败可重复执行；没导完的批次允许清理已存在部分。不会删除索引、别名、队列或其他文章，不删共享用户/分类；不会清Redis数据库。测试搜索热词缓存和删除事件带来的合成作者统计缓存可能继续存留到业务TTL/恢复逻辑处理，不能声称清理了全部缓存副作用。

## 一致性与性能记录

每次输出summary.json、preflight.json（版本、表引擎、Mapping/Settings）、batches.jsonl、sync-polls.jsonl、consistency.jsonl（逐篇ID及判定）。参数和数据hash关联同一批次。

验证DB批次数/在线数和ES该批次文档数；逐篇核对slug、title/shortTitle/summary、正文SHA-256、分类/作者(DB)、发布状态、日期、推荐标记、阅读量、点赞量和qualityScore。数据库正文版本必须恰一份。数量一样但内容不符也失败。非批次article的数量及头字段CRC校验前后必须一致；这只是额外变化检测，不替代备份，也不是所有关联表的强一致性证明。并发业务编辑可导致该检测失败，应在结果中注明。

当前项目Long序列化器会使ES `_source` 中readCount/praiseCount/qualityScore为十进制字符串，尽管Mapping类型为long。校验器按相同十进制值比较，不把字符串误判为内容差异；null/错误数值仍失败。首次冒烟误判已保留，未修改业务序列化器。

指标：articleTotal为计划规模、importedThisRun为本轮新提交数、mysqlImportSeconds为批事务执行总耗时（包括关联数据和Outbox）、importArticlesPerSecond=新提交篇数/该耗时。恢复轮无新增时吞吐为null。totalSeconds另含预检、校验等总时间。

esSyncSeconds从SQL阶段结束到本批次ES数量收敛及完整验证完成，**是尾部同步与核验时间**；消费与导入并行，不能当作独立ES Bulk压测耗时。保存firstCommitAt、逐批committedAt和所有poll时间以追踪重叠。indexDocumentCount是本批次文档数，不是整个既有索引的文档总量。计数/耗时只有在线命令实跑成功才写数值；缺环境值为null/N/A。

没有完整在线执行证据时，工具单测通过不能写成“10万篇成功索引”。合成数据的效果与真实生产语料不同，后续相关性实验需独立人工标注。
