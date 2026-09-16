package com.github.paicoding.forum.service.statistics.service;

import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.statistics.dto.StatisticsDayDTO;
import com.github.paicoding.forum.service.statistics.repository.entity.RequestCountDO;

import java.util.List;

/**
 * 微信搜索「沉默王二」，回复 Java
 *
 * @author 沉默王二
 * @date 5/24/23
 */
public interface RequestCountService {
    /** 根据访问来源查询请求统计。 */
    RequestCountDO getRequestCount(String host);

    /** 新增访问来源的请求统计。 */
    void insert(String host);

    /** 增加指定记录的请求次数。 */
    void incrementCount(Long id);

    /** 获取累计页面访问量。 */
    Long getPvTotalCount();

    /** 获取指定天数内的 PV、UV 统计。 */
    List<StatisticsDayDTO> getPvUvDayList(Integer day);

    /** 获取请求统计记录总数。 */
    long count();

    /** 分页查询请求统计记录。 */
    List<RequestCountDO> listRequestCount(PageParam pageParam);
}
