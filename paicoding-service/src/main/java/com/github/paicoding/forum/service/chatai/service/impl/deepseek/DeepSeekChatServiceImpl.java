package com.github.paicoding.forum.service.chatai.service.impl.deepseek;

import com.github.paicoding.forum.api.model.enums.ChatAnswerTypeEnum;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.enums.ai.AiChatStatEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatItemVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.service.chatai.service.AbsChatService;
import com.plexpt.chatgpt.listener.AbstractStreamListener;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * deepSeek 聊天接入
 *
 * @author YiHui
 * @date 2025/2/6
 */
@Slf4j
@Service
public class DeepSeekChatServiceImpl extends AbsChatService {
    @Autowired
    private DeepSeekIntegration deepSeekIntegration;

    /**
     * 同步的响应返回结果
     *
     * @param user
     * @param chat
     * @return
     */
    @Override
    public AiChatStatEnum doAnswer(Long user, ChatItemVo chat) {
        if (deepSeekIntegration.directReturn(chat)) {
            return AiChatStatEnum.END;
        }
        return AiChatStatEnum.ERROR;
    }

    @Override
    public AiChatStatEnum doAnswer(Long user, ChatRecordsVo response) {
        if (deepSeekIntegration.directReturn(response.getRecords(), response.getRecords().get(0))) {
            return AiChatStatEnum.END;
        }
        return AiChatStatEnum.ERROR;
    }

    /**
     * 异步流式的返回结果
     *
     * @param user 用户ID，用于标识提问的用户
     * @param response 保存提问 & 返回的结果，最终会返回给前端用户
     * @param consumer 具体将 response 写回前端的实现策略
     * @return 返回聊天的状态枚举
     */
    @Override
    public AiChatStatEnum doAsyncAnswer(Long user, ChatRecordsVo response, BiConsumer<AiChatStatEnum, ChatRecordsVo> consumer) {
        // 获取问答中的最新的记录，用于问答
        ChatItemVo item = response.getRecords().get(0);
        AtomicBoolean terminal = new AtomicBoolean();
        // 创建一个抽象流监听器来处理流式返回的结果
        AbstractStreamListener listener = new AbstractStreamListener() {
            // 当连接打开时的处理
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                super.onOpen(eventSource, response);
                if (log.isDebugEnabled()) {
                    log.debug("正确建立了连接: {}, res: {}", eventSource, response);
                }
            }

            // 当连接关闭时的处理
            @Override
            public void onClosed(EventSource eventSource) {
                super.onClosed(eventSource);
                if (log.isDebugEnabled()) {
                    log.debug("已经关闭了连接: {}", eventSource);
                }
                // A transport EOF is not the protocol's [DONE] marker.
                if (terminal.compareAndSet(false, true)) {
                    response.setErrorCode("UPSTREAM_INCOMPLETE").setErrorMessage("模型连接提前结束，已保留部分输出");
                    item.setAnswerType(ChatAnswerTypeEnum.STREAM_END);
                    consumer.accept(AiChatStatEnum.ERROR, response);
                }
            }

            // 当接收到消息时的处理
            @Override
            public void onMsg(String message) {
                if (terminal.get()) return;
                // 成功返回结果的场景, 过滤掉开头的空行
                if (StringUtils.isNotBlank(lastMessage)) {
                    item.appendAnswer(message);
                    consumer.accept(AiChatStatEnum.MID, response);
                    if (log.isDebugEnabled()) {
                        log.debug("DeepSeek返回内容: {}", lastMessage);
                    }
                }
            }

            // 当遇到错误时的处理
            @Override
            public void onError(Throwable throwable, String res) {
                if (!terminal.compareAndSet(false, true)) return;
                response.setErrorCode("MODEL_ERROR").setErrorMessage("模型调用失败，已保留部分输出");
                // 返回异常的场景
                String errorMsg = StringUtils.defaultIfBlank(res, throwable == null ? null : throwable.getMessage());
                if (StringUtils.isNotBlank(errorMsg)) {
                    item.appendAnswer("Error:" + errorMsg);
                } else if (StringUtils.isBlank(item.getAnswer())) {
                    item.appendAnswer("AI 回复生成失败，请稍后再试");
                }
                item.setAnswerType(ChatAnswerTypeEnum.STREAM_END);
                consumer.accept(AiChatStatEnum.ERROR, response);
                if (log.isDebugEnabled()) {
                    log.debug("DeepSeek返回异常: {}", lastMessage);
                }
            }
        };

        // 注册回答结束的回调钩子
        listener.setOnComplate((s) -> {
            if (!terminal.compareAndSet(false, true)) return;
            if (log.isDebugEnabled()) {
                log.debug("这一轮对话聊天已结束，完整的返回结果是：{}", s);
            }
            item.appendAnswer("\n")
                    .setAnswerType(ChatAnswerTypeEnum.STREAM_END);
            consumer.accept(AiChatStatEnum.END, response);
        });
        if (deepSeekIntegration.isWebSearchToolAvailable()) {
            deepSeekIntegration.streamReActReturn(response.getRecords(), listener);
        } else {
            deepSeekIntegration.streamReturn(response.getRecords(), listener);
        }
        return AiChatStatEnum.IGNORE;
    }

    @Override
    public AISourceEnum source() {
        return AISourceEnum.DEEP_SEEK;
    }
}
