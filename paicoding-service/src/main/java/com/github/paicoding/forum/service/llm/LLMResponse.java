package com.github.paicoding.forum.service.llm;

import lombok.Data;

@Data
public class LLMResponse {
    private String content;
    private String provider;
    private String model;
    private long latencyMs;
    private long firstTokenLatencyMs;
    private long promptTokens;
    private long completionTokens;
    private double estimatedCost;
    private boolean fallback;
    private String failureReason;
}
