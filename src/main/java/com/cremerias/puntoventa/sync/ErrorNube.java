package com.cremerias.puntoventa.sync;

/**
 * Supabase respondió con error. Los errores de red no llegan aquí ({@link java.io.IOException}):
 * esos solo significan "sin conexión" y se reintenta después.
 */
public class ErrorNube extends Exception {

    private final int estado;

    public ErrorNube(int estado, String mensaje) {
        super(mensaje);
        this.estado = estado;
    }

    /** Código HTTP. */
    public int estado() {
        return estado;
    }

    /**
     * El problema está en los datos enviados (una fila que la base rechaza), no en la conexión ni
     * en la sesión: reenviar lo mismo volverá a fallar.
     */
    public boolean esDeLosDatos() {
        return estado == 400 || estado == 403 || estado == 409 || estado == 422;
    }
}
