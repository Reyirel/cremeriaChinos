package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Masa;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Comisiones para los empleados:
 * <ul>
 *   <li>Meta de producto: al vender cierta cantidad (gramos o piezas) de un producto en el periodo.</li>
 *   <li>Meta de venta: al vender cierto importe en el periodo.</li>
 *   <li>Por producto vendido: una comisión por cada cierta cantidad vendida de un producto.</li>
 * </ul>
 */
public class ComisionService {

    public enum Tipo {
        META_CANTIDAD("Meta de producto"),
        META_TOTAL("Meta de venta"),
        POR_CANTIDAD("Por producto vendido");

        private final String nombre;

        Tipo(String nombre) {
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

    /** En qué se mide la meta de producto: gramos (gramaje neto vendido) o piezas. */
    public enum Medida {
        GRAMOS("Gramos"), UNIDADES("Piezas");

        private final String nombre;

        Medida(String nombre) {
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

    public enum Periodo {
        DIARIO("Diario"), SEMANAL("Semanal"), MENSUAL("Mensual");

        private final String nombre;

        Periodo(String nombre) {
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

    /** Regla de comisión. usuarioId/sucursalId nulos = aplica a todos. */
    /**
     * Regla de comisión. usuarioId/sucursalId nulos = aplica a todos.
     * {@code cantidadMeta} está en gramos si la medida es GRAMOS, o en unidades base si es UNIDADES.
     */
    public record Regla(String id, String folio, String nombre, Tipo tipo, Medida medida, String productoId,
                        String producto, Unidad unidad, BigDecimal cantidadMeta, long importeMetaCentavos,
                        long comisionCentavos, Periodo periodo, String usuarioId, String usuario, String sucursalId,
                        String sucursal, boolean activo) {

        /** "Al vender 5 kg de Queso Oaxaca → $50.00" */
        public String descripcion() {
            return switch (tipo) {
                case META_CANTIDAD -> "Al vender " + meta() + " de " + producto + " → " + Dinero.formatear(comisionCentavos);
                case META_TOTAL -> "Al vender " + Dinero.formatear(importeMetaCentavos) + " → "
                        + Dinero.formatear(comisionCentavos);
                case POR_CANTIDAD -> Dinero.formatear(comisionCentavos) + " por cada " + meta() + " de " + producto;
            };
        }

        private String meta() {
            return medida == Medida.GRAMOS ? Masa.formatear(cantidadMeta) : Cantidades.formatear(unidad, cantidadMeta);
        }
    }

    /** Comisión ganada por un empleado con una regla en un periodo. */
    public record Ganada(String usuario, Regla regla, String periodo, String logrado, long comisionCentavos) {
    }

    private record Venta(String usuarioId, String usuario, String sucursalId, LocalDate dia, long total,
                         Map<String, BigDecimal> cantidades) {
    }

    private final Database database;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public ComisionService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public List<Regla> reglas() {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT r.*, p.nombre AS producto, p.unidad AS unidad_producto, u.nombre_completo AS usuario,
                           s.nombre AS sucursal
                    FROM reglas_comision r
                    LEFT JOIN productos p ON p.id = r.producto_id
                    LEFT JOIN usuarios u ON u.id = r.usuario_id
                    LEFT JOIN sucursales s ON s.id = r.sucursal_id
                    WHERE r.eliminado_en IS NULL
                    ORDER BY r.activo DESC, r.nombre COLLATE NOCASE""");
                 ResultSet rs = ps.executeQuery()) {
                List<Regla> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Regla(rs.getString("id"), rs.getString("folio"), rs.getString("nombre"),
                            Tipo.valueOf(rs.getString("tipo")), Medida.valueOf(rs.getString("medida")),
                            rs.getString("producto_id"), rs.getString("producto"),
                            rs.getString("unidad_producto") == null ? null : Unidad.valueOf(rs.getString("unidad_producto")),
                            BigDecimal.valueOf(rs.getDouble("cantidad_meta")).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros(),
                            rs.getLong("importe_meta_centavos"), rs.getLong("comision_centavos"),
                            Periodo.valueOf(rs.getString("periodo")), rs.getString("usuario_id"), rs.getString("usuario"),
                            rs.getString("sucursal_id"), rs.getString("sucursal"), rs.getBoolean("activo")));
                }
                return lista;
            }
        });
    }

    /** Crea (id nulo) o edita una regla. Devuelve el folio. */
    public String guardar(Regla r, Usuario quien) {
        validar(r);
        return database.enTransaccion(c -> {
            String ahora = Tiempo.ahora();
            boolean nueva = r.id() == null;
            String id = nueva ? Ids.nuevo() : r.id();
            String folio = bitacora.siguienteFolio(c, AccionBitacora.REGLA_COMISION_CREADA.prefijo(), dispositivoId);
            if (nueva) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO reglas_comision (id, folio, nombre, tipo, producto_id, cantidad_meta, importe_meta_centavos,
                                                     comision_centavos, periodo, usuario_id, sucursal_id, activo, medida,
                                                     creado_en, actualizado_en)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, id);
                    ps.setString(2, folio);
                    llenar(ps, 3, r);
                    ps.setString(14, ahora);
                    ps.setString(15, ahora);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE reglas_comision SET nombre = ?, tipo = ?, producto_id = ?, cantidad_meta = ?,
                               importe_meta_centavos = ?, comision_centavos = ?, periodo = ?, usuario_id = ?,
                               sucursal_id = ?, activo = ?, medida = ?, actualizado_en = ?
                        WHERE id = ?""")) {
                    llenar(ps, 1, r);
                    ps.setString(12, ahora);
                    ps.setString(13, id);
                    ps.executeUpdate();
                }
            }
            return bitacora.registrar(c, nueva ? AccionBitacora.REGLA_COMISION_CREADA : AccionBitacora.REGLA_COMISION_EDITADA,
                    quien, dispositivoId, "reglas_comision", id, r.nombre().strip() + ": " + r.descripcion()
                            + " (" + r.periodo().nombre().toLowerCase() + (r.activo() ? "" : ", inactiva") + ")", folio);
        });
    }

    private static void llenar(PreparedStatement ps, int i, Regla r) throws java.sql.SQLException {
        ps.setString(i, r.nombre().strip());
        ps.setString(i + 1, r.tipo().name());
        ps.setString(i + 2, r.tipo() == Tipo.META_TOTAL ? null : r.productoId());
        if (r.tipo() == Tipo.META_TOTAL) {
            ps.setNull(i + 3, java.sql.Types.REAL);
            ps.setLong(i + 4, r.importeMetaCentavos());
        } else {
            ps.setBigDecimal(i + 3, r.cantidadMeta());
            ps.setNull(i + 4, java.sql.Types.INTEGER);
        }
        ps.setLong(i + 5, r.comisionCentavos());
        ps.setString(i + 6, r.periodo().name());
        ps.setString(i + 7, r.usuarioId());
        ps.setString(i + 8, r.sucursalId());
        ps.setBoolean(i + 9, r.activo());
        ps.setString(i + 10, (r.medida() == null ? Medida.UNIDADES : r.medida()).name());
    }

    public String eliminar(Regla r, Usuario quien) {
        return database.enTransaccion(c -> {
            String ahora = Tiempo.ahora();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reglas_comision SET eliminado_en = ?, activo = 0, actualizado_en = ? WHERE id = ?")) {
                ps.setString(1, ahora);
                ps.setString(2, ahora);
                ps.setString(3, r.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.REGLA_COMISION_ELIMINADA, quien, dispositivoId,
                    "reglas_comision", r.id(), r.nombre());
        });
    }

    /** Comisiones ganadas entre dos fechas (inclusive), por empleado, regla y periodo. */
    public List<Ganada> calcular(LocalDate desde, LocalDate hasta) {
        List<Regla> reglas = reglas().stream().filter(Regla::activo).toList();
        List<Venta> ventas = ventas(desde, hasta);
        Map<String, BigDecimal> gramajeNeto = gramajesNetos();
        List<Ganada> ganadas = new ArrayList<>();
        for (Regla regla : reglas) {
            // empleado → periodo → ventas
            Map<String, Map<String, List<Venta>>> grupos = new LinkedHashMap<>();
            for (Venta v : ventas) {
                if (regla.usuarioId() != null && !regla.usuarioId().equals(v.usuarioId())) {
                    continue;
                }
                if (regla.sucursalId() != null && !regla.sucursalId().equals(v.sucursalId())) {
                    continue;
                }
                grupos.computeIfAbsent(v.usuarioId(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(periodo(regla.periodo(), v.dia()), k -> new ArrayList<>()).add(v);
            }
            for (Map<String, List<Venta>> porPeriodo : grupos.values()) {
                for (Map.Entry<String, List<Venta>> e : porPeriodo.entrySet()) {
                    List<Venta> lista = e.getValue();
                    String usuario = lista.getFirst().usuario();
                    switch (regla.tipo()) {
                        case META_TOTAL -> {
                            long total = lista.stream().mapToLong(Venta::total).sum();
                            if (total >= regla.importeMetaCentavos()) {
                                ganadas.add(new Ganada(usuario, regla, e.getKey(), Dinero.formatear(total),
                                        regla.comisionCentavos()));
                            }
                        }
                        case META_CANTIDAD, POR_CANTIDAD -> {
                            BigDecimal cantidad = lista.stream()
                                    .map(v -> v.cantidades().getOrDefault(regla.productoId(), BigDecimal.ZERO))
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            if (regla.medida() == Medida.GRAMOS) {
                                // Unidades base vendidas × gramaje neto (gramaje − merma).
                                cantidad = cantidad.multiply(gramajeNeto.getOrDefault(regla.productoId(), BigDecimal.ZERO));
                            }
                            long veces = regla.tipo() == Tipo.META_CANTIDAD
                                    ? (cantidad.compareTo(regla.cantidadMeta()) >= 0 ? 1 : 0)
                                    : cantidad.divide(regla.cantidadMeta(), 0, RoundingMode.DOWN).longValue();
                            if (veces > 0) {
                                ganadas.add(new Ganada(usuario, regla, e.getKey(), regla.medida() == Medida.GRAMOS
                                        ? Masa.formatear(cantidad) : Cantidades.formatear(regla.unidad(), cantidad),
                                        veces * regla.comisionCentavos()));
                            }
                        }
                    }
                }
            }
        }
        return ganadas;
    }

    /** Gramos netos por unidad base de cada producto (gramaje − merma). */
    private Map<String, BigDecimal> gramajesNetos() {
        return database.con(c -> {
            Map<String, BigDecimal> mapa = new java.util.HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, COALESCE(gramaje_gramos, 0) - merma_gramos FROM productos");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mapa.put(rs.getString(1), BigDecimal.valueOf(rs.getDouble(2)).max(BigDecimal.ZERO));
                }
            }
            return mapa;
        });
    }

    private List<Venta> ventas(LocalDate desde, LocalDate hasta) {
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            Map<String, Venta> ventas = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT v.id, v.usuario_id, u.nombre_completo, v.sucursal_id, v.fecha, v.total_centavos,
                           d.producto_id, COALESCE(d.cantidad_base, d.cantidad) AS cantidad
                    FROM ventas v
                    JOIN usuarios u ON u.id = v.usuario_id
                    JOIN venta_detalle d ON d.venta_id = v.id
                    WHERE v.estado = 'COMPLETADA' AND v.fecha >= ? AND v.fecha < ?""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Venta v = ventas.computeIfAbsent(rs.getString(1), id -> {
                            try {
                                return new Venta(rs.getString(2), rs.getString(3), rs.getString(4),
                                        Tiempo.leer(rs.getString(5)).atZone(zona).toLocalDate(), rs.getLong(6),
                                        new LinkedHashMap<>());
                            } catch (java.sql.SQLException ex) {
                                throw new IllegalStateException(ex);
                            }
                        });
                        v.cantidades().merge(rs.getString(7),
                                BigDecimal.valueOf(rs.getDouble(8)).setScale(3, RoundingMode.HALF_UP), BigDecimal::add);
                    }
                }
            }
            return new ArrayList<>(ventas.values());
        });
    }

    static String periodo(Periodo periodo, LocalDate dia) {
        Locale es = Locale.forLanguageTag("es-MX");
        return switch (periodo) {
            case DIARIO -> dia.format(DateTimeFormatter.ofPattern("d MMM yyyy", es));
            case SEMANAL -> {
                LocalDate lunes = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield "Semana del " + lunes.format(DateTimeFormatter.ofPattern("d MMM", es)) + " al "
                        + lunes.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM yyyy", es));
            }
            case MENSUAL -> dia.format(DateTimeFormatter.ofPattern("MMMM yyyy", es));
        };
    }

    private static void validar(Regla r) {
        if (r.nombre() == null || r.nombre().isBlank()) {
            throw new IllegalArgumentException("Escribe un nombre para la regla.");
        }
        if (r.comisionCentavos() <= 0) {
            throw new IllegalArgumentException("La comisión debe ser mayor a cero.");
        }
        if (r.tipo() == Tipo.META_TOTAL) {
            if (r.importeMetaCentavos() <= 0) {
                throw new IllegalArgumentException("Escribe el importe de la meta.");
            }
        } else {
            if (r.productoId() == null) {
                throw new IllegalArgumentException("Elige el producto.");
            }
            if (r.cantidadMeta() == null || r.cantidadMeta().signum() <= 0) {
                throw new IllegalArgumentException("Escribe la cantidad (gramos o piezas).");
            }
            if (r.medida() == Medida.UNIDADES && r.unidad() == Unidad.KG) {
                throw new IllegalArgumentException("Los productos por kilo se miden en gramos.");
            }
        }
    }
}
