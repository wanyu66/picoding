package com.github.paicoding.forum.service.article.service;

import com.github.paicoding.forum.api.model.enums.OperateArticleEnum;
import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.article.AiSlugGenerateReq;
import com.github.paicoding.forum.api.model.vo.article.ArticlePostReq;
import com.github.paicoding.forum.api.model.vo.article.ArticleUrlSlugReq;
import com.github.paicoding.forum.api.model.vo.article.SearchArticleReq;
import com.github.paicoding.forum.api.model.vo.article.dto.ArticleAdminDTO;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchConsistencyReport;
import com.github.paicoding.forum.service.article.service.search.ArticleSearchRebuildTask;

/**
 * 文章后台接口
 *
 * @author louzai
 * @date 2022-09-19
 */
public interface ArticleSettingService {

    /**
     * 更新文章
     *
     * @param req
     */
    void updateArticle(ArticlePostReq req);

    /**
     * AI生成文章语义URL
     *
     * @param req
     * @return
     */
    String generateUrlSlug(AiSlugGenerateReq req);

    /**
     * 更新文章URL Slug
     *
     * @param req
     * @return 最终保存的URL Slug
     */
    String updateArticleUrlSlug(ArticleUrlSlugReq req);

    /**
     * 获取文章列表
     *
     * @param req
     * @return
     */
    PageVo<ArticleAdminDTO> getArticleList(SearchArticleReq req);

    /**
     * 重建文章全文搜索索引
     */
    ArticleSearchRebuildTask rebuildArticleSearchIndex();

    ArticleSearchRebuildTask getArticleSearchRebuildTask(String taskId);

    ArticleSearchRebuildTask retryArticleSearchRebuildTask(String taskId);

    ArticleSearchRebuildTask abortArticleSearchRebuildTask(String taskId);

    ArticleSearchConsistencyReport verifyArticleSearchConsistency();

    /**
     * 删除文章
     *
     * @param articleId
     */
    void deleteArticle(Long articleId);

    /**
     * 操作文章
     *
     * @param articleId
     * @param operate
     */
    void operateArticle(Long articleId, OperateArticleEnum operate);
}
