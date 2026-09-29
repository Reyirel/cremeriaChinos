package com.cremerias.puntoventa.service.admin;

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
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Surtido del almacén a una sucursal. La mercancía sale de los lotes más antiguos del almacén
 * (su costo es lo que se envía) y entra a la sucursal como un lote nuevo con el precio de venta
 * de cada presentación. Puede ser en efectivo o a crédito (se carga al saldo de la sucursal).
 */
public class SurtidoService {

    public enum FormaPago {
        EFECTIVO("Efectivo"), CREDITO("Crédito");

        private final String nombre;

        FormaPago(String nombre) {
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

    /** Renglón a surtir: cantidad en unidad base y precio de venta por presentación (id → centavos). */
    public record Linea(ProductoCatalogo producto, BigDecimal cantidad, Map<String, Long> precios) {
    }

    /** Cuánto cuesta sacar la cantidad del almacén, lote por lote. */
    public record Costeo(List<AlmacenService.SalidaLote> salidas, long costoCentavos, BigDecimal disponible,
                         boolean suficiente) {
    }

    public record Resumen(String id, String folio, Instant fecha, String sucursal, FormaPago formaPago,
                          long costoCentavos, long ventaCentavos, int productos, String usuario, String ordenFolio) {
    }

    public record DetalleLinea(String producto, Unidad unidad, BigDecimal cantidad, long costoCentavos,
                               long ventaCentavos, Map<String, Long> precios) {
    }

    public record Detalle(Resumen resumen, List<DetalleLinea> lineas, String notas, long saldoCredito) {
    }

    private final Database database;
    private final String dispositivoId;
    private final AlmacenService almacen;
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final OrdenRepository ordenes = new OrdenRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public SurtidoService(Database database, String dispositivoId, AlmacenService almacen) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.almacen = almacen;
    }

    /** Costo de la cantidad según los lotes del almacén (primero el más antiguo). */
    public Costeo costear(ProductoCatalogo producto, BigDecimal cantidad) {
        return database.con(c -> {
            List<AlmacenService.SalidaLote> salidas = new ArrayList<>();
            BigDecimal restante = cantidad;
            BigDecimal disponible = BigDecimal.ZERO;
            long costo = 0;
            for (LoteRepository.Lote lote : lotes.deProducto(c, producto.id(), almacen.almacenId())) {
                if (lote.existencia().signum() <= 0) {
                    continue;
                }
                disponible = disponible.add(lote.existencia());
                if (restante.signum() > 0) {
                    BigDecimal toma = lote.existencia().min(restante);
                    long parte = Dinero.importe(lote.costoUnitarioCentavos(), toma);
                    salidas.add(new AlmacenService.SalidaLote(lote, toma, parte));
                    costo += parte;
                    restante = restante.subtract(toma);
                }
            }
            return new Costeo(salidas, costo, disponible, restante.signum() <= 0);
        });
    }

    /** Últimos precios de cada presentación en la sucursal (para sugerirlos al surtir). */
    public Map<String, Long> preciosSugeridos(String sucursalId, String productoId) {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT lp.presentacion_id, lp.precio_centavos
                    FROM lote_precios lp JOIN lotes l ON l.id = lp.lote_id
                    WHERE l.sucursal_id = ? AND l.producto_id = ?
                    ORDER BY l.fecha_ingreso DESC, l.creado_en DESC""")) {
                ps.setString(1, sucursalId);
                ps.setString(2, productoId);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Long> precios = new HashMap<>();
                    while (rs.next()) {
                        precios.putIfAbsent(rs.getString(1), rs.getLong(2));
                    }
                    return precios;
                }
            }
        });
    }

    /**
     * Precio promedio de cada presentación entre los lotes que la sucursal ya tiene del producto.
     *
     * @param precios       presentación → promedio en centavos (solo las que tienen precio en algún lote)
     * @param lotes         cuántos lotes se promediaron
     * @param conExistencia true si se promediaron los lotes con existencia; false si ninguno tenía
     *                      existencia y se usaron todos los lotes anteriores
     */
    public record PreciosPromedio(Map<String, Long> precios, int lotes, boolean conExistencia) {
    }

    /**
     * Promedio simple de los precios de los lotes de la sucursal que aún tienen existencia. Si
     * ninguno tiene, se promedian todos los lotes anteriores del producto en esa sucursal.
     */
    public PreciosPromedio preciosPromedio(String sucursalId, String productoId) {
        return database.con(c -> {
            Map<String, Map<String, Long>> precios = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT lp.lote_id, lp.presentacion_id, lp.precio_centavos
                    FROM lote_precios lp JOIN lotes l ON l.id = lp.lote_id
                    WHERE l.sucursal_id = ? AND l.producto_id = ?""")) {
                ps.setString(1, sucursalId);
                ps.setString(2, productoId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        precios.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                    }
                }
            }
            List<LoteRepository.Lote> conPrecio = lotes.deProducto(c, productoId, sucursalId).stream()
                    .filter(l -> precios.containsKey(l.id())).toList();
            List<LoteRepository.Lote> conExistencia = conPrecio.stream().filter(l -> l.existencia().signum() > 0).toList();
            List<LoteRepository.Lote> promediados = conExistencia.isEmpty() ? conPrecio : conExistencia;
            Map<String, long[]> sumas = new LinkedHashMap<>(); // presentación → {suma, lotes}
            for (LoteRepository.Lote lote : promediados) {
                for (Map.Entry<String, Long> e : precios.get(lote.id()).entrySet()) {
                    long[] s = sumas.computeIfAbsent(e.getKey(), k -> new long[2]);
                    s[0] += e.getValue();
                    s[1]++;
                }
            }
            Map<String, Long> promedio = new LinkedHashMap<>();
            sumas.forEach((presentacion, s) -> promedio.put(presentacion,
                    BigDecimal.valueOf(s[0]).divide(BigDecimal.valueOf(s[1]), 0, RoundingMode.HALF_UP).longValue()));
            return new PreciosPromedio(promedio, promediados.size(), !conExistencia.isEmpty());
        });
    }

    /** Valor de la mercancía al precio de venta de la presentación principal. */
    public static long valorVenta(ProductoCatalogo producto, BigDecimal cantidad, Map<String, Long> precios) {
        Presentacion principal = producto.principal();
        Long precio = principal == null ? null : precios.get(principal.id());
        if (precio == null) {
            return 0;
        }
        return cantidad.divide(principal.factor(), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(precio)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Registra el surtido. Si viene de una orden de reabastecimiento, la marca como aprobada. */
    public Detalle registrar(Sucursal sucursal, FormaPago formaPago, List<Linea> lineas, String notas, Usuario quien,
                             String ordenId) {
        if (sucursal == null || sucursal.esAlmacen()) {
            throw new IllegalArgumentException("Elige la sucursal a surtir.");
        }
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException("Agrega al menos un producto.");
        }
        for (Linea l : lineas) {
            if (l.cantidad() == null || l.cantidad().signum() <= 0) {
                throw new IllegalArgumentException("La cantidad de " + l.producto().nombre() + " debe ser mayor a cero.");
            }
            for (Presentacion p : l.producto().presentaciones()) {
                if (p.activo() && (l.precios().get(p.id()) == null || l.precios().get(p.id()) <= 0)) {
                    throw new IllegalArgumentException("Falta el precio de " + l.producto().nombre() + " · "
                            + p.nombre() + ".");
                }
            }
        }
        String surtidoId = Ids.nuevo();
        database.enTransaccion(c -> {
            String folio = bitacora.siguienteFolio(c, AccionBitacora.SURTIDO.prefijo(), dispositivoId);
            List<long[]> totales = new ArrayList<>();
            long costoTotal = 0;
            long ventaTotal = 0;
            List<List<AlmacenService.SalidaLote>> salidasPorLinea = new ArrayList<>();
            for (Linea l : lineas) {
                List<AlmacenService.SalidaLote> salidas = almacen.salidaPeps(c, l.producto(), almacen.almacenId(), l.cantidad());
                long costo = salidas.stream().mapToLong(AlmacenService.SalidaLote::costoCentavos).sum();
                long venta = valorVenta(l.producto(), l.cantidad(), l.precios());
                salidasPorLinea.add(salidas);
                totales.add(new long[]{costo, venta});
                costoTotal += costo;
                ventaTotal += venta;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO surtidos (id, folio, sucursal_id, forma_pago, total_costo_centavos, total_venta_centavos,
                                          usuario_id, fecha, notas)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, surtidoId);
                ps.setString(2, folio);
                ps.setString(3, sucursal.id());
                ps.setString(4, formaPago.name());
                ps.setLong(5, costoTotal);
                ps.setLong(6, ventaTotal);
                ps.setString(7, quien.id());
                ps.setString(8, Tiempo.ahora());
                ps.setString(9, notas == null || notas.isBlank() ? null : notas.strip());
                ps.executeUpdate();
            }
            for (int i = 0; i < lineas.size(); i++) {
                Linea l = lineas.get(i);
                long costo = totales.get(i)[0];
                for (AlmacenService.SalidaLote s : salidasPorLinea.get(i)) {
                    inventario.registrar(c, l.producto().id(), almacen.almacenId(), s.lote().id(), "SURTIDO_SALIDA",
                            s.cantidad().negate(), surtidoId, quien.id(), "Surtido " + folio);
                }
                long costoUnitario = BigDecimal.valueOf(costo).divide(l.cantidad(), 0, RoundingMode.HALF_UP).longValue();
                String lote = lotes.insertar(c, folio, l.producto().id(), sucursal.id(), "SURTIDO", surtidoId,
                        l.cantidad(), costoUnitario, quien.id());
                for (Map.Entry<String, Long> precio : l.precios().entrySet()) {
                    lotes.insertarPrecio(c, lote, precio.getKey(), precio.getValue());
                }
                inventario.registrar(c, l.producto().id(), sucursal.id(), lote, "SURTIDO_ENTRADA", l.cantidad(),
                        surtidoId, quien.id(), "Surtido " + folio);
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO surtido_detalle (id, surtido_id, renglon, producto_id, cantidad, costo_total_centavos,
                                                     valor_venta_centavos, lote_destino_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, surtidoId);
                    ps.setInt(3, i + 1);
                    ps.setString(4, l.producto().id());
                    ps.setBigDecimal(5, l.cantidad());
                    ps.setLong(6, costo);
                    ps.setLong(7, totales.get(i)[1]);
                    ps.setString(8, lote);
                    ps.executeUpdate();
                }
            }
            if (formaPago == FormaPago.CREDITO && costoTotal > 0) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO movimientos_credito (id, folio, sucursal_id, tipo, monto_centavos, surtido_id, usuario_id, fecha)
                        VALUES (?, ?, ?, 'CARGO', ?, ?, ?, ?)""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, folio);
                    ps.setString(3, sucursal.id());
                    ps.setLong(4, costoTotal);
                    ps.setString(5, surtidoId);
                    ps.setString(6, quien.id());
                    ps.setString(7, Tiempo.ahora());
                    ps.executeUpdate();
                }
            }
            String orden = "";
            if (ordenId != null) {
                ordenes.resolver(c, ordenId, "APROBADA", quien.id(), null, surtidoId);
                orden = " · aprueba orden " + ordenes.porId(c, ordenId).map(OrdenRepository.Orden::folio).orElse("");
            }
            bitacora.registrar(c, AccionBitacora.SURTIDO, quien, dispositivoId, "surtidos", surtidoId,
                    "A " + sucursal.nombre() + " (" + formaPago.nombre().toLowerCase() + "): " + lineas.size()
                            + " producto(s), costo " + Dinero.formatear(costoTotal) + ", valor de venta "
                            + Dinero.formatear(ventaTotal) + orden, folio);
            return null;
        });
        return detalle(surtidoId);
    }

    public List<Resumen> listar(LocalDate desde, LocalDate hasta, String sucursalId) {
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT_RESUMEN + """
                    WHERE t.fecha >= ? AND t.fecha < ? AND (? IS NULL OR t.sucursal_id = ?)
                    ORDER BY t.fecha DESC""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                ps.setString(3, sucursalId);
                ps.setString(4, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Resumen> lista = new ArrayList<>();
                    while (rs.next()) {
                        lista.add(resumen(rs));
                    }
                    return lista;
                }
            }
        });
    }

    public Detalle detalle(String surtidoId) {
        return database.con(c -> {
            Resumen resumen;
            String notas;
            String sucursalId;
            try (PreparedStatement ps = c.prepareStatement(SELECT_RESUMEN + " WHERE t.id = ?")) {
                ps.setString(1, surtidoId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("No existe el surtido.");
                    }
                    resumen = resumen(rs);
                    notas = rs.getString("notas");
                    sucursalId = rs.getString("sucursal_id");
                }
            }
            Map<String, String> nombresPresentacion = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, nombre FROM presentaciones");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    nombresPresentacion.put(rs.getString(1), rs.getString(2));
                }
            }
            List<DetalleLinea> lineas = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.lote_destino_id, p.nombre, p.unidad, d.cantidad, d.costo_total_centavos, d.valor_venta_centavos
                    FROM surtido_detalle d JOIN productos p ON p.id = d.producto_id
                    WHERE d.surtido_id = ? ORDER BY d.renglon""")) {
                ps.setString(1, surtidoId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Long> precios = new LinkedHashMap<>();
                        try (PreparedStatement pp = c.prepareStatement("""
                                SELECT lp.presentacion_id, lp.precio_centavos FROM lote_precios lp
                                JOIN presentaciones pr ON pr.id = lp.presentacion_id
                                WHERE lp.lote_id = ? ORDER BY pr.es_principal DESC, pr.factor""")) {
                            pp.setString(1, rs.getString(1));
                            try (ResultSet rp = pp.executeQuery()) {
                                while (rp.next()) {
                                    precios.put(nombresPresentacion.get(rp.getString(1)), rp.getLong(2));
                                }
                            }
                        }
                        lineas.add(new DetalleLinea(rs.getString(2), Unidad.valueOf(rs.getString(3)),
                                BigDecimal.valueOf(rs.getDouble(4)).setScale(3, RoundingMode.HALF_UP), rs.getLong(5),
                                rs.getLong(6), precios));
                    }
                }
            }
            return new Detalle(resumen, lineas, notas, CreditoService.saldo(c, sucursalId));
        });
    }

    private static final String SELECT_RESUMEN = """
            SELECT t.id, t.folio, t.fecha, s.nombre AS sucursal, t.forma_pago, t.total_costo_centavos,
                   t.total_venta_centavos, t.notas, t.sucursal_id, u.nombre_completo AS usuario,
                   (SELECT COUNT(*) FROM surtido_detalle d WHERE d.surtido_id = t.id) AS productos,
                   (SELECT o.folio FROM ordenes_reabastecimiento o WHERE o.surtido_id = t.id LIMIT 1) AS orden
            FROM surtidos t
            JOIN sucursales s ON s.id = t.sucursal_id
            JOIN usuarios u ON u.id = t.usuario_id
            """;

    private static Resumen resumen(ResultSet rs) throws java.sql.SQLException {
        return new Resumen(rs.getString("id"), rs.getString("folio"), Tiempo.leer(rs.getString("fecha")),
                rs.getString("sucursal"), FormaPago.valueOf(rs.getString("forma_pago")),
                rs.getLong("total_costo_centavos"), rs.getLong("total_venta_centavos"), rs.getInt("productos"),
                rs.getString("usuario"), rs.getString("orden"));
    }

    /** Texto para mostrar una cantidad capturada con su unidad (g o pzas). */
    public static String cantidad(ProductoCatalogo producto, BigDecimal cantidad) {
        return Cantidades.formatear(producto.unidad(), cantidad);
    }
}
