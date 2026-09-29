package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
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
