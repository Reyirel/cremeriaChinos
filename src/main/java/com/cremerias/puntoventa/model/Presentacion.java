package com.cremerias.puntoventa.model;

import java.math.BigDecimal;

/**
 * Forma en que se vende un producto (pieza, caja de 12, kilo a granel...).
 *
 * @param granel si se vende por peso (solo productos por kilo)
 * @param factor unidades base que contiene (1 caja = 12 piezas; 1 pieza de queso = 0.400 kg)
 */
public record Presentacion(String id, String productoId, String nombre, boolean granel, BigDecimal factor,
                           String codigoBarras, boolean principal, boolean activo) {
}
