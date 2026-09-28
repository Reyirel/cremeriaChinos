package com.cremerias.puntoventa.model;

import java.time.Instant;

/** Totales de un turno para el corte de caja. */
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
        Instant generadoEn
) {

    /** Efectivo que debería haber en el cajón. */
    public long efectivoEsperado() {
        return turno.fondoInicialCentavos() + ventasEfectivo + entradas - retiros;
    }
}
