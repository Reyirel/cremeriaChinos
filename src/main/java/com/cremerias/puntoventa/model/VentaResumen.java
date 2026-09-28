package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Renglón del listado de ventas del turno. */
public record VentaResumen(
        String id,
        String folio,
        Instant fecha,
        BigDecimal articulos,
        long totalCentavos,
        String metodos,
        boolean cancelada
) {
}
