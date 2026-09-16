# 搜索排序离线评测

`judgments.jsonl` 是人工标注集：每行一个查询，相关性取 `0..3`。文章 ID 是模板值，上线前应替换为稳定的真实文章 ID，并至少覆盖高频词、长尾词、拼音、同义词、错拼和无答案查询。

`sample-run.jsonl` 是一次搜索实验的输出，每行保存实际排序后的文章 ID；`clicked` 来自同一实验窗口的点击日志。服务端 `/search/api/click` 记录点击，热门查询和空结果词分别保存在 Redis 的 `article_search:queries:hot` 与 `article_search:queries:zero`。

运行：

```bash
node scripts/search-evaluation/evaluate-search-ranking.js \
  scripts/search-evaluation/judgments.jsonl \
  scripts/search-evaluation/sample-run.jsonl 10
```

评测器回归测试：`node scripts/search-evaluation/test-search-ranking.js`。

输出包含 `NDCG@K`、`MRR`、零结果率和查询级点击率。发布排序改动前后使用同一份 judgments，对比指标而不是只验证文章能否被召回。
