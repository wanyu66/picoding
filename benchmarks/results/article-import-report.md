# 第二阶段：文章批量造数与一致性验证

本报告对应2026-09-24导入及2026-09-25复查。所有数据均为独立标识的合成数据；不含真实用户资料、密码或API Key。最终运行状态见各目录summary.json，逐篇结果见consistency.jsonl。机器可读汇总为 [article-import-summary.json](article-import-summary.json)，指标表为 [article-import-metrics.csv](article-import-metrics.csv)。

## 实现与环境

实际文件：`benchmarks/scripts/articles.mjs`、`cleanup-articles.mjs`、`articles.test.mjs`、`check-articles.mjs`、`scripts/articles/*.mjs`；`config/articles*.json`；`article-import.md`；生成数据与本目录原始报告。业务代码、配置、接口和表结构均未修改。

环境：Windows10.0.26100 x64、i7-14650HX/24逻辑CPU、16,944,848,896字节内存；Node24.15.0、MySQL8.0.45、ES8.10.0。用户授权复用本机既有项目库；实际库名为pai_coding，paicoding不存在。ES别名paicoding_article解析到paicoding_article_v20260916165124894。凭据来自用户指定的.env，只读提取数据库密码到子进程，不复制文件或输出密码。

生成seed=20260924，固定参考时刻2026-01-01 UTC；1万篇每类2000篇，正文240/1200/6000字符，覆盖六类关键词变体、不同日期/阅读量/点赞数。数据JSONL为45,529,826字节，SHA-256为 `7ea217234e795ecdf75e94ce6ec0ab4086f4f7e0bbb0cb9cf7de1763b4f47846`。生成原始文件在 `../data/search-10k-v1/`，大JSONL不默认进入git，可按配置重建。

## 测试及已完成结果

- 离线测试：`node benchmarks/scripts/check-articles.mjs benchmarks/results/article-selftests-01`，11项通过、0失败。实际验证了1万/5万/10万生成条数、边界、固定seed、批次标识、目标保护、SQL范围和错误字段拒绝。5万/10万**未进行在线导入**。
- 首次fixture初始化：`fixtures-smoke-01` 失败，现有user_info.ip为必填JSON。事务回滚；测试工具改为检测并写空JSON，不改表。
- 首次文章预检：`import-smoke-01` 失败，临时表列名reads是MySQL保留字。修正测试临时列名，无文章写入。
- 100篇导入：`import-smoke-02`，MySQL新增100，ES100；初次严格类型比较将ES Long字符串判为不匹配，整体状态FAIL保留。之后按项目实际Long序列化形式修正数值比较，没有更改期望值或业务逻辑。
- 100篇复查：`verify-smoke-01` PASS，逐篇100条、0差异，正文/日期/状态/热度均验证。
- 100篇清理：`cleanup-smoke-01` PASS，ES批次文档0，DB在线文章0，详情/阅读计数/足迹0。保留100条删除墓碑和事件审计记录、批次合成用户/分类。原有10,008篇文章数量和头字段CRC前后相同。

## 1万篇在线执行

命令：

```powershell
node benchmarks/scripts/articles.mjs generate --config benchmarks/config/articles.local.json --data benchmarks/data/search-10k-v1
node benchmarks/scripts/articles.mjs fixtures --config benchmarks/config/articles.local.json --env-file .env --report benchmarks/results/fixtures-10k-01
node benchmarks/scripts/articles.mjs import --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/import-10k-01
```

单个导入连接、每事务200篇，共50批。原始每批执行耗时和提交时间在import-10k-01/batches.jsonl；同步数量时间线在sync-polls.jsonl。MySQL已提交10,000篇，事务耗时总和9.7396264秒，吞吐1026.7334篇/秒（包括正文、关联热度、Outbox；不含预检和字段检查）。该数值不是发布接口QPS，也不是只写单表吞吐。

ES通过现有Outbox publisher与搜索consumer逐篇同步，和MySQL导入重叠。首次执行在1800秒同步等待窗口内超时，原始import-10k-01/summary.json保持FAIL。最后一条观测为2026-09-24T12:02:29.292Z，ES文档9,242；当时距离最后一批SQL提交已过1,799.353秒，仍未收敛。早期消息积压快照与指标保存在outbox-snapshot-early.json、metrics-early.json；SENT明显多于ES可见文档，说明发布确认不等于索引完成。

2026-09-25继续任务后执行只读完整复查：

```powershell
node benchmarks/scripts/articles.mjs verify --config benchmarks/results/fixtures-10k-01/config.ready.json --env-file .env --data benchmarks/data/search-10k-v1 --report benchmarks/results/verify-10k-01
```

结果 [verify-10k-01/summary.json](verify-10k-01/summary.json) 为PASS，逐篇原始判定见 [consistency.jsonl](verify-10k-01/consistency.jsonl)。没有重新导入或重发事件。

| 指标 | 实测结果 |
|---|---:|
| 本批次MySQL在线文章 | 10,000 |
| 本批次ES文档 | 10,000 |
| 逐篇检查 / 缺失 / 差异 | 10,000 / 0 / 0 |
| MySQL批事务总耗时 | 9.7396264秒 |
| MySQL导入吞吐 | 1026.7334篇/秒 |
| ES精确同步完成耗时 | N/A；停止观测前尚未完成 |
| 已观测同步时间下界（自最后SQL提交） | 大于1,799.353秒 |
| 次日完整复查耗时 | 7.9025秒（不是ES同步耗时） |

最终结论：**最终一致性通过，首次30分钟时限验收失败**。不将次日复查时间充当同步完成时间，不把失败轮次改为PASS。上次导入前和本次复查后的非本批次文章数量均为10,108（包括100条已清理的冒烟墓碑），头字段CRC均为2066887287；额外检查未发现这些头字段发生变化，不声称对所有关联表或生产数据完成了审计。

保留1万篇在线测试文章及ES文档供下一阶段使用；100篇冒烟数据已逻辑清理，保留项见上文。未来清理这1万篇请按操作文档调用cleanup，使用新报告目录；本次没有主动删除用于后续检索测试的数据。

## 剩余问题与下一阶段

- 2026-09-25复查时应用仍为MYSQL_TITLE_FALLBACK（[原始状态](verify-10k-01/search-status.json)），原有索引覆盖不完整；本工具只同步本批次，不重建或更改原有文档。当前可对本批次进行直接ES检索实验；HTTP搜索性能测试须先确认ES_NORMAL，不能把MySQL回退称为ES性能。
- 清理采用墓碑而非物理删除article，原因是生产ES删除使用数据库版本；合成用户/分类和审计行明确保留。全文/计数/足迹及ES文档已通过100篇清理验证。
- 无性能对照实验，本阶段只报告实测绝对值，不宣称性能提升。单次导入不能作为稳定容量上限。
- 下一阶段使用保留的1万篇数据与冻结查询进行搜索实验；先解决覆盖状态与人工标注，再决定是否执行5万/10万规模。
