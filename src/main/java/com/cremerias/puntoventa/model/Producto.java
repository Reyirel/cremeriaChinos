package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
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
 * @param tramoExtra      lote y precio para vender aunque no haya existencia
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
        Tarifa.Tramo tramoExtra
) {

    public boolean sinExistencia() {
        return existencia.signum() <= 0;
    }

    public boolean existenciaBaja() {
        return !sinExistencia() && inventarioMinimo.signum() > 0 && existencia.compareTo(inventarioMinimo) <= 0;
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
