package com.example.ordersapp.system;

import com.example.ordersapp.system.ServiceStatus;
import com.example.ordersapp.system.SystemStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.example.ordersapp.services.UserValidationService;

import java.util.List;

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
        return new SystemStatusResponse(List.of(
            checkDatabase(),
            checkHttp(),
            checkExternalApiUsers()
        ));
    }

    private ServiceStatus checkDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ServiceStatus("database", "ok");
        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage(), e);
            return new ServiceStatus("database", shortMessage(e));
        }
    }


    private ServiceStatus checkHttp() {
        try {
            String response = restTemplate.getForObject("https://httpbin.org/get", String.class);
            return new ServiceStatus("http", response != null ? "ok" : "fail");
        } catch (Exception e) {
            log.error("HTTP health check failed: {}", e.getMessage(), e);
            return new ServiceStatus("http", shortMessage(e));
        }
    }

    private ServiceStatus checkExternalApiUsers() {
        // Pasa por el gateway, igual que la validacion de usuario: comprueba la
        // cadena completa orders -> tyk -> users, no solo el ultimo salto.
        String usersApiUrl = userValidationService.getUsersApiBaseUrl() + "/status";
        try {
            String response = userValidationService.getRestTemplate().getForObject(usersApiUrl, String.class);
            return new ServiceStatus("external_api_users", response != null ? "ok" : "fail");
        } catch (Exception e) {
            log.error("external_api_users health check failed: {}", e.getMessage(), e);
            return new ServiceStatus("external_api_users", shortMessage(e));
        }
    }

    private String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }
}
