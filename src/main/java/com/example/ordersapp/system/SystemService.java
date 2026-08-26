package com.example.ordersapp.system;

import com.example.ordersapp.observability.Observability;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.example.ordersapp.services.UserValidationService;

import java.util.List;

/**
 * Cada comprobacion anota su resultado y su duracion como atributos, no solo
 * en el cuerpo de la respuesta.
 *
 * Sin eso, la unica forma de saber en New Relic si una dependencia estaba
 * disponible era leer el JSON devuelto, que no es consultable. Con
 * health.&lt;dep&gt;.status y health.&lt;dep&gt;.duration_ms se puede graficar la
 * disponibilidad y la latencia de cada salto directamente en NRQL, y ver cual
 * de los tres es el que se degrada.
 */
@Service
public class SystemService {

    private static final Logger log = LoggerFactory.getLogger(SystemService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final UserValidationService userValidationService;

    // El RestTemplate se inyecta, no se instancia: uno creado con "new" no pasa
    // por HttpClientConfig y se queda sin el interceptor de salida, asi que la
    // llamada no se identificaria con su dependencia ni registraria el cuerpo.
    public SystemService(JdbcTemplate jdbcTemplate,
                         RestTemplate httpBinRestTemplate,
                         UserValidationService userValidationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = httpBinRestTemplate;
        this.userValidationService = userValidationService;
    }

    public SystemStatusResponse getStatus() {
        log.debug("Ejecutando comprobacion de estado sobre las tres dependencias");

        ServiceStatus database = checkDatabase();
        ServiceStatus http = checkHttp();
        ServiceStatus usersApi = checkExternalApiUsers();

        long degraded = List.of(database, http, usersApi).stream()
                .filter(s -> !"ok".equals(s.getStatus()))
                .count();

        Observability.attr("health.degraded_count", degraded);
        Observability.attr("health.overall", degraded == 0 ? "ok" : "degraded");

        if (degraded > 0) {
            log.atWarn()
                    .addKeyValue("health.overall", "degraded")
                    .addKeyValue("health.degraded_count", degraded)
                    .log("Estado degradado: {} de 3 dependencias no responden", degraded);
        } else {
            log.atInfo()
                    .addKeyValue("health.overall", "ok")
                    .log("Las tres dependencias responden correctamente");
        }
        return new SystemStatusResponse(List.of(database, http, usersApi));
    }

    private ServiceStatus checkDatabase() {
        long start = System.nanoTime();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long elapsedMs = elapsedMs(start);
            recordOk("database", elapsedMs);
            return new ServiceStatus("database", "ok");
        } catch (Exception e) {
            return recordFailure("database", e, elapsedMs(start));
        }
    }

    private ServiceStatus checkHttp() {
        long start = System.nanoTime();
        try {
            String response = restTemplate.getForObject("https://httpbin.org/get", String.class);
            long elapsedMs = elapsedMs(start);
            if (response == null) {
                Observability.attr("health.http.status", "fail");
                Observability.attr("health.http.duration_ms", elapsedMs);
                log.atWarn()
                        .addKeyValue("health.http.status", "fail")
                        .addKeyValue("health.http.duration_ms", elapsedMs)
                        .log("httpbin respondio con cuerpo vacio tras {} ms", elapsedMs);
                return new ServiceStatus("http", "fail");
            }
            recordOk("http", elapsedMs);
            return new ServiceStatus("http", "ok");
        } catch (Exception e) {
            return recordFailure("http", e, elapsedMs(start));
        }
    }

    private ServiceStatus checkExternalApiUsers() {
        // Pasa por el gateway, igual que la validacion de usuario: comprueba la
        // cadena completa orders -> tyk -> users, no solo el ultimo salto.
        String usersApiUrl = userValidationService.getUsersApiBaseUrl() + "/status";
        Observability.attr("health.external_api_users.endpoint", usersApiUrl);

        long start = System.nanoTime();
        try {
            String response = userValidationService.getRestTemplate()
                    .getForObject(usersApiUrl, String.class);
            long elapsedMs = elapsedMs(start);
            if (response == null) {
                Observability.attr("health.external_api_users.status", "fail");
                Observability.attr("health.external_api_users.duration_ms", elapsedMs);
                log.atWarn()
                        .addKeyValue("health.external_api_users.status", "fail")
                        .addKeyValue("health.external_api_users.duration_ms", elapsedMs)
                        .log("La cadena orders -> tyk -> users devolvio cuerpo vacio tras {} ms",
                                elapsedMs);
                return new ServiceStatus("external_api_users", "fail");
            }
            recordOk("external_api_users", elapsedMs);
            return new ServiceStatus("external_api_users", "ok");
        } catch (Exception e) {
            return recordFailure("external_api_users", e, elapsedMs(start));
        }
    }

    private void recordOk(String dependency, long elapsedMs) {
        Observability.attr("health." + dependency + ".status", "ok");
        Observability.attr("health." + dependency + ".duration_ms", elapsedMs);
        log.atDebug()
                .addKeyValue("health." + dependency + ".duration_ms", elapsedMs)
                .log("Dependencia {} accesible en {} ms", dependency, elapsedMs);
    }

    private ServiceStatus recordFailure(String dependency, Exception e, long elapsedMs) {
        Observability.attr("health." + dependency + ".status", "error");
        Observability.attr("health." + dependency + ".duration_ms", elapsedMs);
        Observability.attr("error.type", e.getClass().getSimpleName());

        // La excepcion se registra en el span: el health check la captura y
        // devuelve un 200 o un 503 con el detalle en el cuerpo, asi que sin
        // esto el fallo no aparece en la traza por ningun lado.
        Span.current().recordException(e);

        log.atError()
                .addKeyValue("health." + dependency + ".status", "error")
                .addKeyValue("health." + dependency + ".duration_ms", elapsedMs)
                .addKeyValue("error.type", e.getClass().getSimpleName())
                .setCause(e)
                .log("Comprobacion de {} fallida tras {} ms: {}",
                        dependency, elapsedMs, e.getMessage());
        return new ServiceStatus(dependency, shortMessage(e));
    }

    private String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
