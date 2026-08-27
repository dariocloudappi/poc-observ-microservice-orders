package com.example.ordersapp.exceptions;

/**
 * Error provocado a proposito desde el endpoint /force-errors.
 *
 * POR QUE UNA EXCEPCION Y NO UN ResponseEntity DIRECTO
 * ----------------------------------------------------
 * Devolver el codigo desde el controlador seria mas corto, pero se saltaria el
 * GlobalExceptionHandler, que es donde la PoC registra la excepcion en el span,
 * marca el status del span y anota error.code. El resultado seria un error que
 * NO se parece a un error real en la telemetria, y entonces no sirve para
 * probar alertas ni dashboards.
 *
 * Lanzandola, el error recorre exactamente el mismo camino que un fallo de
 * verdad.
 */
public class ForcedErrorException extends RuntimeException {

    private final int status;

    public ForcedErrorException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
