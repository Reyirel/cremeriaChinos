package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Consultas de ventas/costo/meta para el módulo Indicadores. */
public class IndicadoresRepository {

    /** Suma de ventas completadas (no canceladas) del periodo. */
    public long ventaNeta(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(total_centavos), 0) FROM ventas
                WHERE sucursal_id = ? AND estado = 'COMPLETADA' AND fecha >= ? AND fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Costo real de la mercancía vendida: el costo por unidad de cada lote realmente surtido
     * (PEPS), no el costo/precio actual del producto. Las ventas canceladas no cuentan porque se
     * filtra por el estado de la venta, aunque el movimiento VENTA original siga en la tabla.
     */
    public long costoVenta(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(ROUND(-mi.cantidad * l.costo_unitario_centavos)), 0)
                FROM movimientos_inventario mi
                JOIN lotes l ON l.id = mi.lote_id
                JOIN ventas v ON v.id = mi.referencia_id
                WHERE mi.tipo = 'VENTA' AND v.sucursal_id = ? AND v.estado = 'COMPLETADA'
                  AND v.fecha >= ? AND v.fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public Optional<Long> metaCentavos(Connection c, String sucursalId, int anio, int mes) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT meta_centavos FROM metas_sucursal WHERE sucursal_id = ? AND anio = ? AND mes = ?")) {
            ps.setString(1, sucursalId);
            ps.setInt(2, anio);
            ps.setInt(3, mes);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public void guardarMeta(Connection c, String sucursalId, int anio, int mes, long metaCentavos) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO metas_sucursal (id, sucursal_id, anio, mes, meta_centavos, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (sucursal_id, anio, mes) DO UPDATE SET
                    meta_centavos = excluded.meta_centavos, actualizado_en = excluded.actualizado_en""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, sucursalId);
            ps.setInt(3, anio);
            ps.setInt(4, mes);
            ps.setLong(5, metaCentavos);
            ps.setString(6, ahora);
            ps.setString(7, ahora);
            ps.executeUpdate();
        }
    }
}
