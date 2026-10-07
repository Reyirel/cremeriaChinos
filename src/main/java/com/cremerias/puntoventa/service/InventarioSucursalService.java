package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Productos de una sucursal con su existencia, precios y mínimo/máximo, solo para consulta
 * (supervisor). No modifica nada: la existencia baja con las ventas registradas en caja.
 */
public class InventarioSucursalService {

    /** Precio actual de una presentación en la sucursal (lote más antiguo con existencia). */
    public record Precio(Presentacion presentacion, long precioCentavos) {
    }

    public record Fila(ProductoCatalogo producto, BigDecimal existencia, BigDecimal minimo, BigDecimal maximo,
                       List<Precio> precios) {

        public boolean agotado() {
            return existencia.signum() <= 0;
        }

        public boolean enMinimo() {
            return !agotado() && maximo.signum() > 0 && existencia.compareTo(minimo) <= 0;
        }

        /** Con mínimo y máximo, ya llegó a su mínimo o se agotó. */
        public boolean porReabastecer() {
            return maximo.signum() > 0 && existencia.compareTo(minimo) <= 0;
        }
    }

    /**
     * Producto que llegó a su mínimo (o se agotó) en la sucursal.
     *
     * @param ordenPendiente folio de la orden de reabastecimiento que espera al administrador, o {@code null}
     */
    public record Bajo(String productoId, String nombre, Unidad unidad, BigDecimal existencia, BigDecimal minimo,
                       String ordenPendiente) {

        public boolean agotado() {
            return existencia.signum() <= 0;
        }
    }

    private final Database database;
    private final ProductoRepository productos = new ProductoRepository();
    private final LoteRepository lotes = new LoteRepository();

    public InventarioSucursalService(Database database) {
        this.database = database;
    }

    /**
     * Productos activos de la sucursal: los que ya se le surtieron (tienen lote) o ya tienen
     * mínimo/máximo capturado, igual que en «Mínimos y máximos» del administrador.
     */
    public List<Fila> listar(String sucursalId) {
        return database.con(c -> {
            Map<String, List<LoteRepository.Lote>> lotesPorProducto = new HashMap<>();
            for (LoteRepository.Lote lote : lotes.deSucursal(c, sucursalId)) {
                lotesPorProducto.computeIfAbsent(lote.productoId(), k -> new ArrayList<>()).add(lote);
            }
            Map<String, Map<String, Long>> precios = lotes.preciosDeSucursal(c, sucursalId);
            Map<String, BigDecimal[]> limites = limites(c, sucursalId);
            Map<String, BigDecimal> existencias = existencias(c, sucursalId);

            List<Fila> filas = new ArrayList<>();
            for (ProductoCatalogo p : productos.listar(c)) {
                List<LoteRepository.Lote> suyos = lotesPorProducto.get(p.id());
                if (!p.activo() || (suyos == null && !limites.containsKey(p.id()))) {
                    continue;
                }
                List<Precio> preciosProducto = new ArrayList<>();
                for (Presentacion pr : p.presentaciones()) {
                    PreciosLote.Tramos tramos = pr.activo() && suyos != null
                            ? PreciosLote.de(suyos, precios, pr.id()) : null;
                    if (tramos != null) {
                        preciosProducto.add(new Precio(pr, tramos.precioActual()));
                    }
                }
                BigDecimal[] l = limites.getOrDefault(p.id(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                filas.add(new Fila(p, existencias.getOrDefault(p.id(), BigDecimal.ZERO.setScale(3)), l[0], l[1],
                        List.copyOf(preciosProducto)));
            }
            return filas;
        });
    }

    /**
     * Productos con mínimo y máximo que ya llegaron a su mínimo, los agotados primero. Lo que queda
     * se sigue vendiendo; es el aviso para que el supervisor vea el reabastecimiento.
     */
    public List<Bajo> enMinimo(String sucursalId) {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, p.nombre, p.unidad, COALESCE(e.existencia, 0) AS existencia, l.minimo,
                           (SELECT o.folio FROM ordenes_reabastecimiento o
                            WHERE o.sucursal_id = l.sucursal_id AND o.producto_id = l.producto_id
                              AND o.estado = 'PENDIENTE'
                            ORDER BY o.creado_en DESC LIMIT 1) AS orden
                    FROM limites_sucursal l
                    JOIN productos p ON p.id = l.producto_id AND p.eliminado_en IS NULL AND p.activo = 1
                    LEFT JOIN v_existencias e ON e.producto_id = l.producto_id AND e.sucursal_id = l.sucursal_id
                    WHERE l.sucursal_id = ? AND l.maximo > 0 AND COALESCE(e.existencia, 0) <= l.minimo
                    ORDER BY COALESCE(e.existencia, 0) > 0, p.nombre""")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Bajo> bajos = new ArrayList<>();
                    while (rs.next()) {
                        bajos.add(new Bajo(rs.getString(1), rs.getString(2), Unidad.valueOf(rs.getString(3)),
                                decimal(rs.getDouble(4)), decimal(rs.getDouble(5)), rs.getString(6)));
                    }
                    return bajos;
                }
            }
        });
    }

    private static Map<String, BigDecimal[]> limites(Connection c, String sucursalId) throws SQLException {
        Map<String, BigDecimal[]> limites = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, minimo, maximo FROM limites_sucursal WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    limites.put(rs.getString(1), new BigDecimal[]{decimal(rs.getDouble(2)), decimal(rs.getDouble(3))});
                }
            }
        }
        return limites;
    }

    private static Map<String, BigDecimal> existencias(Connection c, String sucursalId) throws SQLException {
        Map<String, BigDecimal> existencias = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, existencia FROM v_existencias WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existencias.put(rs.getString(1), decimal(rs.getDouble(2)));
                }
            }
        }
        return existencias;
    }

    private static BigDecimal decimal(double valor) {
        return BigDecimal.valueOf(valor).setScale(3, RoundingMode.HALF_UP);
    }
}
