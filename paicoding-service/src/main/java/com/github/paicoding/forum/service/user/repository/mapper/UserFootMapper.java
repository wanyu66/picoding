package com.github.paicoding.forum.service.user.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.paicoding.forum.api.model.vo.PageParam;
import com.github.paicoding.forum.api.model.vo.user.dto.ArticleFootCountDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.SimpleUserInfoDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.UserFootStatisticDTO;
import com.github.paicoding.forum.service.user.repository.entity.UserFootDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户足迹mapper接口
 *
 * @author louzai
 * @date 2022-07-18
 */
public interface UserFootMapper extends BaseMapper<UserFootDO> {

    /** Create the neutral state or lock the existing unique user/document row. */
    @Insert("INSERT INTO user_foot(user_id,document_id,document_type,document_user_id) " +
            "VALUES(#{userId},#{documentId},#{documentType},#{authorId}) ON DUPLICATE KEY UPDATE id=id")
    void ensureFootForUpdate(@Param("userId") Long userId, @Param("documentId") Long documentId,
                             @Param("documentType") Integer documentType, @Param("authorId") Long authorId);

    /**
     * 查询文章计数信息
     *
     * @param articleId
     * @return
     */
    ArticleFootCountDTO countArticleByArticleId(@Param("articleId") Long articleId);

    /**
     * 查询作者的文章统计
     *
     * @param author
     * @return
     */
    ArticleFootCountDTO countArticleByUserId(@Param("userId") Long author);

    /**
     * 查询作者的所有文章阅读计数
     *
     * @param author
     * @return
     */
    Integer countArticleReadsByUserId(@Param("userId") Long author);

    /**
     * 查询用户收藏的文章列表
     *
     * @param userId
     * @param pageParam
     * @return
     */
    List<Long> listCollectedArticlesByUserId(@Param("userId") Long userId, @Param("pageParam") PageParam pageParam);


    /**
     * 查询用户阅读的文章列表
     *
     * @param userId
     * @param pageParam
     * @return
     */
    List<Long> listReadArticleByUserId(@Param("userId") Long userId, @Param("pageParam") PageParam pageParam);

    /**
     * 查询用户在指定教程中的已读文章列表，按最近阅读倒序
     *
     * @param userId
     * @param columnId
     * @return
     */
    List<Long> listReadArticleByUserIdAndColumnId(@Param("userId") Long userId, @Param("columnId") Long columnId);

    /**
     * 查询文章的点赞列表
     *
     * @param documentId
     * @param type
     * @param size
     * @return
     */
    List<SimpleUserInfoDTO> listSimpleUserInfosByArticleId(@Param("documentId") Long documentId,
                                                           @Param("type") Integer type,
                                                           @Param("size") int size);


    UserFootStatisticDTO getFootCount();
}
