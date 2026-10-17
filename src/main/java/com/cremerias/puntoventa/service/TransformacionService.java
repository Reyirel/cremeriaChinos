package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
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
import java.util.Map;
import java.util.Optional;

/**
 * Transformación de un producto en otro con existencia propia (p. ej. 5 kg de queso entero en 10
 * paquetes de 500 g). El origen sale de sus lotes más antiguos; el destino entra en un lote nuevo con
 * todo el costo del origen consumido (la merma del proceso queda dentro del costo del destino) y la
 * merma real queda registrada. Los movimientos de salida y de entrada apuntan a la transformación,
 * así se sabe de qué lotes salió cada lote nuevo. No se crea existencia sin su consumo.
 */
public class TransformacionService {

    /**
     * @param equivalencia unidades base del origen que lleva cada unidad base del destino
     *                     (un paquete de 500 g de un queso por kilo = 0.5)
     * @param merma        origen consumido que no quedó en el destino
     */
    public record Transformacion(String id, String folio, Instant fecha, String sucursalId, String sucursal,
                                 String origen, Unidad unidadOrigen, BigDecimal cantidadOrigen, String destino,
                                 Unidad unidadDestino, BigDecimal cantidadDestino, BigDecimal equivalencia,
                                 BigDecimal merma, long costoCentavos, long costoUnitarioDestino, String loteDestinoId,
                                 String nota, String usuario) {
    }

    private final Database database;
    private final String dispositivoId;
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public TransformacionService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Merma que resulta: lo consumido menos lo que lleva el producido. Negativa = se produjo más de lo posible. */
    public static BigDecimal merma(BigDecimal cantidadOrigen, BigDecimal cantidadDestino, BigDecimal equivalencia) {
        return cantidadOrigen.subtract(cantidadDestino.multiply(equivalencia)).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * Registra la transformación (idempotente por {@code id}). En una sucursal, el lote nuevo necesita
     * precio para cada presentación activa del destino ({@code preciosDestino}); en el almacén los
     * precios se fijan al surtir. Devuelve el folio.
     */
    public String registrar(String id, Sucursal ubicacion, ProductoCatalogo origen, BigDecimal cantidadOrigen,
                            ProductoCatalogo destino, BigDecimal cantidadDestino, BigDecimal equivalencia,
                            Map<String, Long> preciosDestino, String nota, Usuario quien) {
        if (ubicacion == null || origen == null || destino == null) {
            throw new IllegalArgumentException("Elige la ubicación, el producto de origen y el de destino.");
        }
        if (ubicacion.esAlmacen()) {
            Permisos.exigirAdministrador(quien, "transformar productos en el almacén");
        } else {
            Permisos.exigirSupervisorOAdministrador(quien, ubicacion.id(), "transformar productos");
        }
        if (origen.id().equals(destino.id())) {
            throw new IllegalArgumentException("El origen y el destino deben ser productos distintos.");
        }
        if (cantidadOrigen == null || cantidadOrigen.signum() <= 0 || cantidadDestino == null
                || cantidadDestino.signum() <= 0) {
            throw new IllegalArgumentException("Las cantidades deben ser mayores a cero.");
        }
        if (equivalencia == null || equivalencia.signum() <= 0) {
            throw new IllegalArgumentException("Indica cuánto del origen lleva cada unidad del destino.");
        }
        BigDecimal consumido = origen.unidad().normalizar(cantidadOrigen);
        BigDecimal producido = destino.unidad().normalizar(cantidadDestino);
        if (producido.compareTo(cantidadDestino.stripTrailingZeros()) != 0 && destino.unidad() == Unidad.PZA) {
            throw new IllegalArgumentException(destino.nombre() + " se cuenta por piezas: produce piezas enteras.");
        }
        BigDecimal merma = merma(consumido, producido, equivalencia);
        if (merma.signum() < 0) {
            throw new IllegalArgumentException("Con " + Cantidades.formatear(origen.unidad(), consumido) + " no alcanza para "
                    + Cantidades.formatear(destino.unidad(), producido) + " (se necesitan "
                    + Cantidades.formatear(origen.unidad(), producido.multiply(equivalencia)) + ").");
        }
        if (!ubicacion.esAlmacen()) {
            for (Presentacion p : destino.presentaciones()) {
                if (p.activo() && (preciosDestino == null || preciosDestino.get(p.id()) == null
                        || preciosDestino.get(p.id()) <= 0)) {
                    throw new IllegalArgumentException("Falta el precio de " + destino.nombre() + " · " + p.nombre() + ".");
                }
            }
        }
        return database.enTransaccion(c -> {
            Optional<String> ya = folio(c, id);
            if (ya.isPresent()) {
                return ya.get();
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.TRANSFORMACION.prefijo(), dispositivoId);
            long costo = 0;
            List<Peps.Salida> salidas = Peps.salida(c, origen.id(), origen.nombre(), origen.unidad(), ubicacion.id(),
                    consumido, null);
            for (Peps.Salida s : salidas) {
                inventario.registrar(c, origen.id(), ubicacion.id(), s.lote().id(), "TRANSFORMACION_SALIDA",
                        s.cantidad().negate(), id, quien.id(), "Transformación " + folio);
                costo += s.costoCentavos();
            }
            long costoUnitario = BigDecimal.valueOf(costo).divide(producido, 0, RoundingMode.HALF_UP).longValueExact();
            String lote = lotes.insertar(c, folio, destino.id(), ubicacion.id(), "ENTRADA", id, producido, costoUnitario,
                    quien.id());
            if (!ubicacion.esAlmacen()) {
                for (Presentacion p : destino.presentaciones()) {
                    if (p.activo()) {
                        lotes.insertarPrecio(c, lote, p.id(), preciosDestino.get(p.id()));
                    }
                }
            }
            inventario.registrar(c, destino.id(), ubicacion.id(), lote, "TRANSFORMACION_ENTRADA", producido, id, quien.id(),
                    "Transformación " + folio);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO transformaciones (id, folio, sucursal_id, producto_origen_id, cantidad_origen,
                                                  producto_destino_id, cantidad_destino, equivalencia, merma_origen,
                                                  costo_origen_centavos, costo_unitario_destino_centavos,
                                                  lote_destino_id, nota, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, id);
                ps.setString(2, folio);
                ps.setString(3, ubicacion.id());
                ps.setString(4, origen.id());
                ps.setBigDecimal(5, consumido);
                ps.setString(6, destino.id());
                ps.setBigDecimal(7, producido);
                ps.setBigDecimal(8, equivalencia);
                ps.setBigDecimal(9, merma);
                ps.setLong(10, costo);
                ps.setLong(11, costoUnitario);
                ps.setString(12, lote);
                ps.setString(13, nota == null || nota.isBlank() ? null : nota.strip());
                ps.setString(14, quien.id());
                ps.setString(15, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.TRANSFORMACION, quien, dispositivoId, "transformaciones", id,
                    "En " + ubicacion.nombre() + ": " + Cantidades.formatear(origen.unidad(), consumido) + " de "
                            + origen.nombre() + " → " + Cantidades.formatear(destino.unidad(), producido) + " de "
                            + destino.nombre() + " (costo " + Dinero.formatear(costo) + ", "
                            + Dinero.formatear(costoUnitario) + " c/u" + (merma.signum() > 0 ? ", merma "
                            + Cantidades.formatear(origen.unidad(), merma) : "") + ")", folio);
        });
    }

    public List<Transformacion> listar(String sucursalId, LocalDate desde, LocalDate hasta, Usuario quien) {
        if (sucursalId != null) {
            Permisos.exigirAlcance(quien, sucursalId, "ver las transformaciones");
        } else {
            Permisos.exigirAdministrador(quien, "ver las transformaciones de todas las ubicaciones");
        }
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id, t.folio, t.fecha, t.sucursal_id, s.nombre, o.nombre, o.unidad, t.cantidad_origen,
                           d.nombre, d.unidad, t.cantidad_destino, t.equivalencia, t.merma_origen, t.costo_origen_centavos,
                           t.costo_unitario_destino_centavos, t.lote_destino_id, t.nota, u.nombre_completo
                    FROM transformaciones t
                    JOIN sucursales s ON s.id = t.sucursal_id
                    JOIN productos o ON o.id = t.producto_origen_id
                    JOIN productos d ON d.id = t.producto_destino_id
                    JOIN usuarios u ON u.id = t.usuario_id
                    WHERE t.fecha >= ? AND t.fecha < ? AND (? IS NULL OR t.sucursal_id = ?)
                    ORDER BY t.fecha DESC""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                ps.setString(3, sucursalId);
                ps.setString(4, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Transformacion> lista = new ArrayList<>();
                    while (rs.next()) {
                        Unidad uo = Unidad.valueOf(rs.getString(7));
                        Unidad ud = Unidad.valueOf(rs.getString(10));
                        lista.add(new Transformacion(rs.getString(1), rs.getString(2), Tiempo.leer(rs.getString(3)),
                                rs.getString(4), rs.getString(5), rs.getString(6), uo,
                                uo.normalizar(BigDecimal.valueOf(rs.getDouble(8))), rs.getString(9), ud,
                                ud.normalizar(BigDecimal.valueOf(rs.getDouble(11))),
                                BigDecimal.valueOf(rs.getDouble(12)).stripTrailingZeros(),
                                BigDecimal.valueOf(rs.getDouble(13)).setScale(3, RoundingMode.HALF_UP), rs.getLong(14),
                                rs.getLong(15), rs.getString(16), rs.getString(17), rs.getString(18)));
                    }
                    return lista;
                }
            }
        });
    }

    /** Lotes de origen de una transformación (de dónde salió el lote nuevo). */
    public List<String> lotesDeOrigen(String transformacionId) {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT lote_id FROM movimientos_inventario
                    WHERE referencia_id = ? AND tipo = 'TRANSFORMACION_SALIDA' ORDER BY rowid""")) {
                ps.setString(1, transformacionId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> lista = new ArrayList<>();
                    while (rs.next()) {
                        lista.add(rs.getString(1));
                    }
                    return lista;
                }
            }
        });
    }

    private static Optional<String> folio(Connection c, String id) throws SQLException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Falta el identificador de la transformación.");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT folio FROM transformaciones WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }
}
