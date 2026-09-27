package com.github.paicoding.forum.service.article.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.paicoding.forum.api.model.vo.article.dto.TagDTO;
import com.github.paicoding.forum.service.article.repository.entity.ArticleTagDO;
import com.github.paicoding.forum.service.article.repository.entity.ArticleTagDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Collection;

/**
 * 文章标签映mapper接口
 *
 * @author louzai
 * @date 2022-07-18
 */
public interface ArticleTagMapper extends BaseMapper<ArticleTagDO> {
    List<ArticleTagDetail> listArticleTagDetailsBatch(@Param("articleIds") Collection<Long> articleIds);

    /**
     * 查询文章标签
     *
     * @param articleId
     * @return
     */
    List<TagDTO> listArticleTagDetails(@Param("articleId") Long articleId);



}
