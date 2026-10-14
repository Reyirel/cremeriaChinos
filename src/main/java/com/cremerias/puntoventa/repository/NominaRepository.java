package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.Nomina;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Salario y comida de cada empleado por semana (lunes de inicio), por sucursal. */
public class NominaRepository {

    public List<Nomina> listar(Connection c, String sucursalId, LocalDate desdeSemana, LocalDate hastaSemana)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT n.sucursal_id, n.usuario_id, u.nombre_completo, n.semana_inicio, n.salario_centavos,
                       n.comida_centavos
                FROM nomina_semanal n JOIN usuarios u ON u.id = n.usuario_id
                WHERE n.sucursal_id = ? AND n.semana_inicio >= ? AND n.semana_inicio <= ?
                ORDER BY n.semana_inicio, u.nombre_completo COLLATE NOCASE""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, desdeSemana.toString());
            ps.setString(3, hastaSemana.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<Nomina> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
                return lista;
            }
        }
    }

    public Optional<Nomina> buscar(Connection c, String sucursalId, String usuarioId, LocalDate semanaInicio)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT n.sucursal_id, n.usuario_id, u.nombre_completo, n.semana_inicio, n.salario_centavos,
                       n.comida_centavos
                FROM nomina_semanal n JOIN usuarios u ON u.id = n.usuario_id
                WHERE n.sucursal_id = ? AND n.usuario_id = ? AND n.semana_inicio = ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, usuarioId);
            ps.setString(3, semanaInicio.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    /** Suma de salario + comida de las semanas cuyo lunes de inicio cae en el periodo, para Indicadores. */
    public long sumar(Connection c, String sucursalId, LocalDate desde, LocalDate hasta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(salario_centavos + comida_centavos), 0) FROM nomina_semanal
                WHERE sucursal_id = ? AND semana_inicio >= ? AND semana_inicio <= ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, desde.toString());
            ps.setString(3, hasta.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    public void guardar(Connection c, String sucursalId, String usuarioId, LocalDate semanaInicio,
                        long salarioCentavos, long comidaCentavos) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO nomina_semanal (id, sucursal_id, usuario_id, semana_inicio, salario_centavos,
                                            comida_centavos, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (sucursal_id, usuario_id, semana_inicio) DO UPDATE SET
                    salario_centavos = excluded.salario_centavos, comida_centavos = excluded.comida_centavos,
                    actualizado_en = excluded.actualizado_en""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, sucursalId);
            ps.setString(3, usuarioId);
            ps.setString(4, semanaInicio.toString());
            ps.setLong(5, salarioCentavos);
            ps.setLong(6, comidaCentavos);
            ps.setString(7, ahora);
            ps.setString(8, ahora);
            ps.executeUpdate();
        }
    }

    private static Nomina mapear(ResultSet rs) throws SQLException {
        return new Nomina(rs.getString(1), rs.getString(2), rs.getString(3), LocalDate.parse(rs.getString(4)),
                rs.getLong(5), rs.getLong(6));
    }
}
