package com.example.ordersapp.exceptions;

/**
 * Error provocado desde el endpoint /force-errors.
 *
 * Se lanza una excepcion en lugar de devolver un ResponseEntity con el codigo
 * porque esa segunda forma no pasa por el GlobalExceptionHandler, que es donde
 * se registra la excepcion en el span, se marca su status y se anota error.code.
 * El resultado seria un error que no se corresponde con un error real en la
 * telemetria y no permitiria validar alertas ni paneles.
 *
 * Al lanzarla, el error recorre el mismo camino que un fallo real.
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
