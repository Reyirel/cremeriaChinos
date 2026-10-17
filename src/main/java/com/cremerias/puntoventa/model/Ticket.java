package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Datos de una venta tal como se imprimen en el ticket. */
public record Ticket(
        String ventaId,
        String folio,
        Instant fecha,
        String sucursal,
        String cajero,
        List<Renglon> renglones,
        long totalCentavos,
        List<PagoTicket> pagos,
        long cambioCentavos,
        BigDecimal articulos,
        boolean cancelada
) {

    /** @param nivel precio de mayoreo aplicado; {@code null} = menudeo */
    public record Renglon(String descripcion, Unidad unidad, BigDecimal cantidad, long precioCentavos,
                          long importeCentavos, Escala.Nivel nivel) {

        public Renglon(String descripcion, Unidad unidad, BigDecimal cantidad, long precioCentavos, long importeCentavos) {
            this(descripcion, unidad, cantidad, precioCentavos, importeCentavos, null);
        }
    }

    public record PagoTicket(MetodoPago metodo, long recibidoCentavos, String referencia) {
    }
}
