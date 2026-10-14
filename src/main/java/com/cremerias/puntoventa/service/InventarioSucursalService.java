package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Productos de una sucursal con su existencia, precios y mínimo/máximo, solo para consulta
 * (supervisor). No modifica nada: la existencia baja con las ventas registradas en caja.
 */
public class InventarioSucursalService {

    /** Precio actual de una presentación en la sucursal (lote más antiguo con existencia). */
    public record Precio(Presentacion presentacion, long precioCentavos) {
    }

    public record Fila(ProductoCatalogo producto, BigDecimal existencia, BigDecimal minimo, BigDecimal maximo,
                       List<Precio> precios) {

        public boolean agotado() {
            return existencia.signum() <= 0;
        }

        public boolean enMinimo() {
            return !agotado() && maximo.signum() > 0 && existencia.compareTo(minimo) <= 0;
        }

        /** Con mínimo y máximo, ya llegó a su mínimo o se agotó. */
        public boolean porReabastecer() {
            return maximo.signum() > 0 && existencia.compareTo(minimo) <= 0;
        }
    }

    /**
     * Producto que llegó a su mínimo (o se agotó) en la sucursal.
     *
     * @param ordenPendiente folio de la orden de reabastecimiento que espera al administrador, o {@code null}
     */
    public record Bajo(String productoId, String nombre, Unidad unidad, BigDecimal existencia, BigDecimal minimo,
                       String ordenPendiente) {

        public boolean agotado() {
            return existencia.signum() <= 0;
        }
    }

    private final Database database;
    private final ProductoRepository productos = new ProductoRepository();
    private final LoteRepository lotes = new LoteRepository();

    public InventarioSucursalService(Database database) {
        this.database = database;
    }

    /**
     * Productos activos de la sucursal: los que ya se le surtieron (tienen lote) o ya tienen
     * mínimo/máximo capturado, igual que en «Mínimos y máximos» del administrador.
     */
    public List<Fila> listar(String sucursalId) {
        return database.con(c -> {
            Map<String, List<LoteRepository.Lote>> lotesPorProducto = new HashMap<>();
            for (LoteRepository.Lote lote : lotes.deSucursal(c, sucursalId)) {
                lotesPorProducto.computeIfAbsent(lote.productoId(), k -> new ArrayList<>()).add(lote);
            }
            Map<String, Map<String, Long>> precios = lotes.preciosDeSucursal(c, sucursalId);
            Map<String, BigDecimal[]> limites = limites(c, sucursalId);
            Map<String, BigDecimal> existencias = existencias(c, sucursalId);

            List<Fila> filas = new ArrayList<>();
            for (ProductoCatalogo p : productos.listar(c)) {
                List<LoteRepository.Lote> suyos = lotesPorProducto.get(p.id());
                if (!p.activo() || (suyos == null && !limites.containsKey(p.id()))) {
                    continue;
                }
                List<Precio> preciosProducto = new ArrayList<>();
                for (Presentacion pr : p.presentaciones()) {
                    PreciosLote.Tramos tramos = pr.activo() && suyos != null
                            ? PreciosLote.de(suyos, precios, pr.id()) : null;
                    if (tramos != null) {
                        preciosProducto.add(new Precio(pr, tramos.precioActual()));
                    }
                }
                BigDecimal[] l = limites.getOrDefault(p.id(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                filas.add(new Fila(p, existencias.getOrDefault(p.id(), BigDecimal.ZERO.setScale(3)), l[0], l[1],
                        List.copyOf(preciosProducto)));
            }
            return filas;
        });
    }

    /**
     * Productos con mínimo y máximo que ya llegaron a su mínimo, los agotados primero. Lo que queda
     * se sigue vendiendo; es el aviso para que el supervisor vea el reabastecimiento.
     */
    public List<Bajo> enMinimo(String sucursalId) {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, p.nombre, p.unidad, COALESCE(e.existencia, 0) AS existencia, l.minimo,
                           (SELECT o.folio FROM ordenes_reabastecimiento o
                            WHERE o.sucursal_id = l.sucursal_id AND o.producto_id = l.producto_id
                              AND o.estado = 'PENDIENTE'
                            ORDER BY o.creado_en DESC LIMIT 1) AS orden
                    FROM limites_sucursal l
                    JOIN productos p ON p.id = l.producto_id AND p.eliminado_en IS NULL AND p.activo = 1
                    LEFT JOIN v_existencias e ON e.producto_id = l.producto_id AND e.sucursal_id = l.sucursal_id
                    WHERE l.sucursal_id = ? AND l.maximo > 0 AND COALESCE(e.existencia, 0) <= l.minimo
                    ORDER BY COALESCE(e.existencia, 0) > 0, p.nombre""")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Bajo> bajos = new ArrayList<>();
                    while (rs.next()) {
                        bajos.add(new Bajo(rs.getString(1), rs.getString(2), Unidad.valueOf(rs.getString(3)),
                                decimal(rs.getDouble(4)), decimal(rs.getDouble(5)), rs.getString(6)));
                    }
                    return bajos;
                }
            }
        });
    }

    /** Estado de reabasto de un producto en la sucursal, para el panel de solo lectura del administrador. */
    public enum EstadoInventario { AGOTADO, EN_MINIMO, POR_REABASTECER, OK }

    /** Cuánto se vendió de un producto en el periodo filtrado (cantidad e importe). */
    private record VentaProducto(BigDecimal cantidad, long importeCentavos) {
    }

    /**
     * Fila de {@link #listar} con lo que además necesita el administrador: costo promedio y valor
     * del inventario al costo, lo vendido en el periodo, y última venta/días sin venta.
     */
    public record FilaDetallada(Sucursal sucursal, Fila fila, EstadoInventario estado, long costoPromedioCentavos,
                                long valorInventarioCentavos, BigDecimal cantidadVendida, long importeVendidoCentavos,
                                Instant ultimaVenta, Long diasSinVenta) {
    }

    /** Un movimiento de inventario de un producto en una sucursal, para el detalle del administrador. */
    public record Movimiento(String tipo, BigDecimal cantidad, Instant fecha, String notas) {
    }

    /**
     * Como {@link #listar}, pero con costo/valor de inventario, lo vendido en el periodo y la
     * última venta de cada producto. Solo para el administrador, igual que Gastos y Nómina.
     */
    public List<FilaDetallada> listarDetallado(Usuario quien, Sucursal sucursal, LocalDate desde, LocalDate hasta) {
        validarAdmin(quien);
        List<Fila> base = listar(sucursal.id());
        ZoneId zona = ZoneId.systemDefault();
        Instant inicio = desde.atStartOfDay(zona).toInstant();
        Instant fin = hasta.plusDays(1).atStartOfDay(zona).toInstant();
        Instant ahora = Instant.now();
        return database.con(c -> {
            Map<String, Long> valorPorProducto = new HashMap<>();
            for (LoteRepository.Lote l : lotes.deSucursal(c, sucursal.id())) {
                valorPorProducto.merge(l.productoId(),
                        Math.round(l.existencia().doubleValue() * l.costoUnitarioCentavos()), Long::sum);
            }
            Map<String, VentaProducto> vendido = vendidoPorProducto(c, sucursal.id(), inicio, fin);
            Map<String, Instant> ultimaVenta = ultimaVentaPorProducto(c, sucursal.id());
            Set<String> conOrdenPendiente = conOrdenPendiente(c, sucursal.id());

            List<FilaDetallada> filas = new ArrayList<>();
            for (Fila f : base) {
                String id = f.producto().id();
                long valorInventario = valorPorProducto.getOrDefault(id, 0L);
                long costoPromedio = f.existencia().signum() == 0 ? 0
                        : Math.round(valorInventario / f.existencia().doubleValue());
                VentaProducto v = vendido.getOrDefault(id, new VentaProducto(BigDecimal.ZERO, 0));
                Instant ultima = ultimaVenta.get(id);
                Long dias = ultima == null ? null : ChronoUnit.DAYS.between(ultima, ahora);
                EstadoInventario estado = f.agotado() ? EstadoInventario.AGOTADO
                        : f.porReabastecer() ? (conOrdenPendiente.contains(id) ? EstadoInventario.POR_REABASTECER
                                : EstadoInventario.EN_MINIMO)
                        : EstadoInventario.OK;
                filas.add(new FilaDetallada(sucursal, f, estado, costoPromedio, valorInventario, v.cantidad(),
                        v.importeCentavos(), ultima, dias));
            }
            return filas;
        });
    }

    /** Lotes de un producto en una sucursal (más antiguo primero), para el detalle del administrador. */
    public List<LoteRepository.Lote> lotesDe(Usuario quien, String productoId, String sucursalId) {
        validarAdmin(quien);
        return database.con(c -> lotes.deProducto(c, productoId, sucursalId));
    }

    /** Últimos movimientos de inventario de un producto en una sucursal, para el detalle del administrador. */
    public List<Movimiento> movimientos(Usuario quien, String productoId, String sucursalId, int limite) {
        validarAdmin(quien);
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT tipo, cantidad, fecha, notas FROM movimientos_inventario
                    WHERE producto_id = ? AND sucursal_id = ?
                    ORDER BY fecha DESC LIMIT ?""")) {
                ps.setString(1, productoId);
                ps.setString(2, sucursalId);
                ps.setInt(3, limite);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Movimiento> lista = new ArrayList<>();
                    while (rs.next()) {
                        lista.add(new Movimiento(rs.getString(1), decimal(rs.getDouble(2)),
                                Tiempo.leer(rs.getString(3)), rs.getString(4)));
                    }
                    return lista;
                }
            }
        });
    }

    private static Map<String, VentaProducto> vendidoPorProducto(Connection c, String sucursalId, Instant desde,
                                                                  Instant hastaExclusivo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.producto_id, SUM(COALESCE(d.cantidad_base, d.cantidad)), SUM(d.importe_centavos)
                FROM venta_detalle d JOIN ventas v ON v.id = d.venta_id
                WHERE v.sucursal_id = ? AND v.estado = 'COMPLETADA' AND v.fecha >= ? AND v.fecha < ?
                GROUP BY d.producto_id""")) {
            ps.setString(1, sucursalId);
            ps.setString(2, Tiempo.formatear(desde));
            ps.setString(3, Tiempo.formatear(hastaExclusivo));
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, VentaProducto> mapa = new HashMap<>();
                while (rs.next()) {
                    mapa.put(rs.getString(1), new VentaProducto(decimal(rs.getDouble(2)), rs.getLong(3)));
                }
                return mapa;
            }
        }
    }

    private static Map<String, Instant> ultimaVentaPorProducto(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.producto_id, MAX(v.fecha) FROM venta_detalle d JOIN ventas v ON v.id = d.venta_id
                WHERE v.sucursal_id = ? AND v.estado = 'COMPLETADA'
                GROUP BY d.producto_id""")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Instant> mapa = new HashMap<>();
                while (rs.next()) {
                    mapa.put(rs.getString(1), Tiempo.leer(rs.getString(2)));
                }
                return mapa;
            }
        }
    }

    private static Set<String> conOrdenPendiente(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT producto_id FROM ordenes_reabastecimiento
                WHERE sucursal_id = ? AND estado = 'PENDIENTE'""")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> set = new HashSet<>();
                while (rs.next()) {
                    set.add(rs.getString(1));
                }
                return set;
            }
        }
    }

    private static void validarAdmin(Usuario quien) {
        if (quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede ver el detalle de productos por sucursal.");
        }
    }

    private static Map<String, BigDecimal[]> limites(Connection c, String sucursalId) throws SQLException {
        Map<String, BigDecimal[]> limites = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, minimo, maximo FROM limites_sucursal WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    limites.put(rs.getString(1), new BigDecimal[]{decimal(rs.getDouble(2)), decimal(rs.getDouble(3))});
                }
            }
        }
        return limites;
    }

    private static Map<String, BigDecimal> existencias(Connection c, String sucursalId) throws SQLException {
        Map<String, BigDecimal> existencias = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, existencia FROM v_existencias WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existencias.put(rs.getString(1), decimal(rs.getDouble(2)));
                }
            }
        }
        return existencias;
    }

    private static BigDecimal decimal(double valor) {
        return BigDecimal.valueOf(valor).setScale(3, RoundingMode.HALF_UP);
    }
}
