package com.github.paicoding.forum.web.front.chat.stomp;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.notify.service.NotifyService;
import com.github.paicoding.forum.service.user.service.LoginService;
import com.github.paicoding.forum.web.global.GlobalInitService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

public class AuthInChannelInterceptor implements ChannelInterceptor {
    private final WsConnectionRegistry connections;
    private final GlobalInitService globalInitService;
    private final WsChatProperties properties;

    public AuthInChannelInterceptor(WsConnectionRegistry connections, GlobalInitService globalInitService,
                                    WsChatProperties properties) {
        this.connections = connections;
        this.globalInitService = globalInitService;
        this.properties = properties;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            if (!(accessor.getUser() instanceof ReqInfoContext.ReqInfo)) throw new MessageDeliveryException("WebSocket authentication required");
            ReqInfoContext.ReqInfo user = (ReqInfoContext.ReqInfo) accessor.getUser();
            if (!connections.connect(user.getUserId(), accessor.getSessionId())) {
                throw new MessageDeliveryException("WebSocket connection limit exceeded");
            }
            return message;
        }
        if (command == StompCommand.DISCONNECT) {
            if (accessor.getUser() instanceof ReqInfoContext.ReqInfo) {
                connections.disconnect(((ReqInfoContext.ReqInfo) accessor.getUser()).getUserId(), accessor.getSessionId());
            }
            return message;
        }
        if (!(accessor.getUser() instanceof ReqInfoContext.ReqInfo)) throw new MessageDeliveryException("WebSocket authentication expired");
        if (command == StompCommand.SEND) renewAuthentication(accessor, (ReqInfoContext.ReqInfo) accessor.getUser());
        return message;
    }

    private void renewAuthentication(StompHeaderAccessor accessor, ReqInfoContext.ReqInfo current) {
        Object value = accessor.getSessionAttributes().get(AuthHandshakeInterceptor.AUTHENTICATED_AT);
        long authenticatedAt = value instanceof Number ? ((Number) value).longValue() : 0L;
        boolean requested = "true".equalsIgnoreCase(accessor.getFirstNativeHeader("x-auth-renew"));
        if (!requested && System.currentTimeMillis() - authenticatedAt < properties.getAuthMaxAgeSeconds() * 1000L) return;
        ReqInfoContext.ReqInfo refreshed = new ReqInfoContext.ReqInfo();
        globalInitService.initLoginUser(current.getSession(), refreshed);
        if (refreshed.getUser() == null) throw new MessageDeliveryException("WebSocket authentication expired");
        accessor.getSessionAttributes().put(LoginService.SESSION_KEY, refreshed);
        accessor.getSessionAttributes().put(AuthHandshakeInterceptor.AUTHENTICATED_AT, System.currentTimeMillis());
    }

    @Override
    public void postSend(Message<?> message, MessageChannel channel, boolean sent) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();
        if (accessor.getUser() == null || StringUtils.isBlank(destination) || accessor.getCommand() == null) return;
        if (destination.startsWith("/msg") || destination.startsWith("/user/msg")) {
            SpringUtil.getBean(NotifyService.class).notifyChannelMaintain(accessor);
        }
    }
}
