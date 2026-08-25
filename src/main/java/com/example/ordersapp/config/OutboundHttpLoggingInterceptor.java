package com.example.ordersapp.config;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registra cada llamada HTTP saliente con su url, sus cabeceras y su cuerpo.
 *
 * Por qué existe, teniendo el agente OpenTelemetry: el agente instrumenta
 * RestTemplate y genera el span de cliente solo, con el método, la url y el
 * código de respuesta. Lo que NO hace, por diseño, es capturar el cuerpo de la
 * petición ni de la respuesta: eso no forma parte de las convenciones HTTP de
 * OTel y ninguna configuración lo activa. Si se quiere ver el payload, hay que
 * registrarlo desde la aplicación, y este interceptor es ese sitio.
 *
 * Las cabeceras sí las puede capturar el agente en el span mediante
 * OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS, pero aquí también se
 * dejan en el log para que la traza y el log cuenten lo mismo.
 *
 * Cuidado: el cuerpo puede contener datos personales. Es aceptable en un PoC
 * contra httpbin.org; contra un servicio real hay que filtrar o desactivarlo.
 */
public class OutboundHttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpLoggingInterceptor.class);

    /** Límite de atributo de New Relic: 4095. Se recorta antes de llegar. */
    private static final int MAX_BODY_CHARS = 2000;

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-api-key",
            "x-auth-token",
            "x-forwarded-authorization"
    );

    /** Nombre lógico de la dependencia, para poder filtrar por ella. */
    private final String dependencyName;

    /**
     * Si se registran los cuerpos. Debe quedar en false contra cualquier
     * dependencia que devuelva datos de personas: el cuerpo se enviaría entero a
     * New Relic. Solo tiene sentido en true contra destinos inocuos, como
     * httpbin.org, donde la gracia es precisamente ver el payload.
     */
    private final boolean logBodies;

    public OutboundHttpLoggingInterceptor(String dependencyName, boolean logBodies) {
        this.dependencyName = dependencyName;
        this.logBodies = logBodies;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long start = System.currentTimeMillis();

        // El agente OTel instrumenta RestTemplate con su propio interceptor en
        // primera posición, así que cuando este se ejecuta el span activo es el
        // span de CLIENTE de esta llamada. Marcarlo aquí permite localizar las
        // llamadas salientes propias entre todos los spans del servicio.
        Span span = Span.current();
        span.setAttribute("peer.service", dependencyName);
        span.setAttribute("http.client.dependency", dependencyName);
        span.setAttribute("url.full", request.getURI().toString());

        String requestBody = logBodies ? asText(body) : bodyOmitted(body);
        if (logBodies && !requestBody.isEmpty()) {
            span.setAttribute("http.request.body", requestBody);
        }

        log.atInfo()
                .addKeyValue("http.client.dependency", dependencyName)
                .addKeyValue("http.request.method", request.getMethod().name())
                .addKeyValue("url.full", request.getURI().toString())
                .addKeyValue("server.address", request.getURI().getHost())
                .addKeyValue("http.request.headers", safeHeaders(request.getHeaders()).toString())
                .addKeyValue("http.request.body", requestBody)
                .log("Outbound {} {} starting", request.getMethod(), request.getURI());

        ClientHttpResponse response = null;
        try {
            response = execution.execute(request, body);
            return response;
        } finally {
            long duration = System.currentTimeMillis() - start;

            if (response != null) {
                // Requiere BufferingClientHttpRequestFactory: sin ella, leer el
                // cuerpo aquí lo consume y el llamante recibe un stream vacío.
                String responseBody = logBodies ? readBody(response) : "<omitido>";
                int status = statusOf(response);

                log.atInfo()
                        .addKeyValue("http.client.dependency", dependencyName)
                        .addKeyValue("http.request.method", request.getMethod().name())
                        .addKeyValue("url.full", request.getURI().toString())
                        .addKeyValue("http.status_code", status)
                        .addKeyValue("http.response.status_code", status)
                        .addKeyValue("http.client.duration_ms", duration)
                        .addKeyValue("http.response.headers", safeHeaders(response.getHeaders()).toString())
                        .addKeyValue("http.response.body", responseBody)
                        .log("Outbound {} {} finished {} in {} ms",
                                request.getMethod(), request.getURI(), status, duration);
            } else {
                // La llamada no llegó a completarse: timeout, DNS, TLS.
                log.atWarn()
                        .addKeyValue("http.client.dependency", dependencyName)
                        .addKeyValue("url.full", request.getURI().toString())
                        .addKeyValue("http.client.duration_ms", duration)
                        .log("Outbound {} {} failed without response after {} ms",
                                request.getMethod(), request.getURI(), duration);
            }
        }
    }

    private int statusOf(ClientHttpResponse response) {
        try {
            return response.getStatusCode().value();
        } catch (IOException e) {
            return -1;
        }
    }

    private String readBody(ClientHttpResponse response) {
        try {
            return truncate(StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "<no se pudo leer el cuerpo: " + e.getMessage() + ">";
        }
    }

    /** Con logBodies desactivado se registra solo el tamaño, nunca el contenido. */
    private String bodyOmitted(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return "<omitido, " + body.length + " bytes>";
    }

    private String asText(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        return truncate(new String(body, StandardCharsets.UTF_8));
    }

    private String truncate(String value) {
        if (value.length() <= MAX_BODY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_BODY_CHARS) + "...<truncado>";
    }

    private Map<String, String> safeHeaders(org.springframework.http.HttpHeaders headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                safe.put(name, String.join(",", values));
            }
        });
        return safe;
    }
}
