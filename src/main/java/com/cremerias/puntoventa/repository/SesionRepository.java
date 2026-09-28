package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

public class SesionRepository {

    public void abrir(Connection c, String id, String usuarioId, String dispositivoId, Instant inicio,
                      ModoConexion modo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sesiones (id, usuario_id, dispositivo_id, inicio, modo)
                VALUES (?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, usuarioId);
            ps.setString(3, dispositivoId);
            ps.setString(4, Tiempo.formatear(inicio));
            ps.setString(5, modo.name());
            ps.executeUpdate();
        }
    }

    public void cerrar(Connection c, String id, String motivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sesiones SET fin = ?, motivo_cierre = ? WHERE id = ? AND fin IS NULL")) {
            ps.setString(1, Tiempo.ahora());
            ps.setString(2, motivo);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    /** Cierra sesiones que quedaron abiertas (ej. la app se cerró por un apagón). */
    public int cerrarHuerfanas(Connection c, String dispositivoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE sesiones SET fin = ?, motivo_cierre = 'CIERRE_INESPERADO'
                WHERE dispositivo_id = ? AND fin IS NULL""")) {
            ps.setString(1, Tiempo.ahora());
            ps.setString(2, dispositivoId);
            return ps.executeUpdate();
        }
    }
}
