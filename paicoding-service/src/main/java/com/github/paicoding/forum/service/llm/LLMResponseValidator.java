package com.github.paicoding.forum.service.llm;

public interface LLMResponseValidator {
    void validate(String content) throws Exception;
}
