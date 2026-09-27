# 相关性报告

7条合成查询，覆盖精确标题/正文/拼音/错词/热门意图/新发布意图/无结果；A/B各Top20池化候选。查询不是人工流量日志，相关性评分必须由人完成。

10000篇：7条查询、211个候选对。**人工标签待完成，MRR@10/NDCG@10/judged Recall@10 = N/A，收益 = N/A。**

[人工评分页面](run-01/relevance-10000/review.html)，原始 A.jsonl/B.jsonl、requests.jsonl、candidates.jsonl、judgments.pending.jsonl 均保留。

50000篇：7条查询、217个候选对。**人工标签待完成，MRR@10/NDCG@10/judged Recall@10 = N/A，收益 = N/A。**

[人工评分页面](run-01/relevance-50000/review.html)，原始 A.jsonl/B.jsonl、requests.jsonl、candidates.jsonl、judgments.pending.jsonl 均保留。

100000篇：7条查询、215个候选对。**人工标签待完成，MRR@10/NDCG@10/judged Recall@10 = N/A，收益 = N/A。**

[人工评分页面](run-01/relevance-100000/review.html)，原始 A.jsonl/B.jsonl、requests.jsonl、candidates.jsonl、judgments.pending.jsonl 均保留。

用户已选择本轮指标标 N/A、保留标注入口；不代填人工评分。0..3评分，>=2相关；仅对已判定可回答查询算MRR/NDCG。池化候选没有穷举语料，不输出全量Recall；无结果不能自动等于不相关。固定原点用于“新发布”意图。拼音/错词语料包含字面词，普通命中数不能代替召回能力验证。

|功能探针|原文含查询字面值|基础检索命中|隔离真实分支命中|结果|
|---|---|---|---|---|
|pinyin|false|false|true|PASS|
|fuzzy|false|false|true|PASS|

功能探针用真实DSL中的pinyin/fuzzy分支在指定不含字面查询词的文章上 _explain；仅证明功能路径，不是人工相关性分数。原始 source/DSL/explanation 见 recall-probes-10000.json。

通过 es-report.mjs relevance <候选目录> <真实人工评分文件> 计算，复用现有严格人审校验，不自动填标签。扩大真实查询集、盲审与多标注者一致性后再谈普遍收益。
