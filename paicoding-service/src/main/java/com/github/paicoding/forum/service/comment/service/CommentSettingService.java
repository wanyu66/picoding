package com.github.paicoding.forum.service.comment.service;

import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.comment.CommentSaveReq;
import com.github.paicoding.forum.api.model.vo.comment.SearchCommentReq;
import com.github.paicoding.forum.api.model.vo.comment.dto.CommentAdminDTO;

public interface CommentSettingService {

    /** 分页查询后台评论列表。 */
    PageVo<CommentAdminDTO> getCommentList(SearchCommentReq req);

    /** 获取评论详情。 */
    CommentAdminDTO getCommentDetail(Long commentId);

    /** 保存评论。 */
    Long saveComment(CommentSaveReq req, Long operateUserId);

    /** 删除评论。 */
    void deleteComment(Long commentId);
}
