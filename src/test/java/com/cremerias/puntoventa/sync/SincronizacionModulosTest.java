package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.AjusteService;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.CajaService;
import com.cremerias.puntoventa.service.CatalogoService;
import com.cremerias.puntoventa.service.DatosDemo;
import com.cremerias.puntoventa.service.DatosIniciales;
import com.cremerias.puntoventa.service.DevolucionService;
import com.cremerias.puntoventa.service.ReconciliacionService;
import com.cremerias.puntoventa.service.ResultadoAutorizacion;
import com.cremerias.puntoventa.service.ResultadoLogin;
import com.cremerias.puntoventa.service.VentaService;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.admin.PreciosService;
import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escenarios sin conexión con dos cajas de la misma sucursal y una nube en memoria que se porta como
 * Supabase: guarda por llave, las tablas de movimientos son solo alta (reenviar no duplica) y baja lo
 * que cambió después del cursor de cada caja.
 */
class SincronizacionModulosTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path carpeta;

    private Path plantilla;
    private String sucursalId;
    private NubeMemoria nube;

    /** Una caja con su base local y sus servicios. */
    private final class Caja {
        final Database db;
        final String dispositivo;
        final CatalogoService catalogo;
        final CajaService caja;
        final VentaService ventas;
        final Administracion admin;
        final Sesion cajero;
        final Usuario supervisor;
        final Usuario administrador;
        Turno turno;

        Caja(String nombre) throws IOException {
            Path archivo = carpeta.resolve(nombre + ".db");
            Files.copy(plantilla, archivo);
            db = new Database(archivo);
            dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, sucursalId));
            PasswordHasher hasher = new PasswordHasher(4);
            AuthService auth = new AuthService(db, hasher, Clock.systemDefaultZone(), dispositivo);
            catalogo = new CatalogoService(db, sucursalId);
            caja = new CajaService(db, dispositivo);
            ventas = new VentaService(db, dispositivo);
            admin = new Administracion(db, hasher, Clock.systemDefaultZone(), dispositivo);
            cajero = ((ResultadoLogin.Exitoso) auth.iniciarSesion("cajero", "cajero123".toCharArray(),
                    ModoConexion.OFFLINE)).sesion();
            administrador = ((ResultadoLogin.Exitoso) auth.iniciarSesion("admin", "admin123".toCharArray(),
                    ModoConexion.OFFLINE)).sesion().usuario();
            supervisor = ((ResultadoAutorizacion.Autorizado) auth.autorizar("supervisor", "super123".toCharArray(),
                    EnumSet.of(Rol.SUPERVISOR, Rol.ADMINISTRADOR), "prueba")).autorizador();
            turno = caja.abrir(cajero, 0);
        }

        Producto articulo(String nombre) {
            catalogo.recargar();
            return catalogo.buscar(nombre, 20).stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
        }

        Ticket vender(String nombre, String cantidad) {
            return ventas.registrar(List.of(new VentaService.Renglon(articulo(nombre), new BigDecimal(cantidad))),
                    List.of(new Pago(MetodoPago.EFECTIVO, 10_000_000, null)), cajero, turno);
        }

        void sincronizar() throws Exception {
            new Subida(db).subir(nube);
            new Bajada(db).bajar(nube);
        }

        long contar(String sql) {
            return db.con(c -> {
                ResultSet rs = c.createStatement().executeQuery(sql);
                rs.next();
                return rs.getLong(1);
            });
        }

        BigDecimal existencia(String producto) {
            String productoId = admin.productos().listar().stream().filter(p -> p.nombre().equals(producto))
                    .findFirst().orElseThrow().id();
            return admin.almacen().existencia(productoId, sucursalId);
        }
    }

    @BeforeEach
    void plantilla() {
        plantilla = carpeta.resolve("plantilla.db");
        Database db = new Database(plantilla);
        new Migraciones(db).aplicar();
        sucursalId = new DatosIniciales(db, new PasswordHasher(4)).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(sucursalId);
        // Lo que ya existe se da por sincronizado (las dos cajas parten del mismo catálogo).
        db.con(c -> {
            c.createStatement().executeUpdate("UPDATE sync_outbox SET enviado_en = '2026-01-01T00:00:00.000Z'");
            c.createStatement().execute("PRAGMA wal_checkpoint(TRUNCATE)");
            return null;
        });
        nube = new NubeMemoria();
    }

    @Test
    void dosCajasSinConexionVendenLoMismoYAlSincronizarSeVeElNegativoParaReconciliar() throws Exception {
        Caja a = new Caja("a");
        Caja b = new Caja("b");
        // Hay 40 refrescos; cada caja, sin conexión, vende 30.
        a.vender("Refresco de cola 600 ml", "30");
        b.vender("Refresco de cola 600 ml", "30");

        a.sincronizar();
        b.sincronizar();
        a.sincronizar();

        for (Caja caja : List.of(a, b)) {
            assertEquals(2, caja.contar("SELECT COUNT(*) FROM ventas"));
            assertEquals(0, new BigDecimal("-20").compareTo(caja.existencia("Refresco de cola 600 ml")));
        }
        assertEquals(2, nube.filas("ventas"));
        // La reconciliación lo muestra al administrador; no se corrige sola.
        var negativos = new ReconciliacionService(a.db, a.dispositivo).negativos(a.administrador);
        assertEquals(1, negativos.size());
        assertEquals("Refresco de cola 600 ml", negativos.getFirst().producto());
        assertTrue(!negativos.getFirst().soloLotes());
        assertThrows(IllegalStateException.class, () -> new ReconciliacionService(a.db, a.dispositivo)
                .reacomodarLotes(sucursalId, negativos.getFirst().productoId(), a.administrador));
    }

    @Test
    void siSoloQuedanLotesEnNegativoSeReacomodanSinCambiarElTotal() throws Exception {
        Caja a = new Caja("a");
        Caja b = new Caja("b");
        var leche = a.admin.productos().listar().stream().filter(p -> p.nombre().equals("Leche entera 1 L"))
                .findFirst().orElseThrow();
        Map<String, Long> precios = new HashMap<>();
        leche.presentaciones().forEach(p -> precios.put(p.id(), p.principal() ? 2900L : 33000L));
        Sucursal matriz = a.admin.sucursales().listar().getFirst();
        a.admin.surtidos().registrar(matriz, SurtidoService.FormaPago.EFECTIVO,
                List.of(new SurtidoService.Linea(leche, new BigDecimal("12"), precios)), null, a.administrador, null);
        a.sincronizar();
        b.sincronizar();
        // Las dos cajas, sin conexión, toman del mismo lote viejo (48 piezas): 40 + 10.
        a.vender("Leche entera 1 L", "40");
        b.vender("Leche entera 1 L", "10");
        a.sincronizar();
        b.sincronizar();
        a.sincronizar();

        ReconciliacionService reconciliacion = new ReconciliacionService(a.db, a.dispositivo);
        var negativos = reconciliacion.negativos(a.administrador);
        assertEquals(1, negativos.size());
        assertTrue(negativos.getFirst().soloLotes());
        assertEquals(0, new BigDecimal("10").compareTo(a.existencia("Leche entera 1 L")));

        assertTrue(reconciliacion.reacomodarLotes(sucursalId, leche.id(), a.administrador).startsWith("AJU-"));
        assertTrue(reconciliacion.negativos(a.administrador).isEmpty());
        assertEquals(0, new BigDecimal("10").compareTo(a.existencia("Leche entera 1 L")));
        // El reacomodo también viaja a la otra caja.
        a.sincronizar();
        b.sincronizar();
        assertTrue(new ReconciliacionService(b.db, b.dispositivo).negativos(b.administrador).isEmpty());
    }

    @Test
    void siSePierdeLaRespuestaDeLaNubeElReintentoNoDuplicaNada() throws Exception {
        Caja a = new Caja("a");
        Caja b = new Caja("b");
        Ticket venta = a.vender("Leche entera 1 L", "2");
        String devolucion = Ids.nuevo();
        new DevolucionService(a.db, a.dispositivo).registrar(devolucion, venta.ventaId(), List.of(
                        new DevolucionService.LineaDevolucion(new DevolucionService(a.db, a.dispositivo).venta(venta.ventaId())
                                .renglones().getFirst().ventaDetalleId(), BigDecimal.ONE,
                                DevolucionService.EstadoProducto.VENDIBLE)), DevolucionService.Tipo.DEVOLUCION,
                DevolucionService.Metodo.EFECTIVO, "No la quiso", a.turno, a.cajero.usuario(), a.supervisor);
        Sucursal matriz = a.admin.sucursales().listar().getFirst();
        new AjusteService(a.db, a.dispositivo).registrar(Ids.nuevo(), matriz,
                a.admin.productos().listar().stream().filter(p -> p.nombre().equals("Bolillo")).findFirst().orElseThrow(),
                AjusteService.Motivo.DANO, new BigDecimal("-3"), null, null, null, a.supervisor);
        long encolados = a.contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL");

        // La nube guarda el lote pero la respuesta no llega: la caja no sabe que subió.
        nube.perderRespuesta = true;
        assertThrows(IOException.class, () -> new Subida(a.db).subir(nube));
        assertEquals(encolados, a.contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL AND intentos = 0"));

        // Al volver la conexión se reenvía todo: la nube no duplica (mismos ids, tablas solo alta).
        a.sincronizar();
        assertEquals(0, a.contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL"));
        assertEquals(1, nube.filas("ventas"));
        assertEquals(1, nube.filas("devoluciones_venta"));
        assertEquals(1, nube.filas("ajustes_inventario"));
        long movimientos = nube.filas("movimientos_inventario");

        // La otra caja recibe una sola vez cada cosa, y volver a sincronizar no cambia nada.
        b.sincronizar();
        b.sincronizar();
        assertEquals(1, b.contar("SELECT COUNT(*) FROM ventas"));
        assertEquals(1, b.contar("SELECT COUNT(*) FROM devoluciones_venta"));
        assertEquals(1, b.contar("SELECT COUNT(*) FROM devolucion_venta_detalle"));
        assertEquals(1, b.contar("SELECT COUNT(*) FROM ajustes_inventario"));
        assertEquals(movimientos, nube.filas("movimientos_inventario"));
        assertEquals(0, a.existencia("Leche entera 1 L").compareTo(b.existencia("Leche entera 1 L")));
        assertEquals(0, a.existencia("Bolillo").compareTo(b.existencia("Bolillo")));
        assertEquals(0, b.contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL"));
    }

    @Test
    void elCargoACreditoDeUnSurtidoLlegaUnaVezAunqueSeReintente() throws Exception {
        Caja almacen = new Caja("almacen");
        Caja sucursal = new Caja("sucursal");
        var queso = almacen.admin.productos().listar().stream().filter(p -> p.nombre().equals("Queso Oaxaca"))
                .findFirst().orElseThrow();
        Sucursal matriz = almacen.admin.sucursales().listar().getFirst();
        almacen.admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO, List.of(new SurtidoService.Linea(queso,
                new BigDecimal("5"), Map.of(queso.principal().id(), 21000L))), null, almacen.administrador, null);

        nube.perderRespuesta = true;
        assertThrows(IOException.class, () -> new Subida(almacen.db).subir(nube));
        almacen.sincronizar();
        almacen.sincronizar();
        sucursal.sincronizar();

        assertEquals(1, nube.filas("movimientos_credito"));
        assertEquals(almacen.admin.credito().saldo(matriz.id()), sucursal.admin.credito().saldo(matriz.id()));
        assertEquals(66500, sucursal.admin.credito().saldo(matriz.id()));
    }

    /**
     * Surtido pendiente de recepción entre equipos: la bodega surte, la sucursal confirma desde su caja
     * con un faltante, la bodega lo ve al sincronizar (no antes) y su aclaración llega a la sucursal una vez.
     */
    @Test
    void unSurtidoPorRecibirSeConfirmaEnLaSucursalYLaBodegaVeLaDiferencia() throws Exception {
        Caja almacen = new Caja("almacen");
        Caja sucursal = new Caja("sucursal");
        var queso = almacen.admin.productos().listar().stream().filter(p -> p.nombre().equals("Queso Oaxaca"))
                .findFirst().orElseThrow();
        Sucursal matriz = almacen.admin.sucursales().listar().getFirst();
        String surtidoId = almacen.admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("5"), Map.of(queso.principal().id(), 21000L))),
                null, almacen.administrador, null).resumen().id();
        almacen.sincronizar();
        sucursal.sincronizar();

        RecepcionSurtidoService enSucursal = sucursal.admin.recepciones();
        assertEquals(1, enSucursal.pendientes(matriz.id(), sucursal.supervisor).size());
        String renglon = enSucursal.porRecibir(surtidoId).lineas().getFirst().surtidoDetalleId();
        enSucursal.recibir(Ids.nuevo(), surtidoId, List.of(new RecepcionSurtidoService.LineaRecibida(renglon,
                new BigDecimal("4.5"))), null, sucursal.supervisor);
        assertEquals(RecepcionSurtidoService.Estado.CON_DIFERENCIA, enSucursal.estado(surtidoId));
        // Sin sincronizar, la bodega todavía lo ve enviado: no hay consistencia inmediata entre equipos.
        assertEquals(RecepcionSurtidoService.Estado.ENVIADO, almacen.admin.recepciones().estado(surtidoId));

        sucursal.sincronizar();
        almacen.sincronizar();
        assertEquals(RecepcionSurtidoService.Estado.CON_DIFERENCIA, almacen.admin.recepciones().estado(surtidoId));
        assertEquals(0, almacen.existencia("Queso Oaxaca").compareTo(sucursal.existencia("Queso Oaxaca")));
        RecepcionSurtidoService.Diferencia dif = almacen.admin.recepciones().diferencias(true, almacen.administrador)
                .getFirst();
        almacen.admin.recepciones().aclarar(Ids.nuevo(), dif, RecepcionSurtidoService.Decision.REINGRESO_ALMACEN, true,
                null, almacen.administrador);

        almacen.sincronizar();
        sucursal.sincronizar();
        sucursal.sincronizar();
        assertEquals(RecepcionSurtidoService.Estado.ACLARADO, enSucursal.estado(surtidoId));
        assertEquals(almacen.admin.credito().saldo(matriz.id()), sucursal.admin.credito().saldo(matriz.id()));
        assertEquals(1, nube.filas("recepciones_surtido"));
        assertEquals(1, nube.filas("recepcion_surtido_detalle"));
        assertEquals(1, nube.filas("aclaraciones_surtido"));
        assertEquals(2, nube.filas("movimientos_credito")); // el cargo y su nota de crédito, sin repetir
    }

    /** Devolución sin conexión en una caja de un ticket cobrado en otra: llega una vez y el inventario cuadra. */
    @Test
    void unaDevolucionSinConexionDeUnTicketDeOtraCajaLlegaUnaVez() throws Exception {
        Caja a = new Caja("a");
        Caja b = new Caja("b");
        Ticket venta = a.vender("Refresco de cola 600 ml", "5");
        a.sincronizar();
        b.sincronizar();

        // La caja B, ya sin conexión, recibe la devolución de 2 piezas del ticket de A (reembolso a la tarjeta).
        DevolucionService enB = new DevolucionService(b.db, b.dispositivo);
        String renglon = enB.buscarVenta(venta.folio(), sucursalId).orElseThrow().renglones().getFirst().ventaDetalleId();
        enB.registrar(Ids.nuevo(), venta.ventaId(), List.of(new DevolucionService.LineaDevolucion(renglon,
                        new BigDecimal("2"), DevolucionService.EstadoProducto.VENDIBLE)), DevolucionService.Tipo.DEVOLUCION,
                DevolucionService.Metodo.TARJETA, "No era el sabor", b.turno, b.cajero.usuario(), b.supervisor);
        assertTrue(a.existencia("Refresco de cola 600 ml").compareTo(b.existencia("Refresco de cola 600 ml")) < 0);

        b.sincronizar();
        b.sincronizar();
        a.sincronizar();
        assertEquals(1, nube.filas("devoluciones_venta"));
        assertEquals(1, a.contar("SELECT COUNT(*) FROM devoluciones_venta"));
        assertEquals(1, a.contar("SELECT COUNT(*) FROM devolucion_venta_detalle"));
        assertEquals(0, a.existencia("Refresco de cola 600 ml").compareTo(b.existencia("Refresco de cola 600 ml")));
        // En A ya cuenta lo devuelto: no se puede devolver otra vez más de lo que queda del ticket.
        assertEquals(new BigDecimal("3"), new DevolucionService(a.db, a.dispositivo).venta(venta.ventaId()).renglones()
                .getFirst().disponible().stripTrailingZeros());
    }

    @Test
    void unaCajaDesconectadaVendeConLasEscalasQueConoceYLuegoTomaLasNuevas() throws Exception {
        Caja admin = new Caja("admin");
        Caja desconectada = new Caja("desconectada");
        String refresco = admin.admin.productos().listar().stream().filter(p -> p.nombre().equals("Refresco de cola 600 ml"))
                .findFirst().orElseThrow().id();
        admin.admin.precios().guardarEscala(new PreciosService.DatosEscala(refresco, null, null, Escala.Nivel.MAYOREO,
                new BigDecimal("6"), Escala.Tipo.DESCUENTO_PCT, BigDecimal.TEN, null, LocalDate.now().minusDays(1), null),
                admin.administrador);
        admin.sincronizar();

        // Sin conexión la otra caja todavía cobra menudeo; ese ticket ya no cambia.
        Ticket antes = desconectada.vender("Refresco de cola 600 ml", "6");
        assertEquals(12000, antes.totalCentavos());
        desconectada.sincronizar();
        Ticket despues = desconectada.vender("Refresco de cola 600 ml", "6");
        assertEquals(10800, despues.totalCentavos());
        assertEquals(12000, desconectada.ventas.ticket(antes.ventaId()).totalCentavos());
    }

    /**
     * Nube en memoria con el comportamiento de {@code subir_cambios} y {@code bajar_cambios}: por llave,
     * solo alta en las tablas sin permiso de cambio, {@code sincronizado_en} lo pone la nube.
     */
    private static final class NubeMemoria implements Nube {

        /** Tablas con permiso de UPDATE en la nube (las demás son solo alta). */
        private static final Set<String> CON_CAMBIOS = Set.of("sucursales", "dispositivo", "usuarios", "sesiones",
                "categorias", "productos", "presentaciones", "turnos_caja", "ventas", "limites_sucursal",
                "ordenes_reabastecimiento", "reglas_comision", "metas_sucursal", "horarios", "bloqueos_acceso",
                "configuracion_general", "alertas_sucursal", "gastos", "nomina_semanal", "reglas_precio",
                "escalas_precio", "proveedores", "devoluciones_venta");

        private final Map<String, LinkedHashMap<String, ObjectNode>> tablas = new HashMap<>();
        private Instant reloj = Instant.now().minusSeconds(3600);
        boolean perderRespuesta;

        long filas(String tabla) {
            return tablas.getOrDefault(tabla, new LinkedHashMap<>()).size();
        }

        @Override
        public JsonNode rpc(String funcion, ObjectNode parametros) throws IOException, ErrorNube {
            return switch (funcion) {
                case "subir_cambios" -> {
                    for (JsonNode cambio : parametros.get("p_cambios")) {
                        String tabla = cambio.path("tabla").asText();
                        ObjectNode fila = ((ObjectNode) cambio.get("fila")).deepCopy();
                        String llave = fila.path(TablasNube.llave(tabla)).asText();
                        LinkedHashMap<String, ObjectNode> filas = tablas.computeIfAbsent(tabla, t -> new LinkedHashMap<>());
                        if (filas.containsKey(llave) && !CON_CAMBIOS.contains(tabla)) {
                            continue; // on conflict do nothing
                        }
                        reloj = reloj.plusMillis(1);
                        fila.put("sincronizado_en", OffsetDateTime.ofInstant(reloj, ZoneOffset.UTC).toString());
                        filas.put(llave, fila);
                    }
                    if (perderRespuesta) {
                        perderRespuesta = false;
                        throw new IOException("Se cortó la conexión antes de la respuesta");
                    }
                    yield IntNode.valueOf(parametros.get("p_cambios").size());
                }
                case "bajar_cambios" -> bajar(parametros);
                default -> throw new ErrorNube(404, "No existe " + funcion);
            };
        }

        private JsonNode bajar(ObjectNode parametros) {
            int limite = parametros.get("p_limite").asInt();
            ObjectNode respuesta = JSON.createObjectNode();
            parametros.get("p_cursores").fields().forEachRemaining(entrada -> {
                String tabla = entrada.getKey();
                String llave = TablasNube.llave(tabla);
                JsonNode cursor = entrada.getValue();
                List<ObjectNode> lista = new ArrayList<>(tablas.getOrDefault(tabla, new LinkedHashMap<>()).values());
                lista.sort(Comparator.comparing((ObjectNode f) -> OffsetDateTime.parse(f.path("sincronizado_en").asText()))
                        .thenComparing(f -> f.path(llave).asText()));
                ArrayNode filas = JSON.createArrayNode();
                lista.stream().filter(f -> !cursor.has("desde") || despues(f, llave, cursor)).limit(limite)
                        .forEach(filas::add);
                respuesta.set(tabla, filas);
            });
            return respuesta;
        }

        private static boolean despues(JsonNode fila, String llave, JsonNode cursor) {
            OffsetDateTime f = OffsetDateTime.parse(fila.path("sincronizado_en").asText());
            OffsetDateTime d = OffsetDateTime.parse(cursor.path("desde").asText());
            int c = f.compareTo(d);
            return c > 0 || (c == 0 && fila.path(llave).asText().compareTo(cursor.path("llave").asText("")) > 0);
        }

        @Override
        public ArrayNode consultar(String consulta) {
            return JSON.createArrayNode();
        }
    }
}
