package com.github.paicoding.forum.api.model.vo.chat;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChatStreamRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    /** generate, cancel, resume, ping */
    private String type;
    private String requestId;
    private String generationId;
    private String content;
    private Long lastSeq;
}
