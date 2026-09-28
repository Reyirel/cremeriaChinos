package com.cremerias.puntoventa.model;

import java.time.Instant;

/** Turno ya cerrado, para el historial de cortes. */
public record CorteRealizado(
        Turno turno,
        Instant cerradoEn,
        int numeroVentas,
        long totalVendido,
        long efectivoEsperado,
        long efectivoContado,
        long diferencia,
        String cerradoPor,
        String notas
) {
}
