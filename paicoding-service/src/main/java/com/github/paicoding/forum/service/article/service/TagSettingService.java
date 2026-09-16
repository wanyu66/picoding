package com.github.paicoding.forum.service.article.service;

import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.article.SearchTagReq;
import com.github.paicoding.forum.api.model.vo.article.TagReq;
import com.github.paicoding.forum.api.model.vo.article.dto.TagDTO;

/**
 * 标签后台接口
 *
 * @author louzai
 * @date 2022-09-17
 */
public interface TagSettingService {

    /** 保存标签。 */
    void saveTag(TagReq tagReq);

    /** 删除标签。 */
    void deleteTag(Integer tagId);

    /** 更新标签的运营状态。 */
    void operateTag(Integer tagId, Integer pushStatus);

    /**
     * 获取tag列表
     *
     * @param pageParam
     * @return
     */
    PageVo<TagDTO> getTagList(SearchTagReq req);

    /** 根据编号获取标签。 */
    TagDTO getTagById(Long tagId);
}
