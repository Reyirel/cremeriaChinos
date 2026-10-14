package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.CategoriaGasto;
import com.cremerias.puntoventa.model.Gasto;
import com.cremerias.puntoventa.model.TipoGasto;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Gastos (fijos y variables) de cada sucursal. */
public class GastoRepository {

    public List<Gasto> listar(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT g.id, g.folio, g.sucursal_id, g.fecha, g.categoria, g.tipo, g.concepto, g.monto_centavos,
                       g.nota, g.usuario_id, u.nombre_completo
                FROM gastos g JOIN usuarios u ON u.id = g.usuario_id
                WHERE g.sucursal_id = ? AND g.eliminado_en IS NULL AND g.fecha >= ? AND g.fecha < ?
                ORDER BY g.fecha DESC""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                List<Gasto> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
                return lista;
            }
        }
    }

    /** Suma de gastos (fijos y variables) del periodo, para la utilidad neta del módulo Indicadores. */
    public long sumar(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(monto_centavos), 0) FROM gastos
                WHERE sucursal_id = ? AND eliminado_en IS NULL AND fecha >= ? AND fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void insertar(Connection c, Gasto g) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO gastos (id, folio, sucursal_id, fecha, categoria, tipo, concepto, monto_centavos, nota,
                                    usuario_id, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, g.id());
            ps.setString(2, g.folio());
            ps.setString(3, g.sucursalId());
            ps.setString(4, Tiempo.formatear(g.fecha()));
            ps.setString(5, g.categoria().name());
            ps.setString(6, g.tipo().name());
            ps.setString(7, g.concepto());
            ps.setLong(8, g.montoCentavos());
            ps.setString(9, g.nota());
            ps.setString(10, g.usuarioId());
            ps.setString(11, ahora);
            ps.setString(12, ahora);
            ps.executeUpdate();
        }
    }

    public void actualizar(Connection c, Gasto g) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE gastos SET sucursal_id = ?, fecha = ?, categoria = ?, tipo = ?, concepto = ?,
                                  monto_centavos = ?, nota = ?, actualizado_en = ?
                WHERE id = ?""")) {
            ps.setString(1, g.sucursalId());
            ps.setString(2, Tiempo.formatear(g.fecha()));
            ps.setString(3, g.categoria().name());
            ps.setString(4, g.tipo().name());
            ps.setString(5, g.concepto());
            ps.setLong(6, g.montoCentavos());
            ps.setString(7, g.nota());
            ps.setString(8, Tiempo.ahora());
            ps.setString(9, g.id());
            ps.executeUpdate();
        }
    }

    public void eliminar(Connection c, String id) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE gastos SET eliminado_en = ?, actualizado_en = ? WHERE id = ?")) {
            ps.setString(1, ahora);
            ps.setString(2, ahora);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    private static Gasto mapear(ResultSet rs) throws SQLException {
        return new Gasto(rs.getString(1), rs.getString(2), rs.getString(3), Tiempo.leer(rs.getString(4)),
                CategoriaGasto.valueOf(rs.getString(5)), TipoGasto.valueOf(rs.getString(6)), rs.getString(7),
                rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11));
    }
}
