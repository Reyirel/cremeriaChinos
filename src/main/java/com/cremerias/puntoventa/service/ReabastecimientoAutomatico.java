package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.util.Cantidades;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Cuando la existencia de un producto en una sucursal llega a su mínimo se genera una orden
 * de reabastecimiento por (máximo − existencia), que le llega al administrador.
 */
public class ReabastecimientoAutomatico {

    private static final Logger log = LoggerFactory.getLogger(ReabastecimientoAutomatico.class);

    private final OrdenRepository ordenes = new OrdenRepository();
    private final LoteRepository lotes = new LoteRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    /** @return el folio de la orden generada, o {@code null} si no hizo falta. */
    public String revisar(Connection c, String sucursalId, String productoId, Usuario usuario, String dispositivoId)
            throws SQLException {
        var limite = ordenes.limite(c, sucursalId, productoId);
        if (limite.isEmpty() || limite.get().maximo().signum() <= 0) {
            return null;
        }
        BigDecimal existencia = lotes.existencia(c, productoId, sucursalId);
        if (existencia.compareTo(limite.get().minimo()) > 0 || ordenes.hayPendiente(c, sucursalId, productoId)) {
            return null;
        }
        BigDecimal sugerida = limite.get().maximo().subtract(existencia.max(BigDecimal.ZERO));
        if (sugerida.signum() <= 0) {
            return null;
        }
        String folio = bitacora.siguienteFolio(c, AccionBitacora.ORDEN_GENERADA.prefijo(), dispositivoId);
        String id = ordenes.insertar(c, folio, sucursalId, productoId, existencia, limite.get(), sugerida);
        String[] datos = nombreYUnidad(c, productoId, sucursalId);
        Unidad unidad = Unidad.valueOf(datos[1]);
        bitacora.registrar(c, AccionBitacora.ORDEN_GENERADA, usuario, dispositivoId, "ordenes_reabastecimiento", id,
                datos[0] + " en " + datos[2] + ": quedan " + Cantidades.formatear(unidad, existencia)
                        + " (mínimo " + Cantidades.formatear(unidad, limite.get().minimo()) + "). Se piden "
                        + Cantidades.formatear(unidad, sugerida) + ".", folio);
        log.info("Orden de reabastecimiento {} generada", folio);
        return folio;
    }

    /** Revisa todos los productos con límites de una sucursal (ej. al guardar mínimos y máximos). */
    public int revisarSucursal(Connection c, String sucursalId, Usuario usuario, String dispositivoId)
            throws SQLException {
        int generadas = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id FROM limites_sucursal WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<String> productos = new java.util.ArrayList<>();
                while (rs.next()) {
                    productos.add(rs.getString(1));
                }
                for (String productoId : productos) {
                    if (revisar(c, sucursalId, productoId, usuario, dispositivoId) != null) {
                        generadas++;
                    }
                }
            }
        }
        return generadas;
    }

    private static String[] nombreYUnidad(Connection c, String productoId, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.nombre, p.unidad, (SELECT nombre FROM sucursales WHERE id = ?)
                FROM productos p WHERE p.id = ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new String[]{rs.getString(1), rs.getString(2), rs.getString(3)};
            }
        }
    }
}
