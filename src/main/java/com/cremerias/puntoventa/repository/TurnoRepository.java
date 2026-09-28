package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.CorteRealizado;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public class TurnoRepository {

    private static final String SELECT_TURNO = """
            SELECT t.id, t.usuario_id, u.nombre_completo, t.abierto_en, t.fondo_inicial_centavos, t.sucursal_id,
                   t.dispositivo_id, COALESCE(d.nombre, 'Caja ' || upper(substr(replace(t.dispositivo_id, '-', ''), 1, 4))) AS dispositivo_nombre
            FROM turnos_caja t
            JOIN usuarios u ON u.id = t.usuario_id
            LEFT JOIN dispositivo d ON d.id = t.dispositivo_id
            """;

    public Optional<Turno> abierto(Connection c, String dispositivoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_TURNO
                + " WHERE t.dispositivo_id = ? AND t.estado = 'ABIERTO'")) {
            ps.setString(1, dispositivoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public Optional<Turno> abiertoPorId(Connection c, String turnoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_TURNO + " WHERE t.id = ? AND t.estado = 'ABIERTO'")) {
            ps.setString(1, turnoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        }
    }

    public List<Turno> abiertosDeSucursal(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_TURNO
                + " WHERE (? IS NULL OR t.sucursal_id = ?) AND t.estado = 'ABIERTO' ORDER BY t.abierto_en")) {
            ps.setString(1, sucursalId);
            ps.setString(2, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Turno> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(mapear(rs));
                }
                return lista;
            }
        }
    }

    /** Cortes de la sucursal cerrados entre dos instantes (UTC). */
    public List<CorteRealizado> cerradosDeSucursal(Connection c, String sucursalId, Instant desde, Instant hasta)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_TURNO.replace("FROM turnos_caja t", """
                     , t.cerrado_en, t.efectivo_esperado_centavos, t.efectivo_contado_centavos, t.diferencia_centavos,
                       t.notas_cierre, COALESCE(cp.nombre_completo, '—') AS cerrado_por_nombre,
                       (SELECT COUNT(*) FROM ventas v WHERE v.turno_id = t.id AND v.estado = 'COMPLETADA') AS num_ventas,
                       (SELECT COALESCE(SUM(v.total_centavos), 0) FROM ventas v
                         WHERE v.turno_id = t.id AND v.estado = 'COMPLETADA') AS total_vendido
                FROM turnos_caja t
                LEFT JOIN usuarios cp ON cp.id = t.cerrado_por""")
                + " WHERE (? IS NULL OR t.sucursal_id = ?) AND t.estado = 'CERRADO' AND t.cerrado_en >= ? AND t.cerrado_en < ?"
                + " ORDER BY t.cerrado_en DESC")) {
            ps.setString(1, sucursalId);
            ps.setString(2, sucursalId);
            ps.setString(3, Tiempo.formatear(desde));
            ps.setString(4, Tiempo.formatear(hasta));
            try (ResultSet rs = ps.executeQuery()) {
                List<CorteRealizado> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new CorteRealizado(mapear(rs), Tiempo.leer(rs.getString("cerrado_en")),
                            rs.getInt("num_ventas"), rs.getLong("total_vendido"),
                            rs.getLong("efectivo_esperado_centavos"), rs.getLong("efectivo_contado_centavos"),
                            rs.getLong("diferencia_centavos"), rs.getString("cerrado_por_nombre"),
                            rs.getString("notas_cierre")));
                }
                return lista;
            }
        }
    }

    private static Turno mapear(ResultSet rs) throws SQLException {
        return new Turno(rs.getString("id"), rs.getString("usuario_id"), rs.getString("nombre_completo"),
                Tiempo.leer(rs.getString("abierto_en")), rs.getLong("fondo_inicial_centavos"),
                rs.getString("sucursal_id"), rs.getString("dispositivo_id"), rs.getString("dispositivo_nombre"));
    }

    public String abrir(Connection c, String sucursalId, String dispositivoId, String usuarioId, long fondo)
            throws SQLException {
        String id = Ids.nuevo();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO turnos_caja (id, sucursal_id, dispositivo_id, usuario_id, abierto_en, fondo_inicial_centavos)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, sucursalId);
            ps.setString(3, dispositivoId);
            ps.setString(4, usuarioId);
            ps.setString(5, Tiempo.ahora());
            ps.setLong(6, fondo);
            ps.executeUpdate();
        }
        return id;
    }

    public void registrarMovimiento(Connection c, String turnoId, String tipo, long monto, String concepto,
                                    String usuarioId, String autorizadoPor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO movimientos_caja (id, turno_id, tipo, monto_centavos, concepto, usuario_id, autorizado_por, fecha)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, turnoId);
            ps.setString(3, tipo);
            ps.setLong(4, monto);
            ps.setString(5, concepto);
            ps.setString(6, usuarioId);
            ps.setString(7, autorizadoPor);
            ps.setString(8, Tiempo.ahora());
            ps.executeUpdate();
        }
    }

    public ResumenTurno resumen(Connection c, Turno turno) throws SQLException {
        int ventas = 0;
        int canceladas = 0;
        long total = 0;
        long totalCancelado = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT estado, COUNT(*), COALESCE(SUM(total_centavos), 0)
                FROM ventas WHERE turno_id = ? GROUP BY estado""")) {
            ps.setString(1, turno.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("COMPLETADA".equals(rs.getString(1))) {
                        ventas = rs.getInt(2);
                        total = rs.getLong(3);
                    } else {
                        canceladas = rs.getInt(2);
                        totalCancelado = rs.getLong(3);
                    }
                }
            }
        }

        Map<MetodoPago, Long> porMetodo = new EnumMap<>(MetodoPago.class);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.metodo, COALESCE(SUM(p.monto_centavos), 0)
                FROM venta_pagos p JOIN ventas v ON v.id = p.venta_id
                WHERE v.turno_id = ? AND v.estado = 'COMPLETADA'
                GROUP BY p.metodo""")) {
            ps.setString(1, turno.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    porMetodo.put(MetodoPago.valueOf(rs.getString(1)), rs.getLong(2));
                }
            }
        }

        long entradas = 0;
        long retiros = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT tipo, COALESCE(SUM(monto_centavos), 0) FROM movimientos_caja
                WHERE turno_id = ? GROUP BY tipo""")) {
            ps.setString(1, turno.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("ENTRADA".equals(rs.getString(1))) {
                        entradas = rs.getLong(2);
                    } else {
                        retiros = rs.getLong(2);
                    }
                }
            }
        }

        return new ResumenTurno(turno, ventas, canceladas, total, totalCancelado,
                porMetodo.getOrDefault(MetodoPago.EFECTIVO, 0L),
                porMetodo.getOrDefault(MetodoPago.TARJETA, 0L),
                porMetodo.getOrDefault(MetodoPago.TRANSFERENCIA, 0L),
                entradas, retiros, Instant.now());
    }

    public void cerrar(Connection c, String turnoId, long esperado, long contado, String notas, String cerradoPor)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE turnos_caja
                SET estado = 'CERRADO', cerrado_en = ?, efectivo_esperado_centavos = ?,
                    efectivo_contado_centavos = ?, diferencia_centavos = ?, notas_cierre = ?, cerrado_por = ?
                WHERE id = ? AND estado = 'ABIERTO'""")) {
            ps.setString(1, Tiempo.ahora());
            ps.setLong(2, esperado);
            ps.setLong(3, contado);
            ps.setLong(4, contado - esperado);
            ps.setString(5, notas);
            ps.setString(6, cerradoPor);
            ps.setString(7, turnoId);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("A esta caja ya se le hizo el corte.");
            }
        }
    }
}
