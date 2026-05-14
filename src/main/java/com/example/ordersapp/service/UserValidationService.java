package com.example.ordersapp.service;

import com.example.ordersapp.client.dto.UserDto;
import com.example.ordersapp.client.dto.UserSingleEnvelope;
import com.example.ordersapp.exception.UserNotFoundException;
import com.example.ordersapp.exception.UsersServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final String usersServiceUrl;

    public UserValidationService(
            RestTemplateBuilder builder,
            @Value("${users.service.url}") String url,
            @Value("${users.service.username}") String username,
            @Value("${users.service.password}") String password) {
        this.restTemplate = builder
                .basicAuthentication(username, password)
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    return factory;
                })
                .build();
        this.usersServiceUrl = url;
    }

    public UserDto validateUser(String userId) {
        String endpoint = usersServiceUrl + "/users/" + userId;
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

    public String getUsersServiceUrl() {
        return usersServiceUrl;
    }
}