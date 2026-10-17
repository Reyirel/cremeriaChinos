package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Artículo que se vende en la caja: una presentación de un producto (ej. "Leche entera 1 L"
 * por pieza o "· Caja 12 pzas") con sus precios por lote en la sucursal.
 *
 * @param id              id de la presentación
 * @param unidad          KG si se vende a granel (por peso), PZA si por unidades
 * @param unidadBase      unidad en que se lleva el inventario del producto
 * @param factor          unidades base por unidad vendida (1 caja = 12 piezas)
 * @param precioCentavos  precio actual (lote más antiguo con existencia)
 * @param existencia      existencia del producto en la sucursal, en unidad base
 * @param inventarioMinimo mínimo configurado para la sucursal (0 si no hay)
 * @param tramos          lotes con existencia y su precio, del más antiguo al más nuevo
 * @param tramoExtra      lote y precio para cotizar lo que exceda la existencia (no se deja vender)
 * @param escalas         escalas de mayoreo que cubren esta presentación en la sucursal
 * @param mermaPct        merma del derivado: al venderlo se descuenta además este % del original
 */
public record Producto(
        String id,
        String productoId,
        String codigoBarras,
        String clave,
        String codigoInventario,
        String nombre,
        String categoria,
        Unidad unidad,
        Unidad unidadBase,
        BigDecimal factor,
        long precioCentavos,
        BigDecimal existencia,
        BigDecimal inventarioMinimo,
        List<Tarifa.Tramo> tramos,
        Tarifa.Tramo tramoExtra,
        List<Escala> escalas,
        BigDecimal mermaPct
) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public Producto {
        escalas = escalas == null ? List.of() : List.copyOf(escalas);
        mermaPct = mermaPct == null ? BigDecimal.ZERO : mermaPct;
    }

    /** Artículo sin escalas de mayoreo ni merma. */
    public Producto(String id, String productoId, String codigoBarras, String clave, String codigoInventario,
                    String nombre, String categoria, Unidad unidad, Unidad unidadBase, BigDecimal factor,
                    long precioCentavos, BigDecimal existencia, BigDecimal inventarioMinimo,
                    List<Tarifa.Tramo> tramos, Tarifa.Tramo tramoExtra) {
        this(id, productoId, codigoBarras, clave, codigoInventario, nombre, categoria, unidad, unidadBase, factor,
                precioCentavos, existencia, inventarioMinimo, tramos, tramoExtra, List.of(), BigDecimal.ZERO);
    }

    public boolean sinExistencia() {
        return existencia.signum() <= 0;
    }

    public boolean existenciaBaja() {
        return !sinExistencia() && inventarioMinimo.signum() > 0 && existencia.compareTo(inventarioMinimo) <= 0;
    }

    /** Unidades base que salen del inventario al vender esta cantidad (sin merma). */
    public BigDecimal base(BigDecimal cantidad) {
        return unidad.normalizar(cantidad).multiply(factor);
    }

    /** Merma que se descuenta del producto original al vender esta cantidad (0 si no es derivado con merma). */
    public BigDecimal mermaBase(BigDecimal cantidad) {
        if (mermaPct.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return base(cantidad).multiply(mermaPct).divide(CIEN, 3, RoundingMode.HALF_UP);
    }

    /** Importe de esta cantidad considerando lo ya tomado de cada lote por otros renglones. */
    public Tarifa.Cotizacion cotizar(Map<String, BigDecimal> consumidoPorLote, BigDecimal cantidad) {
        return Tarifa.cotizar(tramos, tramoExtra, factor, consumidoPorLote, cantidad);
    }

    /** Importe de esta cantidad por sí sola. */
    public long importe(BigDecimal cantidad) {
        return cotizar(new HashMap<>(), cantidad).totalCentavos();
    }
}
