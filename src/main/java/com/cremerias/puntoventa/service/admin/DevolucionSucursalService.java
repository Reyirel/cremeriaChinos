package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.DevolucionService.EstadoProducto;
import com.cremerias.puntoventa.service.Peps;
import com.cremerias.puntoventa.service.Permisos;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Devolución de mercancía de una sucursal a la bodega. Sale de los lotes más antiguos de la sucursal;
 * lo vendible entra al almacén en un lote nuevo con el costo de origen y lo dañado o caducado queda
 * fuera del inventario vendible, valuado al costo. Opcionalmente se acredita a la sucursal el precio
 * interno de lo devuelto (lo que se le había cobrado por esa mercancía).
 */
public class DevolucionSucursalService {

    /** Cuánto regresa de un producto (unidad base) y en qué estado. */
    public record Linea(ProductoCatalogo producto, BigDecimal cantidad, EstadoProducto estado) {
    }

    public record Resumen(String id, String folio, Instant fecha, String sucursalId, String sucursal, String motivo,
                          long costoCentavos, long notaCreditoCentavos, int productos, String usuario) {
    }

    private final Database database;
    private final String dispositivoId;
    private final String almacenId;
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public DevolucionSucursalService(Database database, String dispositivoId, String almacenId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.almacenId = almacenId;
    }

    /** Registra la devolución (idempotente por {@code id}). Devuelve el folio. */
    public String registrar(String id, Sucursal sucursal, List<Linea> lineas, String motivo, boolean acreditar,
                            Usuario quien) {
        Permisos.exigirAdministrador(quien, "recibir devoluciones de sucursales");
        String motivoLimpio = Permisos.exigirTexto(motivo, "Escribe el motivo de la devolución.");
        if (sucursal == null || sucursal.esAlmacen()) {
            throw new IllegalArgumentException("Elige la sucursal que devuelve.");
        }
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("Agrega al menos un producto.");
        }
        for (Linea l : lineas) {
            if (l.cantidad() == null || l.cantidad().signum() <= 0 || l.estado() == null) {
                throw new IllegalArgumentException("Indica cuánto regresa de " + l.producto().nombre() + " y en qué estado.");
            }
        }
        return database.enTransaccion(c -> {
            Optional<String> ya = CompraService.folioDe(c, "devoluciones_sucursal", id);
            if (ya.isPresent()) {
                return ya.get();
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.DEVOLUCION_SUCURSAL.prefijo(), dispositivoId);
            long costoTotal = 0;
            long internoTotal = 0;
            List<Object[]> detalle = new ArrayList<>(); // {linea, cantidad, costo, loteDestino}
            List<String> textos = new ArrayList<>();
            for (Linea l : lineas) {
                BigDecimal cantidad = l.producto().unidad().normalizar(l.cantidad());
                List<Peps.Salida> salidas = Peps.salida(c, l.producto().id(), l.producto().nombre(), l.producto().unidad(),
                        sucursal.id(), cantidad, null);
                long costo = 0;
                for (Peps.Salida s : salidas) {
                    inventario.registrar(c, l.producto().id(), sucursal.id(), s.lote().id(), "DEVOLUCION_SUCURSAL_SALIDA",
                            s.cantidad().negate(), id, quien.id(), motivoLimpio);
                    costo += s.costoCentavos();
                    internoTotal += interno(c, s);
                }
                String loteDestino = null;
                if (l.estado() == EstadoProducto.VENDIBLE) {
                    long costoUnitario = BigDecimal.valueOf(costo).divide(cantidad, 0, RoundingMode.HALF_UP).longValueExact();
                    loteDestino = lotes.insertar(c, folio, l.producto().id(), almacenId, "ENTRADA", id, cantidad,
                            costoUnitario, quien.id());
                    inventario.registrar(c, l.producto().id(), almacenId, loteDestino, "DEVOLUCION_SUCURSAL_ENTRADA",
                            cantidad, id, quien.id(), "Devolución de " + sucursal.nombre());
                }
                costoTotal += costo;
                detalle.add(new Object[]{l, cantidad, costo, loteDestino});
                textos.add(l.producto().nombre() + " " + Cantidades.formatear(l.producto().unidad(), cantidad)
                        + (l.estado() == EstadoProducto.VENDIBLE ? "" : " (" + l.estado().name().toLowerCase() + ")"));
            }
            long acreditado = 0;
            if (acreditar && internoTotal > 0) {
                acreditado = CreditoService.notaDeCredito(c, bitacora, dispositivoId, sucursal.id(), sucursal.nombre(),
                        CreditoService.Concepto.DEVOLUCION, internoTotal, null, id, "Devolución " + folio, quien);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO devoluciones_sucursal (id, folio, sucursal_id, almacen_id, motivo, total_costo_centavos,
                                                       nota_credito_centavos, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, id);
                ps.setString(2, folio);
                ps.setString(3, sucursal.id());
                ps.setString(4, almacenId);
                ps.setString(5, motivoLimpio);
                ps.setLong(6, costoTotal);
                ps.setLong(7, acreditado);
                ps.setString(8, quien.id());
                ps.setString(9, Tiempo.ahora());
                ps.executeUpdate();
            }
            int renglon = 1;
            for (Object[] x : detalle) {
                Linea l = (Linea) x[0];
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO devolucion_sucursal_detalle (id, devolucion_id, renglon, producto_id, cantidad,
                                                                 estado_producto, costo_centavos, lote_destino_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, id);
                    ps.setInt(3, renglon++);
                    ps.setString(4, l.producto().id());
                    ps.setBigDecimal(5, (BigDecimal) x[1]);
                    ps.setString(6, l.estado().name());
                    ps.setLong(7, (long) x[2]);
                    ps.setString(8, (String) x[3]);
                    ps.executeUpdate();
                }
            }
            return bitacora.registrar(c, AccionBitacora.DEVOLUCION_SUCURSAL, quien, dispositivoId, "devoluciones_sucursal",
                    id, sucursal.nombre() + " devolvió " + String.join(", ", textos) + " (costo "
                            + Dinero.formatear(costoTotal) + (acreditado > 0 ? ", se acreditaron "
                            + Dinero.formatear(acreditado) : "") + ") · " + motivoLimpio, folio);
        });
    }

    /**
     * Precio interno de lo que sale de un lote de la sucursal: la parte proporcional de lo que se le
     * cobró en el surtido que creó ese lote; si el lote no vino de un surtido, su costo.
     */
    private static long interno(Connection c, Peps.Salida s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(importe_interno_centavos, costo_total_centavos), cantidad
                FROM surtido_detalle WHERE lote_destino_id = ?""")) {
            ps.setString(1, s.lote().id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return RecepcionSurtidoService.proporcion(rs.getLong(1), s.cantidad(),
                            BigDecimal.valueOf(rs.getDouble(2)));
                }
            }
        }
        return s.costoCentavos();
    }

    public List<Resumen> listar(LocalDate desde, LocalDate hasta, String sucursalId, Usuario quien) {
        Permisos.exigirAdministrador(quien, "ver las devoluciones de sucursales");
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.id, d.folio, d.fecha, d.sucursal_id, s.nombre, d.motivo, d.total_costo_centavos,
                           d.nota_credito_centavos,
                           (SELECT COUNT(*) FROM devolucion_sucursal_detalle x WHERE x.devolucion_id = d.id),
                           u.nombre_completo
                    FROM devoluciones_sucursal d
                    JOIN sucursales s ON s.id = d.sucursal_id
                    JOIN usuarios u ON u.id = d.usuario_id
                    WHERE d.fecha >= ? AND d.fecha < ? AND (? IS NULL OR d.sucursal_id = ?)
                    ORDER BY d.fecha DESC""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                ps.setString(3, sucursalId);
                ps.setString(4, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Resumen> lista = new ArrayList<>();
                    while (rs.next()) {
                        lista.add(new Resumen(rs.getString(1), rs.getString(2), Tiempo.leer(rs.getString(3)),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8),
                                rs.getInt(9), rs.getString(10)));
                    }
                    return lista;
                }
            }
        });
    }
}
