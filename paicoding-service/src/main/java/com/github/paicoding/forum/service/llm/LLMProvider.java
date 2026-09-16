package com.github.paicoding.forum.service.llm;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;

public interface LLMProvider {
    String id();
    AISourceEnum source();
    String modelName(String requestedModel);
    LLMResponse complete(LLMRequest request) throws Exception;
}
