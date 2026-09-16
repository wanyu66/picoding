package com.github.paicoding.forum.service.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LLMRequest {
    private LLMScene scene;
    private Long userId;
    private String systemPrompt;
    private String userPrompt;
    private String responseJsonSchema;
    private String fallbackResponse;
    private String model;
    private LLMResponseValidator validator;
}
