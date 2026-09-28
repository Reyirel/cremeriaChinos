package com.cremerias.puntoventa.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class ConfigLocalRepository {

    public static final String ULTIMO_USUARIO = "login.ultimo_usuario";
    public static final String TEMA = "ui.tema";

    public Optional<String> obtener(Connection c, String clave) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT valor FROM config_local WHERE clave = ?")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public void guardar(Connection c, String clave, String valor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO config_local (clave, valor) VALUES (?, ?)
                ON CONFLICT (clave) DO UPDATE SET valor = excluded.valor""")) {
            ps.setString(1, clave);
            ps.setString(2, valor);
            ps.executeUpdate();
        }
    }

    public void eliminar(Connection c, String clave) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM config_local WHERE clave = ?")) {
            ps.setString(1, clave);
            ps.executeUpdate();
        }
    }
}
