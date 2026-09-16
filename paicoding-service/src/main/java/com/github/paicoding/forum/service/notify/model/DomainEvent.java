package com.github.paicoding.forum.service.notify.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/** 全业务统一领域事件契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private String eventId;
    private String eventType;
    private Long operatorId;
    private Target target;
    private Date occurredAt;
    private Integer version;
    private String traceId;
    private String content;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Target implements Serializable {
        private static final long serialVersionUID = 1L;
        private String type;
        private Long id;
        private Long ownerId;
        private Long articleId;
        private Long parentId;
        private Long commentId;
    }
}
