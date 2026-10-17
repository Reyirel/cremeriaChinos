package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Ids;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reconciliación de existencias entre cajas. Sin conexión cada caja impide vender más de lo que ella
 * ve, pero dos cajas pueden vender las mismas últimas piezas; al sincronizar se suman los dos
 * movimientos y la existencia puede quedar negativa. No se corrige sola (no se sabe cuál venta "sobra"):
 * se avisa al administrador para que cuente y ajuste.
 *
 * <p>Si el total del producto alcanza pero un lote quedó negativo (dos cajas tomaron el mismo lote), se
 * puede <b>reacomodar</b>: el faltante del lote pasa al lote más antiguo que sí tiene, sin cambiar la
 * existencia total del producto.
 */
public class ReconciliacionService {

    public record Negativo(String sucursalId, String sucursal, String productoId, String producto, Unidad unidad,
                           BigDecimal existencia, int lotesNegativos) {

        /** El producto alcanza en total; solo hay lotes en negativo (se puede reacomodar). */
        public boolean soloLotes() {
            return existencia.signum() >= 0;
        }
    }

    private final Database database;
    private final String dispositivoId;
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public ReconciliacionService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Productos con existencia negativa o con algún lote negativo, por ubicación. */
    public List<Negativo> negativos(Usuario quien) {
        Permisos.exigirAdministrador(quien, "revisar existencias negativas");
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT l.sucursal_id, s.nombre, l.producto_id, p.nombre, p.unidad,
                           (SELECT COALESCE(ROUND(SUM(m.cantidad), 3), 0) FROM movimientos_inventario m
                            WHERE m.producto_id = l.producto_id AND m.sucursal_id = l.sucursal_id) AS existencia,
                           SUM(CASE WHEN COALESCE(e.existencia, 0) < 0 THEN 1 ELSE 0 END) AS negativos
                    FROM lotes l
                    JOIN sucursales s ON s.id = l.sucursal_id
                    JOIN productos p ON p.id = l.producto_id
                    LEFT JOIN v_existencias_lote e ON e.lote_id = l.id
                    GROUP BY l.sucursal_id, l.producto_id
                    HAVING existencia < 0 OR negativos > 0
                    ORDER BY s.nombre, p.nombre""");
                 ResultSet rs = ps.executeQuery()) {
                List<Negativo> lista = new ArrayList<>();
                while (rs.next()) {
                    Unidad unidad = Unidad.valueOf(rs.getString(5));
                    lista.add(new Negativo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), unidad,
                            BigDecimal.valueOf(rs.getDouble(6)).setScale(3, RoundingMode.HALF_UP), rs.getInt(7)));
                }
                return lista;
            }
        });
    }

    /**
     * Pasa el faltante de los lotes negativos a los lotes más antiguos con existencia (movimientos de
     * ajuste que suman cero). Devuelve el folio, o {@code null} si no había nada que reacomodar.
     */
    public String reacomodarLotes(String sucursalId, String productoId, Usuario quien) {
        Permisos.exigirAdministrador(quien, "reacomodar lotes");
        return database.enTransaccion(c -> {
            List<LoteRepository.Lote> suyos = lotes.deProducto(c, productoId, sucursalId);
            BigDecimal faltante = suyos.stream().map(LoteRepository.Lote::existencia).filter(e -> e.signum() < 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).negate();
            if (faltante.signum() <= 0) {
                return null;
            }
            BigDecimal disponible = suyos.stream().map(LoteRepository.Lote::existencia).filter(e -> e.signum() > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (disponible.compareTo(faltante) < 0) {
                throw new IllegalStateException("La existencia total es negativa: primero cuenta el producto y registra"
                        + " el ajuste; luego se reacomodan los lotes.");
            }
            String referencia = Ids.nuevo();
            for (LoteRepository.Lote lote : suyos) {
                if (lote.existencia().signum() < 0) {
                    inventario.registrar(c, productoId, sucursalId, lote.id(), "AJUSTE", lote.existencia().negate(),
                            referencia, quien.id(), "Reacomodo de lotes (ventas sin conexión)");
                }
            }
            BigDecimal restante = faltante;
            for (LoteRepository.Lote lote : suyos) {
                if (restante.signum() <= 0) {
                    break;
                }
                if (lote.existencia().signum() <= 0) {
                    continue;
                }
                BigDecimal toma = lote.existencia().min(restante);
                inventario.registrar(c, productoId, sucursalId, lote.id(), "AJUSTE", toma.negate(), referencia, quien.id(),
                        "Reacomodo de lotes (ventas sin conexión)");
                restante = restante.subtract(toma);
            }
            String nombre = nombre(c, productoId);
            Unidad unidad = unidad(c, productoId);
            return bitacora.registrar(c, AccionBitacora.AJUSTE_INVENTARIO, quien, dispositivoId, "lotes", referencia,
                    nombre + ": se reacomodaron " + Cantidades.formatear(unidad, faltante)
                            + " de lotes en negativo a los lotes con existencia (la existencia total no cambia)");
        });
    }

    private static String nombre(Connection c, String productoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT nombre FROM productos WHERE id = ?")) {
            ps.setString(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : productoId;
            }
        }
    }

    private static Unidad unidad(Connection c, String productoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT unidad FROM productos WHERE id = ?")) {
            ps.setString(1, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Unidad.valueOf(rs.getString(1)) : Unidad.PZA;
            }
        }
    }
}
