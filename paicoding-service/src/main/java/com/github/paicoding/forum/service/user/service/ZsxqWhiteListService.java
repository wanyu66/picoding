package com.github.paicoding.forum.service.user.service;

import com.github.paicoding.forum.api.model.enums.user.UserAIStatEnum;
import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.user.SearchZsxqUserReq;
import com.github.paicoding.forum.api.model.vo.user.ZsxqUserPostReq;
import com.github.paicoding.forum.api.model.vo.user.dto.ZsxqUserInfoDTO;

import java.util.List;

/**
 * 微信搜索「沉默王二」，回复 Java
 *
 * @author 沉默王二
 * @date 6/29/23
 */
public interface ZsxqWhiteListService {
    /** 分页查询知识星球白名单。 */
    PageVo<ZsxqUserInfoDTO> getList(SearchZsxqUserReq req);

    /** 更新白名单记录状态。 */
    void operate(Long id, UserAIStatEnum operate);

    /** 更新知识星球白名单记录。 */
    void update(ZsxqUserPostReq req);

    /** 批量更新白名单记录状态。 */
    void batchOperate(List<Long> ids, UserAIStatEnum operate);

    /** 重置指定作者的白名单信息。 */
    void reset(Integer authorId);
}
