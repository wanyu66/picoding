# 搜索排序离线评测

目前已建立 **64 条查询、8 篇真实文章、512 对候选**的小语料试评集，并完成三个版本的 192 次 ES 查询。**人工相关性尚未标注，不能据此得出质量提升结论。**

入口：[本地人工盲标页](datasets/local-2026-09-23/review.html)；[快照清单](datasets/local-2026-09-23/manifest.json)。用浏览器打开 HTML 即可，无需服务或登录。旧模板移到 `examples/`，不作为评测证据；根目录 `judgments.jsonl` 已替换成真实文章 ID 的待标注表，分数全部为 `null`，不能当作 0 分。

## 数据与实验边界

查询包括中文 20、拼音 12、错拼 12、同义词 12、无答案候选 8。查询由助手依据本地文章内容编写，**并非真实用户搜索日志**，应由人工审查意图与代表性；“无答案候选”也必须经过人工确认。共有 34 个意图族，同族的拼写／语言变体一起划入 dev 或 test，当前为 38／26 条，避免变体跨集合泄漏。不得根据 test 标签调参。

真实文章 ID：`1、14、15、16、17、100、101、102`。仅有 8 篇文章，主题集中于技术派和分布式，不能代表完整社区；重复表达同一意图也不等于增加独立用户样本。本次对每个查询提供全部 8 篇文章，避免仅标注某一版本召回结果的偏差。

使用 ES point-in-time（PIT）固定同一批文档，在同一 PIT 内导出正文、热度、日期并运行三个版本。时间衰减 origin 固定为采集时间，过滤条件均为 `deleted=0,status=1`，并统一用文章 ID 打破同分。保存语料、查询、运行结果 SHA-256，以及请求 DSL、mapping 和 settings。

| 版本 | 定义 |
| --- | --- |
| A `a_fulltext` | 四个文本字段等权 `multi_match`，无拼音分支、模糊匹配或热度加分。 |
| B `b_recall` | 使用当前代码的标题／短标题／摘要／正文 8:5:2:1 权重及短语、前缀、模糊、拼音、包含匹配。 |
| C `c_heat_decay` | 在 B 上增加阅读／点赞热度和固定时刻的时间衰减。 |

三个版本共享现有中文和同义词分析器，因此同义词能力不是 B 独有的新增项。C 为隔离本次指定变量，**未加入生产代码中的 `qualityScore` 加分**；这是一组可解释的离线消融实验，不冒充线上三个已部署历史版本。DSL 入口为 `search-variants.js`，与生产方法的对应关系见代码注释。

采集期间在线接口曾报告 `MYSQL_TITLE_FALLBACK`。本实验直接读真实 ES，未调用标题回退或改写线上索引、别名、配置。此前的 681 次搜索成功和 3 个关键词顺序稳定不计入相关性证据。

## 人工标注

1. 打开盲标页，填写姓名或稳定代号。候选隐藏检索版本、名次、分数、热度和日期，顺序按查询打散。
2. 根据查询意图阅读文章，逐篇标注：**0 无关、1 仅提及／弱相关、2 部分满足、3 直接且充分满足**。折叠区提供完整正文，不会加载外部图片。
3. 选择语料是否有答案：至少一篇 2/3 分为“有答案”；全部为 0/1 分才可选“无答案”。确认检查全部候选，记录判断依据，再点击完成本查询。
4. 定期导出 `judgments.reviewed.jsonl`。浏览器草稿只在本机保留，导出也允许未完成草稿；但评测器会拒绝未完成标注。可导入相同快照的草稿继续。
5. 建议第二位标注者独立复核至少 20% 查询和所有无答案查询，分歧讨论后保留最终标注者及说明。当前工具不把两份分歧标注自动合并为共识。

人工标注是当前实际未完成的步骤。模型评分、UI 自动化中的合成分数均不能代替人工标签。当前所有真实候选保持 `null`，未伪造标注者或复核时间。

## 标注后比较

在仓库根目录执行（Node.js 18+）：

```powershell
node scripts/search-evaluation/compare-search-variants.js scripts/search-evaluation/datasets/local-2026-09-23 'D:/path/to/judgments.reviewed.jsonl' 10
# 完整 JSON 可在成功运行后保存到报告文件；请检查进程退出码。
```

输出整体、分类型、dev/test 的 `NDCG@K`、`MRR@K`、已标注范围 `Recall@K`、零结果率、有答案查询的空结果率、无答案查询误召回率，以及 B−A、C−B 的配对差异。置信区间按意图族进行固定种子的 2,000 次 bootstrap，不将同族变体视作独立样本。小语料上的区间不等价于生产效果显著提升。

口径：NDCG 使用 0..3 分的分级增益；MRR／Recall 将 2/3 分视为有效答案，MRR 截止到 K。无答案查询不以 NDCG=0 混入有答案均值，单独计算有返回结果的比例；分母为空时输出 `null`。离线无真实曝光／点击窗口，**CTR 输出 `null`**。本次为全语料标注，Recall 分母可覆盖整份快照；大语料候选池仅能报告池内 Recall。

会拒绝：待标注或非人工复核、空等级、非法分数、重复查询／结果 ID、遗漏或额外查询、未知文章、错误的答案状态、失败运行、混用／改动快照。缺失结果不等同于“检索为空”，未标注文章不等同于不相关。

单版本评估：

```powershell
node scripts/search-evaluation/evaluate-search-ranking.js 'D:/path/to/judgments.reviewed.jsonl' scripts/search-evaluation/datasets/local-2026-09-23/a_fulltext.jsonl 10
```

## 新语料采集与复现

```powershell
# 默认 ES_URL=http://127.0.0.1:9200，ES_INDEX=paicoding_article。
# 需要时设置 ES_URL / ES_INDEX / ES_API_KEY 环境变量。
node scripts/search-evaluation/collect-search-evaluation.js scripts/search-evaluation/datasets/new-snapshot
```

输出目录必须不存在，防止覆盖固定实验。重采会生成新快照，旧标签不能直接混用。无需重采即可用已冻结的三份 run 比较同一套人工标签。语料超过 100 篇时使用三个版本 Top20 的并集；此时标注页禁止仅凭候选池确认全语料无答案，需补充全语料复核。扩展语料后应重新审查查询集，加入真实流量查询和长尾需求。

保留文件：`corpus.jsonl`、`queries.jsonl`、`judgments.pending.jsonl`、三个版本的 `.jsonl`、`requests.jsonl`、mapping/settings、`manifest.json`、`review.html`。manifest 中 `judgmentsComplete=false` 描述采集时状态，最终评测结果以新导入标签及其哈希为准，不修改原始采集证据。

回归测试：`node scripts/search-evaluation/test-search-ranking.js`。浏览器交互测试：安装或配置 Playwright 后执行 `node scripts/search-evaluation/test-review-page.js`；默认使用本机 Edge，可用 `BROWSER_CHANNEL` 切换已安装浏览器。浏览器测试只在临时上下文使用合成评分，结束即丢弃，不写入真实标签。
