package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Compras a proveedores: un documento (proveedor, nota, fecha) con varias líneas. Cada línea crea su
 * lote en el almacén con su propio costo, todo en una sola transacción (si algo falla no queda nada a
 * medias). Las bonificaciones del proveedor (mercancía sin costo por volumen) se registran aparte de
 * lo pagado; cómo se valúan lo decide la política {@link PoliticaBonificacion}.
 *
 * <p>También registra las devoluciones a proveedor (salen del lote de la compra o PEPS).
 */
public class CompraService {

    /** Cómo se valúa la mercancía bonificada. */
    public enum PoliticaBonificacion {
        /** El costo por unidad baja: importe pagado ÷ todas las unidades recibidas (un solo lote). */
        PRORRATEAR("Repartir el costo entre todo lo recibido"),
        /** Lo bonificado entra en un lote aparte a costo cero; lo pagado conserva su costo. */
        COSTO_CERO("Lo bonificado entra a costo cero");

        private final String nombre;

        PoliticaBonificacion(String nombre) {
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

    /** Tratamiento financiero de una devolución a proveedor (se acuerda con el proveedor). */
    public enum Tratamiento {
        POR_DEFINIR("Por definir"), NOTA_CREDITO("Nota de crédito del proveedor"), REEMBOLSO("Reembolso"),
        REPOSICION("Reposición de mercancía"), SIN_COMPENSACION("Sin compensación");

        private final String nombre;

        Tratamiento(String nombre) {
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

    public record Proveedor(String id, String nombre, String rfc, String telefono, String contacto, String notas,
                            boolean activo) {
        @Override
        public String toString() {
            return nombre;
        }
    }

    /**
     * Línea de compra en unidades de la presentación comprada (cajas, piezas o kilos).
     *
     * @param presentacion        presentación comprada; {@code null} = la unidad base (pieza o kilo)
     * @param costoUnitarioCentavos lo que cobra el proveedor por cada unidad comprada
     */
    public record Linea(ProductoCatalogo producto, Presentacion presentacion, BigDecimal cantidadPagada,
                        BigDecimal cantidadBonificada, long costoUnitarioCentavos) {

        BigDecimal factor() {
            return presentacion == null ? BigDecimal.ONE : presentacion.factor();
        }

        long importe() {
            return Dinero.importe(costoUnitarioCentavos, cantidadPagada);
        }
    }

    public record Resumen(String id, String folio, Instant fecha, String proveedorId, String proveedor,
                          String documento, LocalDate fechaDocumento, long totalCentavos, int lineas, String usuario,
                          PoliticaBonificacion politica, boolean conBonificacion) {
    }

    public record DetalleLinea(String productoId, String producto, Unidad unidad, String presentacion, BigDecimal factor,
                               BigDecimal cantidadPagada, BigDecimal cantidadBonificada, long costoUnitarioCentavos,
                               long importeCentavos, BigDecimal recibidaBase, long costoBaseCentavos, String loteId,
                               String loteBonificadoId) {
    }

    public record Detalle(Resumen resumen, List<DetalleLinea> lineas, String notas) {
    }

    /** Línea a devolver al proveedor; {@code loteId} nulo = de los lotes de la compra o los más antiguos. */
    public record LineaDevolucion(ProductoCatalogo producto, String loteId, BigDecimal cantidad) {
    }

    public record DevolucionResumen(String id, String folio, Instant fecha, String proveedor, String compraFolio,
                                    String motivo, Tratamiento tratamiento, long costoCentavos, String usuario) {
    }

    private final Database database;
    private final String dispositivoId;
    private final String almacenId;
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public CompraService(Database database, String dispositivoId, String almacenId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.almacenId = almacenId;
    }

    // ================================================================== proveedores

    public List<Proveedor> proveedores() {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, nombre, rfc, telefono, contacto, notas, activo FROM proveedores
                    WHERE eliminado_en IS NULL ORDER BY activo DESC, nombre COLLATE NOCASE""");
                 ResultSet rs = ps.executeQuery()) {
                List<Proveedor> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Proveedor(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7) == 1));
                }
                return lista;
            }
        });
    }

    /** Crea (id nulo) o edita un proveedor. Devuelve el folio. */
    public String guardarProveedor(String id, String nombre, String rfc, String telefono, String contacto, String notas,
                                   Usuario quien) {
        Permisos.exigirAdministrador(quien, "administrar proveedores");
        String nom = Permisos.exigirTexto(nombre, "Escribe el nombre del proveedor.");
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT 1 FROM proveedores WHERE nombre = ? COLLATE NOCASE AND eliminado_en IS NULL AND id <> ?""")) {
                ps.setString(1, nom);
                ps.setString(2, id == null ? "" : id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalArgumentException("Ya hay un proveedor llamado " + nom + ".");
                    }
                }
            }
            boolean nuevo = id == null;
            String proveedorId = nuevo ? Ids.nuevo() : id;
            String ahora = Tiempo.ahora();
            String sql = nuevo
                    ? "INSERT INTO proveedores (nombre, rfc, telefono, contacto, notas, creado_en, actualizado_en, id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    : "UPDATE proveedores SET nombre = ?, rfc = ?, telefono = ?, contacto = ?, notas = ?, "
                    + "actualizado_en = ? WHERE id = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, nom);
                ps.setString(2, vacio(rfc));
                ps.setString(3, vacio(telefono));
                ps.setString(4, vacio(contacto));
                ps.setString(5, vacio(notas));
                ps.setString(6, ahora);
                if (nuevo) {
                    ps.setString(7, ahora);
                    ps.setString(8, proveedorId);
                } else {
                    ps.setString(7, proveedorId);
                }
                ps.executeUpdate();
            }
            return bitacora.registrar(c, nuevo ? AccionBitacora.PROVEEDOR_CREADO : AccionBitacora.PROVEEDOR_EDITADO,
                    quien, dispositivoId, "proveedores", proveedorId, nom);
        });
    }

    public String cambiarActivoProveedor(Proveedor proveedor, boolean activo, Usuario quien) {
        Permisos.exigirAdministrador(quien, "administrar proveedores");
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE proveedores SET activo = ?, actualizado_en = ? WHERE id = ?")) {
                ps.setInt(1, activo ? 1 : 0);
                ps.setString(2, Tiempo.ahora());
                ps.setString(3, proveedor.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.PROVEEDOR_EDITADO, quien, dispositivoId, "proveedores",
                    proveedor.id(), proveedor.nombre() + (activo ? " habilitado" : " deshabilitado"));
        });
    }

    // ================================================================== política de bonificación

    public PoliticaBonificacion politica() {
        return database.con(CompraService::politica);
    }

    static PoliticaBonificacion politica(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT valor FROM configuracion_general WHERE clave = 'compras.bonificacion'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? PoliticaBonificacion.valueOf(rs.getString(1)) : PoliticaBonificacion.PRORRATEAR;
        }
    }

    public String cambiarPolitica(PoliticaBonificacion politica, Usuario quien) {
        Permisos.exigirAdministrador(quien, "cambiar cómo se valúan las bonificaciones");
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO configuracion_general (clave, valor, actualizado_en) VALUES ('compras.bonificacion', ?, ?)
                    ON CONFLICT (clave) DO UPDATE SET valor = excluded.valor, actualizado_en = excluded.actualizado_en""")) {
                ps.setString(1, politica.name());
                ps.setString(2, Tiempo.ahora());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.CONFIGURACION, quien, dispositivoId, "configuracion_general",
                    "compras.bonificacion", "Bonificaciones de proveedor: " + politica.nombre().toLowerCase());
        });
    }

    // ================================================================== compras

    /**
     * Registra la compra completa. {@code compraId} lo genera la pantalla al abrir el formulario: si se
     * manda dos veces (doble clic, reintento), la segunda devuelve la compra ya registrada sin duplicarla.
     */
    public Detalle registrar(String compraId, Proveedor proveedor, String documento, LocalDate fechaDocumento,
                             List<Linea> lineas, String notas, Usuario quien) {
        Permisos.exigirAdministrador(quien, "registrar compras");
        if (compraId == null || compraId.isBlank()) {
            throw new IllegalArgumentException("Falta el identificador de la compra.");
        }
        if (proveedor == null) {
            throw new IllegalArgumentException("Elige el proveedor.");
        }
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("Agrega al menos un producto.");
        }
        if (almacenId == null) {
            throw new IllegalStateException("No hay almacén central para recibir la compra.");
        }
        for (Linea l : lineas) {
            validar(l);
        }
        database.enTransaccion(c -> {
            if (existe(c, "compras", compraId)) {
                return null; // Ya se registró (doble clic o reintento): no se repite.
            }
            if (!proveedorActivo(c, proveedor.id())) {
                throw new IllegalArgumentException("El proveedor " + proveedor.nombre() + " no está activo.");
            }
            PoliticaBonificacion politica = politica(c);
            String folio = bitacora.siguienteFolio(c, AccionBitacora.COMPRA.prefijo(), dispositivoId);
            long total = lineas.stream().mapToLong(Linea::importe).sum();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO compras (id, folio, proveedor_id, sucursal_id, documento, fecha_documento,
                                         politica_bonificacion, total_centavos, notas, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, compraId);
                ps.setString(2, folio);
                ps.setString(3, proveedor.id());
                ps.setString(4, almacenId);
                ps.setString(5, vacio(documento));
                ps.setString(6, fechaDocumento == null ? null : fechaDocumento.toString());
                ps.setString(7, politica.name());
                ps.setLong(8, total);
                ps.setString(9, vacio(notas));
                ps.setString(10, quien.id());
                ps.setString(11, Tiempo.ahora());
                ps.executeUpdate();
            }
            int renglon = 1;
            long bonificadas = 0;
            for (Linea l : lineas) {
                registrarLinea(c, compraId, folio, renglon++, l, politica, quien);
                if (l.cantidadBonificada().signum() > 0) {
                    bonificadas++;
                }
            }
            bitacora.registrar(c, AccionBitacora.COMPRA, quien, dispositivoId, "compras", compraId,
                    proveedor.nombre() + (vacio(documento) == null ? "" : " · nota " + documento.strip()) + ": "
                            + lineas.size() + " producto(s), total " + Dinero.formatear(total)
                            + (bonificadas == 0 ? "" : " · " + bonificadas + " con bonificación ("
                            + politica.nombre().toLowerCase() + ")"), folio);
            return null;
        });
        return detalle(compraId);
    }

    private void registrarLinea(Connection c, String compraId, String folio, int renglon, Linea l,
                                PoliticaBonificacion politica, Usuario quien) throws SQLException {
        Unidad unidad = l.producto().unidad();
        BigDecimal pagadaBase = unidad.normalizar(l.cantidadPagada().multiply(l.factor()));
        BigDecimal bonificadaBase = unidad.normalizar(l.cantidadBonificada().multiply(l.factor()));
        BigDecimal recibidaBase = pagadaBase.add(bonificadaBase);
        long importe = l.importe();
        String loteId;
        String loteBonificado = null;
        long costoBase;
        if (politica == PoliticaBonificacion.PRORRATEAR || bonificadaBase.signum() == 0) {
            costoBase = costoPorUnidad(importe, recibidaBase);
            loteId = lotes.insertar(c, folio, l.producto().id(), almacenId, "ENTRADA", compraId, recibidaBase, costoBase,
                    quien.id());
            inventario.registrar(c, l.producto().id(), almacenId, loteId, "COMPRA", recibidaBase, compraId, quien.id(),
                    "Compra " + folio);
        } else {
            costoBase = costoPorUnidad(importe, pagadaBase);
            loteId = lotes.insertar(c, folio, l.producto().id(), almacenId, "ENTRADA", compraId, pagadaBase, costoBase,
                    quien.id());
            inventario.registrar(c, l.producto().id(), almacenId, loteId, "COMPRA", pagadaBase, compraId, quien.id(),
                    "Compra " + folio);
            loteBonificado = lotes.insertar(c, folio, l.producto().id(), almacenId, "ENTRADA", compraId, bonificadaBase, 0,
                    quien.id());
            inventario.registrar(c, l.producto().id(), almacenId, loteBonificado, "COMPRA", bonificadaBase, compraId,
                    quien.id(), "Bonificación de la compra " + folio);
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO compra_detalle (id, compra_id, renglon, producto_id, presentacion_id, factor, cantidad_pagada,
                                            cantidad_bonificada, costo_unitario_centavos, importe_centavos,
                                            cantidad_recibida_base, costo_base_centavos, lote_id, lote_bonificado_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, compraId);
            ps.setInt(3, renglon);
            ps.setString(4, l.producto().id());
            ps.setString(5, l.presentacion() == null ? null : l.presentacion().id());
            ps.setBigDecimal(6, l.factor());
            ps.setBigDecimal(7, l.cantidadPagada());
            ps.setBigDecimal(8, l.cantidadBonificada());
            ps.setLong(9, l.costoUnitarioCentavos());
            ps.setLong(10, importe);
            ps.setBigDecimal(11, recibidaBase);
            ps.setLong(12, costoBase);
            ps.setString(13, loteId);
            ps.setString(14, loteBonificado);
            ps.executeUpdate();
        }
    }

    /** Costo por unidad base redondeado al centavo (el lote guarda centavos por pieza o por kilo). */
    static long costoPorUnidad(long importe, BigDecimal unidadesBase) {
        return BigDecimal.valueOf(importe).divide(unidadesBase, 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void validar(Linea l) {
        if (l.producto() == null) {
            throw new IllegalArgumentException("Elige el producto de cada línea.");
        }
        String nombre = l.producto().nombre();
        if (!l.producto().activo()) {
            throw new IllegalArgumentException(nombre + " no está activo.");
        }
        if (l.presentacion() != null && !l.presentacion().productoId().equals(l.producto().id())) {
            throw new IllegalArgumentException("La presentación no es de " + nombre + ".");
        }
        if (l.cantidadPagada() == null || l.cantidadPagada().signum() <= 0) {
            throw new IllegalArgumentException("La cantidad pagada de " + nombre + " debe ser mayor a cero.");
        }
        if (l.cantidadBonificada() == null || l.cantidadBonificada().signum() < 0) {
            throw new IllegalArgumentException("La cantidad bonificada de " + nombre + " no puede ser negativa.");
        }
        if (l.costoUnitarioCentavos() < 0) {
            throw new IllegalArgumentException("El costo de " + nombre + " no puede ser negativo.");
        }
        if (l.producto().unidad() == Unidad.PZA) {
            BigDecimal base = l.cantidadPagada().add(l.cantidadBonificada()).multiply(l.factor());
            if (base.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException(nombre + " se cuenta por piezas: la cantidad debe dar piezas enteras.");
            }
        }
    }

    public List<Resumen> listar(LocalDate desde, LocalDate hasta, String proveedorId) {
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT_RESUMEN + """
                     WHERE k.fecha >= ? AND k.fecha < ? AND (? IS NULL OR k.proveedor_id = ?)
                     ORDER BY k.fecha DESC""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                ps.setString(3, proveedorId);
                ps.setString(4, proveedorId);
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

    public Detalle detalle(String compraId) {
        return database.con(c -> {
            Resumen resumen;
            String notas;
            try (PreparedStatement ps = c.prepareStatement(SELECT_RESUMEN + " WHERE k.id = ?")) {
                ps.setString(1, compraId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("No existe la compra.");
                    }
                    resumen = resumen(rs);
                    notas = rs.getString("notas");
                }
            }
            List<DetalleLinea> lineas = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.producto_id, p.nombre, p.unidad, pr.nombre AS presentacion, d.factor, d.cantidad_pagada,
                           d.cantidad_bonificada, d.costo_unitario_centavos, d.importe_centavos, d.cantidad_recibida_base,
                           d.costo_base_centavos, d.lote_id, d.lote_bonificado_id
                    FROM compra_detalle d
                    JOIN productos p ON p.id = d.producto_id
                    LEFT JOIN presentaciones pr ON pr.id = d.presentacion_id
                    WHERE d.compra_id = ? ORDER BY d.renglon""")) {
                ps.setString(1, compraId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Unidad unidad = Unidad.valueOf(rs.getString("unidad"));
                        lineas.add(new DetalleLinea(rs.getString("producto_id"), rs.getString("nombre"), unidad,
                                rs.getString("presentacion"), decimal(rs, "factor"), decimal(rs, "cantidad_pagada"),
                                decimal(rs, "cantidad_bonificada"), rs.getLong("costo_unitario_centavos"),
                                rs.getLong("importe_centavos"), decimal(rs, "cantidad_recibida_base"),
                                rs.getLong("costo_base_centavos"), rs.getString("lote_id"),
                                rs.getString("lote_bonificado_id")));
                    }
                }
            }
            return new Detalle(resumen, lineas, notas);
        });
    }

    private static final String SELECT_RESUMEN = """
            SELECT k.id, k.folio, k.fecha, k.proveedor_id, v.nombre AS proveedor, k.documento, k.fecha_documento,
                   k.total_centavos, k.politica_bonificacion, k.notas, u.nombre_completo AS usuario,
                   (SELECT COUNT(*) FROM compra_detalle d WHERE d.compra_id = k.id) AS lineas,
                   (SELECT COUNT(*) FROM compra_detalle d WHERE d.compra_id = k.id AND d.cantidad_bonificada > 0) AS bonificadas
            FROM compras k
            JOIN proveedores v ON v.id = k.proveedor_id
            JOIN usuarios u ON u.id = k.usuario_id
            """;

    private static Resumen resumen(ResultSet rs) throws SQLException {
        String fechaDocumento = rs.getString("fecha_documento");
        return new Resumen(rs.getString("id"), rs.getString("folio"), Tiempo.leer(rs.getString("fecha")),
                rs.getString("proveedor_id"), rs.getString("proveedor"), rs.getString("documento"),
                fechaDocumento == null ? null : LocalDate.parse(fechaDocumento), rs.getLong("total_centavos"),
                rs.getInt("lineas"), rs.getString("usuario"),
                PoliticaBonificacion.valueOf(rs.getString("politica_bonificacion")), rs.getInt("bonificadas") > 0);
    }

    // ================================================================== devoluciones a proveedor

    /**
     * Devuelve mercancía del almacén al proveedor. Sale del lote indicado, si no de los lotes de la
     * compra y si no de los más antiguos. Idempotente por {@code devolucionId}. Devuelve el folio.
     */
    public String devolverAProveedor(String devolucionId, Proveedor proveedor, String compraId,
                                     List<LineaDevolucion> lineas, String motivo, Tratamiento tratamiento,
                                     String evidencia, Usuario quien) {
        Permisos.exigirAdministrador(quien, "devolver mercancía al proveedor");
        String motivoLimpio = Permisos.exigirTexto(motivo, "Escribe el motivo de la devolución.");
        if (proveedor == null) {
            throw new IllegalArgumentException("Elige el proveedor.");
        }
        if (lineas == null || lineas.isEmpty()) {
            throw new IllegalArgumentException("Agrega al menos un producto.");
        }
        for (LineaDevolucion l : lineas) {
            if (l.cantidad() == null || l.cantidad().signum() <= 0) {
                throw new IllegalArgumentException("La cantidad de " + l.producto().nombre() + " debe ser mayor a cero.");
            }
        }
        return database.enTransaccion(c -> {
            Optional<String> yaRegistrada = folioDe(c, "devoluciones_proveedor", devolucionId);
            if (yaRegistrada.isPresent()) {
                return yaRegistrada.get();
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.DEVOLUCION_PROVEEDOR.prefijo(), dispositivoId);
            List<Object[]> salidas = new ArrayList<>(); // {linea, lote, cantidad, costo}
            long total = 0;
            for (LineaDevolucion l : lineas) {
                for (AlmacenService.SalidaLote s : salida(c, l, compraId)) {
                    salidas.add(new Object[]{l, s});
                    total += s.costoCentavos();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO devoluciones_proveedor (id, folio, proveedor_id, compra_id, sucursal_id, motivo, tratamiento,
                                                        evidencia, total_costo_centavos, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, devolucionId);
                ps.setString(2, folio);
                ps.setString(3, proveedor.id());
                ps.setString(4, compraId);
                ps.setString(5, almacenId);
                ps.setString(6, motivoLimpio);
                ps.setString(7, (tratamiento == null ? Tratamiento.POR_DEFINIR : tratamiento).name());
                ps.setString(8, vacio(evidencia));
                ps.setLong(9, total);
                ps.setString(10, quien.id());
                ps.setString(11, Tiempo.ahora());
                ps.executeUpdate();
            }
            int renglon = 1;
            for (Object[] par : salidas) {
                LineaDevolucion l = (LineaDevolucion) par[0];
                AlmacenService.SalidaLote s = (AlmacenService.SalidaLote) par[1];
                inventario.registrar(c, l.producto().id(), almacenId, s.lote().id(), "DEVOLUCION_PROVEEDOR",
                        s.cantidad().negate(), devolucionId, quien.id(), motivoLimpio);
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO devolucion_proveedor_detalle (id, devolucion_id, renglon, producto_id, lote_id,
                                                                  cantidad, costo_centavos)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, devolucionId);
                    ps.setInt(3, renglon++);
                    ps.setString(4, l.producto().id());
                    ps.setString(5, s.lote().id());
                    ps.setBigDecimal(6, s.cantidad());
                    ps.setLong(7, s.costoCentavos());
                    ps.executeUpdate();
                }
            }
            bitacora.registrar(c, AccionBitacora.DEVOLUCION_PROVEEDOR, quien, dispositivoId, "devoluciones_proveedor",
                    devolucionId, "A " + proveedor.nombre() + ": " + lineas.size() + " producto(s), costo "
                            + Dinero.formatear(total) + " · " + (tratamiento == null ? Tratamiento.POR_DEFINIR : tratamiento)
                            .nombre().toLowerCase() + " · " + motivoLimpio, folio);
            return folio;
        });
    }

    /** Lotes de donde sale la devolución: el elegido, los de la compra o los más antiguos del almacén. */
    private List<AlmacenService.SalidaLote> salida(Connection c, LineaDevolucion l, String compraId) throws SQLException {
        List<LoteRepository.Lote> candidatos = lotes.deProducto(c, l.producto().id(), almacenId);
        if (l.loteId() != null) {
            candidatos = candidatos.stream().filter(lote -> lote.id().equals(l.loteId())).toList();
            if (candidatos.isEmpty()) {
                throw new IllegalArgumentException("El lote elegido no es de " + l.producto().nombre() + " en el almacén.");
            }
        } else if (compraId != null) {
            Set<String> deLaCompra = new LinkedHashSet<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT lote_id, lote_bonificado_id FROM compra_detalle WHERE compra_id = ? AND producto_id = ?""")) {
                ps.setString(1, compraId);
                ps.setString(2, l.producto().id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        deLaCompra.add(rs.getString(1));
                        if (rs.getString(2) != null) {
                            deLaCompra.add(rs.getString(2));
                        }
                    }
                }
            }
            if (deLaCompra.isEmpty()) {
                throw new IllegalArgumentException(l.producto().nombre() + " no viene en esa compra.");
            }
            candidatos = candidatos.stream().filter(lote -> deLaCompra.contains(lote.id())).toList();
        }
        List<AlmacenService.SalidaLote> salidas = new ArrayList<>();
        BigDecimal restante = l.cantidad();
        BigDecimal disponible = BigDecimal.ZERO;
        for (LoteRepository.Lote lote : candidatos) {
            if (lote.existencia().signum() <= 0) {
                continue;
            }
            disponible = disponible.add(lote.existencia());
            if (restante.signum() <= 0) {
                continue;
            }
            BigDecimal toma = lote.existencia().min(restante);
            salidas.add(new AlmacenService.SalidaLote(lote, toma, Dinero.importe(lote.costoUnitarioCentavos(), toma)));
            restante = restante.subtract(toma);
        }
        if (restante.signum() > 0) {
            throw new IllegalStateException("No hay suficiente " + l.producto().nombre() + " para devolver: hay "
                    + Cantidades.formatear(l.producto().unidad(), disponible.setScale(3, RoundingMode.HALF_UP)) + ".");
        }
        return salidas;
    }

    public List<DevolucionResumen> devolucionesProveedor(LocalDate desde, LocalDate hasta) {
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.id, d.folio, d.fecha, v.nombre, k.folio, d.motivo, d.tratamiento, d.total_costo_centavos,
                           u.nombre_completo
                    FROM devoluciones_proveedor d
                    JOIN proveedores v ON v.id = d.proveedor_id
                    LEFT JOIN compras k ON k.id = d.compra_id
                    JOIN usuarios u ON u.id = d.usuario_id
                    WHERE d.fecha >= ? AND d.fecha < ? ORDER BY d.fecha DESC""")) {
                ps.setString(1, Tiempo.formatear(desde.atStartOfDay(zona).toInstant()));
                ps.setString(2, Tiempo.formatear(hasta.plusDays(1).atStartOfDay(zona).toInstant()));
                try (ResultSet rs = ps.executeQuery()) {
                    List<DevolucionResumen> lista = new ArrayList<>();
                    while (rs.next()) {
                        lista.add(new DevolucionResumen(rs.getString(1), rs.getString(2), Tiempo.leer(rs.getString(3)),
                                rs.getString(4), rs.getString(5), rs.getString(6), Tratamiento.valueOf(rs.getString(7)),
                                rs.getLong(8), rs.getString(9)));
                    }
                    return lista;
                }
            }
        });
    }

    // ================================================================== utilidades

    static boolean existe(Connection c, String tabla, String id) throws SQLException {
        // tabla es fija en el código, no viene de datos.
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + tabla + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static Optional<String> folioDe(Connection c, String tabla, String id) throws SQLException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Falta el identificador de la operación.");
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT folio FROM " + tabla + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private static boolean proveedorActivo(Connection c, String proveedorId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM proveedores WHERE id = ? AND activo = 1 AND eliminado_en IS NULL")) {
            ps.setString(1, proveedorId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static BigDecimal decimal(ResultSet rs, String columna) throws SQLException {
        BigDecimal valor = BigDecimal.valueOf(rs.getDouble(columna)).setScale(3, RoundingMode.HALF_UP);
        return valor;
    }

    private static String vacio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
