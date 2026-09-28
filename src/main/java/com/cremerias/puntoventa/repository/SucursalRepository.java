package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class SucursalRepository {

    public void insertar(Connection c, String id, String codigo, String nombre) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sucursales (id, codigo, nombre, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, codigo);
            ps.setString(3, nombre);
            ps.setString(4, ahora);
            ps.setString(5, ahora);
            ps.executeUpdate();
        }
    }

    public Optional<String> nombre(Connection c, String id) throws SQLException {
        if (id == null) {
            return Optional.empty();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT nombre FROM sucursales WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }
}
