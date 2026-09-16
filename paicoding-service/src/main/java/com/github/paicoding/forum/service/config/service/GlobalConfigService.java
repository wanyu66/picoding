package com.github.paicoding.forum.service.config.service;

import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.config.GlobalConfigReq;
import com.github.paicoding.forum.api.model.vo.config.SearchGlobalConfigReq;
import com.github.paicoding.forum.api.model.vo.config.dto.GlobalConfigDTO;

/**
 * 微信搜索「沉默王二」，回复 Java
 *
 * @author 沉默王二
 * @date 6/30/23
 */
public interface GlobalConfigService {
    /** 分页查询全局配置。 */
    PageVo<GlobalConfigDTO> getList(SearchGlobalConfigReq req);

    /** 保存全局配置。 */
    void save(GlobalConfigReq req);

    /** 删除全局配置。 */
    void delete(Long id);

    /**
     * 添加敏感词白名单
     *
     * @param word
     */
    void addSensitiveWhiteWord(String word);
}
