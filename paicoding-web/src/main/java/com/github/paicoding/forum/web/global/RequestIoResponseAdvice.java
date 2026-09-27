package com.github.paicoding.forum.web.global;

import com.github.paicoding.forum.core.cache.RequestIoStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Opt-in diagnostics for local measurement; contains counts only, no keys or SQL parameters. */
@RestControllerAdvice
@ConditionalOnProperty(value = "observability.request-io.enabled", havingValue = "true")
public class RequestIoResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
                                  Class<? extends HttpMessageConverter<?>> converter,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().set("X-Request-Io", RequestIoStats.snapshot());
        return body;
    }
}
