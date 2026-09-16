package com.github.paicoding.forum.service.notify.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.notify.dto.NotifyMsgDTO;
import com.github.paicoding.forum.service.notify.repository.entity.NotifyMsgDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * @author YiHui
 * @date 2022/9/3
 */
public interface NotifyMsgMapper extends BaseMapper<NotifyMsgDO> {

    @Insert("INSERT IGNORE INTO notify_msg(event_id,business_key,related_id,comment_id,notify_user_id,operate_user_id,msg,type,state,create_time,update_time) " +
            "VALUES(#{eventId},#{businessKey},#{relatedId},#{commentId},#{notifyUserId},#{operateUserId},#{msg},#{type},#{state},NOW(),NOW())")
    int insertIgnore(NotifyMsgDO msg);

    @Delete("DELETE FROM notify_msg WHERE business_key=#{businessKey}")
    int deleteByBusinessKey(@Param("businessKey") String businessKey);

    /**
     * 查询文章相关的通知列表
     *
     * @param userId
     * @param type
     * @param page   分页
     * @return
     */
    List<NotifyMsgDTO> listArticleRelatedNotices(@Param("userId") long userId, @Param("type") int type, @Param("pageParam") PageParam page);

    /**
     * 查询关注、系统等没有关联id的通知列表
     *
     * @param userId
     * @param type
     * @param page   分页
     * @return
     */
    List<NotifyMsgDTO> listNormalNotices(@Param("userId") long userId, @Param("type") int type, @Param("pageParam") PageParam page);

    /**
     * 标记消息为已阅读
     *
     * @param ids
     */
    void updateNoticeRead(@Param("ids") List<Long> ids);
}
