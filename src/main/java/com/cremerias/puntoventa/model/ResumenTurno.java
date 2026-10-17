package com.cremerias.puntoventa.model;

import java.time.Instant;

/**
 * Totales de un turno para el corte de caja.
 *
 * @param numeroDevoluciones devoluciones de clientes registradas en el turno
 * @param totalDevuelto      importe devuelto por cualquier método
 * @param reembolsosEfectivo lo que salió del cajón para devolver a clientes
 */
public record ResumenTurno(
        Turno turno,
        int numeroVentas,
        int numeroCanceladas,
        long totalVentas,
        long totalCancelado,
        long ventasEfectivo,
        long ventasTarjeta,
        long ventasTransferencia,
        long entradas,
        long retiros,
        Instant generadoEn,
        int numeroDevoluciones,
        long totalDevuelto,
        long reembolsosEfectivo
) {

    /** Turno sin devoluciones. */
    public ResumenTurno(Turno turno, int numeroVentas, int numeroCanceladas, long totalVentas, long totalCancelado,
                        long ventasEfectivo, long ventasTarjeta, long ventasTransferencia, long entradas, long retiros,
                        Instant generadoEn) {
        this(turno, numeroVentas, numeroCanceladas, totalVentas, totalCancelado, ventasEfectivo, ventasTarjeta,
                ventasTransferencia, entradas, retiros, generadoEn, 0, 0, 0);
    }

    /** Efectivo que debería haber en el cajón: fondo + ventas en efectivo + entradas − retiros − reembolsos. */
    public long efectivoEsperado() {
        return turno.fondoInicialCentavos() + ventasEfectivo + entradas - retiros - reembolsosEfectivo;
    }

    /** Lo vendido en el turno menos lo devuelto a clientes. */
    public long ventaNeta() {
        return totalVentas - totalDevuelto;
    }
}
