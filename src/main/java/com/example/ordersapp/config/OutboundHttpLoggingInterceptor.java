package com.example.ordersapp.config;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registra cada llamada HTTP saliente con su url, sus cabeceras y su cuerpo.
 *
 * Por que existe, teniendo el agente OpenTelemetry: el agente instrumenta
 * RestTemplate y genera el span de cliente solo, con el metodo, la url y el
 * codigo de respuesta. Lo que NO hace, por diseno, es capturar el cuerpo de la
 * peticion ni de la respuesta: eso no forma parte de las convenciones HTTP de
 * OTel y ninguna configuracion lo activa. Si se quiere ver el payload, hay que
 * registrarlo desde la aplicacion, y este interceptor es ese sitio.
 *
 * CABECERAS
 * ---------
 * Se emiten como atributos INDIVIDUALES, http.request.header.&lt;nombre&gt;, y no
 * como un unico texto con el mapa entero. La diferencia es practica: sobre
 * atributos individuales se puede hacer FACET y WHERE en NRQL; sobre un blob
 * "{a=1, b=2}" solo se puede buscar por subcadena.
 *
 * De las cabeceras sensibles no se registra el VALOR, pero si el NOMBRE, en
 * http.request.headers_redacted. Asi se puede confirmar que la peticion llevaba
 * Authorization sin exponer la credencial, que era imposible cuando
 * simplemente se omitian.
 *
 * OJO con traceparent y baggage: el agente puede inyectarlas por debajo de esta
 * capa, en el transporte HttpURLConnection, es decir DESPUES de que este
 * interceptor lea las cabeceras. Que no aparezcan aqui no significa que no se
 * hayan enviado. Para ver lo que llega de verdad al otro lado esta el endpoint
 * /get, que devuelve las cabeceras tal como las recibio el destino.
 */
public class OutboundHttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OutboundHttpLoggingInterceptor.class);

    /** Limite de atributo de New Relic: 4095. Se recorta antes de llegar. */
    private static final int MAX_BODY_CHARS = 2000;

    /** Un valor de cabecera muy largo no aporta y consume cuota de atributo. */
    private static final int MAX_HEADER_CHARS = 512;

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization",
            "x-api-key",
            "x-auth-token",
            "x-forwarded-authorization"
    );

    /** Nombre logico de la dependencia, para poder filtrar por ella. */
    private final String dependencyName;

    /**
     * Si se registran los cuerpos. Debe quedar en false contra cualquier
     * dependencia que devuelva datos de personas: el cuerpo se enviaria entero a
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

        String method = request.getMethod().name();
        String url = request.getURI().toString();

        // El agente OTel instrumenta RestTemplate con su propio interceptor en
        // primera posicion, asi que cuando este se ejecuta el span activo es el
        // span de CLIENTE de esta llamada. Marcarlo aqui permite localizar las
        // llamadas salientes propias entre todos los spans del servicio.
        Span span = Span.current();
        span.setAttribute("peer.service", dependencyName);
        span.setAttribute("http.client.dependency", dependencyName);
        span.setAttribute("url.full", url);
        span.setAttribute("http.request.method", method);

        String requestBody = logBodies ? asText(body) : bodyOmitted(body);
        if (logBodies && !requestBody.isEmpty()) {
            span.setAttribute("http.request.body", requestBody);
        }

        Map<String, String> requestHeaders = safeHeaders(request.getHeaders());
        String requestRedacted = redactedNames(request.getHeaders());

        LoggingEventBuilder startEvent = log.atInfo()
                .addKeyValue("http.client.dependency", dependencyName)
                .addKeyValue("http.request.method", method)
                .addKeyValue("url.full", url)
                .addKeyValue("server.address", request.getURI().getHost())
                .addKeyValue("http.request.header_count", request.getHeaders().size())
                .addKeyValue("http.request.headers_redacted", requestRedacted)
                .addKeyValue("http.request.body", requestBody);

        // Cada cabecera como atributo propio, tambien en el span.
        requestHeaders.forEach((name, value) -> {
            String key = "http.request.header." + name.toLowerCase();
            startEvent.addKeyValue(key, value);
            span.setAttribute(key, value);
        });
        span.setAttribute("http.request.headers_redacted", requestRedacted);

        startEvent.log("Llamada saliente a {} iniciada: {} {}", dependencyName, method, url);

        ClientHttpResponse response = null;
        try {
            response = execution.execute(request, body);
            return response;
        } finally {
            long duration = System.currentTimeMillis() - start;

            if (response != null) {
                // Requiere BufferingClientHttpRequestFactory: sin ella, leer el
                // cuerpo aqui lo consume y el llamante recibe un stream vacio.
                String responseBody = logBodies ? readBody(response) : "<omitido>";
                int status = statusOf(response);

                span.setAttribute("http.response.status_code", status);
                span.setAttribute("http.client.duration_ms", duration);

                // El nivel se deriva del codigo. Con todo en INFO no se puede
                // alertar sobre fallos de una dependencia sin parsear el mensaje.
                LoggingEventBuilder endEvent = status >= 500 ? log.atError()
                        : status >= 400 ? log.atWarn()
                        : log.atInfo();

                endEvent.addKeyValue("http.client.dependency", dependencyName)
                        .addKeyValue("http.request.method", method)
                        .addKeyValue("url.full", url)
                        .addKeyValue("http.status_code", status)
                        .addKeyValue("http.response.status_code", status)
                        .addKeyValue("http.client.duration_ms", duration)
                        .addKeyValue("http.response.headers_redacted", redactedNames(response.getHeaders()))
                        .addKeyValue("http.response.body", responseBody);

                safeHeaders(response.getHeaders()).forEach((name, value) -> {
                    String key = "http.response.header." + name.toLowerCase();
                    endEvent.addKeyValue(key, value);
                    span.setAttribute(key, value);
                });

                endEvent.log("Llamada saliente a {} terminada: {} {} -> {} en {} ms",
                        dependencyName, method, url, status, duration);
            } else {
                // La llamada no llego a completarse: timeout, DNS, TLS.
                span.setAttribute("http.client.duration_ms", duration);
                span.setAttribute("error.type", "NoResponse");

                log.atError()
                        .addKeyValue("http.client.dependency", dependencyName)
                        .addKeyValue("http.request.method", method)
                        .addKeyValue("url.full", url)
                        .addKeyValue("http.client.duration_ms", duration)
                        .addKeyValue("error.type", "NoResponse")
                        .log("Llamada saliente a {} sin respuesta tras {} ms: {} {}",
                                dependencyName, duration, method, url);
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
            return truncate(StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8),
                    MAX_BODY_CHARS);
        } catch (IOException e) {
            return "<no se pudo leer el cuerpo: " + e.getMessage() + ">";
        }
    }

    /** Con logBodies desactivado se registra solo el tamano, nunca el contenido. */
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
        return truncate(new String(body, StandardCharsets.UTF_8), MAX_BODY_CHARS);
    }

    private String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...<truncado>";
    }

    private Map<String, String> safeHeaders(HttpHeaders headers) {
        Map<String, String> safe = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                safe.put(name, truncate(String.join(",", values), MAX_HEADER_CHARS));
            }
        });
        return safe;
    }

    /**
     * Nombres de las cabeceras cuyo valor se ha ocultado. Se registra el nombre
     * y nunca el valor: sirve para confirmar que la credencial viajaba sin
     * exponerla.
     */
    private String redactedNames(HttpHeaders headers) {
        List<String> names = new ArrayList<>(new TreeSet<>(
                headers.keySet().stream()
                        .map(String::toLowerCase)
                        .filter(SENSITIVE_HEADERS::contains)
                        .toList()));
        return names.isEmpty() ? "" : String.join(",", names);
    }
}
