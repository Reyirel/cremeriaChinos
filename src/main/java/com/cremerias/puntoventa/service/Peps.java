package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Salidas por lote, primero el más antiguo (PEPS), con su costo. Lo usan los documentos que sacan
 * mercancía de una ubicación (ajustes, transformaciones, devoluciones).
 */
public final class Peps {

    /** Parte de una salida tomada de un lote. */
    public record Salida(LoteRepository.Lote lote, BigDecimal cantidad, long costoCentavos) {
    }

    private static final LoteRepository LOTES = new LoteRepository();

    private Peps() {
    }

    /**
     * Toma la cantidad (unidad base) de los lotes más antiguos de la ubicación, o solo del lote
     * indicado. Falla si no alcanza.
     */
    public static List<Salida> salida(Connection c, String productoId, String nombre, Unidad unidad, String ubicacionId,
                                      BigDecimal cantidad, String loteId) throws SQLException {
        List<LoteRepository.Lote> lotes = LOTES.deProducto(c, productoId, ubicacionId);
        if (loteId != null) {
            lotes = lotes.stream().filter(l -> l.id().equals(loteId)).toList();
            if (lotes.isEmpty()) {
                throw new IllegalArgumentException("El lote elegido no es de " + nombre + " en esta ubicación.");
            }
        }
        List<Salida> salidas = new ArrayList<>();
        BigDecimal restante = cantidad;
        BigDecimal disponible = BigDecimal.ZERO;
        for (LoteRepository.Lote lote : lotes) {
            if (lote.existencia().signum() <= 0) {
                continue;
            }
            disponible = disponible.add(lote.existencia());
            if (restante.signum() <= 0) {
                continue;
            }
            BigDecimal toma = lote.existencia().min(restante);
            salidas.add(new Salida(lote, toma, Dinero.importe(lote.costoUnitarioCentavos(), toma)));
            restante = restante.subtract(toma);
        }
        if (restante.signum() > 0) {
            throw new IllegalStateException("No hay suficiente " + nombre + ": hay "
                    + Cantidades.formatear(unidad, disponible.setScale(3, RoundingMode.HALF_UP)) + ".");
        }
        return salidas;
    }

    /**
     * Lote donde entra mercancía que aparece (sobrante, devolución vendible): el elegido, o el más
     * reciente de la ubicación que ya tenga precio (en sucursal un lote sin precio no se vendería); en el
     * almacén, el más reciente.
     */
    public static Optional<LoteRepository.Lote> loteDeEntrada(Connection c, String productoId, String ubicacionId,
                                                              String loteId, boolean conPrecio) throws SQLException {
        List<LoteRepository.Lote> lotes = LOTES.deProducto(c, productoId, ubicacionId);
        if (loteId != null) {
            return lotes.stream().filter(l -> l.id().equals(loteId)).findFirst();
        }
        for (int i = lotes.size() - 1; i >= 0; i--) {
            LoteRepository.Lote lote = lotes.get(i);
            if (!conPrecio || tienePrecio(c, lote.id())) {
                return Optional.of(lote);
            }
        }
        return Optional.empty();
    }

    private static boolean tienePrecio(Connection c, String loteId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM lote_precios WHERE lote_id = ? LIMIT 1")) {
            ps.setString(1, loteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Si la ubicación es el almacén central. */
    public static boolean esAlmacen(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT es_almacen FROM sucursales WHERE id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }
}
