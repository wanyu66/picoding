package com.github.paicoding.forum.service.chatai.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.function.Supplier;

@Component
public class ChatGenerationControl {
    private final ThreadLocal<State> current = new ThreadLocal<>();
    private final ConcurrentHashMap<String, State> generations = new ConcurrentHashMap<>();

    /** Called before publishing started, so an immediate client cancellation cannot be lost. */
    public void prepare(String generationId) { generations.computeIfAbsent(generationId, key -> new State()); }

    public <T> T within(String generationId, Supplier<T> action) {
        State state = generations.computeIfAbsent(generationId, key -> new State());
        State previous = current.get();
        current.set(state);
        try { return action.get(); }
        finally {
            if (previous == null) current.remove(); else current.set(previous);
            synchronized (state) {
                state.registrationFinished = true;
                if (state.closed) generations.remove(generationId, state);
            }
        }
    }

    public void registerCancelHook(Runnable hook) {
        State state = current.get();
        if (state == null || hook == null) return;
        synchronized (state) {
            if (!state.closed) { state.hooks.add(hook); return; }
        }
        run(hook);
    }

    public void cancel(String generationId) {
        close(generationId, true);
    }

    public void complete(String generationId) { close(generationId, false); }

    private void close(String generationId, boolean cancel) {
        State state = generations.get(generationId);
        if (state == null) return;
        List<Runnable> hooks;
        synchronized (state) {
            state.closed = true;
            hooks = new ArrayList<>(state.hooks);
            state.hooks.clear();
            // Keep the state until registration ends; a late hook must see closed.
            if (state.registrationFinished) generations.remove(generationId, state);
        }
        if (cancel) hooks.forEach(this::run);
    }

    private void run(Runnable hook) { try { hook.run(); } catch (RuntimeException ignored) { } }

    private static final class State {
        final List<Runnable> hooks = new ArrayList<>();
        boolean closed;
        boolean registrationFinished;
    }
}
