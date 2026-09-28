package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public class UsuarioRepository {

    public Optional<Usuario> buscarPorUsuario(Connection c, String usuario) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM usuarios WHERE usuario = ? AND eliminado_en IS NULL")) {
            ps.setString(1, usuario.strip());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Usuario> porId(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM usuarios WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public int contar(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM usuarios");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void insertar(Connection c, Usuario u) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO usuarios (id, sucursal_id, nombre_completo, usuario, password_hash, rol,
                                      activo, debe_cambiar_password, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, u.id());
            ps.setString(2, u.sucursalId());
            ps.setString(3, u.nombreCompleto());
            ps.setString(4, u.usuario());
            ps.setString(5, u.passwordHash());
            ps.setString(6, u.rol().name());
            ps.setBoolean(7, u.activo());
            ps.setBoolean(8, u.debeCambiarPassword());
            ps.setString(9, ahora);
            ps.setString(10, ahora);
            ps.executeUpdate();
        }
    }

    public void registrarAccesoExitoso(Connection c, String id, Instant cuando) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL, ultimo_acceso = ?
                WHERE id = ?""")) {
            ps.setString(1, Tiempo.formatear(cuando));
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    public void registrarIntentoFallido(Connection c, String id, int intentos, Instant bloqueadoHasta)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE usuarios SET intentos_fallidos = ?, bloqueado_hasta = ? WHERE id = ?")) {
            ps.setInt(1, intentos);
            ps.setString(2, Tiempo.formatear(bloqueadoHasta));
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    private static Usuario mapear(ResultSet rs) throws SQLException {
        return new Usuario(
                rs.getString("id"),
                rs.getString("sucursal_id"),
                rs.getString("nombre_completo"),
                rs.getString("usuario"),
                rs.getString("password_hash"),
                Rol.valueOf(rs.getString("rol")),
                rs.getBoolean("activo"),
                rs.getBoolean("debe_cambiar_password"),
                rs.getInt("intentos_fallidos"),
                Tiempo.leer(rs.getString("bloqueado_hasta")),
                Tiempo.leer(rs.getString("ultimo_acceso")));
    }
}
