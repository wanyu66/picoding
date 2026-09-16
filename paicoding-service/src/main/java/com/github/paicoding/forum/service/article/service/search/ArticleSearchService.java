package com.github.paicoding.forum.service.article.service.search;

import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.service.article.repository.params.SearchArticleParams;

import java.util.List;

public interface ArticleSearchService {

    /** 判断文章搜索服务是否启用。 */
    boolean enabled();

    /** Current serving/degradation state without issuing a network request to ES. */
    ArticleSearchRuntimeStatus runtimeStatus();

    /** 搜索与提示词匹配的文章编号。 */
    ArticleSearchResult searchHintArticleIds(String keyword, int limit);

    /** 同步搜索提示词。 */
    void syncHintKeyword(String keyword, int limit);

    /** 分页搜索已上线文章编号。 */
    ArticleSearchResult searchOnlineArticleIds(String keyword, PageParam pageParam);

    /** Popular successful queries, ordered by request count. */
    List<String> hotQueries(int limit);

    /** Prefix-based query suggestions collected from successful searches. */
    List<String> suggestQueries(String prefix, int limit);

    /** Frequently queried terms which currently produce no result. */
    List<String> zeroResultQueries(int limit);

    /** Record a click so online CTR can be tracked independently of relevance labels. */
    void recordSearchClick(String keyword, Long articleId);

    /** 同步线上文章搜索关键词。 */
    void syncOnlineKeyword(String keyword, PageParam pageParam);

    /** 按后台筛选条件搜索文章编号。 */
    ArticleSearchResult searchAdminArticleIds(SearchArticleParams params);

    /** 同步后台文章搜索条件。 */
    void syncAdminKeyword(SearchArticleParams params);

    /** 启动文章搜索索引全量重建。 */
    ArticleSearchRebuildTask rebuildArticleIndex();

    /** 查询重建任务。 */
    ArticleSearchRebuildTask getRebuildTask(String taskId);

    /** 重试任务中的失败批次。 */
    ArticleSearchRebuildTask retryRebuildTask(String taskId);

    /** 请求中止正在执行的重建任务。 */
    ArticleSearchRebuildTask abortRebuildTask(String taskId);

    /** 立即执行一次 MySQL/ES 一致性校验。 */
    ArticleSearchConsistencyReport verifyConsistency();

    /** 定时执行一致性校验，供调度器通过 JDK 代理调用。 */
    void scheduledConsistencyCheck();

    /** 同步指定文章到搜索索引。 */
    void syncArticle(Long articleId);

    /** 从搜索索引中删除指定文章。 */
    void deleteArticle(Long articleId);
}
