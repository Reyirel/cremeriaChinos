package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class BitacoraAccesosRepository {

    public void registrar(Connection c, String usuarioTexto, String usuarioId, String dispositivoId,
                          boolean exitoso, String motivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bitacora_accesos (id, usuario_texto, usuario_id, dispositivo_id, exitoso, motivo, fecha)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, usuarioTexto);
            ps.setString(3, usuarioId);
            ps.setString(4, dispositivoId);
            ps.setBoolean(5, exitoso);
            ps.setString(6, motivo);
            ps.setString(7, Tiempo.ahora());
            ps.executeUpdate();
        }
    }
}
