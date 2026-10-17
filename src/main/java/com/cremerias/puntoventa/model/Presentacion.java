package com.cremerias.puntoventa.model;

import java.math.BigDecimal;

/**
 * Forma en que se vende un producto (pieza, caja de 12, kilo a granel...). También sirve para un
 * <b>derivado virtual</b> (p. ej. queso rebanado de 300 g): tiene su propio nombre, código de barras y
 * precio, pero consume la existencia del producto original.
 *
 * @param granel   si se vende por peso (solo productos por kilo)
 * @param factor   unidades base que contiene (1 caja = 12 piezas; 1 pieza de queso = 0.400 kg)
 * @param mermaPct merma del derivado al prepararlo (rebanar, empacar): al vender, además de lo vendido
 *                 se descuenta este porcentaje del producto original como merma. 0 = sin merma.
 */
public record Presentacion(String id, String productoId, String nombre, boolean granel, BigDecimal factor,
                           String codigoBarras, boolean principal, boolean activo, BigDecimal mermaPct) {

    public Presentacion {
        if (mermaPct == null) {
            mermaPct = BigDecimal.ZERO;
        }
    }

    /** Presentación sin merma (la mayoría). */
    public Presentacion(String id, String productoId, String nombre, boolean granel, BigDecimal factor,
                        String codigoBarras, boolean principal, boolean activo) {
        this(id, productoId, nombre, granel, factor, codigoBarras, principal, activo, BigDecimal.ZERO);
    }

    /** Si al venderla se registra merma del producto original. */
    public boolean conMerma() {
        return mermaPct.signum() > 0;
    }
}
