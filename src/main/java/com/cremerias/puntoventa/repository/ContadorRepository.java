package com.cremerias.puntoventa.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Consecutivos locales (folios). Debe usarse dentro de una transacción. */
public class ContadorRepository {

    public long siguiente(Connection c, String clave) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO contadores (clave, valor) VALUES (?, 1)
                ON CONFLICT (clave) DO UPDATE SET valor = valor + 1""")) {
            ps.setString(1, clave);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT valor FROM contadores WHERE clave = ?")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
