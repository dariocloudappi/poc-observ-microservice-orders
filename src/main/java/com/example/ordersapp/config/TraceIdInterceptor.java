package com.example.ordersapp.config;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TraceIdInterceptor implements HandlerInterceptor {

    private static final String X_TRACE_ID = "x-trace-id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            response.setHeader(X_TRACE_ID, span.getSpanContext().getTraceId());
        }
        return true;
    }
}
