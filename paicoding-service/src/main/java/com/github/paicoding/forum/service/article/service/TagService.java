package com.github.paicoding.forum.service.article.service;

import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.article.dto.TagDTO;

/**
 * 标签Service
 *
 * @author louzai
 * @date 2022-07-20
 */
public interface TagService {

    /** 分页查询标签。 */
    PageVo<TagDTO> queryTags(String key, PageParam pageParam);

    /** 根据标签名查询标签编号。 */
    Long queryTagId(String tag);
}
