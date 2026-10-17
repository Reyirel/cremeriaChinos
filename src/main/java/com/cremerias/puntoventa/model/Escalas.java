package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Precio por volumen: decide el nivel (menudeo, mayoreo, súper mayoreo) de un renglón y su importe.
 * Lo usan igual el carrito de la caja y el registro de la venta, para que el total mostrado y el
 * guardado coincidan.
 *
 * <p>El nivel se decide con la cantidad total del producto en la venta, en unidades base: una caja
 * de 12 y 3 piezas suman 15 piezas. Si hay escalas de distinta especificidad para un artículo, se
 * usan solo las del grupo más específico (presentación + sucursal &gt; presentación &gt; sucursal &gt;
 * todo el producto).
 */
public final class Escalas {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /** Nivel aplicado a un renglón; {@code escala} es nula en menudeo. */
    public record Aplicacion(Escala.Nivel nivel, Escala escala, long importeLista, long importe) {
    }

    private Escalas() {
    }

    /** Escalas del producto que cubren esta presentación y sucursal. */
    public static List<Escala> deArticulo(List<Escala> delProducto, String presentacionId, String sucursalId) {
        return delProducto.stream().filter(e -> e.cubre(presentacionId, sucursalId)).toList();
    }

    /** Escala que corresponde a la cantidad base total del producto, o {@code null} (menudeo). */
    public static Escala aplicable(List<Escala> delArticulo, LocalDate dia, BigDecimal totalBase) {
        List<Escala> vigentes = delArticulo.stream().filter(e -> e.vigenteEn(dia)).toList();
        if (vigentes.isEmpty() || totalBase == null) {
            return null;
        }
        int masEspecifica = vigentes.stream().mapToInt(Escala::especificidad).max().orElse(0);
        return vigentes.stream()
                .filter(e -> e.especificidad() == masEspecifica)
                .filter(e -> totalBase.compareTo(e.cantidadMinima()) >= 0)
                .max(Comparator.comparing(Escala::cantidadMinima))
                .orElse(null);
    }

    /**
     * Importe del renglón con su nivel. Una escala que no baja el precio de lista (p. ej. un precio
     * fijo más alto que el lote actual) no se aplica: comprar más nunca cuesta más.
     *
     * @param cantidad cantidad en unidades de la presentación (kilos si es a granel)
     */
    public static Aplicacion aplicar(long importeLista, BigDecimal cantidad, Escala escala) {
        if (escala == null) {
            return new Aplicacion(Escala.Nivel.MENUDEO, null, importeLista, importeLista);
        }
        long conEscala = switch (escala.tipo()) {
            case DESCUENTO_PCT -> BigDecimal.valueOf(importeLista).multiply(CIEN.subtract(escala.descuentoPct()))
                    .divide(CIEN, 0, RoundingMode.HALF_UP).longValueExact();
            case PRECIO_FIJO -> cantidad.multiply(BigDecimal.valueOf(escala.precioCentavos()))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        };
        if (conEscala >= importeLista) {
            return new Aplicacion(Escala.Nivel.MENUDEO, null, importeLista, importeLista);
        }
        return new Aplicacion(escala.nivel(), escala, importeLista, conEscala);
    }
}
