package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lotes de inventario por ubicación (almacén o sucursal) y sus precios por presentación. */
public class LoteRepository {

    public record Lote(String id, String folio, String productoId, String sucursalId, String origen,
                       BigDecimal cantidadInicial, BigDecimal existencia, long costoUnitarioCentavos,
                       Instant fechaIngreso) {
    }

    private static final String SELECT = """
            SELECT l.id, l.folio, l.producto_id, l.sucursal_id, l.origen, l.cantidad_inicial,
                   COALESCE(e.existencia, 0) AS existencia, l.costo_unitario_centavos, l.fecha_ingreso
            FROM lotes l
            LEFT JOIN v_existencias_lote e ON e.lote_id = l.id
            """;
    /** Primero en entrar, primero en salir. */
    private static final String ORDEN_PEPS = " ORDER BY l.fecha_ingreso, l.creado_en, l.id";

    public List<Lote> deSucursal(Connection c, String sucursalId) throws SQLException {
        return consultar(c, SELECT + " WHERE l.sucursal_id = ?" + ORDEN_PEPS, sucursalId);
    }

    public List<Lote> deProducto(Connection c, String productoId, String sucursalId) throws SQLException {
        return consultar(c, SELECT + " WHERE l.producto_id = ? AND l.sucursal_id = ?" + ORDEN_PEPS,
                productoId, sucursalId);
    }

    /** Precios por presentación de todos los lotes de una sucursal: lote → (presentación → precio). */
    public Map<String, Map<String, Long>> preciosDeSucursal(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT lp.lote_id, lp.presentacion_id, lp.precio_centavos
                FROM lote_precios lp JOIN lotes l ON l.id = lp.lote_id
                WHERE l.sucursal_id = ?""")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Map<String, Long>> precios = new HashMap<>();
                while (rs.next()) {
                    precios.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                }
                return precios;
            }
        }
    }

    public String insertar(Connection c, String folio, String productoId, String sucursalId, String origen,
                           String referenciaId, BigDecimal cantidad, long costoUnitario, String usuarioId)
            throws SQLException {
        String id = Ids.nuevo();
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO lotes (id, folio, producto_id, sucursal_id, origen, referencia_id, cantidad_inicial,
                                   costo_unitario_centavos, fecha_ingreso, usuario_id, creado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, folio);
            ps.setString(3, productoId);
            ps.setString(4, sucursalId);
            ps.setString(5, origen);
            ps.setString(6, referenciaId);
            ps.setBigDecimal(7, cantidad);
            ps.setLong(8, costoUnitario);
            ps.setString(9, ahora);
            ps.setString(10, usuarioId);
            ps.setString(11, ahora);
            ps.executeUpdate();
        }
        return id;
    }

    public void insertarPrecio(Connection c, String loteId, String presentacionId, long precio) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lote_precios (id, lote_id, presentacion_id, precio_centavos) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, loteId);
            ps.setString(3, presentacionId);
            ps.setLong(4, precio);
            ps.executeUpdate();
        }
    }

    public BigDecimal existencia(Connection c, String productoId, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(cantidad), 0) FROM movimientos_inventario WHERE producto_id = ? AND sucursal_id = ?")) {
            ps.setString(1, productoId);
            ps.setString(2, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return ProductoRepository.decimal(rs, 1);
            }
        }
    }

    private List<Lote> consultar(Connection c, String sql, String... parametros) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setString(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Lote> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Lote(rs.getString("id"), rs.getString("folio"), rs.getString("producto_id"),
                            rs.getString("sucursal_id"), rs.getString("origen"),
                            ProductoRepository.decimal(rs, "cantidad_inicial"),
                            ProductoRepository.decimal(rs, "existencia"), rs.getLong("costo_unitario_centavos"),
                            Tiempo.leer(rs.getString("fecha_ingreso"))));
                }
                return lista;
            }
        }
    }
}
