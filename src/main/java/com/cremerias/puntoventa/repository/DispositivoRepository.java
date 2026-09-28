package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DispositivoRepository {

    /** Devuelve el id de esta terminal, creándolo la primera vez. */
    public String obtenerOCrear(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM dispositivo LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        String id = Ids.nuevo();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dispositivo (id, nombre, sucursal_id, creado_en) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, nombreCaja(id));
            ps.setString(3, sucursalId);
            ps.setString(4, Tiempo.ahora());
            ps.executeUpdate();
        }
        return id;
    }

    /** "Caja 1987": mismo código de terminal que usan los folios de venta. */
    public static String nombreCaja(String dispositivoId) {
        return "Caja " + dispositivoId.replace("-", "").substring(0, 4).toUpperCase(java.util.Locale.ROOT);
    }
}
