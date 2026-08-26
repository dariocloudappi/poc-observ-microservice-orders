package com.example.ordersapp.filters;

import com.example.ordersapp.observability.Observability;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
@Component
// HIGHEST_PRECEDENCE y no 1: la cadena de filtros de Spring Security se
// registra en -100, asi que con cualquier orden mayor Security corre antes,
// responde 401 y nunca invoca este filtro. El resultado era que las
// peticiones rechazadas no dejaban ni log ni http.status_code. Envolviendo
// toda la cadena, TODA respuesta queda registrada con su codigo.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter{

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final String X_TRACE_ID = "X-Trace-Id";

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

        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString() != null ? request.getQueryString() : "";

        Span span = Span.current();

        // La cabecera de traza se fija AQUI y no en un HandlerInterceptor.
        // Motivo: un HandlerInterceptor solo corre despues del handler
        // mapping, y Spring Security rechaza con 401 antes de llegar ahi. El
        // resultado era que las peticiones rechazadas, las que mas interesa
        // trazar, salian sin X-Trace-Id. Este filtro envuelve toda la cadena,
        // asi que la cabecera va en TODAS las respuestas.
        String traceId = span.getSpanContext().isValid()
                ? span.getSpanContext().getTraceId()
                : null;
        if (traceId != null) {
            response.setHeader(X_TRACE_ID, traceId);
        }

        // Contexto que llega del llamante a traves de la cabecera `baggage`,
        // es decir del cliente a traves del gateway de Tyk. Los atributos de
        // span no cruzan la frontera del servicio; solo lo hace el Baggage.
        Map<String, String> adopted = Observability.adoptIncomingBaggage();

        span.setAttribute("url.query", query);
        safeRequestHeaders(request).forEach((name, value) ->
                span.setAttribute("http.request.header." + name.toLowerCase(), value));

        MDC.put("http.method", method);
        MDC.put("http.url", path);
        MDC.put("url.query", query);

        // El metodo y la ruta viajan dos veces a proposito: dentro del mensaje,
        // para que sea legible tal cual, y como atributos estructurados, que son
        // los que permiten filtrar y agrupar en New Relic.
        log.atInfo()
                .addKeyValue("http.method", method)
                .addKeyValue("http.target", path)
                .addKeyValue("http.query", query)
                .addKeyValue("http.client_ip", request.getRemoteAddr())
                .addKeyValue("http.user_agent", request.getHeader("User-Agent"))
                .addKeyValue("http.content_type", request.getContentType())
                .addKeyValue("http.scheme", request.getScheme())
                .addKeyValue("trace.id", traceId)
                .log("{} Starting request {}", method, path);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;

            safeResponseHeaders(response).forEach((name, value) ->
                    span.setAttribute("http.response.header." + name.toLowerCase(), value));

            int status = response.getStatus();

            MDC.put("http.status_code", String.valueOf(status));
            MDC.put("http.duration_ms", String.valueOf(duration));

            // El nivel se deriva del codigo de respuesta. Con todo en INFO,
            // un "FACET level" en New Relic no distingue una peticion correcta
            // de un 500, y filtrar los fallos obliga a parsear el mensaje.
            String outcome = status >= 500 ? "server_error"
                    : status >= 400 ? "client_error"
                    : "success";

            var event = status >= 500 ? log.atError()
                    : status >= 400 ? log.atWarn()
                    : log.atInfo();

            event.addKeyValue("http.method", method)
                    .addKeyValue("http.target", path)
                    .addKeyValue("http.status_code", status)
                    .addKeyValue("http.duration_ms", duration)
                    .addKeyValue("http.outcome", outcome)
                    .addKeyValue("trace.id", traceId)
                    .log("{} Ending request {} {} -> {} ({} ms)",
                            method, path, query.isEmpty() ? "" : "?" + query, status, duration);

            span.setAttribute("http.outcome", outcome);
            span.setAttribute("http.duration_ms", duration);

            MDC.remove("http.method");
            MDC.remove("http.url");
            MDC.remove("url.query");
            MDC.remove("http.status_code");
            MDC.remove("http.duration_ms");
            // Los hilos de Tomcat se reutilizan: sin esto el baggage de esta
            // peticion se veria en la siguiente que caiga en el mismo hilo.
            Observability.clear(adopted.keySet().toArray(new String[0]));
        }
    }

    private Map<String, String> safeRequestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, request.getHeader(name));
            }
        });
        return headers;
    }

    private Map<String, String> safeResponseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaderNames().forEach(name -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, response.getHeader(name));
            }
        });
        return headers;
    }
}