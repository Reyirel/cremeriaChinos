package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.TurnoRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Devoluciones de clientes sobre un ticket: total, parcial o como cambio de mercancía. Es distinto de
 * cancelar (que anula la venta completa del turno): aquí la venta queda como estaba y la devolución es
 * un documento aparte, así el historial sigue íntegro.
 *
 * <ul>
 *   <li>Se devuelve por renglón, sin pasar de lo vendido menos lo ya devuelto (no hay devolución doble).</li>
 *   <li>El importe es proporcional a lo que se cobró en el renglón (con el precio que se aplicó, incluido
 *       el mayoreo); el último resto del renglón devuelve exactamente lo que faltaba.</li>
 *   <li>Lo vendible regresa a los mismos lotes de la venta; lo dañado o caducado no vuelve al inventario
 *       vendible y queda valuado al costo en la devolución.</li>
 *   <li>Un reembolso en efectivo sale del turno abierto de la caja y se resta del efectivo esperado del
 *       corte; con tarjeta o transferencia se registra sin tocar el cajón.</li>
 *   <li>Siempre autoriza un supervisor de la sucursal o el administrador; el motivo es obligatorio.</li>
 * </ul>
 */
public class DevolucionService {

    public enum EstadoProducto {
        VENDIBLE("En buen estado: regresa al inventario", "Buen estado"),
        DANADO("Dañado: no se vuelve a vender", "Dañado"),
        CADUCADO("Caducado: no se vuelve a vender", "Caducado");

        private final String nombre;
        private final String corto;

        EstadoProducto(String nombre, String corto) {
            this.nombre = nombre;
            this.corto = corto;
        }

        public String nombre() {
            return nombre;
        }

        /** Para listas y tickets angostos. */
        public String corto() {
            return corto;
        }

        @Override
        public String toString() {
            return nombre;
        }
    }

    public enum Metodo {
        EFECTIVO("Efectivo de la caja"), TARJETA("A la tarjeta"), TRANSFERENCIA("Transferencia"),
        SIN_REEMBOLSO("Sin reembolso");

        private final String nombre;

        Metodo(String nombre) {
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

    public enum Tipo { DEVOLUCION, CAMBIO }

    /** Renglón de un ticket con lo que todavía se puede devolver. */
    public record RenglonDevolvible(String ventaDetalleId, int renglon, String productoId, String descripcion,
                                    Unidad unidad, BigDecimal cantidadVendida, BigDecimal cantidadDevuelta,
                                    BigDecimal basePorUnidad, long importeCentavos, long importeDevueltoCentavos) {

        public BigDecimal disponible() {
            return cantidadVendida.subtract(cantidadDevuelta);
        }

        /** Precio cobrado por unidad (con mayoreo si se aplicó). */
        public long precioCentavos() {
            return BigDecimal.valueOf(importeCentavos).divide(cantidadVendida, 0, RoundingMode.HALF_UP).longValue();
        }
    }

    public record VentaDevolvible(String ventaId, String folio, Instant fecha, String sucursalId, long totalCentavos,
                                  long devueltoCentavos, boolean cancelada, List<RenglonDevolvible> renglones) {

        public boolean algoPorDevolver() {
            return !cancelada && renglones.stream().anyMatch(r -> r.disponible().signum() > 0);
        }
    }

    /** Cuánto devolver de un renglón y en qué estado viene. */
    public record LineaDevolucion(String ventaDetalleId, BigDecimal cantidad, EstadoProducto estado) {
    }

    /** Devolución registrada (para el comprobante). */
    public record Resultado(String id, String folio, String ventaFolio, Instant fecha, Tipo tipo, Metodo metodo,
                            long reembolsoCentavos, String motivo, String usuario, String autorizo, List<Renglon> renglones) {

        public record Renglon(String descripcion, Unidad unidad, BigDecimal cantidad, long importeCentavos,
                              EstadoProducto estado) {
        }
    }

    public record Resumen(String id, String folio, Instant fecha, String sucursal, String ventaFolio, Tipo tipo,
                          Metodo metodo, long reembolsoCentavos, long costoNoVendibleCentavos, String motivo,
                          String usuario, String autorizo, String ventaCambioFolio) {
    }

    private final Database database;
    private final String dispositivoId;
    private final InventarioRepository inventario = new InventarioRepository();
    private final TurnoRepository turnos = new TurnoRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public DevolucionService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Ticket de la sucursal por su folio, con lo que aún se puede devolver de cada renglón. */
    public Optional<VentaDevolvible> buscarVenta(String folio, String sucursalId) {
        if (folio == null || folio.isBlank()) {
            return Optional.empty();
        }
        return database.con(c -> {
            String ventaId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM ventas WHERE folio = ? COLLATE NOCASE AND sucursal_id = ?")) {
                ps.setString(1, folio.strip());
                ps.setString(2, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    ventaId = rs.getString(1);
                }
            }
            return Optional.of(venta(c, ventaId));
        });
    }

    public VentaDevolvible venta(String ventaId) {
        return database.con(c -> venta(c, ventaId));
    }

    private static VentaDevolvible venta(Connection c, String ventaId) throws SQLException {
        String folio;
        Instant fecha;
        String sucursalId;
        long total;
        boolean cancelada;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT folio, fecha, sucursal_id, total_centavos, estado FROM ventas WHERE id = ?")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No existe la venta.");
                }
                folio = rs.getString(1);
                fecha = Tiempo.leer(rs.getString(2));
                sucursalId = rs.getString(3);
                total = rs.getLong(4);
                cancelada = "CANCELADA".equals(rs.getString(5));
            }
        }
        List<RenglonDevolvible> renglones = new ArrayList<>();
        long devuelto = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.id, d.renglon, d.producto_id, d.descripcion, d.unidad, d.cantidad,
                       COALESCE(d.cantidad_base, d.cantidad) AS cantidad_base, d.importe_centavos,
                       COALESCE((SELECT SUM(x.cantidad) FROM devolucion_venta_detalle x WHERE x.venta_detalle_id = d.id), 0),
                       COALESCE((SELECT SUM(x.importe_centavos) FROM devolucion_venta_detalle x
                                 WHERE x.venta_detalle_id = d.id), 0)
                FROM venta_detalle d WHERE d.venta_id = ? ORDER BY d.renglon""")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Unidad unidad = Unidad.valueOf(rs.getString(5));
                    BigDecimal cantidad = unidad.normalizar(BigDecimal.valueOf(rs.getDouble(6)));
                    BigDecimal base = BigDecimal.valueOf(rs.getDouble(7)).setScale(3, RoundingMode.HALF_UP);
                    BigDecimal basePorUnidad = base.divide(cantidad, 6, RoundingMode.HALF_UP);
                    long importeDevuelto = rs.getLong(10);
                    devuelto += importeDevuelto;
                    renglones.add(new RenglonDevolvible(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            unidad, cantidad, unidad.normalizar(BigDecimal.valueOf(rs.getDouble(9))), basePorUnidad,
                            rs.getLong(8), importeDevuelto));
                }
            }
        }
        return new VentaDevolvible(ventaId, folio, fecha, sucursalId, total, devuelto, cancelada, renglones);
    }

    /**
     * Registra la devolución (idempotente por {@code devolucionId}).
     *
     * @param turno turno abierto de la caja donde se hace; obligatorio si el reembolso es en efectivo
     */
    public Resultado registrar(String devolucionId, String ventaId, List<LineaDevolucion> lineas, Tipo tipo,
                               Metodo metodo, String motivo, Turno turno, Usuario usuario, Usuario autorizador) {
        String motivoLimpio = Permisos.exigirTexto(motivo, "Escribe el motivo de la devolución.");
        if (lineas == null || lineas.stream().noneMatch(l -> l.cantidad() != null && l.cantidad().signum() > 0)) {
            throw new IllegalArgumentException("Elige qué productos y cuánto se devuelve.");
        }
        if (metodo == null || tipo == null) {
            throw new IllegalArgumentException("Elige cómo se reembolsa.");
        }
        return database.enTransaccion(c -> {
            Optional<Resultado> ya = resultado(c, devolucionId);
            if (ya.isPresent()) {
                return ya.get();
            }
            VentaDevolvible venta = venta(c, ventaId);
            Permisos.exigirAlcance(usuario, venta.sucursalId(), "registrar devoluciones");
            Permisos.exigirAutorizador(autorizador, venta.sucursalId(), "registrar una devolución");
            if (venta.cancelada()) {
                throw new IllegalStateException("La venta " + venta.folio() + " está cancelada: no hay nada que devolver.");
            }
            Map<String, RenglonDevolvible> porId = new HashMap<>();
            venta.renglones().forEach(r -> porId.put(r.ventaDetalleId(), r));

            // Importes y cantidades de cada línea, validando lo disponible.
            List<Object[]> calculadas = new ArrayList<>(); // {linea, renglon, cantidad, base, importe}
            long reembolso = 0;
            for (LineaDevolucion l : lineas) {
                if (l.cantidad() == null || l.cantidad().signum() <= 0) {
                    continue;
                }
                RenglonDevolvible r = porId.get(l.ventaDetalleId());
                if (r == null) {
                    throw new IllegalArgumentException("El renglón no es de la venta " + venta.folio() + ".");
                }
                if (l.estado() == null) {
                    throw new IllegalArgumentException("Indica en qué estado regresa " + r.descripcion() + ".");
                }
                BigDecimal cantidad = r.unidad().normalizar(l.cantidad());
                if (cantidad.compareTo(l.cantidad().stripTrailingZeros()) != 0 && r.unidad() == Unidad.PZA) {
                    throw new IllegalArgumentException(r.descripcion() + " se devuelve por piezas completas.");
                }
                if (cantidad.compareTo(r.disponible()) > 0) {
                    throw new IllegalArgumentException("De " + r.descripcion() + " solo se pueden devolver "
                            + r.unidad().formatear(r.disponible()) + (r.unidad() == Unidad.PZA ? " pzas" : "")
                            + (r.cantidadDevuelta().signum() > 0 ? " (ya se devolvió parte)" : "") + ".");
                }
                long importe = cantidad.compareTo(r.disponible()) == 0
                        ? r.importeCentavos() - r.importeDevueltoCentavos()
                        : BigDecimal.valueOf(r.importeCentavos()).multiply(cantidad)
                        .divide(r.cantidadVendida(), 0, RoundingMode.HALF_UP).longValueExact();
                BigDecimal base = cantidad.multiply(r.basePorUnidad()).setScale(3, RoundingMode.HALF_UP);
                calculadas.add(new Object[]{l, r, cantidad, base, importe});
                reembolso += importe;
            }
            long reembolsoFinal = metodo == Metodo.SIN_REEMBOLSO ? 0 : reembolso;
            if (metodo == Metodo.EFECTIVO) {
                if (turno == null || turnos.abiertoPorId(c, turno.id()).isEmpty()) {
                    throw new IllegalStateException("Para reembolsar en efectivo la caja necesita su turno abierto.");
                }
                if (!venta.sucursalId().equals(turno.sucursalId())) {
                    throw new IllegalStateException("El efectivo se devuelve en una caja de la sucursal de la venta.");
                }
                long enCaja = turnos.resumen(c, turno).efectivoEsperado();
                if (reembolsoFinal > enCaja) {
                    throw new IllegalStateException("Solo hay " + Dinero.formatear(enCaja) + " en efectivo en la caja.");
                }
            }

            String folio = bitacora.siguienteFolio(c, AccionBitacora.DEVOLUCION_VENTA.prefijo(), dispositivoId);
            String ahora = Tiempo.ahora();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO devoluciones_venta (id, folio, venta_id, sucursal_id, turno_id, tipo, metodo_reembolso,
                                                    reembolso_centavos, motivo, usuario_id, autorizado_por, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, devolucionId);
                ps.setString(2, folio);
                ps.setString(3, venta.ventaId());
                ps.setString(4, venta.sucursalId());
                ps.setString(5, turno == null ? null : turno.id());
                ps.setString(6, tipo.name());
                ps.setString(7, metodo.name());
                ps.setLong(8, reembolsoFinal);
                ps.setString(9, motivoLimpio);
                ps.setString(10, usuario.id());
                ps.setString(11, autorizador.id());
                ps.setString(12, ahora);
                ps.executeUpdate();
            }
            List<Resultado.Renglon> renglones = new ArrayList<>();
            List<String> descripcion = new ArrayList<>();
            for (Object[] x : calculadas) {
                LineaDevolucion l = (LineaDevolucion) x[0];
                RenglonDevolvible r = (RenglonDevolvible) x[1];
                BigDecimal cantidad = (BigDecimal) x[2];
                BigDecimal base = (BigDecimal) x[3];
                long importe = (long) x[4];
                long costo = regresarALotes(c, venta, r.productoId(), base, l.estado() == EstadoProducto.VENDIBLE,
                        devolucionId, usuario, motivoLimpio);
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO devolucion_venta_detalle (id, devolucion_id, venta_detalle_id, producto_id, cantidad,
                                                              cantidad_base, importe_centavos, estado_producto, costo_centavos)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, devolucionId);
                    ps.setString(3, r.ventaDetalleId());
                    ps.setString(4, r.productoId());
                    ps.setBigDecimal(5, cantidad);
                    ps.setBigDecimal(6, base);
                    ps.setLong(7, importe);
                    ps.setString(8, l.estado().name());
                    ps.setLong(9, costo);
                    ps.executeUpdate();
                }
                renglones.add(new Resultado.Renglon(r.descripcion(), r.unidad(), cantidad, importe, l.estado()));
                descripcion.add(r.descripcion() + " " + r.unidad().formatear(cantidad)
                        + (l.estado() == EstadoProducto.VENDIBLE ? "" : " (" + l.estado().name().toLowerCase() + ")"));
            }
            bitacora.registrar(c, AccionBitacora.DEVOLUCION_VENTA, usuario, dispositivoId, "devoluciones_venta",
                    devolucionId, (tipo == Tipo.CAMBIO ? "Cambio" : "Devolución") + " del ticket " + venta.folio() + ": "
                            + String.join(", ", descripcion) + " · reembolso " + Dinero.formatear(reembolsoFinal) + " ("
                            + metodo.nombre().toLowerCase() + ", autorizó " + autorizador.nombreCompleto() + ") · "
                            + motivoLimpio, folio);
            return new Resultado(devolucionId, folio, venta.folio(), Tiempo.leer(ahora), tipo, metodo, reembolsoFinal,
                    motivoLimpio, usuario.nombreCompleto(), autorizador.nombreCompleto(), renglones);
        });
    }

    /**
     * Valúa lo devuelto con los lotes de la venta, empezando por el último lote del que salió, y si es
     * vendible lo regresa a esos lotes. Devuelve el costo.
     */
    private long regresarALotes(Connection c, VentaDevolvible venta, String productoId, BigDecimal base, boolean vendible,
                                String devolucionId, Usuario usuario, String motivo) throws SQLException {
        List<Object[]> salidas = new ArrayList<>(); // {loteId, cantidad, costoUnitario}
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT m.lote_id, -m.cantidad, l.costo_unitario_centavos
                FROM movimientos_inventario m JOIN lotes l ON l.id = m.lote_id
                WHERE m.referencia_id = ? AND m.tipo = 'VENTA' AND m.producto_id = ?
                ORDER BY m.rowid""")) {
            ps.setString(1, venta.ventaId());
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    salidas.add(new Object[]{rs.getString(1), BigDecimal.valueOf(rs.getDouble(2)), rs.getLong(3)});
                }
            }
        }
        Map<String, BigDecimal> yaRegreso = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT m.lote_id, SUM(m.cantidad)
                FROM movimientos_inventario m JOIN devoluciones_venta d ON d.id = m.referencia_id
                WHERE d.venta_id = ? AND m.tipo = 'DEVOLUCION_CLIENTE' AND m.producto_id = ?
                GROUP BY m.lote_id""")) {
            ps.setString(1, venta.ventaId());
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    yaRegreso.put(rs.getString(1), BigDecimal.valueOf(rs.getDouble(2)));
                }
            }
        }
        BigDecimal restante = base;
        long costo = 0;
        long ultimoCosto = 0;
        for (int i = salidas.size() - 1; i >= 0 && restante.signum() > 0; i--) {
            String lote = (String) salidas.get(i)[0];
            BigDecimal libre = ((BigDecimal) salidas.get(i)[1]).subtract(yaRegreso.getOrDefault(lote, BigDecimal.ZERO));
            long costoUnitario = (long) salidas.get(i)[2];
            ultimoCosto = costoUnitario;
            if (libre.signum() <= 0) {
                continue;
            }
            BigDecimal toma = libre.min(restante);
            costo += Dinero.importe(costoUnitario, toma);
            if (vendible) {
                inventario.registrar(c, productoId, venta.sucursalId(), lote, "DEVOLUCION_CLIENTE", toma, devolucionId,
                        usuario.id(), motivo);
            }
            yaRegreso.merge(lote, vendible ? toma : BigDecimal.ZERO, BigDecimal::add);
            restante = restante.subtract(toma);
        }
        if (restante.signum() > 0) {
            if (vendible) {
                throw new IllegalStateException("No se encontró de qué lotes salió lo que se devuelve de la venta "
                        + venta.folio() + ".");
            }
            costo += Dinero.importe(ultimoCosto, restante);
        }
        return costo;
    }

    /** Liga un cambio con la venta de la mercancía que se llevó el cliente. */
    public void ligarCambio(String devolucionId, String ventaNuevaId, Usuario usuario) {
        database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE devoluciones_venta SET venta_cambio_id = ?
                    WHERE id = ? AND tipo = 'CAMBIO' AND venta_cambio_id IS NULL""")) {
                ps.setString(1, ventaNuevaId);
                ps.setString(2, devolucionId);
                if (ps.executeUpdate() != 1) {
                    throw new IllegalStateException("La devolución no es un cambio pendiente de ligar.");
                }
            }
            return null;
        });
    }

    /** Devoluciones de la sucursal en el periodo (o de todas si es nula, solo el administrador). */
    public List<Resumen> listar(String sucursalId, LocalDate desde, LocalDate hasta, Usuario quien) {
        if (sucursalId == null) {
            Permisos.exigirAdministrador(quien, "ver las devoluciones de todas las sucursales");
        } else {
            Permisos.exigirAlcance(quien, sucursalId, "ver las devoluciones");
        }
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.id, d.folio, d.fecha, s.nombre, v.folio, d.tipo, d.metodo_reembolso, d.reembolso_centavos,
                           COALESCE((SELECT SUM(x.costo_centavos) FROM devolucion_venta_detalle x
                                     WHERE x.devolucion_id = d.id AND x.estado_producto <> 'VENDIBLE'), 0),
                           d.motivo, u.nombre_completo, COALESCE(a.nombre_completo, '—'), vc.folio
                    FROM devoluciones_venta d
                    JOIN ventas v ON v.id = d.venta_id
                    JOIN sucursales s ON s.id = d.sucursal_id
                    JOIN usuarios u ON u.id = d.usuario_id
                    LEFT JOIN usuarios a ON a.id = d.autorizado_por
                    LEFT JOIN ventas vc ON vc.id = d.venta_cambio_id
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
                                rs.getString(4), rs.getString(5), Tipo.valueOf(rs.getString(6)),
                                Metodo.valueOf(rs.getString(7)), rs.getLong(8), rs.getLong(9), rs.getString(10),
                                rs.getString(11), rs.getString(12), rs.getString(13)));
                    }
                    return lista;
                }
            }
        });
    }

    /** Comprobante de una devolución ya registrada (para reimprimirla). */
    public Resultado comprobante(String devolucionId) {
        return database.con(c -> resultado(c, devolucionId))
                .orElseThrow(() -> new IllegalArgumentException("No existe la devolución."));
    }

    private static Optional<Resultado> resultado(Connection c, String devolucionId) throws SQLException {
        if (devolucionId == null || devolucionId.isBlank()) {
            throw new IllegalArgumentException("Falta el identificador de la devolución.");
        }
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.folio, v.folio, d.fecha, d.tipo, d.metodo_reembolso, d.reembolso_centavos, d.motivo,
                       u.nombre_completo, COALESCE(a.nombre_completo, '—')
                FROM devoluciones_venta d
                JOIN ventas v ON v.id = d.venta_id
                JOIN usuarios u ON u.id = d.usuario_id
                LEFT JOIN usuarios a ON a.id = d.autorizado_por
                WHERE d.id = ?""")) {
            ps.setString(1, devolucionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String folio = rs.getString(1);
                String ventaFolio = rs.getString(2);
                Instant fecha = Tiempo.leer(rs.getString(3));
                Tipo tipo = Tipo.valueOf(rs.getString(4));
                Metodo metodo = Metodo.valueOf(rs.getString(5));
                long reembolso = rs.getLong(6);
                String motivo = rs.getString(7);
                String usuario = rs.getString(8);
                String autorizo = rs.getString(9);
                List<Resultado.Renglon> renglones = new ArrayList<>();
                try (PreparedStatement pd = c.prepareStatement("""
                        SELECT vd.descripcion, vd.unidad, x.cantidad, x.importe_centavos, x.estado_producto
                        FROM devolucion_venta_detalle x JOIN venta_detalle vd ON vd.id = x.venta_detalle_id
                        WHERE x.devolucion_id = ? ORDER BY vd.renglon""")) {
                    pd.setString(1, devolucionId);
                    try (ResultSet rd = pd.executeQuery()) {
                        while (rd.next()) {
                            Unidad unidad = Unidad.valueOf(rd.getString(2));
                            renglones.add(new Resultado.Renglon(rd.getString(1), unidad,
                                    unidad.normalizar(BigDecimal.valueOf(rd.getDouble(3))), rd.getLong(4),
                                    EstadoProducto.valueOf(rd.getString(5))));
                        }
                    }
                }
                return Optional.of(new Resultado(devolucionId, folio, ventaFolio, fecha, tipo, metodo, reembolso, motivo,
                        usuario, autorizo, renglones));
            }
        }
    }
}
