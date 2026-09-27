package com.github.paicoding.forum.service.sensitive.service.impl;

import com.github.paicoding.forum.api.model.vo.user.dto.BaseUserInfoDTO;
import com.github.paicoding.forum.core.permission.UserRole;
import com.github.paicoding.forum.service.chatai.bot.AiBots;
import com.github.paicoding.forum.service.sensitive.service.SensitiveBypassService;
import com.github.paicoding.forum.service.user.service.AuthorWhiteListService;
import com.github.paicoding.forum.service.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 敏感词处理豁免服务实现
 *
 * @author Codex
 * @date 2026/3/25
 */
@Service
public class SensitiveBypassServiceImpl implements SensitiveBypassService {

    @Autowired
    private AuthorWhiteListService authorWhiteListService;

    @Autowired
    private AiBots aiBots;

    @Autowired
    private UserService userService;

    @Override
    public Set<Long> bypassAuthors(Map<Long, BaseUserInfoDTO> authors) {
        Set<Long> bypass = new HashSet<>();
        Set<Long> candidates = new LinkedHashSet<>();
        authors.forEach((id, user) -> {
            if (id != null) {
                if (aiBots.aiBots(id)) bypass.add(id);
                else candidates.add(id);
            }
        });
        bypass.addAll(authorWhiteListService.authorsInArticleWhiteList(candidates));
        candidates.forEach(id -> {
            BaseUserInfoDTO user = authors.get(id);
            if (user != null && UserRole.hasAdminPermission(user.getRole())) bypass.add(id);
        });
        return bypass;
    }

    @Override
    public boolean shouldBypassByUserId(Long userId) {
        if (userId == null) {
            return false;
        }
        if (aiBots.aiBots(userId)) {
            return true;
        }
        if (authorWhiteListService.authorInArticleWhiteList(userId)) {
            return true;
        }
        BaseUserInfoDTO user = userService.queryBasicUserInfo(userId);
        return user != null && UserRole.hasAdminPermission(user.getRole());
    }
}
