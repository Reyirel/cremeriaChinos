package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.HorarioDia;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.repository.SyncOutboxRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.CajaService;
import com.cremerias.puntoventa.service.CatalogoService;
import com.cremerias.puntoventa.service.IndicadoresService;
import com.cremerias.puntoventa.service.ResultadoLogin;
import com.cremerias.puntoventa.service.VentaService;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.admin.ComisionService;
import com.cremerias.puntoventa.service.admin.LimitesService;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.util.CodigoBarras;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba de punta a punta sobre la base real de la caja y el Supabase de verdad (no corre con las
 * demás pruebas). Con la app cerrada:
 *
 * <pre>
 * ./mvnw test -Dtest=PruebaOfflineYModulosTest -Dprueba.bd=$HOME/.cremerias-pos/cremerias.db \
 *     -Dprueba.config=$HOME/.cremerias-pos/supabase.properties -Dprueba.senales=/ruta/carpeta
 * </pre>
 *
 * <ol>
 *   <li>Sin internet (el chequeo de conexión del sincronizador no alcanza nada): da de alta 3
 *       productos. Escribe {@code 1-offline.txt} y espera el archivo {@code conectar}.</li>
 *   <li>Vuelve el internet con el mismo sincronizador corriendo: espera a que suba todo. Escribe
 *       {@code 2-en-linea.txt} y espera el archivo {@code continuar}.</li>
 *   <li>Captura 3 registros por módulo con los mismos servicios que las pantallas, espera a que se
 *       suban y revisa que una caja nueva los baje de la nube.</li>
 * </ol>
 */
class PruebaOfflineYModulosTest {

    private static final String PASSWORD = "prueba123";
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private Path senales;
    private Database db;
    private Administracion adm;
    private Usuario luis;
    private String caja;

    @Test
    @EnabledIfSystemProperty(named = "prueba.bd", matches = ".+")
    void offlineYModulos() throws Exception {
        Path archivo = Path.of(System.getProperty("prueba.bd"));
        ConfigNube config = ConfigNube.cargar(Path.of(System.getProperty("prueba.config"))).orElseThrow();
        senales = Path.of(System.getProperty("prueba.senales"));
        Files.createDirectories(senales);
        assertTrue(Files.exists(archivo), "No existe " + archivo);

        db = new Database(archivo);
        Path reserva = archivo.resolveSibling("reserva")
                .resolve("cremerias_antes_de_prueba_modulos_" + LocalDateTime.now().format(MARCA) + ".db");
        Files.createDirectories(reserva.getParent());
        db.con(c -> {
            try (var st = c.createStatement()) {
                st.execute("VACUUM INTO '" + reserva.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        avisar("Respaldo previo: " + reserva);
        new Migraciones(db).aplicar();
        caja = db.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, null));
        PasswordHasher hasher = new PasswordHasher();
        adm = new Administracion(db, hasher, Clock.systemDefaultZone(), caja);
        luis = usuario("luis");
        avisar("Caja " + caja + ", administrador " + luis.nombreCompleto());

        Platform.setImplicitExit(false);
        Platform.startup(() -> {
        });

        // ---------------------------------------------------------------- 1. Sin internet
        cortarInternet(true);
        Sincronizador sync = new Sincronizador(db, caja, new ClienteSupabase(config), false);
        sync.iniciar();
        esperar(Duration.ofSeconds(30), () -> fx(() -> sync.detalleProperty().get()).startsWith("Sin internet"));
        avisar("Sincronizador sin internet: " + fx(() -> sync.estadoProperty().get()) + " · "
                + fx(() -> sync.detalleProperty().get()));

        List<String> productos = altaDeProductos();
        sync.revisarAhora();
        Thread.sleep(4_000);
        EstadoNube estadoOffline = fx(() -> sync.estadoProperty().get());
        int pendientesOffline = fx(() -> sync.pendientesProperty().get());
        int productosEnCola = contar("SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'productos' AND enviado_en IS NULL"
                + " AND registro_id IN (" + lista(productos) + ")");
        assertEquals(EstadoNube.SIN_CONEXION, estadoOffline);
        assertEquals(3, productosEnCola);
        escribir("1-offline.txt", "estado=" + estadoOffline + "\npendientes=" + pendientesOffline
                + "\nproductos_en_cola=" + productosEnCola + "\nproductos=" + String.join(",", productos)
                + "\ndetalle=" + fx(() -> sync.detalleProperty().get()) + "\n");
        avisar("SIN INTERNET: 3 productos guardados en la caja, " + pendientesOffline
                + " cambios en la cola. Esperando el archivo 'conectar'…");
        esperarArchivo("conectar");

        // ---------------------------------------------------------------- 2. Vuelve el internet
        long inicio = System.nanoTime();
        cortarInternet(false);
        avisar("Internet de vuelta (sin reiniciar el sincronizador)");
        esperar(Duration.ofSeconds(180), () -> colaVacia() && fx(() -> sync.estadoProperty().get()) == EstadoNube.AL_DIA);
        double segundos = (System.nanoTime() - inicio) / 1e9;
        int productosSinSubir = contar("SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'productos'"
                + " AND (enviado_en IS NULL OR enviado_en = 'ENVIANDO') AND registro_id IN (" + lista(productos) + ")");
        JsonNode enLaNube = new ClienteSupabase(config).consultar(
                "productos?select=id,nombre,sincronizado_en&id=in.(" + String.join(",", productos) + ")");
        JsonNode presentacionesNube = new ClienteSupabase(config).consultar(
                "presentaciones?select=id,nombre&producto_id=in.(" + String.join(",", productos) + ")");
        escribir("2-en-linea.txt", "segundos=" + String.format("%.1f", segundos) + "\nestado="
                + fx(() -> sync.estadoProperty().get()) + "\npendientes=" + fx(() -> sync.pendientesProperty().get())
                + "\nrechazados=" + db.con(new SyncOutboxRepository()::contarRechazados)
                + "\nproductos_sin_subir=" + productosSinSubir + "\nproductos_en_nube=" + enLaNube.size()
                + "\npresentaciones_en_nube=" + presentacionesNube.size() + "\nnube=" + enLaNube + "\ndetalle="
                + fx(() -> sync.detalleProperty().get()) + "\n");
        assertEquals(3, enLaNube.size());
        avisar("EN LÍNEA: se subió todo en " + String.format("%.1f", segundos) + " s; " + enLaNube.size()
                + " productos y " + presentacionesNube.size() + " presentaciones ya están en la nube."
                + " Esperando el archivo 'continuar'…");
        esperarArchivo("continuar");

        // ---------------------------------------------------------------- 3. Módulos
        Map<String, Set<String>> antes = ids(db);
        modulos();
        sync.revisarAhora();
        esperar(Duration.ofSeconds(300), () -> colaVacia() && fx(() -> sync.estadoProperty().get()) != EstadoNube.SINCRONIZANDO);
        int rechazados = contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL AND intentos > 0");
        avisar("Módulos subidos: estado " + fx(() -> sync.estadoProperty().get()) + ", rechazados " + rechazados);
        sync.close();

        // Una caja nueva (otra computadora) baja todo de la nube: lo capturado debe llegarle.
        Map<String, Set<String>> nuevos = diferencia(antes, ids(db));
        Path temporal = Files.createTempDirectory("caja-nueva");
        Database otra = new Database(temporal.resolve("nueva.db"));
        new Migraciones(otra).aplicar();
        assertTrue(CajaNueva.prepararDesdeNube(otra, new ClienteSupabase(config)));
        Map<String, Set<String>> enOtra = ids(otra);
        StringBuilder reporte = new StringBuilder("tabla|nuevos_en_esta_caja|llegaron_a_caja_nueva\n");
        int faltan = 0;
        for (var e : nuevos.entrySet()) {
            Set<String> llegaron = new HashSet<>(e.getValue());
            llegaron.retainAll(enOtra.getOrDefault(e.getKey(), Set.of()));
            faltan += e.getValue().size() - llegaron.size();
            reporte.append(e.getKey()).append('|').append(e.getValue().size()).append('|').append(llegaron.size())
                    .append('\n');
        }
        reporte.append("ventas_total_local=").append(sumar(db, "SELECT COALESCE(SUM(total_centavos),0) FROM ventas"
                + " WHERE id IN (" + lista(nuevos.getOrDefault("ventas", Set.of())) + ")")).append('\n');
        reporte.append("ventas_total_caja_nueva=").append(sumar(otra, "SELECT COALESCE(SUM(total_centavos),0) FROM ventas"
                + " WHERE id IN (" + lista(nuevos.getOrDefault("ventas", Set.of())) + ")")).append('\n');
        reporte.append("rechazados=").append(rechazados).append("\nfaltan=").append(faltan).append('\n');
        escribir("3-modulos.txt", reporte.toString());
        avisar("Caja nueva: faltan " + faltan + " registros de los capturados");
        Platform.exit();
        assertEquals(0, rechazados);
        assertEquals(0, faltan);
    }

    /** Ensayo sin nube sobre una copia: {@code -Dprueba.ensayo=/ruta/copia.db -Dprueba.senales=/ruta}. */
    @Test
    @EnabledIfSystemProperty(named = "prueba.ensayo", matches = ".+")
    void ensayoSinNube() throws Exception {
        senales = Path.of(System.getProperty("prueba.senales"));
        Files.createDirectories(senales);
        db = new Database(Path.of(System.getProperty("prueba.ensayo")));
        new Migraciones(db).aplicar();
        caja = db.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, null));
        adm = new Administracion(db, new PasswordHasher(), Clock.systemDefaultZone(), caja);
        luis = usuario("luis");
        Map<String, Set<String>> antes = ids(db);
        altaDeProductos();
        modulos();
        diferencia(antes, ids(db)).forEach((tabla, nuevos) -> avisar("ensayo " + tabla + ": " + nuevos.size()));
    }

    // =====================================================================
    // 1. Productos (sin internet)
    // =====================================================================

    private List<String> altaDeProductos() {
        List<String> ids = new ArrayList<>();
        ids.add(producto(new ProductoAdminService.Datos(null, "Queso Oaxaca", "Quesos", null, "101", "INV-Q01", Unidad.KG,
                true, Disponibilidad.REGULAR, null, List.of(
                        pres("Kilo", true, "1", null, true),
                        pres("Bola 1 kg", false, "1", "750123400001", false)),
                List.of(), new BigDecimal("1000"), new BigDecimal("30"), null)));
        ids.add(producto(new ProductoAdminService.Datos(null, "Crema ácida 450 ml", "Cremas y lácteos", null, null, "INV-C12",
                Unidad.PZA, false, Disponibilidad.REGULAR, null, List.of(
                        pres("Pieza", false, "1", "750123400002", true),
                        pres("Caja 12 pzas", false, "12", "750123400003", false)),
                List.of(), new BigDecimal("460"), BigDecimal.ZERO, null)));
        ids.add(producto(new ProductoAdminService.Datos(null, "Jamón de pierna", "Carnes frías", null, "301", "INV-CF01",
                Unidad.KG, true, Disponibilidad.REGULAR, null, List.of(
                        pres("Kilo", true, "1", null, true),
                        pres("Paquete 250 g", false, "0.25", "750123400004", false)),
                List.of(), new BigDecimal("1000"), new BigDecimal("35"),
                new AvisoSinVenta(3, AvisoSinVenta.Unidad.DIAS, true, true, false))));
        return ids;
    }

    private String producto(ProductoAdminService.Datos d) {
        String folio = adm.productos().guardar(d, luis);
        String id = catalogo(d.nombre()).id();
        avisar("Producto " + d.nombre() + " (" + folio + ")");
        return id;
    }

    private static Presentacion pres(String nombre, boolean granel, String factor, String ean12, boolean principal) {
        return new Presentacion(null, null, nombre, granel, new BigDecimal(factor),
                ean12 == null ? null : CodigoBarras.completarEan13(ean12), principal, true);
    }

    // =====================================================================
    // 3. Tres registros por módulo
    // =====================================================================

    private void modulos() {
        ProductoCatalogo oaxaca = catalogo("Queso Oaxaca");
        ProductoCatalogo crema = catalogo("Crema ácida 450 ml");
        ProductoCatalogo jamon = catalogo("Jamón de pierna");

        // Sucursales
        adm.sucursales().guardar(null, "NORTE", "Cremería Norte", "Blvd. Bernardo Quintana 845, Col. Arboledas",
                "442 245 7788", luis);
        adm.sucursales().guardar(null, "MERCADO", "Cremería Mercado Hidalgo", "Mercado Hidalgo, locales 32 y 33",
                "442 214 0921", luis);
        adm.sucursales().guardar(null, "SUR", "Cremería Plaza Sur", "Plaza Sur, local 14", "442 198 3310", luis);
        Sucursal norte = sucursal("NORTE");
        Sucursal mercado = sucursal("MERCADO");
        Sucursal sur = sucursal("SUR");
        avisar("Sucursales: Norte, Mercado Hidalgo y Plaza Sur");

        // Avisos por sucursal (días sin venta) y metas del mes (Indicadores)
        adm.alertas().guardarDiasSinVenta(norte, 15, luis);
        adm.alertas().guardarDiasSinVenta(mercado, 10, luis);
        adm.alertas().guardarDiasSinVenta(sur, 7, luis);
        IndicadoresService indicadores = new IndicadoresService(db, caja, adm.gastos(), adm.nomina(), adm.credito());
        int anio = java.time.LocalDate.now().getYear();
        int mes = java.time.LocalDate.now().getMonthValue();
        indicadores.guardarMeta(luis, norte.id(), anio, mes, 4_500_000, null, null, null);
        indicadores.guardarMeta(luis, mercado.id(), anio, mes, 3_800_000, null, null, null);
        indicadores.guardarMeta(luis, sur.id(), anio, mes, 3_000_000, null, null, null);
        avisar("Avisos de días sin venta y metas del mes");

        // Usuarios y horarios. Las horas se acomodan a la hora en que corre la prueba: Daniela llega
        // tarde, Roberto entra antes de su hora y Carlos descansa hoy.
        LocalTime ahora = LocalTime.now();
        LocalTime tarde = ahora.minusMinutes(15).truncatedTo(ChronoUnit.HOURS);
        LocalTime temprano = ahora.plusMinutes(75).truncatedTo(ChronoUnit.HOURS);
        adm.usuarios().crear("Roberto Sánchez Medina", "rsanchez", PASSWORD.toCharArray(), Rol.SUPERVISOR, norte.id(), luis);
        adm.usuarios().crear("Daniela Cruz Ortega", "dcruz", PASSWORD.toCharArray(), Rol.CAJERO, norte.id(), luis);
        adm.usuarios().crear("Carlos Jiménez Navarro", "cjimenez", PASSWORD.toCharArray(), Rol.CAJERO, mercado.id(), luis);
        Usuario roberto = usuario("rsanchez");
        Usuario daniela = usuario("dcruz");
        Usuario carlos = usuario("cjimenez");
        AuthService auth = new AuthService(db, new PasswordHasher(), Clock.systemDefaultZone(), caja);
        auth.accesos().guardarHorario(roberto, semana(false, temprano, 9, 5, 60), luis);
        auth.accesos().guardarHorario(daniela, semana(false, tarde, 8, 4, 30), luis);
        auth.accesos().guardarHorario(carlos, semana(true, temprano, 8, 4, 30), luis);
        avisar("Usuarios: Roberto (supervisor Norte, entra " + temprano + "), Daniela (cajera Norte, entra " + tarde
                + "), Carlos (cajero Mercado, descansa hoy)");

        // Reglas de comisión
        regla("Venta diaria de $3,000", ComisionService.Tipo.META_TOTAL, ComisionService.Medida.UNIDADES, null, null,
                300_000, 3_000, ComisionService.Periodo.DIARIO, null);
        regla("Queso Oaxaca: 10 kg netos a la semana", ComisionService.Tipo.META_CANTIDAD,
                ComisionService.Medida.GRAMOS, oaxaca, new BigDecimal("10000"), 0, 10_000,
                ComisionService.Periodo.SEMANAL, null);
        regla("Crema 450 ml en el Norte: por cada 10 piezas", ComisionService.Tipo.POR_CANTIDAD,
                ComisionService.Medida.UNIDADES, crema, new BigDecimal("10"), 0, 300, ComisionService.Periodo.SEMANAL,
                norte.id());
        avisar("Reglas de comisión");

        // Inventario del almacén: 3 entradas
        adm.almacen().reabastecer(oaxaca, new BigDecimal("40.000"), 13_500, "Factura F-23817 · Quesería La Esperanza", luis);
        adm.almacen().reabastecer(crema, new BigDecimal("120"), 2_900, "Factura F-55102 · Lácteos Los Altos", luis);
        adm.almacen().reabastecer(jamon, new BigDecimal("30.000"), 11_000, "Factura F-40966 · Embutidos del Norte", luis);
        oaxaca = catalogo("Queso Oaxaca");
        crema = catalogo("Crema ácida 450 ml");
        jamon = catalogo("Jamón de pierna");
        avisar("Entradas al almacén");

        // Surtidos: dos a crédito y uno en efectivo
        Map<String, Long> pOaxaca = precios(oaxaca, 19_000, 18_500);
        Map<String, Long> pCrema = precios(crema, 4_200, 48_000);
        Map<String, Long> pJamon = precios(jamon, 16_000, 4_200);
        adm.surtidos().registrar(norte, SurtidoService.FormaPago.CREDITO, List.of(
                linea(oaxaca, "8", pOaxaca), linea(crema, "36", pCrema), linea(jamon, "6", pJamon)),
                "Surtido de apertura", luis, null);
        adm.surtidos().registrar(mercado, SurtidoService.FormaPago.CREDITO, List.of(
                linea(oaxaca, "6", pOaxaca), linea(crema, "24", pCrema), linea(jamon, "2", pJamon)),
                "Surtido de apertura", luis, null);
        adm.surtidos().registrar(sur, SurtidoService.FormaPago.EFECTIVO, List.of(
                linea(oaxaca, "5", pOaxaca), linea(crema, "24", pCrema), linea(jamon, "4", pJamon)),
                "Surtido de apertura", luis, null);
        avisar("Surtidos a Norte (crédito), Mercado (crédito) y Plaza Sur (efectivo)");

        // Mínimos y máximos: el Mercado (jamón) y Plaza Sur (queso) quedan en su mínimo → órdenes
        LimitesService.Guardado lNorte = adm.limites().guardar(norte, List.of(
                limite(oaxaca, "3", "12"), limite(crema, "30", "48"), limite(jamon, "2", "10")), luis);
        LimitesService.Guardado lMercado = adm.limites().guardar(mercado, List.of(
                limite(oaxaca, "2", "10"), limite(crema, "10", "36"), limite(jamon, "2.5", "8")), luis);
        LimitesService.Guardado lSur = adm.limites().guardar(sur, List.of(
                limite(oaxaca, "6", "12"), limite(crema, "10", "36"), limite(jamon, "2", "8")), luis);
        avisar("Mínimos y máximos; órdenes generadas: " + (lNorte.ordenesGeneradas() + lMercado.ordenesGeneradas()
                + lSur.ordenesGeneradas()));

        // Mermas: almacén, Norte y Mercado
        Sucursal almacen = adm.sucursales().almacen().orElseThrow();
        adm.almacen().registrarMerma(oaxaca, almacen, new BigDecimal("0.350"),
                "Moho detectado en la revisión de la cámara fría", luis);
        adm.almacen().registrarMerma(jamon, norte, new BigDecimal("0.200"), "Merma por rebanado", luis);
        adm.almacen().registrarMerma(oaxaca, mercado, new BigDecimal("0.150"), "Se resecó en la vitrina", luis);
        avisar("Mermas");

        // Crédito: 3 abonos
        adm.credito().abonar(norte, 100_000, "Transferencia SPEI", luis);
        adm.credito().abonar(norte, 50_000, "Depósito bancario", luis);
        adm.credito().abonar(mercado, 80_000, "Pago en efectivo en el almacén", luis);
        avisar("Abonos a crédito");

        // Caja: dos terminales de prueba (Norte y Mercado), para no tocar el turno abierto de esta
        // caja. Tres turnos con 3 ventas cada uno.
        String terminalNorte = nuevaCaja(norte.id());
        String terminalMercado = nuevaCaja(mercado.id());
        AuthService authNorte = new AuthService(db, new PasswordHasher(), Clock.systemDefaultZone(), terminalNorte);
        AuthService authMercado = new AuthService(db, new PasswordHasher(), Clock.systemDefaultZone(), terminalMercado);
        CajaService cajaNorte = new CajaService(db, terminalNorte);
        CajaService cajaMercado = new CajaService(db, terminalMercado);
        VentaService ventasNorte = new VentaService(db, terminalNorte);
        VentaService ventasMercado = new VentaService(db, terminalMercado);
        avisar("Cajas de prueba: " + DispositivoRepository.nombreCaja(terminalNorte) + " (Norte) y "
                + DispositivoRepository.nombreCaja(terminalMercado) + " (Mercado)");
        CatalogoService catNorte = new CatalogoService(db, norte.id());
        CatalogoService catMercado = new CatalogoService(db, mercado.id());
        String kOax = presentacion(oaxaca, "Kilo");
        String bolaOax = presentacion(oaxaca, "Bola 1 kg");
        String pzaCrema = presentacion(crema, "Pieza");
        String cajaCrema = presentacion(crema, "Caja 12 pzas");
        String kJamon = presentacion(jamon, "Kilo");
        String paqJamon = presentacion(jamon, "Paquete 250 g");

        // Turno 1: Daniela llega tarde; su supervisor le da acceso. Corte hecho por el supervisor.
        ResultadoLogin intento = authNorte.iniciarSesion("dcruz", PASSWORD.toCharArray(), ModoConexion.ONLINE);
        ResultadoLogin.Rechazado bloqueo = assertInstanceOf(ResultadoLogin.Rechazado.class, intento);
        authNorte.accesos().autorizar(bloqueo.bloqueoId(), roberto);
        Sesion sDaniela = entrar(authNorte, "dcruz");
        Turno t1 = cajaNorte.abrir(sDaniela, 50_000);
        cajaNorte.registrarMovimiento(t1, CajaService.TipoMovimiento.ENTRADA, 20_000,
                "Cambio en monedas para el fondo", daniela, null);
        vender(ventasNorte, catNorte, sDaniela, t1, MetodoPago.EFECTIVO, null, kOax, "0.750", pzaCrema, "2");
        vender(ventasNorte, catNorte, sDaniela, t1, MetodoPago.TARJETA, "004512", cajaCrema, "1");
        vender(ventasNorte, catNorte, sDaniela, t1, MetodoPago.TRANSFERENCIA, "SPEI 4821930", paqJamon, "2");
        cajaNorte.cerrar(t1, cajaNorte.resumen(t1).efectivoEsperado(), "Corte hecho por el supervisor", roberto);
        authNorte.cerrarSesion(sDaniela, "CIERRE_USUARIO"); // Sale antes de su descanso: queda bloqueada.
        avisar("Turno de Daniela: 3 ventas y corte");

        // Turno 2: Roberto (supervisor) vende; se cancela una venta y hace un retiro. Corte con faltante.
        Sesion sRoberto = entrar(authNorte, "rsanchez");
        Turno t2 = cajaNorte.abrir(sRoberto, 50_000);
        vender(ventasNorte, catNorte, sRoberto, t2, MetodoPago.EFECTIVO, null, bolaOax, "1");
        Ticket cancelada = vender(ventasNorte, catNorte, sRoberto, t2, MetodoPago.TARJETA, "771203", kJamon, "1.200");
        vender(ventasNorte, catNorte, sRoberto, t2, MetodoPago.EFECTIVO, null, pzaCrema, "3", kOax, "0.500");
        ventasNorte.cancelar(cancelada.ventaId(), t2, roberto, luis, "Error en el peso capturado");
        cajaNorte.registrarMovimiento(t2, CajaService.TipoMovimiento.RETIRO, 30_000, "Retiro parcial a caja fuerte",
                roberto, luis);
        cajaNorte.cerrar(t2, cajaNorte.resumen(t2).efectivoEsperado() - 2_000,
                "Faltante; se repone en el siguiente turno", roberto);
        authNorte.cerrarSesion(sRoberto, "CIERRE_USUARIO");
        avisar("Turno de Roberto: 3 ventas (1 cancelada), retiro y corte con faltante");

        // Turno 3: Carlos cubre su día de descanso con permiso del administrador. Gasto y sobrante.
        ResultadoLogin intentoCarlos = authMercado.iniciarSesion("cjimenez", PASSWORD.toCharArray(), ModoConexion.ONLINE);
        authMercado.accesos().autorizar(assertInstanceOf(ResultadoLogin.Rechazado.class, intentoCarlos).bloqueoId(), luis);
        Sesion sCarlos = entrar(authMercado, "cjimenez");
        Turno t3 = cajaMercado.abrir(sCarlos, 50_000);
        vender(ventasMercado, catMercado, sCarlos, t3, MetodoPago.EFECTIVO, null, kOax, "1.000");
        vender(ventasMercado, catMercado, sCarlos, t3, MetodoPago.TARJETA, "093318", pzaCrema, "2");
        vender(ventasMercado, catMercado, sCarlos, t3, MetodoPago.EFECTIVO, null, kJamon, "0.500");
        cajaMercado.registrarMovimiento(t3, CajaService.TipoMovimiento.RETIRO, 6_000, "Pago de hielo", carlos, luis);
        cajaMercado.cerrar(t3, cajaMercado.resumen(t3).efectivoEsperado() + 1_000,
                "Sobrante; se deja en el sobre del fondo", carlos);
        authMercado.cerrarSesion(sCarlos, "CIERRE_USUARIO");
        avisar("Turno de Carlos: 3 ventas, gasto y corte con sobrante");

        // Órdenes de reabasto: una se surte, otra se rechaza y otra queda pendiente
        List<OrdenRepository.Orden> pendientes = adm.ordenes().pendientes();
        avisar("Órdenes pendientes: " + pendientes.stream().map(o -> o.sucursal() + " · " + o.producto()).toList());
        for (OrdenRepository.Orden o : pendientes) {
            if (o.sucursalId().equals(norte.id()) && o.productoId().equals(crema.id())) {
                adm.surtidos().registrar(norte, SurtidoService.FormaPago.EFECTIVO,
                        List.of(linea(catalogo("Crema ácida 450 ml"), "24", pCrema)),
                        "Surtido por orden de reabastecimiento " + o.folio(), luis, o.id());
            } else if (o.sucursalId().equals(sur.id())) {
                adm.ordenes().rechazar(o, "Se incluye en el surtido programado del jueves.", luis);
            }
        }
        avisar("Órdenes atendidas");
    }

    /** Terminal de prueba en la sucursal (como las otras cajas que se dan de alta en la nube). */
    private String nuevaCaja(String sucursalId) {
        return db.enTransaccion(c -> {
            String id;
            do {
                id = com.cremerias.puntoventa.util.Ids.nuevo();
            } while (sumarEn(c, "SELECT COUNT(*) FROM dispositivo WHERE nombre = '"
                    + DispositivoRepository.nombreCaja(id) + "'") > 0);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dispositivo (id, nombre, sucursal_id, creado_en) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, DispositivoRepository.nombreCaja(id));
                ps.setString(3, sucursalId);
                ps.setString(4, com.cremerias.puntoventa.util.Tiempo.ahora());
                ps.executeUpdate();
            }
            return id;
        });
    }

    private static long sumarEn(java.sql.Connection c, String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void regla(String nombre, ComisionService.Tipo tipo, ComisionService.Medida medida, ProductoCatalogo p,
                       BigDecimal cantidad, long importeMeta, long comision, ComisionService.Periodo periodo,
                       String sucursalId) {
        adm.comisiones().guardar(new ComisionService.Regla(null, null, nombre, tipo, medida,
                p == null ? null : p.id(), null, p == null ? null : p.unidad(), cantidad, importeMeta, comision,
                periodo, null, null, sucursalId, null, true), luis);
    }

    /**
     * Horario de la semana: de lunes a sábado (y hoy, aunque sea domingo), o todos los días menos
     * hoy si {@code descansaHoy}.
     */
    private static List<HorarioDia> semana(boolean descansaHoy, LocalTime entrada, int horas, int descansoTras,
                                           int minutosDescanso) {
        List<HorarioDia> lista = new ArrayList<>();
        DayOfWeek hoy = java.time.LocalDate.now().getDayOfWeek();
        for (DayOfWeek dia : DayOfWeek.values()) {
            boolean labora = descansaHoy ? dia != hoy : dia != DayOfWeek.SUNDAY || dia == hoy;
            LocalTime descanso = entrada.plusHours(descansoTras);
            lista.add(labora
                    ? new HorarioDia(dia, true, entrada, entrada.plusHours(horas), descanso,
                    descanso.plusMinutes(minutosDescanso))
                    : HorarioDia.descanso(dia));
        }
        return lista;
    }

    private Sesion entrar(AuthService auth, String usuario) {
        ResultadoLogin r = auth.iniciarSesion(usuario, PASSWORD.toCharArray(), ModoConexion.ONLINE);
        if (r instanceof ResultadoLogin.Rechazado rechazo) {
            throw new IllegalStateException(usuario + ": " + rechazo.mensaje());
        }
        return ((ResultadoLogin.Exitoso) r).sesion();
    }

    /** {@code pares}: id de presentación y cantidad, alternados. */
    private Ticket vender(VentaService ventas, CatalogoService catalogo, Sesion sesion, Turno turno, MetodoPago metodo,
                          String referencia, String... pares) {
        catalogo.recargar();
        List<VentaService.Renglon> renglones = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < pares.length; i += 2) {
            Producto p = catalogo.porId(pares[i]).orElseThrow();
            BigDecimal q = new BigDecimal(pares[i + 1]);
            renglones.add(new VentaService.Renglon(p, q));
            total += p.importe(q);
        }
        long recibido = metodo == MetodoPago.EFECTIVO ? (total + 4_999) / 5_000 * 5_000 : total;
        Ticket ticket = ventas.registrar(renglones, List.of(new Pago(metodo, recibido, referencia)), sesion, turno);
        assertEquals(total, ticket.totalCentavos(), "total de " + ticket.folio());
        return ticket;
    }

    private static SurtidoService.Linea linea(ProductoCatalogo p, String cantidad, Map<String, Long> precios) {
        return new SurtidoService.Linea(p, new BigDecimal(cantidad), precios);
    }

    private static LimitesService.Cambio limite(ProductoCatalogo p, String minimo, String maximo) {
        return new LimitesService.Cambio(p, new BigDecimal(minimo), new BigDecimal(maximo));
    }

    /** Precio de la presentación principal y de la otra. */
    private static Map<String, Long> precios(ProductoCatalogo p, long principal, long otra) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Presentacion pr : p.presentaciones()) {
            m.put(pr.id(), pr.principal() ? principal : otra);
        }
        return m;
    }

    private static String presentacion(ProductoCatalogo p, String nombre) {
        return p.presentaciones().stream().filter(x -> x.nombre().equals(nombre)).findFirst().orElseThrow().id();
    }

    private ProductoCatalogo catalogo(String nombre) {
        return adm.productos().listar().stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Sucursal sucursal(String codigo) {
        return adm.sucursales().listar().stream().filter(s -> s.codigo().equals(codigo)).findFirst().orElseThrow();
    }

    private Usuario usuario(String nombre) {
        return db.con(c -> new UsuarioRepository().buscarPorUsuario(c, nombre)).orElseThrow();
    }

    // =====================================================================
    // Utilerías
    // =====================================================================

    /** Sin internet para el chequeo de conexión: todo socket pasa por un proxy que no existe. */
    private static void cortarInternet(boolean cortar) {
        if (cortar) {
            System.setProperty("socksProxyHost", "127.0.0.1");
            System.setProperty("socksProxyPort", "9");
        } else {
            System.clearProperty("socksProxyHost");
            System.clearProperty("socksProxyPort");
        }
    }

    /** Ids de cada tabla que se sincroniza. */
    private static Map<String, Set<String>> ids(Database base) {
        return base.con(c -> {
            Map<String, Set<String>> mapa = new LinkedHashMap<>();
            for (String tabla : TablasNube.ORDEN) {
                if (!TablasNube.seSincroniza(tabla)) {
                    continue;
                }
                Set<String> ids = new HashSet<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT " + TablasNube.llave(tabla) + " FROM " + tabla);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
                mapa.put(tabla, ids);
            }
            return mapa;
        });
    }

    private static Map<String, Set<String>> diferencia(Map<String, Set<String>> antes, Map<String, Set<String>> despues) {
        Map<String, Set<String>> nuevos = new LinkedHashMap<>();
        despues.forEach((tabla, ids) -> {
            Set<String> d = new HashSet<>(ids);
            d.removeAll(antes.getOrDefault(tabla, Set.of()));
            if (!d.isEmpty()) {
                nuevos.put(tabla, d);
            }
        });
        return nuevos;
    }

    /** Nada por subir, salvo lo que la nube ya rechazó al menos una vez (eso se reporta aparte). */
    private boolean colaVacia() {
        return contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en = 'ENVIANDO'"
                + " OR (enviado_en IS NULL AND intentos = 0)") == 0;
    }

    private int contar(String sql) {
        return (int) sumar(db, sql);
    }

    private static long sumar(Database base, String sql) {
        return base.con(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    private static String lista(java.util.Collection<String> ids) {
        return ids.isEmpty() ? "''" : "'" + String.join("','", ids) + "'";
    }

    private static <T> T fx(Supplier<T> lectura) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> f.complete(lectura.get()));
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void esperar(Duration maximo, BooleanSupplier condicion) throws InterruptedException {
        long limite = System.nanoTime() + maximo.toNanos();
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                throw new IllegalStateException("Se agotó la espera de " + maximo.toSeconds() + " s");
            }
            Thread.sleep(500);
        }
    }

    private void esperarArchivo(String nombre) throws InterruptedException {
        Path archivo = senales.resolve(nombre);
        long limite = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        while (!Files.exists(archivo)) {
            if (System.nanoTime() > limite) {
                throw new IllegalStateException("Nadie creó " + archivo);
            }
            Thread.sleep(500);
        }
    }

    private void escribir(String nombre, String texto) throws IOException {
        Files.writeString(senales.resolve(nombre), texto, StandardCharsets.UTF_8);
    }

    private void avisar(String texto) {
        String linea = LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + texto;
        System.out.println(">>> " + linea);
        try {
            Files.writeString(senales.resolve("progreso.log"), linea + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

}
