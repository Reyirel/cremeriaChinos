package com.cremerias.puntoventa.model;

/**
 * Pago capturado en el cobro. {@code recibidoCentavos} es lo que entregó el cliente
 * (en efectivo puede ser más que el total y se le da cambio).
 */
public record Pago(MetodoPago metodo, long recibidoCentavos, String referencia) {
}
