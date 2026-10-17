package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Porcentaje que convierte un costo en precio para un canal comercial. La regla más específica
 * gana (ver {@link #especificidad()}); sin regla, el precio es el costo.
 *
 * @param porcentaje    p. ej. 23 (por ciento)
 * @param vigenteHasta  inclusive; {@code null} = sin fin
 */
public record ReglaPrecio(String id, String folio, Canal canal, Tipo tipo, BigDecimal porcentaje,
                          String categoriaId, String productoId, String presentacionId, String sucursalId,
                          LocalDate vigenteDesde, LocalDate vigenteHasta, String notas, boolean activo) {

    /** Canal comercial al que aplica la regla. */
    public enum Canal {
        PUBLICO("Público en sucursal", "Precio al cliente en la caja; se sugiere al surtir"),
        BODEGA_SUCURSAL("Bodega a cremerías", "Lo que la sucursal le debe a la bodega por lo surtido"),
        RUTA_SIERRA("Ruta de la Sierra", "Lo que se le cobra a la ruta por lo surtido");

        private final String nombre;
        private final String descripcion;

        Canal(String nombre, String descripcion) {
            this.nombre = nombre;
            this.descripcion = descripcion;
        }

        public String nombre() {
            return nombre;
        }

        public String descripcion() {
            return descripcion;
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    /** Cómo se aplica el porcentaje. */
    public enum Tipo {
        /** precio = costo × (1 + %); el porcentaje se calcula sobre el costo. */
        RECARGO_COSTO("Recargo sobre el costo"),
        /** precio = costo ÷ (1 − %); el porcentaje es la parte del precio que queda de margen. */
        MARGEN_PRECIO("Margen sobre el precio");

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

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /**
     * Qué tan específica es: presentación + sucursal (7) &gt; presentación (6) &gt; producto + sucursal (5)
     * &gt; producto (4) &gt; categoría + sucursal (3) &gt; categoría (2) &gt; sucursal (1) &gt; general (0).
     */
    public int especificidad() {
        int base = presentacionId != null ? 6 : productoId != null ? 4 : categoriaId != null ? 2 : 0;
        return base + (sucursalId != null ? 1 : 0);
    }

    public boolean vigenteEn(LocalDate dia) {
        return activo && !dia.isBefore(vigenteDesde) && (vigenteHasta == null || !dia.isAfter(vigenteHasta));
    }

    /** Si la regla cubre este producto, presentación y sucursal (un campo nulo en la regla cubre todo). */
    public boolean cubre(String producto, String categoria, String presentacion, String sucursal) {
        return (productoId == null || productoId.equals(producto))
                && (categoriaId == null || categoriaId.equals(categoria))
                && (presentacionId == null || presentacionId.equals(presentacion))
                && (sucursalId == null || sucursalId.equals(sucursal));
    }

    /** Precio que resulta de un costo (en centavos), redondeado al centavo. */
    public long precioDesde(long costoCentavos) {
        return precio(tipo, porcentaje, costoCentavos);
    }

    public static long precio(Tipo tipo, BigDecimal porcentaje, long costoCentavos) {
        BigDecimal costo = BigDecimal.valueOf(costoCentavos);
        BigDecimal precio = tipo == Tipo.RECARGO_COSTO
                ? costo.multiply(CIEN.add(porcentaje)).divide(CIEN, 10, RoundingMode.HALF_UP)
                : costo.multiply(CIEN).divide(CIEN.subtract(porcentaje), 10, RoundingMode.HALF_UP);
        return precio.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Recargo sobre costo equivalente (un margen de 20 % sobre el precio es un recargo de 25 %). */
    public BigDecimal recargoEquivalente() {
        return tipo == Tipo.RECARGO_COSTO ? porcentaje
                : porcentaje.multiply(CIEN).divide(CIEN.subtract(porcentaje), 2, RoundingMode.HALF_UP);
    }

    /** Margen sobre precio equivalente (un recargo de 25 % sobre el costo deja 20 % de margen). */
    public BigDecimal margenEquivalente() {
        return tipo == Tipo.MARGEN_PRECIO ? porcentaje
                : porcentaje.multiply(CIEN).divide(CIEN.add(porcentaje), 2, RoundingMode.HALF_UP);
    }
}
