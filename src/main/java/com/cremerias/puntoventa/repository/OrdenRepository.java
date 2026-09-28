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
import java.util.List;
import java.util.Optional;

/** Órdenes de reabastecimiento que las sucursales generan al llegar a su mínimo. */
public class OrdenRepository {

    public record Limite(BigDecimal minimo, BigDecimal maximo) {
    }

    public record Orden(String id, String folio, String sucursalId, String sucursal, String productoId,
                        String producto, String unidad, BigDecimal existencia, BigDecimal minimo, BigDecimal maximo,
                        BigDecimal cantidadSugerida, String estado, Instant creadoEn, String resueltoPor,
                        Instant resueltoEn, String motivoRechazo, String surtidoId) {
    }

    public Optional<Limite> limite(Connection c, String sucursalId, String productoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT minimo, maximo FROM limites_sucursal WHERE sucursal_id = ? AND producto_id = ?")) {
            ps.setString(1, sucursalId);
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new Limite(ProductoRepository.decimal(rs, "minimo"), ProductoRepository.decimal(rs, "maximo")))
                        : Optional.empty();
            }
        }
    }

    public boolean hayPendiente(Connection c, String sucursalId, String productoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM ordenes_reabastecimiento
                WHERE sucursal_id = ? AND producto_id = ? AND estado = 'PENDIENTE'""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public String insertar(Connection c, String folio, String sucursalId, String productoId, BigDecimal existencia,
                           Limite limite, BigDecimal sugerida) throws SQLException {
        String id = Ids.nuevo();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ordenes_reabastecimiento (id, folio, sucursal_id, producto_id, existencia, minimo, maximo,
                                                      cantidad_sugerida, creado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, folio);
            ps.setString(3, sucursalId);
            ps.setString(4, productoId);
            ps.setBigDecimal(5, existencia);
            ps.setBigDecimal(6, limite.minimo());
            ps.setBigDecimal(7, limite.maximo());
            ps.setBigDecimal(8, sugerida);
            ps.setString(9, Tiempo.ahora());
            ps.executeUpdate();
        }
        return id;
    }

    public List<Orden> listar(Connection c, String estado, int limite) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT o.*, s.nombre AS sucursal, p.nombre AS producto, p.unidad, COALESCE(u.nombre_completo, '') AS resuelto_por_nombre
                FROM ordenes_reabastecimiento o
                JOIN sucursales s ON s.id = o.sucursal_id
                JOIN productos p ON p.id = o.producto_id
                LEFT JOIN usuarios u ON u.id = o.resuelto_por
                WHERE (? IS NULL AND o.estado <> 'PENDIENTE') OR o.estado = ?
                ORDER BY o.creado_en DESC LIMIT ?""")) {
            ps.setString(1, estado);
            ps.setString(2, estado);
            ps.setInt(3, limite);
            try (ResultSet rs = ps.executeQuery()) {
                List<Orden> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
                return lista;
            }
        }
    }

    public Optional<Orden> porId(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT o.*, s.nombre AS sucursal, p.nombre AS producto, p.unidad, COALESCE(u.nombre_completo, '') AS resuelto_por_nombre
                FROM ordenes_reabastecimiento o
                JOIN sucursales s ON s.id = o.sucursal_id
                JOIN productos p ON p.id = o.producto_id
                LEFT JOIN usuarios u ON u.id = o.resuelto_por
                WHERE o.id = ?""")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public int contarPendientes(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM ordenes_reabastecimiento WHERE estado = 'PENDIENTE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void resolver(Connection c, String id, String estado, String usuarioId, String motivo, String surtidoId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE ordenes_reabastecimiento
                SET estado = ?, resuelto_por = ?, resuelto_en = ?, motivo_rechazo = ?, surtido_id = ?
                WHERE id = ? AND estado = 'PENDIENTE'""")) {
            ps.setString(1, estado);
            ps.setString(2, usuarioId);
            ps.setString(3, Tiempo.ahora());
            ps.setString(4, motivo);
            ps.setString(5, surtidoId);
            ps.setString(6, id);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("La orden ya había sido atendida.");
            }
        }
    }

    private static Orden mapear(ResultSet rs) throws SQLException {
        return new Orden(rs.getString("id"), rs.getString("folio"), rs.getString("sucursal_id"), rs.getString("sucursal"),
                rs.getString("producto_id"), rs.getString("producto"), rs.getString("unidad"),
                ProductoRepository.decimal(rs, "existencia"), ProductoRepository.decimal(rs, "minimo"),
                ProductoRepository.decimal(rs, "maximo"), ProductoRepository.decimal(rs, "cantidad_sugerida"),
                rs.getString("estado"), Tiempo.leer(rs.getString("creado_en")), rs.getString("resuelto_por_nombre"),
                Tiempo.leer(rs.getString("resuelto_en")), rs.getString("motivo_rechazo"), rs.getString("surtido_id"));
    }
}
