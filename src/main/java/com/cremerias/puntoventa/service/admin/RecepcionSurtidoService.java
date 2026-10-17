package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lo que pasa con un surtido después de registrarse (la mercancía ya entró a la sucursal, como en el
 * proceso actual):
 * <ul>
 *   <li><b>Recepción</b>: la sucursal confirma lo que llegó, renglón por renglón. Si llegó menos, la
 *       diferencia sale del lote de la sucursal (no se cuenta en dos lugares) y queda por aclarar.</li>
 *   <li><b>Aclaración</b>: el administrador decide si la diferencia regresó al almacén o fue una pérdida
 *       en el traslado, y si se acredita al saldo de la sucursal.</li>
 *   <li><b>Cancelación</b>: solo si la sucursal no ha recibido ni vendido nada de lo surtido; la mercancía
 *       vuelve a los lotes del almacén de donde salió y el cargo a crédito se cancela con una nota.</li>
 * </ul>
 * El estado de cada surtido se deduce de estos documentos: no se edita el surtido.
 */
public class RecepcionSurtidoService {

    public enum Estado {
        ENVIADO("Por recibir"), RECIBIDO("Recibido"), CON_DIFERENCIA("Recibido con diferencia"),
        ACLARADO("Diferencia aclarada"), CANCELADO("Cancelado");

        private final String nombre;

        Estado(String nombre) {
            this.nombre = nombre;
        }

        public String nombre() {
            return nombre;
        }
    }

    public enum Decision {
        REINGRESO_ALMACEN("Regresó al almacén"), PERDIDA("Pérdida en el traslado");

        private final String nombre;

        Decision(String nombre) {
            this.nombre = nombre;
        }

        public String nombre() {
            return nombre;
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    /** Renglón enviado: lo que salió del almacén y el lote donde entró a la sucursal. */
    public record LineaEnviada(String surtidoDetalleId, String productoId, String producto, Unidad unidad,
                               BigDecimal enviada, String loteId, BigDecimal existenciaLote) {
    }

    public record PorRecibir(String surtidoId, String folio, Instant fecha, String sucursalId, String sucursal,
                             String formaPago, List<LineaEnviada> lineas) {
    }

    /** Lo que la sucursal dice que recibió de un renglón. */
    public record LineaRecibida(String surtidoDetalleId, BigDecimal recibida) {
    }

    public record Diferencia(String recepcionDetalleId, String surtidoId, String surtidoFolio, String sucursalId,
                             String sucursal, String producto, Unidad unidad, BigDecimal cantidad, long costoCentavos,
                             long internoCentavos, boolean credito, Decision decision, String aclaracionFolio) {

        public boolean aclarada() {
            return decision != null;
        }
    }

    private final Database database;
    private final String dispositivoId;
    private final String almacenId;
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public RecepcionSurtidoService(Database database, String dispositivoId, String almacenId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.almacenId = almacenId;
    }

    // ================================================================== estado

    public Estado estado(String surtidoId) {
        return estados(List.of(surtidoId)).getOrDefault(surtidoId, Estado.ENVIADO);
    }

    /** Estado de varios surtidos a la vez (para las listas). */
    public Map<String, Estado> estados(Collection<String> surtidoIds) {
        return database.con(c -> {
            Map<String, Estado> estados = new HashMap<>();
            for (String id : surtidoIds) {
                estados.put(id, estado(c, id));
            }
            return estados;
        });
    }

    static Estado estado(Connection c, String surtidoId) throws SQLException {
        if (existe(c, "SELECT 1 FROM cancelaciones_surtido WHERE surtido_id = ?", surtidoId)) {
            return Estado.CANCELADO;
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.con_diferencia,
                       (SELECT COUNT(*) FROM recepcion_surtido_detalle d WHERE d.recepcion_id = r.id AND d.diferencia > 0),
                       (SELECT COUNT(*) FROM recepcion_surtido_detalle d
                        JOIN aclaraciones_surtido a ON a.recepcion_detalle_id = d.id WHERE d.recepcion_id = r.id)
                FROM recepciones_surtido r WHERE r.surtido_id = ?""")) {
            ps.setString(1, surtidoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Los surtidos de antes de la confirmación ya entraron a la sucursal: cuentan como recibidos.
                    return existe(c, "SELECT 1 FROM surtidos WHERE id = ? AND requiere_recepcion = 0", surtidoId)
                            ? Estado.RECIBIDO : Estado.ENVIADO;
                }
                if (rs.getInt(1) == 0) {
                    return Estado.RECIBIDO;
                }
                return rs.getInt(3) >= rs.getInt(2) ? Estado.ACLARADO : Estado.CON_DIFERENCIA;
            }
        }
    }

    // ================================================================== recepción

    /** Surtidos de la sucursal que aún no se reciben (ni se cancelaron), del más antiguo al más nuevo. */
    public List<PorRecibir> pendientes(String sucursalId, Usuario quien) {
        Permisos.exigirAlcance(quien, sucursalId, "ver los surtidos por recibir");
        return database.con(c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id FROM surtidos t
                    WHERE t.sucursal_id = ? AND t.requiere_recepcion = 1
                      AND NOT EXISTS (SELECT 1 FROM recepciones_surtido r WHERE r.surtido_id = t.id)
                      AND NOT EXISTS (SELECT 1 FROM cancelaciones_surtido x WHERE x.surtido_id = t.id)
                    ORDER BY t.fecha""")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            }
            List<PorRecibir> lista = new ArrayList<>();
            for (String id : ids) {
                lista.add(porRecibir(c, id));
            }
            return lista;
        });
    }

    public PorRecibir porRecibir(String surtidoId) {
        return database.con(c -> porRecibir(c, surtidoId));
    }

    private PorRecibir porRecibir(Connection c, String surtidoId) throws SQLException {
        String folio;
        Instant fecha;
        String sucursalId;
        String sucursal;
        String formaPago;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.folio, t.fecha, t.sucursal_id, s.nombre, t.forma_pago
                FROM surtidos t JOIN sucursales s ON s.id = t.sucursal_id WHERE t.id = ?""")) {
            ps.setString(1, surtidoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No existe el surtido.");
                }
                folio = rs.getString(1);
                fecha = Tiempo.leer(rs.getString(2));
                sucursalId = rs.getString(3);
                sucursal = rs.getString(4);
                formaPago = rs.getString(5);
            }
        }
        List<LineaEnviada> lineas = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.id, d.producto_id, p.nombre, p.unidad, d.cantidad, d.lote_destino_id,
                       COALESCE(e.existencia, 0)
                FROM surtido_detalle d
                JOIN productos p ON p.id = d.producto_id
                LEFT JOIN v_existencias_lote e ON e.lote_id = d.lote_destino_id
                WHERE d.surtido_id = ? ORDER BY d.renglon""")) {
            ps.setString(1, surtidoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Unidad unidad = Unidad.valueOf(rs.getString(4));
                    lineas.add(new LineaEnviada(rs.getString(1), rs.getString(2), rs.getString(3), unidad,
                            unidad.normalizar(BigDecimal.valueOf(rs.getDouble(5))), rs.getString(6),
                            BigDecimal.valueOf(rs.getDouble(7)).setScale(3, RoundingMode.HALF_UP)));
                }
            }
        }
        return new PorRecibir(surtidoId, folio, fecha, sucursalId, sucursal, formaPago, lineas);
    }

    /**
     * La sucursal confirma lo que recibió (idempotente por {@code recepcionId}). Lo que no llegó sale del
     * lote de la sucursal y queda pendiente de aclarar. Devuelve el folio.
     */
    public String recibir(String recepcionId, String surtidoId, List<LineaRecibida> lineas, String notas, Usuario quien) {
        return database.enTransaccion(c -> {
            Optional<String> ya = folio(c, "recepciones_surtido", recepcionId);
            if (ya.isPresent()) {
                return ya.get();
            }
            PorRecibir surtido = porRecibir(c, surtidoId);
            Permisos.exigirSupervisorOAdministrador(quien, surtido.sucursalId(), "confirmar la recepción de un surtido");
            Estado estado = estado(c, surtidoId);
            if (estado == Estado.CANCELADO) {
                throw new IllegalStateException("El surtido " + surtido.folio() + " se canceló.");
            }
            if (estado != Estado.ENVIADO) {
                throw new IllegalStateException("La recepción del surtido " + surtido.folio() + " ya se registró.");
            }
            Map<String, BigDecimal> recibido = new HashMap<>();
            for (LineaRecibida l : lineas) {
                recibido.put(l.surtidoDetalleId(), l.recibida());
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.RECEPCION_SURTIDO.prefijo(), dispositivoId);
            List<String> diferencias = new ArrayList<>();
            List<Object[]> detalle = new ArrayList<>();
            for (LineaEnviada e : surtido.lineas()) {
                BigDecimal llego = recibido.get(e.surtidoDetalleId());
                if (llego == null) {
                    throw new IllegalArgumentException("Indica cuánto llegó de " + e.producto() + ".");
                }
                llego = e.unidad().normalizar(llego);
                if (llego.signum() < 0) {
                    throw new IllegalArgumentException("Lo recibido de " + e.producto() + " no puede ser negativo.");
                }
                if (llego.compareTo(e.enviada()) > 0) {
                    throw new IllegalArgumentException("De " + e.producto() + " se enviaron "
                            + Cantidades.formatear(e.unidad(), e.enviada()) + ": si llegó de más, regístralo como ajuste.");
                }
                BigDecimal diferencia = e.enviada().subtract(llego);
                if (diferencia.signum() > 0) {
                    if (diferencia.compareTo(e.existenciaLote()) > 0) {
                        throw new IllegalStateException("De " + e.producto() + " ya se vendió parte de lo surtido: no pueden"
                                + " faltar " + Cantidades.formatear(e.unidad(), diferencia) + ".");
                    }
                    inventario.registrar(c, e.productoId(), surtido.sucursalId(), e.loteId(), "DIFERENCIA_SURTIDO",
                            diferencia.negate(), recepcionId, quien.id(), "No llegó en el surtido " + surtido.folio());
                    diferencias.add(e.producto() + " faltan " + Cantidades.formatear(e.unidad(), diferencia));
                }
                detalle.add(new Object[]{e, llego, diferencia});
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO recepciones_surtido (id, folio, surtido_id, sucursal_id, con_diferencia, notas, usuario_id,
                                                     fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, recepcionId);
                ps.setString(2, folio);
                ps.setString(3, surtidoId);
                ps.setString(4, surtido.sucursalId());
                ps.setInt(5, diferencias.isEmpty() ? 0 : 1);
                ps.setString(6, notas == null || notas.isBlank() ? null : notas.strip());
                ps.setString(7, quien.id());
                ps.setString(8, Tiempo.ahora());
                ps.executeUpdate();
            }
            for (Object[] x : detalle) {
                LineaEnviada e = (LineaEnviada) x[0];
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO recepcion_surtido_detalle (id, recepcion_id, surtido_detalle_id, producto_id, lote_id,
                                                               cantidad_enviada, cantidad_recibida, diferencia)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, recepcionId);
                    ps.setString(3, e.surtidoDetalleId());
                    ps.setString(4, e.productoId());
                    ps.setString(5, e.loteId());
                    ps.setBigDecimal(6, e.enviada());
                    ps.setBigDecimal(7, (BigDecimal) x[1]);
                    ps.setBigDecimal(8, (BigDecimal) x[2]);
                    ps.executeUpdate();
                }
            }
            return bitacora.registrar(c, AccionBitacora.RECEPCION_SURTIDO, quien, dispositivoId, "recepciones_surtido",
                    recepcionId, surtido.sucursal() + " recibió el surtido " + surtido.folio()
                            + (diferencias.isEmpty() ? " completo" : " con diferencias: " + String.join(", ", diferencias)),
                    folio);
        });
    }

    // ================================================================== diferencias

    /** Diferencias de recepción, las pendientes primero (el administrador las aclara). */
    public List<Diferencia> diferencias(boolean soloPendientes, Usuario quien) {
        Permisos.exigirAdministrador(quien, "aclarar diferencias de surtidos");
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.id, t.id, t.folio, t.sucursal_id, s.nombre, p.nombre, p.unidad, d.diferencia,
                           l.costo_unitario_centavos,
                           COALESCE(sd.importe_interno_centavos, sd.costo_total_centavos) AS interno, sd.cantidad,
                           t.forma_pago, a.decision, a.folio
                    FROM recepcion_surtido_detalle d
                    JOIN recepciones_surtido r ON r.id = d.recepcion_id
                    JOIN surtidos t ON t.id = r.surtido_id
                    JOIN sucursales s ON s.id = t.sucursal_id
                    JOIN productos p ON p.id = d.producto_id
                    JOIN lotes l ON l.id = d.lote_id
                    JOIN surtido_detalle sd ON sd.id = d.surtido_detalle_id
                    LEFT JOIN aclaraciones_surtido a ON a.recepcion_detalle_id = d.id
                    WHERE d.diferencia > 0 AND (? = 0 OR a.id IS NULL)
                    ORDER BY (a.id IS NOT NULL), r.fecha""")) {
                ps.setInt(1, soloPendientes ? 1 : 0);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Diferencia> lista = new ArrayList<>();
                    while (rs.next()) {
                        Unidad unidad = Unidad.valueOf(rs.getString(7));
                        BigDecimal cantidad = unidad.normalizar(BigDecimal.valueOf(rs.getDouble(8)));
                        long costo = Dinero.importe(rs.getLong(9), cantidad);
                        long interno = proporcion(rs.getLong("interno"), cantidad, BigDecimal.valueOf(rs.getDouble(11)));
                        String decision = rs.getString(13);
                        lista.add(new Diferencia(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), unidad, cantidad, costo, interno,
                                "CREDITO".equals(rs.getString(12)), decision == null ? null : Decision.valueOf(decision),
                                rs.getString(14)));
                    }
                    return lista;
                }
            }
        });
    }

    /**
     * El administrador aclara una diferencia (idempotente por {@code aclaracionId}). Si regresó al almacén,
     * entra en un lote nuevo con el mismo costo. Con {@code acreditar} y surtido a crédito, se abona a la
     * sucursal el precio interno de lo que no llegó. Devuelve el folio.
     */
    public String aclarar(String aclaracionId, Diferencia diferencia, Decision decision, boolean acreditar, String nota,
                          Usuario quien) {
        Permisos.exigirAdministrador(quien, "aclarar diferencias de surtidos");
        if (decision == null) {
            throw new IllegalArgumentException("Elige qué pasó con la diferencia.");
        }
        return database.enTransaccion(c -> {
            Optional<String> ya = folio(c, "aclaraciones_surtido", aclaracionId);
            if (ya.isPresent()) {
                return ya.get();
            }
            if (existe(c, "SELECT 1 FROM aclaraciones_surtido WHERE recepcion_detalle_id = ?",
                    diferencia.recepcionDetalleId())) {
                throw new IllegalStateException("Esa diferencia ya se aclaró.");
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.ACLARACION_SURTIDO.prefijo(), dispositivoId);
            if (decision == Decision.REINGRESO_ALMACEN) {
                String productoId;
                long costoUnitario;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT d.producto_id, l.costo_unitario_centavos FROM recepcion_surtido_detalle d
                        JOIN lotes l ON l.id = d.lote_id WHERE d.id = ?""")) {
                    ps.setString(1, diferencia.recepcionDetalleId());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        productoId = rs.getString(1);
                        costoUnitario = rs.getLong(2);
                    }
                }
                String lote = lotes.insertar(c, folio, productoId, almacenId, "ENTRADA", aclaracionId, diferencia.cantidad(),
                        costoUnitario, quien.id());
                inventario.registrar(c, productoId, almacenId, lote, "DIFERENCIA_SURTIDO", diferencia.cantidad(),
                        aclaracionId, quien.id(), "Regresó del surtido " + diferencia.surtidoFolio());
            }
            long acreditado = 0;
            if (acreditar && diferencia.credito() && diferencia.internoCentavos() > 0) {
                acreditado = CreditoService.notaDeCredito(c, bitacora, dispositivoId, diferencia.sucursalId(),
                        diferencia.sucursal(), CreditoService.Concepto.DIFERENCIA_ENTREGA, diferencia.internoCentavos(),
                        diferencia.surtidoId(), aclaracionId, "Diferencia del surtido " + diferencia.surtidoFolio(), quien);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO aclaraciones_surtido (id, folio, recepcion_detalle_id, decision, cantidad,
                                                      nota_credito_centavos, nota, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, aclaracionId);
                ps.setString(2, folio);
                ps.setString(3, diferencia.recepcionDetalleId());
                ps.setString(4, decision.name());
                ps.setBigDecimal(5, diferencia.cantidad());
                ps.setLong(6, acreditado);
                ps.setString(7, nota == null || nota.isBlank() ? null : nota.strip());
                ps.setString(8, quien.id());
                ps.setString(9, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.ACLARACION_SURTIDO, quien, dispositivoId, "aclaraciones_surtido",
                    aclaracionId, "Surtido " + diferencia.surtidoFolio() + " · " + diferencia.producto() + " "
                            + Cantidades.formatear(diferencia.unidad(), diferencia.cantidad()) + ": "
                            + decision.nombre().toLowerCase()
                            + (acreditado > 0 ? " · se acreditaron " + Dinero.formatear(acreditado) : ""), folio);
        });
    }

    // ================================================================== cancelación

    /**
     * Cancela un surtido que la sucursal todavía no recibe ni ha vendido (idempotente por
     * {@code cancelacionId}): la mercancía vuelve a los lotes del almacén de donde salió y, si fue a
     * crédito, el cargo se cancela con una nota de crédito. Devuelve el folio.
     */
    public String cancelar(String cancelacionId, String surtidoId, String motivo, Usuario quien) {
        Permisos.exigirAdministrador(quien, "cancelar surtidos");
        String motivoLimpio = Permisos.exigirTexto(motivo, "Escribe el motivo de la cancelación.");
        return database.enTransaccion(c -> {
            Optional<String> ya = folio(c, "cancelaciones_surtido", cancelacionId);
            if (ya.isPresent()) {
                return ya.get();
            }
            PorRecibir surtido = porRecibir(c, surtidoId);
            Estado estado = estado(c, surtidoId);
            if (estado == Estado.CANCELADO) {
                throw new IllegalStateException("El surtido " + surtido.folio() + " ya estaba cancelado.");
            }
            if (estado != Estado.ENVIADO) {
                throw new IllegalStateException("La sucursal ya recibió el surtido " + surtido.folio()
                        + ": para regresar mercancía registra una devolución de sucursal.");
            }
            for (LineaEnviada e : surtido.lineas()) {
                if (e.existenciaLote().compareTo(e.enviada()) < 0) {
                    throw new IllegalStateException("La sucursal ya vendió o movió " + e.producto() + " de este surtido:"
                            + " registra una devolución de sucursal por lo que quede.");
                }
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.CANCELACION_SURTIDO.prefijo(), dispositivoId);
            for (LineaEnviada e : surtido.lineas()) {
                inventario.registrar(c, e.productoId(), surtido.sucursalId(), e.loteId(), "CANCELACION_SURTIDO",
                        e.enviada().negate(), cancelacionId, quien.id(), "Cancelación del surtido " + surtido.folio());
            }
            // Regresa a los mismos lotes del almacén de donde salió.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT producto_id, lote_id, -cantidad FROM movimientos_inventario
                    WHERE referencia_id = ? AND tipo = 'SURTIDO_SALIDA'""")) {
                ps.setString(1, surtidoId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        inventario.registrar(c, rs.getString(1), almacenId, rs.getString(2), "CANCELACION_SURTIDO",
                                BigDecimal.valueOf(rs.getDouble(3)).setScale(3, RoundingMode.HALF_UP), cancelacionId,
                                quien.id(), "Cancelación del surtido " + surtido.folio());
                    }
                }
            }
            long acreditado = 0;
            if ("CREDITO".equals(surtido.formaPago())) {
                long cargo = 0;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT COALESCE(SUM(monto_centavos), 0) FROM movimientos_credito
                        WHERE surtido_id = ? AND tipo = 'CARGO'""")) {
                    ps.setString(1, surtidoId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        cargo = rs.getLong(1);
                    }
                }
                acreditado = CreditoService.notaDeCredito(c, bitacora, dispositivoId, surtido.sucursalId(),
                        surtido.sucursal(), CreditoService.Concepto.CANCELACION, cargo, surtidoId, cancelacionId,
                        "Cancelación del surtido " + surtido.folio(), quien);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO cancelaciones_surtido (id, folio, surtido_id, motivo, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, cancelacionId);
                ps.setString(2, folio);
                ps.setString(3, surtidoId);
                ps.setString(4, motivoLimpio);
                ps.setString(5, quien.id());
                ps.setString(6, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.CANCELACION_SURTIDO, quien, dispositivoId, "cancelaciones_surtido",
                    cancelacionId, "Surtido " + surtido.folio() + " a " + surtido.sucursal() + " cancelado: la mercancía"
                            + " regresó al almacén" + (acreditado > 0 ? " y se acreditaron " + Dinero.formatear(acreditado)
                            : "") + " · " + motivoLimpio, folio);
        });
    }

    // ================================================================== utilidades

    /** Parte proporcional de un importe (el precio interno de una parte de la línea). */
    static long proporcion(long importe, BigDecimal parte, BigDecimal total) {
        if (total.signum() == 0) {
            return 0;
        }
        return BigDecimal.valueOf(importe).multiply(parte).divide(total, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static boolean existe(Connection c, String sql, String valor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Optional<String> folio(Connection c, String tabla, String id) throws SQLException {
        return CompraService.folioDe(c, tabla, id);
    }
}
