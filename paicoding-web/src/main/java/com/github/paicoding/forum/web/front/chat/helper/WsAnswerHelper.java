package com.github.paicoding.forum.web.front.chat.helper;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatStreamEvent;
import com.github.paicoding.forum.api.model.vo.chat.ChatStreamRequest;
import com.github.paicoding.forum.api.model.enums.ChatAnswerTypeEnum;
import com.github.paicoding.forum.core.mdc.MdcUtil;
import com.github.paicoding.forum.core.ws.WebSocketResponseUtil;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.chatai.constants.ChatConstants;
import com.github.paicoding.forum.service.chatai.service.ChatGenerationControl;
import com.github.paicoding.forum.service.user.service.LoginService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author YiHui
 * @date 2023/6/9
 */
@Slf4j
@Component
public class WsAnswerHelper {
    public static final String AI_SOURCE_PARAM = "AI";

    @Autowired
    private ChatFacade chatFacade;
    @Autowired
    private ChatStreamSessionService streamSessions;
    @Autowired
    private ChatGenerationControl generationControl;

    public String sendMsgToUser(AISourceEnum ai, String session, String chatId, ChatStreamRequest request) {
        if (request == null || StringUtils.isBlank(request.getContent()) || request.getContent().length() > 512) {
            responseEvent(session, new ChatStreamEvent().setEvent("error").setRequestId(request == null ? null : request.getRequestId())
                    .setTerminal(true).setRetryable(false).setErrorCode("INVALID_REQUEST")
                    .setErrorMessage("问题不能为空且不能超过 512 个字符").setServerTime(System.currentTimeMillis()));
            return null;
        }
        ChatStreamSessionService.Generation generation = streamSessions.start(session, chatId, request.getRequestId(),
                event -> responseEvent(session, event));
        try {
            java.util.function.Consumer<ChatRecordsVo> callback = vo -> {
                if (waitingTip(vo)) return;
                if (terminal(vo)) {
                    streamSessions.complete(generation, vo);
                    generationControl.complete(generation.getGenerationId());
                } else streamSessions.delta(generation, vo);
            };
            generationControl.within(generation.getGenerationId(), () -> {
                if (ai == null) return chatFacade.autoChat(request.getContent(), callback);
                return chatFacade.autoChat(ai, request.getContent(), callback);
            });
        } catch (RuntimeException e) {
            streamSessions.fail(generation, null, "MODEL_ERROR", "模型调用失败", true);
            generationControl.complete(generation.getGenerationId());
            log.warn("chat generation failed: generationId={}, reason={}", generation.getGenerationId(), e.getClass().getSimpleName());
        }
        return generation.getGenerationId();
    }

    public boolean cancel(String session, String generationId) {
        boolean cancelled = streamSessions.cancel(session, generationId);
        if (cancelled) {
            generationControl.cancel(generationId);
            responseEvent(session, new ChatStreamEvent().setEvent("cancelled").setGenerationId(generationId)
                    .setSeq(9007199254740991L).setTerminal(true).setPartial(true)
                    .setErrorCode("CLIENT_CANCELLED").setErrorMessage("生成已由客户端取消")
                    .setServerTime(System.currentTimeMillis()));
        }
        return cancelled;
    }

    public void resume(String session, String chatId, String generationId, long lastSeq) {
        streamSessions.replay(session, chatId, generationId, lastSeq).forEach(event -> responseEvent(session, event));
    }

    public void heartbeat(String session) {
        responseEvent(session, new ChatStreamEvent().setEvent("heartbeat").setServerTime(System.currentTimeMillis()));
    }

    public void sendMsgHistoryToUser(String session, AISourceEnum ai) {
        ChatRecordsVo vo = chatFacade.history(ai);
        response(session, vo);
    }

    /**
     * 将返回结果推送给用户
     *
     * @param session
     * @param response
     */
    public void response(String session, ChatRecordsVo response) {
        // convertAndSendToUser 方法可以发送信给给指定用户,
        // 底层会自动将第二个参数目的地址 /chat/rsp 拼接为
        // /user/username/chat/rsp，其中第二个参数 username 即为这里的第一个参数 session
        // username 也是AuthHandshakeHandler中配置的 Principal 用户识别标志
        WebSocketResponseUtil.sendMsgToUser(session, "/chat/rsp", response);
    }

    public void responseEvent(String session, ChatStreamEvent event) {
        WebSocketResponseUtil.sendMsgToUser(session, "/chat/rsp", event);
    }

    private boolean terminal(ChatRecordsVo response) {
        if (response == null || response.getRecords() == null || response.getRecords().isEmpty()) return true;
        ChatAnswerTypeEnum type = response.getRecords().get(0).getAnswerType();
        boolean terminal = type != ChatAnswerTypeEnum.STREAM;
        return terminal;
    }

    private boolean waitingTip(ChatRecordsVo response) {
        return response != null && response.getRecords() != null && !response.getRecords().isEmpty()
                && StringUtils.equals(ChatConstants.ASYNC_CHAT_TIP, response.getRecords().get(0).getAnswer());
    }

    public void execute(Map<String, Object> attributes, Runnable func) {
        try {
            ReqInfoContext.ReqInfo reqInfo = (ReqInfoContext.ReqInfo) attributes.get(LoginService.SESSION_KEY);
            ReqInfoContext.addReqInfo(reqInfo);
            String traceId = (String) attributes.get(MdcUtil.TRACE_ID_KEY);
            MdcUtil.add(MdcUtil.TRACE_ID_KEY, traceId);


            // 执行具体的业务逻辑
            func.run();

        } finally {
            ReqInfoContext.clear();
            MdcUtil.clear();
        }
    }
}
