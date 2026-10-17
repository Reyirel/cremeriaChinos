package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Nivel de precio por volumen (mayoreo o súper mayoreo) de un producto. Menudeo es el precio del
 * lote y no lleva escala.
 *
 * @param presentacionId   presentación a la que aplica; {@code null} = todas las del producto
 * @param sucursalId       sucursal; {@code null} = todas
 * @param cantidadMinima   en unidades base del producto (una caja de 12 cuenta 12 piezas)
 * @param descuentoPct     para {@link Tipo#DESCUENTO_PCT}: porcentaje sobre el precio del lote
 * @param precioCentavos   para {@link Tipo#PRECIO_FIJO}: precio por unidad de la presentación (o por kg a granel)
 */
public record Escala(String id, String folio, String productoId, String presentacionId, String sucursalId,
                     Nivel nivel, BigDecimal cantidadMinima, Tipo tipo, BigDecimal descuentoPct, Long precioCentavos,
                     LocalDate vigenteDesde, LocalDate vigenteHasta, boolean activo) {

    public enum Nivel {
        MENUDEO("Menudeo"), MAYOREO("Mayoreo"), SUPER_MAYOREO("Súper mayoreo");

        private final String nombre;

        Nivel(String nombre) {
            this.nombre = nombre;
        }

        public String nombre() {
            return nombre;
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    public enum Tipo {
        DESCUENTO_PCT("Descuento sobre el precio"), PRECIO_FIJO("Precio fijo");

        private final String nombre;

        Tipo(String nombre) {
            this.nombre = nombre;
        }

        public String nombre() {
            return nombre;
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    public boolean vigenteEn(LocalDate dia) {
        return activo && !dia.isBefore(vigenteDesde) && (vigenteHasta == null || !dia.isAfter(vigenteHasta));
    }

    /** Si la escala cubre esta presentación y sucursal. */
    public boolean cubre(String presentacion, String sucursal) {
        return (presentacionId == null || presentacionId.equals(presentacion))
                && (sucursalId == null || sucursalId.equals(sucursal));
    }

    /** Presentación + sucursal (3) &gt; presentación (2) &gt; sucursal (1) &gt; todo el producto (0). */
    public int especificidad() {
        return (presentacionId != null ? 2 : 0) + (sucursalId != null ? 1 : 0);
    }
}
