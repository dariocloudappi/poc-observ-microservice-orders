package com.example.ordersapp.controllers;

import com.example.ordersapp.exceptions.ForcedErrorException;
import com.example.ordersapp.models.ForceErrorRequest;
import com.example.ordersapp.observability.Observability;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provoca errores a demanda, para poder probar alertas y dashboards sin esperar
 * a que algo se rompa de verdad.
 *
 * El error recorre el mismo camino que un fallo real: se lanza una excepcion que
 * atiende el GlobalExceptionHandler, asi que en la telemetria queda con la
 * excepcion registrada en el span, el status del span en ERROR si es 5xx, y el
 * cuerpo de error estandar del servicio.
 *
 * Se marca ademas con error.forced = true. Eso permite excluir el ruido de las
 * demos de cualquier metrica de errores reales:
 *
 *   SELECT count(*) FROM Log WHERE error.code IS NOT NULL
 *   AND error.forced IS NULL
 *
 * SEGURIDAD: este endpoint permite a cualquier consumidor autenticado provocar
 * 5xx a voluntad. Va detras del Basic Auth como el resto, pero se puede apagar
 * sin desplegar codigo con app.force-errors.enabled a false, y entonces
 * responde 404 para no revelar que existe.
 */
@RestController
@RequestMapping("/force-errors")
public class ForceErrorController {

    private static final Logger log = LoggerFactory.getLogger(ForceErrorController.class);

    private static final String DEFAULT_MESSAGE = "Error provocado a proposito desde /force-errors";

    private final boolean enabled;

    public ForceErrorController(
            @Value("${app.force-errors.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Forma principal: el status llega como propiedad del cuerpo.
     *
     * <pre>
     * POST /force-errors
     * { "status": 503, "message": "opcional" }
     * </pre>
     */
    @PostMapping
    public ResponseEntity<Void> force(@Valid @RequestBody ForceErrorRequest request) {
        return trigger(request.getStatus(), request.getMessage());
    }

    /**
     * Atajo por query param, para poder generar muchos errores en un bucle
     * cuando se prueba una alerta.
     *
     * <pre>
     * for i in $(seq 1 50); do curl -u user:pass "$URL/force-errors?status=503"; done
     * </pre>
     */
    @GetMapping
    public ResponseEntity<Void> force(
            @RequestParam Integer status,
            @RequestParam(required = false) String message) {
        return trigger(status, message);
    }

    private ResponseEntity<Void> trigger(Integer status, String message) {
        // Apagado: 404 y no 403, para no confirmar que la ruta existe.
        if (!enabled) {
            log.atWarn()
                    .addKeyValue("api.operation", "system.force_error")
                    .log("Se ha intentado usar /force-errors estando desactivado");
            return ResponseEntity.notFound().build();
        }

        // El rango se valida aqui tambien, no solo con anotaciones: la variante
        // GET no pasa por la validacion de @Valid del cuerpo.
        if (status == null || status < 400 || status > 599) {
            throw new ForcedErrorException(400,
                    "El parametro status es obligatorio y debe estar entre 400 y 599");
        }

        String detail = (message == null || message.isBlank()) ? DEFAULT_MESSAGE : message;

        Observability.attr("api.operation", "system.force_error");
        Observability.attr("error.forced", true);
        Observability.attr("error.forced_status", status);

        log.atInfo()
                .addKeyValue("api.operation", "system.force_error")
                .addKeyValue("error.forced_status", status)
                .log("Provocando un error {} a peticion del cliente", status);

        throw new ForcedErrorException(status, detail);
    }
}
