package com.github.paicoding.forum.web.front.chat.helper;

import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatStreamEvent;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.web.front.chat.stomp.WsChatProperties;
import com.github.paicoding.forum.service.chatai.service.ChatGenerationControl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
public class ChatStreamSessionService {
    private static final String REPLAY_PREFIX = "chat:stream:events:";
    private static final String LATEST_PREFIX = "chat:stream:latest:";
    private static final String CANCEL_PREFIX = "chat:stream:cancelled:";
    private static final String OWNER_PREFIX = "chat:stream:owner:";
    private final ConcurrentHashMap<String, Generation> generations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

    @Autowired private WsChatProperties properties;
    @Autowired private ChatGenerationControl generationControl;

    public Generation start(String session, String chatId, String requestId, Consumer<ChatStreamEvent> sender) {
        String generationId = UUID.randomUUID().toString().replace("-", "");
        generationControl.prepare(generationId);
        Generation generation = new Generation(session, chatId, requestId, generationId, sender);
        generations.put(generationId, generation);
        safeRedis(() -> RedisClient.setStrWithExpire(latestKey(session, chatId), generationId, properties.getReplayTtlSeconds()));
        safeRedis(() -> RedisClient.setStrWithExpire(OWNER_PREFIX + generationId, session, properties.getReplayTtlSeconds()));
        generation.timeout = scheduler.schedule(() -> timeout(generationId), properties.getModelTimeoutMs(), TimeUnit.MILLISECONDS);
        emit(generation, "started", null, false, false, false, null, null);
        return generation;
    }

    public boolean cancel(String session, String generationId) {
        if (StringUtils.isBlank(generationId)) return false;
        Generation generation = generations.get(generationId);
        if (generation != null && !StringUtils.equals(session, generation.session)) return false;
        if (generation == null) {
            String owner = safeRedisValue(() -> RedisClient.getStr(OWNER_PREFIX + generationId));
            if (!StringUtils.equals(owner, session)) return false;
        }
        safeRedis(() -> RedisClient.setStrWithExpire(CANCEL_PREFIX + generationId, "1", properties.getReplayTtlSeconds()));
        if (generation == null) return true;
        if (!generation.cancelled.compareAndSet(false, true)) return true;
        generationControl.cancel(generationId);
        emit(generation, "cancelled", generation.lastPayload, true, true, false, "CLIENT_CANCELLED", "生成已由客户端取消");
        finish(generation);
        return true;
    }

    public boolean isCancelled(Generation generation) {
        if (generation.cancelled.get()) return true;
        try {
            if (RedisClient.getStr(CANCEL_PREFIX + generation.generationId) != null) {
                generation.cancelled.set(true);
                return true;
            }
        } catch (RuntimeException ignored) {
            // Redis 故障时仍保留本机取消能力。
        }
        return false;
    }

    public void delta(Generation generation, ChatRecordsVo payload) {
        if (isCancelled(generation)) { generationControl.cancel(generation.generationId); finish(generation); return; }
        if (generation.terminal.get()) return;
        generation.lastPayload = payload == null ? null : payload.clone();
        emit(generation, "delta", payload, false, true, false, null, null);
    }

    public void complete(Generation generation, ChatRecordsVo payload) {
        if (isCancelled(generation)) { generationControl.cancel(generation.generationId); finish(generation); return; }
        if (!generation.terminal.compareAndSet(false, true)) return;
        generation.lastPayload = payload == null ? null : payload.clone();
        emit(generation, "completed", payload, true, false, false, null, null);
        finish(generation);
    }

    public void fail(Generation generation, ChatRecordsVo payload, String code, String message, boolean retryable) {
        if (isCancelled(generation)) { generationControl.cancel(generation.generationId); finish(generation); return; }
        if (!generation.terminal.compareAndSet(false, true)) return;
        generation.lastPayload = payload == null ? null : payload.clone();
        emit(generation, "error", payload, true, payload != null, retryable, code, message);
        finish(generation);
    }

    public List<ChatStreamEvent> replay(String session, String chatId, String generationId, long lastSeq) {
        String resolved = generationId;
        if (StringUtils.isBlank(resolved)) resolved = safeRedisValue(() -> RedisClient.getStr(latestKey(session, chatId)));
        if (StringUtils.isBlank(resolved)) return Collections.emptyList();
        Generation local = generations.get(resolved);
        if (local != null && !StringUtils.equals(local.session, session)) return Collections.emptyList();
        final String id = resolved;
        String owner = safeRedisValue(() -> RedisClient.getStr(OWNER_PREFIX + id));
        if (!StringUtils.equals(owner, session)) return Collections.emptyList();
        List<ChatStreamEvent> events = safeRedisValue(() -> RedisClient.lRange(REPLAY_PREFIX + id, 0,
                properties.getReplayMaxEvents(), ChatStreamEvent.class));
        if (events == null) return Collections.emptyList();
        events.removeIf(event -> event.getSeq() <= lastSeq);
        return events;
    }

    private void timeout(String generationId) {
        Generation generation = generations.get(generationId);
        if (generation == null) return;
        if (isCancelled(generation)) { generationControl.cancel(generationId); finish(generation); return; }
        if (!generation.terminal.compareAndSet(false, true)) return;
        emit(generation, "timeout", generation.lastPayload, true, generation.lastPayload != null, true,
                "MODEL_TIMEOUT", "模型响应超时，已保留当前部分输出");
        generationControl.cancel(generationId);
        finish(generation);
    }

    private void emit(Generation generation, String type, ChatRecordsVo payload, boolean terminal, boolean partial,
                      boolean retryable, String errorCode, String errorMessage) {
        synchronized (generation) {
        ChatStreamEvent event = new ChatStreamEvent().setEvent(type).setRequestId(generation.requestId)
                .setGenerationId(generation.generationId).setChatId(generation.chatId)
                .setSeq(generation.seq.incrementAndGet()).setTerminal(terminal).setPartial(partial)
                .setRetryable(retryable).setErrorCode(errorCode).setErrorMessage(errorMessage)
                .setServerTime(System.currentTimeMillis()).setPayload(payload);
        int bytes = JsonUtil.toStr(event).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (generation.bufferedBytes.addAndGet(bytes) > properties.getGenerationBufferBytes() && !terminal) {
            fail(generation, generation.lastPayload, "BUFFER_LIMIT", "客户端消费过慢，生成缓冲区已达到上限", true);
            return;
        }
        safeRedis(() -> {
            String key = REPLAY_PREFIX + generation.generationId;
            RedisClient.rPush(key, event);
            RedisClient.lTrim(key, -properties.getReplayMaxEvents(), -1);
            RedisClient.expire(key, properties.getReplayTtlSeconds());
        });
        generation.sender.accept(event);
        }
    }

    private void finish(Generation generation) {
        if (generation.timeout != null) generation.timeout.cancel(false);
        generations.remove(generation.generationId, generation);
    }

    private String latestKey(String session, String chatId) { return LATEST_PREFIX + session + ":" + StringUtils.defaultString(chatId); }
    private void safeRedis(Runnable action) { try { action.run(); } catch (RuntimeException e) { log.warn("chat stream replay cache unavailable: reason={}", e.getClass().getSimpleName()); } }
    private <T> T safeRedisValue(java.util.concurrent.Callable<T> action) { try { return action.call(); } catch (Exception e) { return null; } }

    @PreDestroy public void shutdown() { scheduler.shutdownNow(); }

    public static final class Generation {
        private final String session;
        private final String chatId;
        private final String requestId;
        private final String generationId;
        private final Consumer<ChatStreamEvent> sender;
        private final AtomicLong seq = new AtomicLong();
        private final AtomicLong bufferedBytes = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile ChatRecordsVo lastPayload;
        private volatile ScheduledFuture<?> timeout;
        private Generation(String session, String chatId, String requestId, String generationId, Consumer<ChatStreamEvent> sender) {
            this.session = session; this.chatId = chatId; this.requestId = requestId;
            this.generationId = generationId; this.sender = sender;
        }
        public String getGenerationId() { return generationId; }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chat-stream-timeout"); thread.setDaemon(true); return thread;
        }
    }
}
