export const reviewNeeds={
  exact:'定位查询所指的完整标题和编号。仅有相同技术主题不等于匹配该特定文章。',
  body:'寻找正文包含 validate recovery、涉及恢复或数据一致性验证的内容，不能只看标题。',
  pinyin:'用户用 bingfabiancheng 输入“并发编程”，判断文章是否满足并发编程主题；不要仅因拼音字面命中打高分。',
  typo:'用户将 elasticsearch 拼成 elastcisearch，寻找 Elasticsearch 相关技术内容；不能仅因正文出现错词就判定相关。',
  hot:'寻找 Redis 主题中热度较高的文章。先判断主题相关性，再参考候选中的阅读/点赞元数据；热度高不能替代主题相关。',
  recent:'寻找较新的 Spring 文章。按固定参考日期2026-01-01判断新旧，在满足主题的前提下考虑发布时间。',
  zero:'判断 zz zx 类无意义测试字符串 zzzxnonexistentbenchmark 是否存在真正相关的文章；零结果不是自动的人审结论。'
};
