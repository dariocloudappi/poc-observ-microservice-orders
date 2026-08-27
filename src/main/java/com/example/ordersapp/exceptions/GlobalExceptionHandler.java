package com.example.ordersapp.exceptions;

import com.example.ordersapp.observability.Observability;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ademas de traducir la excepcion a una respuesta, cada handler la registra en
 * el span.
 *
 * Por que importa: un @RestControllerAdvice CONSUME la excepcion. Al no
 * propagarse, el agente no la ve y el span queda sin marca de error. El
 * sintoma era que se devolvia un 500 o un 503 y la traza aparecia
 * aparentemente correcta, sin excepcion asociada y sin poder agrupar por tipo
 * de error.
 *
 * Los 4xx NO se marcan como error del span a proposito: son errores del
 * cliente. Si se marcasen, la tasa de error del servicio incluiria cada 404 y
 * dejaria de servir para detectar averias reales. El 503 de users caido SI se
 * marca: ahi la averia es real y ajena al cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_STATUS    = "status";
    private static final String FIELD_ERROR     = "error";
    private static final String FIELD_MESSAGE   = "message";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        annotateSpan(ex, HttpStatus.NOT_FOUND, "NOT_FOUND", false);
        log.atWarn()
                .addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "NOT_FOUND")
                .addKeyValue("http.status_code", 404)
                .log("Recurso no encontrado: {}", ex.getMessage());
        return errorResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UserNotFoundException ex) {
        annotateSpan(ex, HttpStatus.NOT_FOUND, "USER_NOT_FOUND", false);
        // Se separa de NOT_FOUND para poder distinguir en NRQL un pedido
        // inexistente de un usuario inexistente: el segundo apunta a un
        // problema de datos entre orders y users, no a un cliente despistado.
        log.atWarn()
                .addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "USER_NOT_FOUND")
                .addKeyValue("error.origin", "users-service")
                .addKeyValue("http.status_code", 404)
                .log("Usuario no encontrado: {}", ex.getMessage());
        return errorResponse(HttpStatus.NOT_FOUND, "User Not Found", ex.getMessage());
    }

    @ExceptionHandler(UsersServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUsersServiceUnavailable(UsersServiceUnavailableException ex) {
        annotateSpan(ex, HttpStatus.SERVICE_UNAVAILABLE, "USERS_SERVICE_UNAVAILABLE", true);
        Observability.attr("error.dependency", "tyk-gateway");
        log.atError()
                .addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "USERS_SERVICE_UNAVAILABLE")
                .addKeyValue("error.dependency", "tyk-gateway")
                .addKeyValue("http.status_code", 503)
                .setCause(ex)
                .log("Servicio de usuarios no disponible: {}", ex.getMessage());
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Users Service Unavailable", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        annotateSpan(ex, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", false);

        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fieldErrors.put(e.getField(), e.getDefaultMessage()));

        // Los campos que fallan se anotan aparte del mensaje: asi se puede
        // hacer un FACET por campo y ver que parte del contrato incumplen mas
        // los clientes.
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField())
                .distinct()
                .collect(Collectors.joining(","));
        Observability.attr("error.invalid_fields", fields);
        Observability.attr("error.invalid_field_count", fieldErrors.size());

        log.atWarn()
                .addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "VALIDATION_ERROR")
                .addKeyValue("error.invalid_fields", fields)
                .addKeyValue("http.status_code", 400)
                .log("Error de validacion en los campos: {}", fields);

        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "Validation Error");
        body.put("errors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Error provocado desde /force-errors.
     *
     * Reutiliza annotateSpan y errorResponse para que la telemetria Y el cuerpo
     * de la respuesta sean IDENTICOS a los de un error real de este servicio: si
     * es 5xx se registra la excepcion en el span y su status pasa a ERROR. Es lo
     * que hace que sirva para probar una alerta de verdad.
     */
    @ExceptionHandler(ForcedErrorException.class)
    public ResponseEntity<Map<String, Object>> handleForcedError(ForcedErrorException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatus());
        boolean serverFault = status.is5xxServerError();

        // Marca que permite separar el ruido de las demos de los errores reales.
        Observability.attr("error.forced", true);
        annotateSpan(ex, status, "FORCED_ERROR", serverFault);

        var event = serverFault ? log.atError() : log.atWarn();
        event.addKeyValue("error.type", ex.getClass().getSimpleName())
                .addKeyValue("error.code", "FORCED_ERROR")
                .addKeyValue("error.forced", true)
                .addKeyValue("http.status_code", status.value())
                .log("Error provocado {}: {}", status.value(), ex.getMessage());

        return errorResponse(status, "Forced Error", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        annotateSpan(ex, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", true);
        log.atError()
                .addKeyValue("error.type", ex.getClass().getName())
                .addKeyValue("error.code", "INTERNAL_ERROR")
                .addKeyValue("http.status_code", 500)
                .setCause(ex)
                .log("Error inesperado: {}", ex.getMessage());
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Error interno del servidor");
    }

    private void annotateSpan(Exception ex, HttpStatus status, String code, boolean serverFault) {
        Observability.attr("error.type", ex.getClass().getSimpleName());
        Observability.attr("error.code", code);
        Observability.attr("error.handled", true);

        Span span = Span.current();
        if (serverFault) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR, code);
        } else {
            span.setAttribute("error.client_status", status.value());
        }
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = baseBody(status, error);
        body.put(FIELD_MESSAGE, message);
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String error) {
        Map<String, Object> body = new HashMap<>();
        body.put(FIELD_TIMESTAMP, LocalDateTime.now().toString());
        body.put(FIELD_STATUS, status.value());
        body.put(FIELD_ERROR, error);
        return body;
    }
}
