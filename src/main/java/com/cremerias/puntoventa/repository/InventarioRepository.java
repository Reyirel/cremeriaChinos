package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class InventarioRepository {

    /** Registra un movimiento (cantidad positiva entra, negativa sale) sobre un lote. */
    public void registrar(Connection c, String productoId, String sucursalId, String loteId, String tipo,
                          BigDecimal cantidad, String referenciaId, String usuarioId, String notas) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO movimientos_inventario (id, producto_id, sucursal_id, lote_id, tipo, cantidad, referencia_id,
                                                    usuario_id, fecha, notas)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, productoId);
            ps.setString(3, sucursalId);
            ps.setString(4, loteId);
            ps.setString(5, tipo);
            ps.setBigDecimal(6, cantidad);
            ps.setString(7, referenciaId);
            ps.setString(8, usuarioId);
            ps.setString(9, Tiempo.ahora());
            ps.setString(10, notas);
            ps.executeUpdate();
        }
    }
}
