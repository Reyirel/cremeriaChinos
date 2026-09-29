package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Productos con existencia que llevan cierto tiempo sin venderse en una sucursal, para ofertarlos.
 * <ul>
 *   <li>Si el producto tiene aviso propio, se usa su plazo (minutos, horas, días o semanas) y solo
 *   se avisa a quien eligió el administrador (administrador, supervisor y/o caja).</li>
 *   <li>Si no, se usa el plazo en días de la sucursal y solo lo ve el administrador.</li>
 * </ul>
 * El tiempo se cuenta desde la última venta en esa sucursal (o desde que llegó el primer lote si
 * nunca se ha vendido ahí).
 */
public class SinVentaService {

    public static final int DIAS_POR_DEFECTO = 30;

    /**
     * @param ultimaVenta nula si nunca se ha vendido en esa sucursal
     * @param plazo       plazo que se cumplió ("15 minutos", "30 días")
     */
    public record SinVenta(String sucursalId, String sucursal, String productoId, String producto, Unidad unidad,
                           BigDecimal existencia, Instant ultimaVenta, Duration sinVender, String plazo,
                           boolean avisoDelProducto) {
    }

    private final Database database;
    private final Clock reloj;

    public SinVentaService(Database database, Clock reloj) {
        this.database = database;
        this.reloj = reloj;
    }

    /**
     * Avisos que le tocan a este rol.
     *
     * @param sucursalId sucursal a revisar; nula = todas
     */
    public List<SinVenta> pendientes(Rol rol, String sucursalId) {
        Instant ahora = reloj.instant();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT s.id, s.nombre, p.id, p.nombre, p.unidad, e.existencia,
                           (SELECT MAX(v.fecha) FROM venta_detalle d JOIN ventas v ON v.id = d.venta_id
                            WHERE d.producto_id = p.id AND v.sucursal_id = s.id AND v.estado = 'COMPLETADA') AS ultima,
                           (SELECT MIN(l.fecha_ingreso) FROM lotes l
                            WHERE l.sucursal_id = s.id AND l.producto_id = p.id) AS primer_lote,
                           COALESCE(a.dias_sin_venta, ?) AS dias,
                           p.aviso_plazo, p.aviso_unidad, p.aviso_admin, p.aviso_supervisor, p.aviso_caja
                    FROM v_existencias e
                    JOIN sucursales s ON s.id = e.sucursal_id
                    JOIN productos p ON p.id = e.producto_id
                    LEFT JOIN alertas_sucursal a ON a.sucursal_id = s.id
                    WHERE s.es_almacen = 0 AND s.eliminado_en IS NULL AND s.activo = 1
                      AND p.eliminado_en IS NULL AND p.activo = 1 AND e.existencia > 0
                      AND (? IS NULL OR s.id = ?)""")) {
                ps.setInt(1, DIAS_POR_DEFECTO);
                ps.setString(2, sucursalId);
                ps.setString(3, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SinVenta> lista = new ArrayList<>();
                    while (rs.next()) {
                        Instant ultima = Tiempo.leer(rs.getString(7));
                        Instant referencia = ultima != null ? ultima : Tiempo.leer(rs.getString(8));
                        if (referencia == null) {
                            continue;
                        }
                        AvisoSinVenta aviso = aviso(rs);
                        Duration plazo;
                        String textoPlazo;
                        if (aviso != null) {
                            if (!aviso.avisaA(rol)) {
                                continue;
                            }
                            plazo = aviso.duracion();
                            textoPlazo = aviso.describir();
                        } else {
                            if (rol != Rol.ADMINISTRADOR) {
                                continue;
                            }
                            int dias = rs.getInt(9);
                            plazo = Duration.ofDays(dias);
                            textoPlazo = dias + (dias == 1 ? " día" : " días");
                        }
                        Duration transcurrido = Duration.between(referencia, ahora);
                        if (transcurrido.compareTo(plazo) >= 0) {
                            lista.add(new SinVenta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                    Unidad.valueOf(rs.getString(5)),
                                    BigDecimal.valueOf(rs.getDouble(6)).setScale(3, RoundingMode.HALF_UP), ultima,
                                    transcurrido, textoPlazo, aviso != null));
                        }
                    }
                    lista.sort(Comparator.comparing(SinVenta::sinVender).reversed());
                    return lista;
                }
            }
        });
    }

    private static AvisoSinVenta aviso(ResultSet rs) throws SQLException {
        int plazo = rs.getInt(10);
        if (rs.wasNull() || rs.getString(11) == null) {
            return null;
        }
        return new AvisoSinVenta(plazo, AvisoSinVenta.Unidad.valueOf(rs.getString(11)), rs.getBoolean(12),
                rs.getBoolean(13), rs.getBoolean(14));
    }
}
