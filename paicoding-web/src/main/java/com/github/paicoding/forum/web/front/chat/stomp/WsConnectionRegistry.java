package com.github.paicoding.forum.web.front.chat.stomp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.core.cache.RedisClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class WsConnectionRegistry {
    private static final String DISTRIBUTED_CONNECTIONS = "chat:ws:connection-count";
    private final ConcurrentHashMap<Long, Set<String>> sessions = new ConcurrentHashMap<>();
    @Autowired private WsChatProperties properties;

    public boolean connect(Long userId, String sessionId) {
        if (userId == null || sessionId == null) return false;
        Set<String> userSessions = sessions.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet());
        synchronized (userSessions) {
            if (userSessions.contains(sessionId)) return true;
            if (userSessions.size() >= Math.max(1, properties.getMaxConnectionsPerUser())) return false;
            try {
                Long count = RedisClient.hIncr(DISTRIBUTED_CONNECTIONS, String.valueOf(userId), 1);
                RedisClient.expire(DISTRIBUTED_CONNECTIONS, Math.max(3600L, properties.getAuthMaxAgeSeconds() * 2));
                if (count != null && count > Math.max(1, properties.getMaxConnectionsPerUser())) {
                    RedisClient.hIncr(DISTRIBUTED_CONNECTIONS, String.valueOf(userId), -1);
                    return false;
                }
            } catch (RuntimeException e) {
                log.warn("distributed websocket connection counter unavailable: reason={}", e.getClass().getSimpleName());
            }
            return userSessions.add(sessionId);
        }
    }

    public void disconnect(Long userId, String sessionId) {
        Set<String> userSessions = sessions.get(userId);
        if (userSessions == null) return;
        if (userSessions.remove(sessionId)) {
            try { RedisClient.hIncr(DISTRIBUTED_CONNECTIONS, String.valueOf(userId), -1); }
            catch (RuntimeException ignored) { }
        }
        if (userSessions.isEmpty()) sessions.remove(userId, userSessions);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (event.getUser() instanceof ReqInfoContext.ReqInfo) {
            disconnect(((ReqInfoContext.ReqInfo) event.getUser()).getUserId(), event.getSessionId());
        }
    }
}
