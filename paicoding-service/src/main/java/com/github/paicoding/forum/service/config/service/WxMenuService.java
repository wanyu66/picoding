package com.github.paicoding.forum.service.config.service;

import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuDetailDTO;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuPreviewAiReq;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuPreviewAiResDTO;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuPreviewMatchReq;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuPreviewMatchResDTO;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuReplyDTO;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuPublishReq;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuPublishResDTO;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuSaveReq;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuValidateReq;
import com.github.paicoding.forum.api.model.vo.wx.menu.WxMenuValidateResDTO;

/**
 * 微信菜单管理
 *
 * @author Codex
 * @date 2026/3/23
 */
public interface WxMenuService {
    /** 获取微信菜单草稿详情。 */
    WxMenuDetailDTO getDetail();

    /** 保存微信菜单草稿。 */
    void saveDraft(WxMenuSaveReq req);

    /** 校验微信菜单配置。 */
    WxMenuValidateResDTO validate(WxMenuValidateReq req);

    /** 发布微信菜单。 */
    WxMenuPublishResDTO publish(WxMenuPublishReq req);

    /** 将远端微信菜单同步为本地草稿。 */
    WxMenuDetailDTO syncRemoteToDraft();

    /** 获取关注事件回复配置。 */
    WxMenuReplyDTO getSubscribeReply();

    /** 获取默认回复配置。 */
    WxMenuReplyDTO getDefaultReply();

    /** 匹配关键词回复配置。 */
    WxMenuReplyDTO matchKeywordReply(String eventType, String eventKey, String content);

    /** 获取消息兜底策略。 */
    String getMessageFallbackStrategy();

    /** 获取 AI 回复开关。 */
    Boolean getAiEnable();

    /** 获取 AI 回复提示词。 */
    String getAiPrompt();

    /** 获取 AI 服务提供方。 */
    String getAiProvider();

    /** 获取菜单点击事件回复。 */
    WxMenuReplyDTO getClickReply(String eventKey);

    /** 预览消息匹配结果。 */
    WxMenuPreviewMatchResDTO previewMatch(WxMenuPreviewMatchReq req);

    /** 预览 AI 回复结果。 */
    WxMenuPreviewAiResDTO previewAi(WxMenuPreviewAiReq req);
}
