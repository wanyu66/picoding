package com.github.paicoding.forum.service.user.service;

import com.github.paicoding.forum.api.model.vo.PageVo;
import com.github.paicoding.forum.api.model.vo.user.SearchUserLoginAuditReq;
import com.github.paicoding.forum.api.model.vo.user.SearchUserShareRiskReq;
import com.github.paicoding.forum.api.model.vo.user.SearchUserSessionReq;
import com.github.paicoding.forum.api.model.vo.user.dto.UserActiveSessionDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.UserLoginAuditDTO;
import com.github.paicoding.forum.api.model.vo.user.dto.UserShareRiskDTO;
import com.github.paicoding.forum.service.user.repository.entity.UserDO;
import com.github.paicoding.forum.service.user.service.audit.UserShareRiskPolicy.HandleAction;
import com.github.paicoding.forum.service.user.service.help.UserSessionHelper;

/**
 * 登录审计与会话巡检
 *
 * @author Codex
 * @date 2026/4/23
 */
public interface LoginAuditService {
    /** 记录登录成功事件。 */
    void recordLoginSuccess(UserSessionHelper.SessionDeviceMeta sessionMeta, String sessionHash, String riskTag);

    /** 记录登录失败事件。 */
    void recordLoginFail(String loginName, Integer loginType, String reason, UserSessionHelper.SessionDeviceMeta sessionMeta);

    /** 记录会话下线事件。 */
    void recordSessionOffline(String sessionHash, String reason, UserSessionHelper.SessionDeviceMeta sessionMeta, boolean kickout);

    /** 新增或更新活跃会话。 */
    void upsertActiveSession(UserSessionHelper.SessionDeviceMeta sessionMeta, String sessionHash);

    /** 刷新活跃会话信息。 */
    void touchActiveSession(String sessionHash, UserSessionHelper.SessionDeviceMeta sessionMeta);

    /** 记录账号封禁事件。 */
    void recordAccountForbid(UserDO user, String reason, HandleAction action);

    /** 记录账号解封事件。 */
    void recordAccountUnforbid(UserDO user, String reason, HandleAction action);

    /** 分页查询登录审计记录。 */
    PageVo<UserLoginAuditDTO> getLoginAuditPage(SearchUserLoginAuditReq req);

    /** 分页查询活跃会话。 */
    PageVo<UserActiveSessionDTO> getSessionPage(SearchUserSessionReq req);

    /** 分页查询账号共享风险记录。 */
    PageVo<UserShareRiskDTO> getShareRiskPage(SearchUserShareRiskReq req);

    /** 清空全部登录审计数据。 */
    int clearAllAuditData();
}
