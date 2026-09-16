package com.github.paicoding.forum.service.notify.service;

import com.github.paicoding.forum.core.mdc.MdcUtil;
import com.github.paicoding.forum.service.notify.model.DomainEvent;
import com.github.paicoding.forum.service.notify.model.DomainEventType;

public interface DomainEventOutboxService {
    void append(DomainEvent event);
    DomainEvent newEvent(DomainEventType type, Long operatorId, DomainEvent.Target target, String content);
    default String currentTraceId() { return MdcUtil.getTraceId(); }
}
