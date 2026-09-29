package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.service.SinVentaService;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;

/**
 * Aviso de productos con existencia que no se han vendido: plazo en días de cada sucursal (para los
 * productos sin aviso propio) y la lista de pendientes para el administrador.
 */
public class AlertaService {

    private final Database database;
    private final String dispositivoId;
    private final SinVentaService sinVenta;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public AlertaService(Database database, Clock reloj, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.sinVenta = new SinVentaService(database, reloj);
    }

    /** Días configurados para esa sucursal, o el valor por defecto si aún no se ha definido. */
    public int diasSinVenta(String sucursalId) {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT dias_sin_venta FROM alertas_sucursal WHERE sucursal_id = ?")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : SinVentaService.DIAS_POR_DEFECTO;
                }
            }
        });
    }

    public String guardarDiasSinVenta(Sucursal sucursal, int dias, Usuario quien) {
        if (dias < 1 || dias > 365) {
            throw new IllegalArgumentException("Los días deben estar entre 1 y 365.");
        }
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO alertas_sucursal (sucursal_id, dias_sin_venta, actualizado_en) VALUES (?, ?, ?)
                    ON CONFLICT (sucursal_id) DO UPDATE SET
                        dias_sin_venta = excluded.dias_sin_venta, actualizado_en = excluded.actualizado_en""")) {
                ps.setString(1, sucursal.id());
                ps.setInt(2, dias);
                ps.setString(3, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.CONFIGURACION, quien, dispositivoId, "alertas_sucursal",
                    sucursal.id(), sucursal.nombre() + ": aviso de productos sin venta a " + dias + " días");
        });
    }

    /**
     * Productos sin venta que le tocan al administrador en todas las sucursales: los que tienen
     * aviso propio para el administrador y los que no, con el plazo en días de su sucursal.
     */
    public List<SinVentaService.SinVenta> productosSinVenta() {
        return sinVenta.pendientes(Rol.ADMINISTRADOR, null);
    }
}
