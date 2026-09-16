package com.github.paicoding.forum.api.model.vo.chat;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class ChatStreamEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private int version = 2;
    /** connected, heartbeat, started, delta, completed, cancelled, timeout, error, history */
    private String event;
    private String requestId;
    private String generationId;
    private String chatId;
    private long seq;
    private boolean terminal;
    private boolean partial;
    private boolean retryable;
    private String errorCode;
    private String errorMessage;
    private long serverTime;
    private ChatRecordsVo payload;
}
