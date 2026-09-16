package com.github.paicoding.forum.service.notify.config;

import com.github.paicoding.forum.core.mdc.MdcUtil;
import com.github.paicoding.forum.core.mdc.SelfTraceIdGenerator;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;

/**
 * Restores the producer trace id for the complete RabbitMQ listener invocation.
 *
 * <p>The advice is deliberately placed outside the retry advice so retries and
 * recovery logs keep the same trace id. Listener container threads are reused,
 * therefore the MDC must always be cleared after delivery.</p>
 */
public class RabbitMdcTraceAdvice implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        MdcUtil.clear();
        MdcUtil.add(MdcUtil.TRACE_ID_KEY, resolveTraceId(invocation.getArguments()));
        try {
            return invocation.proceed();
        } finally {
            MdcUtil.clear();
        }
    }

    String resolveTraceId(Object[] arguments) {
        Message message = findMessage(arguments);
        Object traceId = message == null ? null
                : message.getMessageProperties().getHeaders().get(MdcUtil.TRACE_ID_KEY);
        String value = traceId == null ? null : String.valueOf(traceId).trim();
        return value == null || value.isEmpty() ? SelfTraceIdGenerator.generate() : value;
    }

    private Message findMessage(Object[] arguments) {
        if (arguments == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (argument instanceof Message) {
                return (Message) argument;
            }
            if (argument instanceof Message[] && ((Message[]) argument).length > 0) {
                return ((Message[]) argument)[0];
            }
        }
        return null;
    }
}
