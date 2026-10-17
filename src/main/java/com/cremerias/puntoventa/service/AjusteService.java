package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
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
 * Ajustes de inventario: correcciones de la existencia de un producto en el almacén o en una
 * sucursal, con su motivo, documento de referencia y valor al costo.
 *
 * <ul>
 *   <li>Lo negativo sale de los lotes más antiguos (o del lote elegido); lo positivo entra al lote
 *       elegido o al más reciente (en sucursal, uno que ya tenga precio).</li>
 *   <li>El administrador ajusta cualquier ubicación; el supervisor, solo su sucursal; el cajero no ajusta.</li>
 *   <li>Un ajuste no sustituye a una compra, un surtido o una devolución: si el documento de referencia
 *       ya es uno de esos folios, se rechaza. Tampoco se ajusta dos veces el mismo documento.</li>
 * </ul>
 */
public class AjusteService {

    /** Motivo del ajuste y su signo permitido. */
    public enum Motivo {
        SOBRANTE("Sobrante físico", 1), FALTANTE("Faltante físico", -1), CADUCIDAD("Caducidad", -1),
        DANO("Producto dañado", -1), CORRECCION("Corrección de captura", 0), CORTESIA("Cortesía", 0),
        DEVOLUCION("Devolución", 0), OTRO("Otro motivo", 0);

        private final String nombre;
        /** 1 = solo entrada, −1 = solo salida, 0 = cualquiera. */
        private final int signo;

        Motivo(String nombre, int signo) {
            this.nombre = nombre;
            this.signo = signo;
        }

        public String nombre() {
            return nombre;
        }

        public int signo() {
            return signo;
        }

        /** Requiere documento de referencia (devolución y cortesía siempre vienen de un papel). */
        public boolean pideDocumento() {
            return this == DEVOLUCION || this == CORTESIA;
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    public record Ajuste(String id, String folio, Instant fecha, String sucursalId, String sucursal, String productoId,
                         String producto, Unidad unidad, Motivo motivo, BigDecimal cantidad, long costoCentavos,
                         String documento, String nota, String usuario) {
    }

    private final Database database;
    private final String dispositivoId;
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public AjusteService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /**
     * Registra el ajuste. {@code cantidad} en unidad base con signo (positiva entra, negativa sale).
     * Idempotente por {@code ajusteId}. Devuelve el folio.
     */
    public String registrar(String ajusteId, Sucursal ubicacion, ProductoCatalogo producto, Motivo motivo,
                            BigDecimal cantidad, String loteId, String documento, String nota, Usuario quien) {
        if (ubicacion == null || producto == null || motivo == null) {
            throw new IllegalArgumentException("Elige la ubicación, el producto y el motivo.");
        }
        if (quien == null || quien.rol() == com.cremerias.puntoventa.model.Rol.CAJERO) {
            throw new IllegalStateException("Solo un supervisor o el administrador puede ajustar el inventario.");
        }
        if (ubicacion.esAlmacen()) {
            Permisos.exigirAdministrador(quien, "ajustar el inventario del almacén");
        } else {
            Permisos.exigirSupervisorOAdministrador(quien, ubicacion.id(), "ajustar el inventario");
        }
        if (cantidad == null || cantidad.signum() == 0) {
            throw new IllegalArgumentException("La cantidad del ajuste no puede ser cero.");
        }
        BigDecimal normal = producto.unidad().normalizar(cantidad);
        if (normal.compareTo(cantidad.stripTrailingZeros()) != 0 && producto.unidad() == Unidad.PZA) {
            throw new IllegalArgumentException(producto.nombre() + " se cuenta por piezas: ajusta piezas enteras.");
        }
        if (motivo.signo() != 0 && normal.signum() != motivo.signo()) {
            throw new IllegalArgumentException(motivo.nombre() + (motivo.signo() > 0
                    ? " suma existencia: la cantidad debe ser positiva." : " resta existencia: la cantidad debe ser negativa."));
        }
        String doc = documento == null || documento.isBlank() ? null : documento.strip();
        if (motivo.pideDocumento() && doc == null) {
            throw new IllegalArgumentException("Escribe el documento de referencia de la " + motivo.nombre().toLowerCase() + ".");
        }
        String motivoTexto = nota == null || nota.isBlank() ? null : nota.strip();
        if ((motivo == Motivo.CORRECCION || motivo == Motivo.OTRO) && motivoTexto == null) {
            throw new IllegalArgumentException("Explica el ajuste en la nota.");
        }
        return database.enTransaccion(c -> {
            Optional<String> ya = folio(c, ajusteId);
            if (ya.isPresent()) {
                return ya.get();
            }
            if (doc != null) {
                validarDocumento(c, doc, ubicacion.id(), producto.id(), motivo);
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.AJUSTE_INVENTARIO.prefijo(), dispositivoId);
            long costo;
            if (normal.signum() < 0) {
                costo = 0;
                for (Peps.Salida s : Peps.salida(c, producto.id(), producto.nombre(), producto.unidad(), ubicacion.id(),
                        normal.negate(), loteId)) {
                    inventario.registrar(c, producto.id(), ubicacion.id(), s.lote().id(), "AJUSTE", s.cantidad().negate(),
                            ajusteId, quien.id(), motivo.nombre());
                    costo -= s.costoCentavos();
                }
            } else {
                LoteRepository.Lote lote = Peps.loteDeEntrada(c, producto.id(), ubicacion.id(), loteId, !ubicacion.esAlmacen())
                        .orElseThrow(() -> new IllegalStateException("No hay lotes de " + producto.nombre() + " en "
                                + ubicacion.nombre() + (ubicacion.esAlmacen() ? ": registra una entrada o compra."
                                : ": primero hay que surtirle el producto.")));
                inventario.registrar(c, producto.id(), ubicacion.id(), lote.id(), "AJUSTE", normal, ajusteId, quien.id(),
                        motivo.nombre());
                costo = Dinero.importe(lote.costoUnitarioCentavos(), normal);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ajustes_inventario (id, folio, sucursal_id, producto_id, motivo, cantidad, costo_centavos,
                                                    documento, nota, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, ajusteId);
                ps.setString(2, folio);
                ps.setString(3, ubicacion.id());
                ps.setString(4, producto.id());
                ps.setString(5, motivo.name());
                ps.setBigDecimal(6, normal);
                ps.setLong(7, costo);
                ps.setString(8, doc);
                ps.setString(9, motivoTexto);
                ps.setString(10, quien.id());
                ps.setString(11, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.AJUSTE_INVENTARIO, quien, dispositivoId, "ajustes_inventario",
                    ajusteId, producto.nombre() + " en " + ubicacion.nombre() + ": " + (normal.signum() > 0 ? "+" : "−")
                            + Cantidades.formatear(producto.unidad(), normal.abs()) + " · " + motivo.nombre()
                            + " (costo " + Dinero.formatear(Math.abs(costo)) + ")"
                            + (doc == null ? "" : " · doc. " + doc) + (motivoTexto == null ? "" : " · " + motivoTexto),
                    folio);
        });
    }

    /** No se ajusta a mano lo que ya es una compra, surtido o devolución, ni dos veces el mismo documento. */
    private static void validarDocumento(Connection c, String documento, String ubicacionId, String productoId,
                                         Motivo motivo) throws SQLException {
        for (String[] t : new String[][]{{"compras", "una compra"}, {"surtidos", "un surtido"},
                {"devoluciones_venta", "una devolución de cliente"}, {"devoluciones_sucursal", "una devolución de sucursal"},
                {"devoluciones_proveedor", "una devolución a proveedor"}, {"transformaciones", "una transformación"}}) {
            // t[0] es fijo en el código, no viene de datos.
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + t[0] + " WHERE folio = ? COLLATE NOCASE")) {
                ps.setString(1, documento);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalArgumentException("El documento " + documento + " ya está registrado como " + t[1]
                                + ": su efecto en el inventario ya se aplicó. No se ajusta a mano.");
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT folio FROM ajustes_inventario
                WHERE documento = ? COLLATE NOCASE AND sucursal_id = ? AND producto_id = ? AND motivo = ?""")) {
            ps.setString(1, documento);
            ps.setString(2, ubicacionId);
            ps.setString(3, productoId);
            ps.setString(4, motivo.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("El documento " + documento + " ya se ajustó para este producto (folio "
                            + rs.getString(1) + ").");
                }
            }
        }
    }

    /** Ajustes del periodo en una ubicación (o en todas si es nula). */
    public List<Ajuste> listar(String sucursalId, LocalDate desde, LocalDate hasta, Usuario quien) {
        if (sucursalId != null) {
            Permisos.exigirAlcance(quien, sucursalId, "ver los ajustes");
        } else {
            Permisos.exigirAdministrador(quien, "ver los ajustes de todas las ubicaciones");
        }
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT a.id, a.folio, a.fecha, a.sucursal_id, s.nombre, a.producto_id, p.nombre, p.unidad, a.motivo,
                           a.cantidad, a.costo_centavos, a.documento, a.nota, u.nombre_completo
                    FROM ajustes_inventario a
                    JOIN sucursales s ON s.id = a.sucursal_id
                    JOIN productos p ON p.id = a.producto_id
                    JOIN usuarios u ON u.id = a.usuario_id
                    WHERE a.fecha >= ? AND a.fecha < ? AND (? IS NULL OR a.sucursal_id = ?)
                    ORDER BY a.fecha DESC""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                ps.setString(3, sucursalId);
                ps.setString(4, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Ajuste> lista = new ArrayList<>();
                    while (rs.next()) {
                        Unidad unidad = Unidad.valueOf(rs.getString(8));
                        lista.add(new Ajuste(rs.getString(1), rs.getString(2), Tiempo.leer(rs.getString(3)),
                                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), unidad,
                                Motivo.valueOf(rs.getString(9)), unidad.normalizar(BigDecimal.valueOf(rs.getDouble(10))),
                                rs.getLong(11), rs.getString(12), rs.getString(13), rs.getString(14)));
                    }
                    return lista;
                }
            }
        });
    }

    private static Optional<String> folio(Connection c, String id) throws SQLException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Falta el identificador del ajuste.");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT folio FROM ajustes_inventario WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }
}
