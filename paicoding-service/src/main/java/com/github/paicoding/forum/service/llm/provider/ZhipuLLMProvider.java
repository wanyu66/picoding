package com.github.paicoding.forum.service.llm.provider;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatItemVo;
import com.github.paicoding.forum.service.chatai.constants.ChatConstants;
import com.github.paicoding.forum.service.chatai.service.impl.zhipu.ZhipuIntegration;
import com.github.paicoding.forum.service.llm.LLMProvider;
import com.github.paicoding.forum.service.llm.LLMRequest;
import com.github.paicoding.forum.service.llm.LLMResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ZhipuLLMProvider implements LLMProvider {
    @Autowired private ZhipuIntegration integration;
    @Override public String id() { return "zhipu"; }
    @Override public AISourceEnum source() { return AISourceEnum.ZHI_PU_AI; }
    @Override public String modelName(String requestedModel) { return integration.modelName(requestedModel); }

    @Override
    public LLMResponse complete(LLMRequest request) {
        List<ChatItemVo> messages = new ArrayList<>();
        String system = StringUtils.defaultString(request.getSystemPrompt());
        if (StringUtils.isNotBlank(request.getResponseJsonSchema())) {
            system += "\nReturn exactly one JSON object conforming to this JSON Schema. No markdown: " + request.getResponseJsonSchema();
        }
        messages.add(new ChatItemVo().initQuestion(ChatConstants.PROMPT_TAG + system));
        ChatItemVo target = new ChatItemVo().initQuestion(request.getUserPrompt());
        messages.add(target);
        String model = modelName(request.getModel());
        integration.directReturn(request.getUserId(), messages, target, model);
        LLMResponse response = new LLMResponse();
        response.setContent(target.getAnswer());
        response.setProvider(id());
        response.setModel(model);
        return response;
    }
}
