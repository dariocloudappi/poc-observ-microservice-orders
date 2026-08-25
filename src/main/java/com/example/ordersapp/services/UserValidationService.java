package com.example.ordersapp.services;

import com.example.ordersapp.client.dtos.UserDto;
import com.example.ordersapp.config.OutboundHttpLoggingInterceptor;
import com.example.ordersapp.client.dtos.UserSingleEnvelope;
import com.example.ordersapp.exceptions.UserNotFoundException;
import com.example.ordersapp.exceptions.UsersServiceUnavailableException;
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

@Service
public class UserValidationService {

    private static final Logger log = LoggerFactory.getLogger(UserValidationService.class);
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
                .additionalInterceptors(new OutboundHttpLoggingInterceptor("tyk-gateway", false))
                .build();
        // Se normaliza aqui una sola vez para no repetir la concatenacion.
        this.usersApiBaseUrl = trimSlash(gatewayUrl) + "/" + usersPath.replaceAll("^/+", "");
    }

    public UserDto validateUser(String userId) {
        String endpoint = usersApiBaseUrl + "/users/" + userId;
        log.debug("Validando usuario {} contra {}", userId, endpoint);
        try {
            ResponseEntity<UserSingleEnvelope> response =
                    restTemplate.getForEntity(endpoint, UserSingleEnvelope.class);
            UserSingleEnvelope envelope = response.getBody();
            if (envelope == null || envelope.getData() == null) {
                log.error("Respuesta vacia o sin data para usuario {}", userId);
                throw new UsersServiceUnavailableException("Respuesta invalida del servicio de usuarios");
            }
            log.debug("Usuario validado: id={} name={}", userId, envelope.getData().getName());
            return envelope.getData();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new UserNotFoundException("Usuario '" + userId + "' no encontrado");
            }
            log.error("Error HTTP {} al validar usuario {}", e.getStatusCode(), userId);
            throw new UsersServiceUnavailableException("Error al contactar el servicio de usuarios", e);
        } catch (RestClientException e) {
            log.error("Error de conexion al servicio de usuarios", e);
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
}