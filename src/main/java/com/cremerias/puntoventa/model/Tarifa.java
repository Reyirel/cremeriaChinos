package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Precio por lotes (primero en entrar, primero en salir): se cobra al precio del lote más antiguo
 * con existencia y, al agotarse, al precio del siguiente. Lo usan igual el carrito de la caja y
 * el registro de la venta, para que el total mostrado y el guardado coincidan.
 */
public final class Tarifa {

    /** Existencia disponible de un lote (en unidad base) y precio de una presentación en ese lote. */
    public record Tramo(String loteId, BigDecimal disponibleBase, long precioCentavos) {
    }

    /** Parte de un renglón surtida de un lote. */
    public record Parte(String loteId, BigDecimal cantidadBase, long importeCentavos) {
    }

    public record Cotizacion(List<Parte> partes, long totalCentavos) {
    }

    private Tarifa() {
    }

    /**
     * @param tramos      lotes con existencia, del más antiguo al más reciente
     * @param extra       lote y precio para lo que exceda la existencia (se vende en negativo)
     * @param factor      unidades base por unidad de la presentación
     * @param consumido   base ya tomada de cada lote por renglones anteriores; se actualiza
     * @param cantidad    cantidad en unidades de la presentación
     */
    public static Cotizacion cotizar(List<Tramo> tramos, Tramo extra, BigDecimal factor,
                                     Map<String, BigDecimal> consumido, BigDecimal cantidad) {
        BigDecimal restante = cantidad.multiply(factor);
        List<Parte> partes = new ArrayList<>();
        long total = 0;
        for (Tramo tramo : tramos) {
            if (restante.signum() <= 0) {
                break;
            }
            BigDecimal usado = consumido.getOrDefault(tramo.loteId(), BigDecimal.ZERO);
            BigDecimal disponible = tramo.disponibleBase().subtract(usado);
            if (disponible.signum() <= 0) {
                continue;
            }
            BigDecimal toma = disponible.min(restante);
            long importe = importe(toma, factor, tramo.precioCentavos());
            partes.add(new Parte(tramo.loteId(), toma, importe));
            total += importe;
            consumido.merge(tramo.loteId(), toma, BigDecimal::add);
            restante = restante.subtract(toma);
        }
        if (restante.signum() > 0) {
            if (extra == null) {
                throw new IllegalStateException("El producto no tiene precio en esta sucursal.");
            }
            long importe = importe(restante, factor, extra.precioCentavos());
            partes.add(new Parte(extra.loteId(), restante, importe));
            total += importe;
            consumido.merge(extra.loteId(), restante, BigDecimal::add);
        }
        return new Cotizacion(partes, total);
    }

    private static long importe(BigDecimal base, BigDecimal factor, long precio) {
        return base.divide(factor, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(precio))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
