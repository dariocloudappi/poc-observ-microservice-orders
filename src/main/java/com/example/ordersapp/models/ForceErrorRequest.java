package com.example.ordersapp.models;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Entrada del endpoint /force-errors.
 *
 * El rango 400-599 se valida con anotaciones a proposito: asi un valor fuera de
 * rango lo rechaza Spring con MethodArgumentNotValidException, que el
 * GlobalExceptionHandler ya traduce a un 400 con el cuerpo de error estandar y
 * el detalle por campo. No hay que escribir esa comprobacion a mano.
 */
public class ForceErrorRequest {

    @NotNull(message = "status is required")
    @Min(value = 400, message = "status must be 400 or greater")
    @Max(value = 599, message = "status must be 599 or lower")
    private Integer status;

    /** Opcional. Si no se envia, se usa un mensaje por defecto. */
    private String message;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
