package com.cremerias.puntoventa.model;

import java.time.Instant;

/** Un gasto (fijo o variable) de una sucursal. */
public record Gasto(String id, String folio, String sucursalId, Instant fecha, CategoriaGasto categoria,
                    TipoGasto tipo, String concepto, long montoCentavos, String nota, String usuarioId,
                    String usuario) {
}
