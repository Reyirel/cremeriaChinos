package com.cremerias.puntoventa.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cola de cambios locales por subir a la nube.
 *
 * <p>Mientras se suben, las filas quedan con {@code enviado_en = 'ENVIANDO'}: si el registro vuelve a
 * cambiar en ese momento, los triggers encolan una fila nueva (solo evitan duplicar las que siguen
 * en {@code NULL}) y el cambio no se pierde.
 */
public class SyncOutboxRepository {

    public static final String ENVIANDO = "ENVIANDO";
    /** Después de tantos intentos fallidos el cambio deja de reintentarse solo. */
    public static final int MAX_INTENTOS = 20;

    public record Pendiente(long id, String tabla, String registroId) {
    }

    public int contarPendientes(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Cambios que la nube rechazó tantas veces que ya no se reintentan. */
    public int contarRechazados(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL AND intentos >= ?")) {
            ps.setInt(1, MAX_INTENTOS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Toma hasta {@code limite} cambios pendientes posteriores a {@code despuesDe} y los marca como enviándose. */
    public List<Pendiente> reclamar(Connection c, long despuesDe, int limite) throws SQLException {
        List<Pendiente> pendientes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, tabla, registro_id FROM sync_outbox
                WHERE enviado_en IS NULL AND intentos < ? AND id > ?
                ORDER BY id LIMIT ?""")) {
            ps.setInt(1, MAX_INTENTOS);
            ps.setLong(2, despuesDe);
            ps.setInt(3, limite);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pendientes.add(new Pendiente(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        cambiarEnvio(c, pendientes.stream().map(Pendiente::id).toList(), ENVIANDO, null);
        return pendientes;
    }

    public void marcarEnviados(Connection c, List<Long> ids, String cuando) throws SQLException {
        cambiarEnvio(c, ids, cuando, null);
    }

    /** Regresa a la cola los que se estaban enviando (no hubo conexión): no cuenta como intento. */
    public void devolver(Connection c, List<Long> ids) throws SQLException {
        cambiarEnvio(c, ids, null, ENVIANDO);
    }

    /** La nube rechazó el cambio: vuelve a la cola con el error y un intento más. */
    public void marcarError(Connection c, long id, String error) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE sync_outbox SET enviado_en = NULL, intentos = intentos + 1, ultimo_error = ?
                WHERE id = ?""")) {
            ps.setString(1, error);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** Al arrancar: lo que se quedó "enviándose" (la app se cerró a medio envío) vuelve a la cola. */
    public int reiniciarEnvios(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE sync_outbox SET enviado_en = NULL WHERE enviado_en = ?")) {
            ps.setString(1, ENVIANDO);
            return ps.executeUpdate();
        }
    }

    /** Registros de una tabla con cambios locales que aún no llegan a la nube. */
    public Set<String> registrosSinSubir(Connection c, String tabla) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT registro_id FROM sync_outbox
                WHERE tabla = ? AND (enviado_en IS NULL OR enviado_en = ?)""")) {
            ps.setString(1, tabla);
            ps.setString(2, ENVIANDO);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    public long ultimoId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM sync_outbox");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Quita lo que encolaron los triggers después de {@code id} (cambios que vinieron de la nube). */
    public void borrarPosteriores(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM sync_outbox WHERE id > ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private void cambiarEnvio(Connection c, List<Long> ids, String enviadoEn, String soloSiEstaEn) throws SQLException {
        String sql = "UPDATE sync_outbox SET enviado_en = ?, ultimo_error = NULL WHERE id = ?"
                + (soloSiEstaEn == null ? "" : " AND enviado_en = ?");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (long id : ids) {
                ps.setString(1, enviadoEn);
                ps.setLong(2, id);
                if (soloSiEstaEn != null) {
                    ps.setString(3, soloSiEstaEn);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
