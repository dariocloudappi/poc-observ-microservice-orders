package com.example.ordersapp.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-api-key",
            "x-auth-token",
            "x-forwarded-authorization"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        MDC.put("http.method", request.getMethod());
        MDC.put("http.target", request.getRequestURI());
        MDC.put("http.client_ip", request.getRemoteAddr());
        MDC.put("user_agent", request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "");

        log.atInfo()
                .addKeyValue("http.method", request.getMethod())
                .addKeyValue("http.target", request.getRequestURI())
                .addKeyValue("http.query", request.getQueryString() != null ? request.getQueryString() : "")
                .addKeyValue("http.client_ip", request.getRemoteAddr())
                .addKeyValue("http.headers", safeHeaders(request).toString())
                .log("Starting request");

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            MDC.put("http.status_code", String.valueOf(response.getStatus()));
            MDC.put("http.duration_ms", String.valueOf(duration));

            log.atInfo()
                    .addKeyValue("http.method", request.getMethod())
                    .addKeyValue("http.target", request.getRequestURI())
                    .addKeyValue("http.status_code", response.getStatus())
                    .addKeyValue("http.duration_ms", duration)
                    .log("Ending request");

            MDC.clear();
        }
    }

    private Map<String, String> safeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, request.getHeader(name));
            }
        });
        return headers;
    }
}
