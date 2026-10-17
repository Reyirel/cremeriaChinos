package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Consultas de ventas/costo/kilos/meta para el módulo Indicadores. */
public class IndicadoresRepository {

    /** Fecha y total de una venta completada; de aquí salen tickets, horas y clientes por día. */
    public record VentaFila(Instant fecha, long totalCentavos) {
    }

    /** Meta capturada de una sucursal/año/mes. Los campos de KPI son nulos si no se han capturado. */
    public record MetaFila(long ventaCentavos, BigDecimal kilosGramos, BigDecimal articulosTicket, Double utilidadPct) {
    }

    /** Ponderación de cada KPI del tablero (venta/kilos/artículos por ticket/utilidad), de configuracion_general. */
    public record Ponderaciones(int venta, int kilos, int articulosTicket, int utilidad) {
    }

    /** Kilos vendidos (gramaje neto) de una marca en el periodo. */
    public record MarcaKilos(String marca, BigDecimal gramos) {
    }

    /**
     * Ventas completadas (no canceladas) del periodo menos lo reembolsado a clientes por devoluciones
     * en el periodo (la devolución cuenta el día en que se hace, como en el corte de caja).
     */
    public long ventaNeta(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE((SELECT SUM(total_centavos) FROM ventas
                                 WHERE sucursal_id = ? AND estado = 'COMPLETADA' AND fecha >= ? AND fecha < ?), 0)
                     - COALESCE((SELECT SUM(reembolso_centavos) FROM devoluciones_venta
                                 WHERE sucursal_id = ? AND fecha >= ? AND fecha < ?), 0)""")) {
            for (int i = 0; i < 2; i++) {
                ps.setString(1 + i * 3, sucursalId);
                ps.setString(2 + i * 3, Tiempo.formatear(desde));
                ps.setString(3 + i * 3, Tiempo.formatear(hastaExclusivo));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Fecha y monto de cada reembolso a clientes del periodo (para restarlo por día). */
    public List<VentaFila> reembolsosDelPeriodo(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT fecha, reembolso_centavos FROM devoluciones_venta
                WHERE sucursal_id = ? AND fecha >= ? AND fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                List<VentaFila> filas = new ArrayList<>();
                while (rs.next()) {
                    filas.add(new VentaFila(Tiempo.leer(rs.getString(1)), rs.getLong(2)));
                }
                return filas;
            }
        }
    }

    /** Devoluciones, ajustes y diferencias de caja del periodo (todo al costo, salvo lo reembolsado). */
    public record Operacion(int devoluciones, long reembolsadoCentavos, long costoNoVendibleCentavos,
                            long ajustesPerdidaCentavos, long ajustesSobranteCentavos, long faltantesCajaCentavos,
                            long sobrantesCajaCentavos) {
    }

    public Operacion operacion(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        String d = Tiempo.formatear(desde);
        String h = Tiempo.formatear(hastaExclusivo);
        int devoluciones;
        long reembolsado;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*), COALESCE(SUM(reembolso_centavos), 0) FROM devoluciones_venta
                WHERE sucursal_id = ? AND fecha >= ? AND fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, d);
            ps.setString(3, h);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                devoluciones = rs.getInt(1);
                reembolsado = rs.getLong(2);
            }
        }
        long noVendible = suma(c, """
                SELECT COALESCE(SUM(x.costo_centavos), 0) FROM devolucion_venta_detalle x
                JOIN devoluciones_venta v ON v.id = x.devolucion_id
                WHERE v.sucursal_id = ? AND v.fecha >= ? AND v.fecha < ? AND x.estado_producto <> 'VENDIBLE'""",
                sucursalId, d, h);
        long perdida = -suma(c, """
                SELECT COALESCE(SUM(costo_centavos), 0) FROM ajustes_inventario
                WHERE sucursal_id = ? AND fecha >= ? AND fecha < ? AND cantidad < 0""", sucursalId, d, h);
        long sobrante = suma(c, """
                SELECT COALESCE(SUM(costo_centavos), 0) FROM ajustes_inventario
                WHERE sucursal_id = ? AND fecha >= ? AND fecha < ? AND cantidad > 0""", sucursalId, d, h);
        long faltantesCaja = -suma(c, """
                SELECT COALESCE(SUM(diferencia_centavos), 0) FROM turnos_caja
                WHERE sucursal_id = ? AND cerrado_en >= ? AND cerrado_en < ? AND diferencia_centavos < 0""",
                sucursalId, d, h);
        long sobrantesCaja = suma(c, """
                SELECT COALESCE(SUM(diferencia_centavos), 0) FROM turnos_caja
                WHERE sucursal_id = ? AND cerrado_en >= ? AND cerrado_en < ? AND diferencia_centavos > 0""",
                sucursalId, d, h);
        return new Operacion(devoluciones, reembolsado, noVendible, perdida, sobrante, faltantesCaja, sobrantesCaja);
    }

    private static long suma(Connection c, String sql, String... parametros) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setString(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Costo real de la mercancía vendida: el costo por unidad de cada lote realmente surtido
     * (PEPS), no el costo/precio actual del producto. Las ventas canceladas no cuentan porque se
     * filtra por el estado de la venta, aunque el movimiento VENTA original siga en la tabla.
     */
    public long costoVenta(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(ROUND(-mi.cantidad * l.costo_unitario_centavos)), 0)
                FROM movimientos_inventario mi
                JOIN lotes l ON l.id = mi.lote_id
                JOIN ventas v ON v.id = mi.referencia_id
                WHERE mi.tipo = 'VENTA' AND v.sucursal_id = ? AND v.estado = 'COMPLETADA'
                  AND v.fecha >= ? AND v.fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                // Lo que un cliente devolvió en buen estado regresó al inventario: ya no es costo de lo vendido.
                return rs.getLong(1) - suma(c, """
                        SELECT COALESCE(SUM(ROUND(mi.cantidad * l.costo_unitario_centavos)), 0)
                        FROM movimientos_inventario mi JOIN lotes l ON l.id = mi.lote_id
                        WHERE mi.tipo = 'DEVOLUCION_CLIENTE' AND mi.sucursal_id = ? AND mi.fecha >= ? AND mi.fecha < ?""",
                        sucursalId, Tiempo.formatear(desde), Tiempo.formatear(hastaExclusivo));
            }
        }
    }

    /** Mermas valuadas al costo (PEPS) del periodo, para la utilidad neta. */
    public long mermasCentavos(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(costo_centavos), 0) FROM mermas
                WHERE sucursal_id = ? AND fecha >= ? AND fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Valor del inventario de la sucursal al costo, justo antes de {@code corte}: la existencia de
     * cada lote (entradas − salidas registradas en movimientos_inventario) valuada a su propio
     * costo unitario (PEPS), sumada en todos sus lotes.
     */
    public long valorInventarioAntesDe(Connection c, String sucursalId, Instant corte) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(ROUND(mi.cantidad * l.costo_unitario_centavos)), 0)
                FROM movimientos_inventario mi
                JOIN lotes l ON l.id = mi.lote_id
                WHERE mi.sucursal_id = ? AND mi.fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(corte));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Fecha y total de cada venta completada del periodo: tickets, horas de venta y clientes por día salen de aquí. */
    public List<VentaFila> ventasDelPeriodo(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT fecha, total_centavos FROM ventas
                WHERE sucursal_id = ? AND estado = 'COMPLETADA' AND fecha >= ? AND fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                List<VentaFila> filas = new ArrayList<>();
                while (rs.next()) {
                    filas.add(new VentaFila(Tiempo.leer(rs.getString(1)), rs.getLong(2)));
                }
                return filas;
            }
        }
    }

    /** Líneas de venta_detalle del periodo (artículos por ticket y valor promedio del artículo). */
    public int lineasVendidas(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM venta_detalle d JOIN ventas v ON v.id = d.venta_id
                WHERE v.sucursal_id = ? AND v.estado = 'COMPLETADA' AND v.fecha >= ? AND v.fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Empleados activos asignados a la sucursal, para clientes por empleado diario. */
    public int empleadosActivos(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM usuarios WHERE sucursal_id = ? AND activo = 1 AND eliminado_en IS NULL")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Kilos vendidos del periodo, en gramos: unidades base vendidas × gramaje neto (gramaje − merma),
     * igual que {@code ComisionService} para las comisiones por gramaje.
     */
    public BigDecimal kilosVendidosGramos(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(COALESCE(d.cantidad_base, d.cantidad)
                                 * MAX(0, COALESCE(p.gramaje_gramos, 0) - p.merma_gramos)), 0)
                FROM venta_detalle d
                JOIN ventas v ON v.id = d.venta_id
                JOIN productos p ON p.id = d.producto_id
                WHERE v.sucursal_id = ? AND v.estado = 'COMPLETADA' AND v.fecha >= ? AND v.fecha < ?""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return BigDecimal.valueOf(rs.getDouble(1));
            }
        }
    }

    /** Kilos vendidos del periodo (gramaje neto), agrupados por marca ("Sin marca" si el producto no tiene). */
    public List<MarcaKilos> kilosPorMarca(Connection c, String sucursalId, Instant desde, Instant hastaExclusivo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(p.marca, 'Sin marca') AS marca,
                       SUM(COALESCE(d.cantidad_base, d.cantidad) * MAX(0, COALESCE(p.gramaje_gramos, 0) - p.merma_gramos))
                           AS gramos
                FROM venta_detalle d
                JOIN ventas v ON v.id = d.venta_id
                JOIN productos p ON p.id = d.producto_id
                WHERE v.sucursal_id = ? AND v.estado = 'COMPLETADA' AND v.fecha >= ? AND v.fecha < ?
                GROUP BY COALESCE(p.marca, 'Sin marca')
                ORDER BY gramos DESC""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                List<MarcaKilos> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new MarcaKilos(rs.getString(1), BigDecimal.valueOf(rs.getDouble(2))));
                }
                return lista;
            }
        }
    }

    public Optional<MetaFila> meta(Connection c, String sucursalId, int anio, int mes) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT meta_centavos, meta_kilos, meta_articulos_ticket, meta_utilidad_pct
                FROM metas_sucursal WHERE sucursal_id = ? AND anio = ? AND mes = ?""")) {
            ps.setString(1, sucursalId);
            ps.setInt(2, anio);
            ps.setInt(3, mes);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long venta = rs.getLong(1);
                BigDecimal kilos = decimalONulo(rs, 2);
                BigDecimal articulos = decimalONulo(rs, 3);
                double utilidadValor = rs.getDouble(4);
                Double utilidad = rs.wasNull() ? null : utilidadValor;
                return Optional.of(new MetaFila(venta, kilos, articulos, utilidad));
            }
        }
    }

    public void guardarMeta(Connection c, String sucursalId, int anio, int mes, long metaCentavos,
                             BigDecimal kilosGramos, BigDecimal articulosTicket, Double utilidadPct) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO metas_sucursal (id, sucursal_id, anio, mes, meta_centavos, meta_kilos,
                                            meta_articulos_ticket, meta_utilidad_pct, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (sucursal_id, anio, mes) DO UPDATE SET
                    meta_centavos = excluded.meta_centavos, meta_kilos = excluded.meta_kilos,
                    meta_articulos_ticket = excluded.meta_articulos_ticket,
                    meta_utilidad_pct = excluded.meta_utilidad_pct, actualizado_en = excluded.actualizado_en""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, sucursalId);
            ps.setInt(3, anio);
            ps.setInt(4, mes);
            ps.setLong(5, metaCentavos);
            if (kilosGramos == null) {
                ps.setNull(6, Types.REAL);
            } else {
                ps.setBigDecimal(6, kilosGramos);
            }
            if (articulosTicket == null) {
                ps.setNull(7, Types.REAL);
            } else {
                ps.setBigDecimal(7, articulosTicket);
            }
            if (utilidadPct == null) {
                ps.setNull(8, Types.REAL);
            } else {
                ps.setDouble(8, utilidadPct);
            }
            ps.setString(9, ahora);
            ps.setString(10, ahora);
            ps.executeUpdate();
        }
    }

    /** Ponderación de cada KPI del tablero, con los valores por defecto cargados en V12. */
    public Ponderaciones ponderaciones(Connection c) throws SQLException {
        return new Ponderaciones(
                ponderacion(c, "kpi.ponderacion.venta", 30),
                ponderacion(c, "kpi.ponderacion.kilos", 34),
                ponderacion(c, "kpi.ponderacion.articulos_ticket", 20),
                ponderacion(c, "kpi.ponderacion.utilidad", 16));
    }

    private static int ponderacion(Connection c, String clave, int defecto) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT valor FROM configuracion_general WHERE clave = ?")) {
            ps.setString(1, clave);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(rs.getString(1)) : defecto;
            }
        }
    }

    /** Número con hasta 3 decimales; nulo si la columna no se capturó. */
    private static BigDecimal decimalONulo(ResultSet rs, int columna) throws SQLException {
        double valor = rs.getDouble(columna);
        return rs.wasNull() ? null : BigDecimal.valueOf(valor);
    }
}
