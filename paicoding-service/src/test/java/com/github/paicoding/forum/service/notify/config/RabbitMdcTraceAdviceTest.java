package com.github.paicoding.forum.service.notify.config;

import com.github.paicoding.forum.core.mdc.MdcUtil;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RabbitMdcTraceAdviceTest {
    private final RabbitMdcTraceAdvice advice = new RabbitMdcTraceAdvice();

    @AfterEach
    void clearMdc() {
        MdcUtil.clear();
    }

    @Test
    void restoresTraceIdDuringInvocationAndClearsItAfterSuccess() throws Throwable {
        MethodInvocation invocation = invocation(message("trace-from-producer"));
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(MdcUtil.getTraceId()).isEqualTo("trace-from-producer");
            return "ok";
        });

        assertThat(advice.invoke(invocation)).isEqualTo("ok");
        assertThat(MdcUtil.getTraceId()).isNull();
    }

    @Test
    void clearsTraceIdAfterListenerFailure() throws Throwable {
        MethodInvocation invocation = invocation(message("failed-trace"));
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(MdcUtil.getTraceId()).isEqualTo("failed-trace");
            throw new IllegalStateException("listener failed");
        });

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("listener failed");
        assertThat(MdcUtil.getTraceId()).isNull();
    }

    @Test
    void replacesStaleThreadContextAndGeneratesFallbackWhenHeaderIsMissing() throws Throwable {
        MdcUtil.add(MdcUtil.TRACE_ID_KEY, "stale-trace");
        MethodInvocation invocation = invocation(message(null));
        when(invocation.proceed()).thenAnswer(ignored -> {
            assertThat(MdcUtil.getTraceId()).isNotBlank().isNotEqualTo("stale-trace");
            return null;
        });

        advice.invoke(invocation);

        assertThat(MdcUtil.getTraceId()).isNull();
    }

    private MethodInvocation invocation(Message message) {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        return invocation;
    }

    private Message message(String traceId) {
        MessageProperties properties = new MessageProperties();
        if (traceId != null) {
            properties.setHeader(MdcUtil.TRACE_ID_KEY, traceId);
        }
        return new Message(new byte[0], properties);
    }
}
