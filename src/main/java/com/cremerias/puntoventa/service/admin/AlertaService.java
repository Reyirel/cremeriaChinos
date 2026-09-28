package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Aviso de productos con existencia que no se han vendido en cierto número de días. */
public class AlertaService {

    private static final String CLAVE_DIAS = "alerta.dias_sin_venta";

    /** @param ultimaVenta nula si nunca se ha vendido en esa sucursal */
    public record SinVenta(String sucursal, String producto, Unidad unidad, BigDecimal existencia, Instant ultimaVenta,
                           long dias) {
    }

    private final Database database;
    private final Clock reloj;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public AlertaService(Database database, Clock reloj, String dispositivoId) {
        this.database = database;
        this.reloj = reloj;
        this.dispositivoId = dispositivoId;
    }

    public int diasSinVenta() {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT valor FROM configuracion_general WHERE clave = ?")) {
                ps.setString(1, CLAVE_DIAS);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Integer.parseInt(rs.getString(1)) : 30;
                }
            }
        });
    }

    public String guardarDiasSinVenta(int dias, Usuario quien) {
        if (dias < 1 || dias > 365) {
            throw new IllegalArgumentException("Los días deben estar entre 1 y 365.");
        }
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO configuracion_general (clave, valor, actualizado_en) VALUES (?, ?, ?)
                    ON CONFLICT (clave) DO UPDATE SET valor = excluded.valor, actualizado_en = excluded.actualizado_en""")) {
                ps.setString(1, CLAVE_DIAS);
                ps.setString(2, String.valueOf(dias));
                ps.setString(3, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.CONFIGURACION, quien, dispositivoId, "configuracion_general",
                    CLAVE_DIAS, "Aviso de productos sin venta: " + dias + " días");
        });
    }

    public List<SinVenta> productosSinVenta() {
        int dias = diasSinVenta();
        Instant ahora = reloj.instant();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT s.nombre, p.nombre, p.unidad, e.existencia,
                           (SELECT MAX(v.fecha) FROM venta_detalle d JOIN ventas v ON v.id = d.venta_id
                            WHERE v.sucursal_id = s.id AND d.producto_id = p.id AND v.estado = 'COMPLETADA') AS ultima,
                           (SELECT MIN(l.fecha_ingreso) FROM lotes l
                            WHERE l.sucursal_id = s.id AND l.producto_id = p.id) AS primer_lote
                    FROM v_existencias e
                    JOIN sucursales s ON s.id = e.sucursal_id
                    JOIN productos p ON p.id = e.producto_id
                    WHERE s.es_almacen = 0 AND s.eliminado_en IS NULL AND s.activo = 1
                      AND p.eliminado_en IS NULL AND p.activo = 1 AND e.existencia > 0""");
                 ResultSet rs = ps.executeQuery()) {
                List<SinVenta> lista = new ArrayList<>();
                while (rs.next()) {
                    Instant ultima = Tiempo.leer(rs.getString(5));
                    Instant referencia = ultima != null ? ultima : Tiempo.leer(rs.getString(6));
                    if (referencia == null) {
                        continue;
                    }
                    long transcurridos = Duration.between(referencia, ahora).toDays();
                    if (transcurridos >= dias) {
                        lista.add(new SinVenta(rs.getString(1), rs.getString(2), Unidad.valueOf(rs.getString(3)),
                                BigDecimal.valueOf(rs.getDouble(4)).setScale(3, RoundingMode.HALF_UP), ultima,
                                transcurridos));
                    }
                }
                lista.sort(Comparator.comparingLong(SinVenta::dias).reversed());
                return lista;
            }
        });
    }
}
