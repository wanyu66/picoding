package com.github.paicoding.forum.service.chatai.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@Component
public class ChatGenerationControl {
    private final ThreadLocal<String> current = new ThreadLocal<>();
    private final ConcurrentHashMap<String, List<Runnable>> cancelHooks = new ConcurrentHashMap<>();

    public <T> T within(String generationId, Supplier<T> action) {
        current.set(generationId);
        try { return action.get(); }
        finally { current.remove(); }
    }

    public void registerCancelHook(Runnable hook) {
        String generationId = current.get();
        if (generationId != null && hook != null) {
            cancelHooks.computeIfAbsent(generationId, key -> new CopyOnWriteArrayList<>()).add(hook);
        }
    }

    public void cancel(String generationId) {
        List<Runnable> hooks = cancelHooks.remove(generationId);
        if (hooks == null) return;
        hooks.forEach(hook -> { try { hook.run(); } catch (RuntimeException ignored) { } });
    }

    public void complete(String generationId) { cancelHooks.remove(generationId); }
}
