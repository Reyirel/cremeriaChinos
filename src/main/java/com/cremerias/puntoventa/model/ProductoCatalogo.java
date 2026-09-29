package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Producto tal como lo administra el almacén.
 *
 * @param avisoSinVenta aviso propio si no se vende en cierto plazo; nulo = usa el de la sucursal
 */
public record ProductoCatalogo(
        String id,
        String nombre,
        String categoriaId,
        String categoria,
        String clave,
        String codigoInventario,
        Unidad unidad,
        boolean sujetoMerma,
        Disponibilidad disponibilidad,
        Temporada temporada,
        boolean activo,
        List<Presentacion> presentaciones,
        BigDecimal gramajeGramos,
        BigDecimal mermaGramos,
        AvisoSinVenta avisoSinVenta
) {

    /**
     * Gramos netos por unidad base (gramaje − merma). Cero si aún no tiene gramaje.
     * En productos por kilo es por cada kilo.
     */
    public BigDecimal gramajeNeto() {
        if (gramajeGramos == null) {
            return BigDecimal.ZERO;
        }
        return gramajeGramos.subtract(mermaGramos == null ? BigDecimal.ZERO : mermaGramos).max(BigDecimal.ZERO);
    }

    public Presentacion principal() {
        return presentaciones.stream().filter(Presentacion::principal).findFirst()
                .orElse(presentaciones.isEmpty() ? null : presentaciones.getFirst());
    }
}
