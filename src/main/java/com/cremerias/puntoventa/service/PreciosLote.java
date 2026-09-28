package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Tarifa;
import com.cremerias.puntoventa.repository.LoteRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Arma los tramos de precio (por lote) de una presentación en una sucursal. */
final class PreciosLote {

    /** @param extra lote más reciente con precio; se usa si se vende más de lo que hay. */
    record Tramos(List<Tarifa.Tramo> tramos, Tarifa.Tramo extra) {
        long precioActual() {
            return tramos.isEmpty() ? extra.precioCentavos() : tramos.getFirst().precioCentavos();
        }
    }

    private PreciosLote() {
    }

    /**
     * @param lotesPeps lotes del producto en la sucursal, del más antiguo al más reciente
     * @return tramos, o {@code null} si ningún lote tiene precio para la presentación
     */
    static Tramos de(List<LoteRepository.Lote> lotesPeps, Map<String, Map<String, Long>> precios,
                     String presentacionId) {
        List<Tarifa.Tramo> tramos = new ArrayList<>();
        Tarifa.Tramo extra = null;
        for (LoteRepository.Lote lote : lotesPeps) {
            Long precio = precios.getOrDefault(lote.id(), Map.of()).get(presentacionId);
            if (precio == null) {
                continue;
            }
            Tarifa.Tramo tramo = new Tarifa.Tramo(lote.id(), lote.existencia(), precio);
            if (lote.existencia().signum() > 0) {
                tramos.add(tramo);
            }
            extra = tramo;
        }
        return extra == null ? null : new Tramos(tramos, extra);
    }
}
