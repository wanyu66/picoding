package com.github.paicoding.forum.service.llm;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.core.async.AsyncUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class LLMRouter {
    private final Map<String, LLMProvider> providers = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Circuit> circuits = new ConcurrentHashMap<>();

    @Autowired private LLMRouteProperties properties;
    @Autowired private LLMUsageGuard usageGuard;
    @Autowired private ObjectProvider<MeterRegistry> meterRegistryProvider;

    public LLMRouter(List<LLMProvider> providerList) {
        for (LLMProvider provider : providerList) providers.put(provider.id(), provider);
    }

    public LLMResponse complete(LLMRequest request) {
        if (request == null || request.getScene() == null) throw new IllegalArgumentException("LLM scene is required");
        LLMRouteProperties.Route route = route(request.getScene());
        try (LLMUsageGuard.Permit ignored = usageGuard.acquire(request.getUserId(), request.getScene())) {
            Throwable last = null;
            String[][] candidates = {
                    {route.getPrimaryProvider(), route.getPrimaryModel()},
                    {route.getFallbackProvider(), route.getFallbackModel()}
            };
            for (String[] candidate : candidates) {
                if (StringUtils.isBlank(candidate[0])) continue;
                LLMProvider provider = providers.get(candidate[0]);
                if (provider == null || !allow(provider.id())) continue;
                int retries = route.getMaxRetries() == null ? properties.getMaxRetries() : route.getMaxRetries();
                for (int attempt = 0; attempt <= Math.max(0, retries); attempt++) {
                    long attemptStarted = System.nanoTime();
                    try {
                        request.setModel(candidate[1]);
                        LLMResponse response = invoke(provider, request, timeout(route));
                        success(provider.id());
                        enrichUsage(response, request, route);
                        observe(request.getScene(), response, "success", null);
                        return response;
                    } catch (Exception e) {
                        last = e;
                        failure(provider.id());
                        LLMResponse failed = new LLMResponse();
                        failed.setProvider(provider.id());
                        failed.setModel(candidate[1]);
                        failed.setLatencyMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStarted));
                        failed.setFailureReason(e.getClass().getSimpleName());
                        observe(request.getScene(), failed, "failure", e);
                        if (!retryable(e)) break;
                    }
                }
            }
            if (StringUtils.isNotBlank(request.getFallbackResponse())) {
                LLMResponse fallback = new LLMResponse();
                fallback.setContent(request.getFallbackResponse());
                fallback.setProvider("template");
                fallback.setModel("deterministic-template");
                fallback.setFallback(true);
                fallback.setFailureReason(last == null ? "no_provider" : last.getClass().getSimpleName());
                if (request.getValidator() != null) {
                    try { request.getValidator().validate(fallback.getContent()); }
                    catch (Exception e) { throw new IllegalStateException("LLM fallback template validation failed", e); }
                }
                enrichUsage(fallback, request, route);
                observe(request.getScene(), fallback, "fallback", last);
                return fallback;
            }
            throw new IllegalStateException("No LLM provider succeeded", last);
        }
    }

    public AISourceEnum routeChatSource(AISourceEnum requested) {
        LLMRouteProperties.Route route = properties.getRoutes().get(LLMScene.CHAT);
        if (route == null || StringUtils.isBlank(route.getPrimaryProvider())) return requested;
        LLMProvider configured = providers.get(route.getPrimaryProvider());
        if (configured != null && allow(configured.id())) return configured.source();
        LLMProvider fallback = providers.get(route.getFallbackProvider());
        return fallback == null || !allow(fallback.id()) ? requested : fallback.source();
    }

    /** Records legacy/streaming chat providers through the same safe telemetry path. */
    public void observeExternal(LLMScene scene, String provider, String model, long startedNanos,
                                long firstTokenNanos, String prompt, String completion, String outcome,
                                Throwable failure) {
        String managedProvider = managedProviderId(provider);
        String routedModel = properties.modelFor(scene, managedProvider);
        LLMResponse response = new LLMResponse();
        response.setProvider(StringUtils.defaultIfBlank(provider, "unknown"));
        response.setModel(resolveObservedModel(managedProvider, StringUtils.defaultIfBlank(model, routedModel)));
        response.setLatencyMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        long first = firstTokenNanos > 0L ? firstTokenNanos : System.nanoTime();
        response.setFirstTokenLatencyMs(TimeUnit.NANOSECONDS.toMillis(first - startedNanos));
        LLMRequest request = LLMRequest.builder().scene(scene).systemPrompt("").userPrompt(prompt).build();
        response.setContent(completion);
        enrichUsage(response, request, route(scene));
        response.setFailureReason(failure == null
                ? ("failure".equals(outcome) ? "provider_reported_error" : null)
                : failure.getClass().getSimpleName());
        observe(scene, response, outcome, failure);
        if (managedProvider != null) {
            if (failure == null && "success".equals(outcome)) success(managedProvider);
            else failure(managedProvider);
        }
    }

    private String managedProviderId(String provider) {
        for (LLMProvider candidate : providers.values()) {
            if (candidate.id().equalsIgnoreCase(StringUtils.defaultString(provider))
                    || candidate.source().name().equalsIgnoreCase(StringUtils.defaultString(provider))) return candidate.id();
        }
        return null;
    }

    private String resolveObservedModel(String provider, String requestedModel) {
        LLMProvider candidate = providers.get(provider);
        if (candidate == null) return StringUtils.defaultIfBlank(requestedModel, "configured");
        return StringUtils.defaultIfBlank(candidate.modelName(requestedModel), "configured");
    }

    private LLMResponse invoke(LLMProvider provider, LLMRequest request, long timeoutMs) throws Exception {
        long start = System.nanoTime();
        try {
            LLMResponse response = AsyncUtil.callWithTimeLimit(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS,
                    () -> provider.complete(request));
            if (response == null || StringUtils.isBlank(response.getContent())) throw new IllegalStateException("empty LLM response");
            if (request.getValidator() != null) request.getValidator().validate(response.getContent());
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            response.setLatencyMs(elapsed);
            response.setFirstTokenLatencyMs(elapsed);
            return response;
        } catch (TimeoutException e) {
            throw new LLMTimeoutException("LLM provider timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("LLM provider execution failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private void enrichUsage(LLMResponse response, LLMRequest request, LLMRouteProperties.Route route) {
        long promptTokens = approximateTokens(StringUtils.defaultString(request.getSystemPrompt()) + request.getUserPrompt());
        long completionTokens = approximateTokens(response.getContent());
        response.setPromptTokens(promptTokens);
        response.setCompletionTokens(completionTokens);
        double inputPrice = route.getInputCostPerMillion() == null ? 0D : route.getInputCostPerMillion();
        double outputPrice = route.getOutputCostPerMillion() == null ? 0D : route.getOutputCostPerMillion();
        response.setEstimatedCost(promptTokens * inputPrice / 1_000_000D + completionTokens * outputPrice / 1_000_000D);
    }

    private long approximateTokens(String value) {
        return Math.max(1L, (StringUtils.defaultString(value).codePointCount(0, StringUtils.defaultString(value).length()) + 3L) / 4L);
    }

    private void observe(LLMScene scene, LLMResponse response, String outcome, Throwable failure) {
        String provider = response == null ? "unknown" : StringUtils.defaultIfBlank(response.getProvider(), "unknown");
        String model = response == null ? "unknown" : StringUtils.defaultIfBlank(response.getModel(), "unknown");
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.counter("paicoding.llm.requests_total", "scene", scene.name(), "provider", provider, "outcome", outcome).increment();
            if (response != null) {
                registry.timer("paicoding.llm.latency", "scene", scene.name(), "provider", provider).record(response.getLatencyMs(), TimeUnit.MILLISECONDS);
                registry.timer("paicoding.llm.first_token_latency", "scene", scene.name(), "provider", provider).record(response.getFirstTokenLatencyMs(), TimeUnit.MILLISECONDS);
                registry.counter("paicoding.llm.tokens_total", "scene", scene.name(), "provider", provider, "type", "prompt").increment(response.getPromptTokens());
                registry.counter("paicoding.llm.tokens_total", "scene", scene.name(), "provider", provider, "type", "completion").increment(response.getCompletionTokens());
                registry.counter("paicoding.llm.estimated_cost_total", "scene", scene.name(), "provider", provider).increment(response.getEstimatedCost());
            }
        }
        if (failure != null || "failure".equals(outcome)) {
            String reason = failure == null ? response.getFailureReason() : failure.getClass().getSimpleName();
            log.warn("LLM call failed: scene={}, provider={}, model={}, reason={}", scene, provider, model, reason);
        } else {
            log.info("LLM call completed: scene={}, provider={}, model={}, outcome={}, latencyMs={}, ttftMs={}, promptTokens={}, completionTokens={}, estimatedCost={}",
                    scene, provider, model, outcome, response.getLatencyMs(), response.getFirstTokenLatencyMs(),
                    response.getPromptTokens(), response.getCompletionTokens(), response.getEstimatedCost());
        }
    }

    private boolean retryable(Exception e) { return true; }
    private long timeout(LLMRouteProperties.Route route) { return route.getTimeoutMs() == null ? properties.getTimeoutMs() : route.getTimeoutMs(); }

    private LLMRouteProperties.Route route(LLMScene scene) {
        LLMRouteProperties.Route route = properties.getRoutes().get(scene);
        if (route != null) return route;
        route = new LLMRouteProperties.Route();
        route.setPrimaryProvider("zhipu");
        return route;
    }

    private boolean allow(String provider) {
        Circuit circuit = circuits.computeIfAbsent(provider, key -> new Circuit());
        long until = circuit.openUntil.get();
        if (until <= 0) return true;
        if (System.currentTimeMillis() < until) return false;
        return circuit.halfOpen.compareAndSet(false, true);
    }

    private void success(String provider) {
        Circuit circuit = circuits.computeIfAbsent(provider, key -> new Circuit());
        circuit.failures.set(0); circuit.openUntil.set(0L); circuit.halfOpen.set(false);
    }

    private void failure(String provider) {
        Circuit circuit = circuits.computeIfAbsent(provider, key -> new Circuit());
        int failures = circuit.failures.incrementAndGet();
        if (circuit.halfOpen.get() || failures >= Math.max(1, properties.getCircuitFailureThreshold())) {
            circuit.openUntil.set(System.currentTimeMillis() + Math.max(1L, properties.getCircuitOpenSeconds()) * 1000L);
            circuit.halfOpen.set(false);
        }
    }

    private static final class Circuit {
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntil = new AtomicLong();
        private final AtomicBoolean halfOpen = new AtomicBoolean();
    }

    private static final class LLMTimeoutException extends Exception {
        private LLMTimeoutException(String message) { super(message); }
    }
}
