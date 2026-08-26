package com.example.ordersapp.services;

import com.example.ordersapp.client.dtos.UserDto;
import com.example.ordersapp.config.OutboundHttpLoggingInterceptor;
import com.example.ordersapp.client.dtos.UserSingleEnvelope;
import com.example.ordersapp.exceptions.UserNotFoundException;
import com.example.ordersapp.exceptions.UsersServiceUnavailableException;
import com.example.ordersapp.observability.Observability;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Service
public class UserValidationService {

    private static final Logger log = LoggerFactory.getLogger(UserValidationService.class);

    private static final String DEPENDENCY = "tyk-gateway";

    private final RestTemplate restTemplate;

    /** Base de la API de usuarios TAL COMO SE PUBLICA en el gateway. */
    private final String usersApiBaseUrl;

    /**
     * Las llamadas NO van directas a la Web App de microservice-users: van al
     * gateway de Tyk, que las enruta. De ahi que la url sea la del gateway mas
     * el listen path de la API de usuarios, y que las credenciales sean las de
     * CONSUMIDOR del gateway, no el Basic Auth del microservicio.
     *
     * Tyk sustituye la cabecera Authorization por la del upstream antes de
     * reenviar, asi que este servicio nunca conoce la credencial de users.
     */
    public UserValidationService(
            RestTemplateBuilder builder,
            @Value("${gateway.url}") String gatewayUrl,
            @Value("${gateway.users-path}") String usersPath,
            @Value("${gateway.username}") String username,
            @Value("${gateway.password}") String password) {
        this.restTemplate = builder
                .basicAuthentication(username, password)
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    // Buffering para que el interceptor pueda leer el cuerpo sin
                    // consumirlo. Aqui los cuerpos no se registran, pero el
                    // wrapper deja la puerta abierta sin romper nada.
                    return new BufferingClientHttpRequestFactory(factory);
                })
                // logBodies en FALSE a proposito: la respuesta de este servicio
                // trae nombre y email de una persona. Se registran la url, el
                // codigo y la duracion, nunca el payload. La cabecera
                // Authorization tampoco se registra, va en SENSITIVE_HEADERS.
                .additionalInterceptors(new OutboundHttpLoggingInterceptor(DEPENDENCY, false))
                .build();
        // Se normaliza aqui una sola vez para no repetir la concatenacion.
        this.usersApiBaseUrl = trimSlash(gatewayUrl) + "/" + usersPath.replaceAll("^/+", "");
    }

    /**
     * Valida el usuario contra poc-microservice-users a traves del gateway.
     *
     * PROPAGACION ENTRE SERVICIOS
     * ---------------------------
     * La llamada se envuelve en un ambito de Baggage. Es lo unico que hace que
     * el contexto de negocio de este servicio sea visible en el siguiente: los
     * atributos de span se quedan en el span donde se ponen y no cruzan el
     * cable. El Baggage viaja en la cabecera baggage del W3C, el agente lo
     * inyecta al salir y lo extrae al entrar, y el filtro de users lo adopta
     * como atributos propios.
     *
     * Resultado practico: en la traza de users se puede ver que la peticion
     * venia de orders y para que usuario, sin tocar el codigo de users.
     */
    public UserDto validateUser(String userId) {
        String endpoint = usersApiBaseUrl + "/users/" + userId;

        Observability.attr("user.id", userId);
        Observability.attr("http.client.dependency", DEPENDENCY);
        Observability.attr("users_api.endpoint", endpoint);

        log.atInfo()
                .addKeyValue("user.id", userId)
                .addKeyValue("http.client.dependency", DEPENDENCY)
                .addKeyValue("users_api.endpoint", endpoint)
                .log("Validando usuario {} a traves del gateway", userId);

        long start = System.nanoTime();

        try (Scope ignored = Observability.propagate(Map.of(
                "caller.service", "poc-microservice-orders",
                "caller.user_id", userId))) {

            ResponseEntity<UserSingleEnvelope> response =
                    restTemplate.getForEntity(endpoint, UserSingleEnvelope.class);

            long elapsedMs = elapsedMs(start);
            Observability.attr("users_api.status_code", response.getStatusCode().value());
            Observability.attr("users_api.duration_ms", elapsedMs);

            UserSingleEnvelope envelope = response.getBody();
            if (envelope == null || envelope.getData() == null) {
                Observability.attr("error.type", "EmptyUsersResponse");
                // ERROR de verdad: el upstream contesto 2xx con un cuerpo que
                // incumple el contrato. Es un fallo de integracion, no del
                // cliente.
                log.atError()
                        .addKeyValue("user.id", userId)
                        .addKeyValue("users_api.duration_ms", elapsedMs)
                        .addKeyValue("error.type", "EmptyUsersResponse")
                        .log("Respuesta vacia o sin data al validar el usuario {}", userId);
                throw new UsersServiceUnavailableException("Respuesta invalida del servicio de usuarios");
            }

            // El nombre NO se registra: es un dato personal y no aporta nada al
            // diagnostico. Basta con saber que la validacion fue correcta.
            log.atInfo()
                    .addKeyValue("user.id", userId)
                    .addKeyValue("users_api.status_code", response.getStatusCode().value())
                    .addKeyValue("users_api.duration_ms", elapsedMs)
                    .log("Usuario validado correctamente: id={} en {} ms", userId, elapsedMs);
            return envelope.getData();

        } catch (HttpClientErrorException e) {
            long elapsedMs = elapsedMs(start);
            Observability.attr("users_api.status_code", e.getStatusCode().value());
            Observability.attr("users_api.duration_ms", elapsedMs);

            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // WARN: el usuario no existe. Es una respuesta legitima del
                // upstream, no una averia.
                Observability.attr("error.type", "UserNotFound");
                log.atWarn()
                        .addKeyValue("user.id", userId)
                        .addKeyValue("users_api.status_code", 404)
                        .addKeyValue("users_api.duration_ms", elapsedMs)
                        .addKeyValue("error.type", "UserNotFound")
                        .log("El servicio de usuarios no conoce al usuario {}", userId);
                throw new UserNotFoundException("Usuario " + userId + " no encontrado");
            }

            Observability.attr("error.type", e.getClass().getSimpleName());
            Span.current().recordException(e);
            log.atError()
                    .addKeyValue("user.id", userId)
                    .addKeyValue("users_api.status_code", e.getStatusCode().value())
                    .addKeyValue("users_api.duration_ms", elapsedMs)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Error HTTP {} al validar el usuario {} tras {} ms",
                            e.getStatusCode(), userId, elapsedMs);
            throw new UsersServiceUnavailableException("Error al contactar el servicio de usuarios", e);

        } catch (RestClientException e) {
            long elapsedMs = elapsedMs(start);
            Observability.attr("users_api.duration_ms", elapsedMs);
            Observability.attr("error.type", e.getClass().getSimpleName());
            Span.current().recordException(e);

            // Distinguir esto del caso anterior importa: aqui no hubo respuesta
            // en absoluto, o sea gateway caido, DNS o timeout. Es el sintoma de
            // una averia de infraestructura, no de un pedido concreto.
            log.atError()
                    .addKeyValue("user.id", userId)
                    .addKeyValue("users_api.endpoint", endpoint)
                    .addKeyValue("users_api.duration_ms", elapsedMs)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Sin respuesta del servicio de usuarios tras {} ms: {}",
                            elapsedMs, e.getMessage());
            throw new UsersServiceUnavailableException("Servicio de usuarios no disponible", e);
        }
    }

    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    /** Base de la API de usuarios a traves del gateway, sin barra final. */
    public String getUsersApiBaseUrl() {
        return usersApiBaseUrl;
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
