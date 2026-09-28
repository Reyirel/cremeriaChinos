package com.cremerias.puntoventa.model;

import java.time.Instant;

/** Turno de una caja (terminal). */
public record Turno(
        String id,
        String usuarioId,
        String usuarioNombre,
        Instant abiertoEn,
        long fondoInicialCentavos,
        String sucursalId,
        String dispositivoId,
        String dispositivoNombre
) {
}
