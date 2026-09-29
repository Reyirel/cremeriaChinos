package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.HorarioDia;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.admin.ComisionService;
import com.cremerias.puntoventa.service.admin.LimitesService;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdministracionTest {

    private static final ZoneId ZONA = ZoneId.of("America/Mexico_City");

    @TempDir
    Path carpeta;

    private Database db;
    private Reloj reloj;
    private AuthService auth;
    private Administracion admin;
    private CatalogoService catalogo;
    private CajaService caja;
    private VentaService ventas;
    private String dispositivo;
    private Sucursal matriz;
    private Usuario administrador;

    @BeforeEach
    void preparar() {
        db = new Database(carpeta.resolve("admin.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        String matrizId = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(matrizId);
        dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, matrizId));
        // Lunes 28 de septiembre de 2026, 10:00 (hora de México).
        reloj = new Reloj(ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZONA).toInstant());
        auth = new AuthService(db, hasher, reloj, dispositivo);
        admin = new Administracion(db, hasher, reloj, dispositivo);
        catalogo = new CatalogoService(db, matrizId);
        catalogo.recargar();
        caja = new CajaService(db, dispositivo);
        ventas = new VentaService(db, dispositivo);
        matriz = admin.sucursales().listar().getFirst();
        administrador = entrar("admin", "admin123").usuario();
    }

    private Sesion entrar(String usuario, String clave) {
        return ((ResultadoLogin.Exitoso) auth.iniciarSesion(usuario, clave.toCharArray(), ModoConexion.OFFLINE)).sesion();
    }

    private ProductoCatalogo catalogoAdmin(String nombre) {
        return admin.productos().listar().stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Producto articulo(String nombre) {
        catalogo.recargar();
        return catalogo.buscar(nombre, 1).getFirst();
    }

    private void vender(Sesion sesion, Turno turno, String nombre, String cantidad) {
        Producto p = articulo(nombre);
        long total = p.importe(new BigDecimal(cantidad));
        ventas.registrar(List.of(new VentaService.Renglon(p, new BigDecimal(cantidad))),
                List.of(new Pago(MetodoPago.EFECTIVO, total, null)), sesion, turno);
    }

    @Test
    void elSurtidoSaleDelLoteMasAntiguoYSeCargaAlCredito() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        // En el almacén hay 50 kg a $133/kg (demo). Llega un lote nuevo más caro.
        String folioEntrada = admin.almacen().reabastecer(queso, new BigDecimal("10"), 15000, "Proveedor", administrador);
        assertTrue(folioEntrada.startsWith("ENT-"));

        SurtidoService.Costeo costeo = admin.surtidos().costear(queso, new BigDecimal("55"));
        assertEquals(50 * 13300 + 5 * 15000, costeo.costoCentavos());
        assertTrue(costeo.suficiente());

        var detalle = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("55"), Map.of(queso.principal().id(), 21000L))),
                null, administrador, null);
        assertTrue(detalle.resumen().folio().startsWith("SUR-"));
        assertEquals(740000, detalle.resumen().costoCentavos());
        assertEquals(55 * 21000, detalle.resumen().ventaCentavos());
        assertEquals(740000, detalle.saldoCredito());
        assertEquals(0, new BigDecimal("5.000").compareTo(admin.almacen().existencia(queso.id(), admin.almacen().almacenId())));

        // No se puede surtir más de lo que hay.
        assertThrows(IllegalStateException.class, () -> admin.surtidos().registrar(matriz,
                SurtidoService.FormaPago.EFECTIVO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("6"), Map.of(queso.principal().id(), 21000L))),
                null, administrador, null));

        var abono = admin.credito().abonar(matriz, 40000, "Pago parcial", administrador);
        assertEquals(700000, abono.saldoNuevo());
        assertThrows(IllegalArgumentException.class, () -> admin.credito().abonar(matriz, 800000, null, administrador));
    }

    @Test
    void elPrecioPromedioSaleDeLosLotesQueTieneLaSucursal() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        String kilo = queso.principal().id();
        // En la sucursal hay un lote de ejemplo a $190/kg; se surten dos lotes más a $200 y $215.
        for (long precio : new long[]{20000, 21500}) {
            admin.surtidos().registrar(matriz, SurtidoService.FormaPago.EFECTIVO,
                    List.of(new SurtidoService.Linea(queso, new BigDecimal("5"), Map.of(kilo, precio))),
                    null, administrador, null);
        }
        var promedio = admin.surtidos().preciosPromedio(matriz.id(), queso.id());
        assertEquals(3, promedio.lotes());
        assertTrue(promedio.conExistencia());
        assertEquals(20167L, promedio.precios().get(kilo)); // (19000 + 20000 + 21500) / 3 = 20166.67

        // Se vende todo el lote de $190 (25 kg): ya solo cuentan los que tienen existencia.
        Sesion cajero = entrar("cajero", "cajero123");
        Turno turno = caja.abrir(cajero, 0);
        vender(cajero, turno, "queso oaxaca", "25");
        promedio = admin.surtidos().preciosPromedio(matriz.id(), queso.id());
        assertEquals(2, promedio.lotes());
        assertEquals(20750L, promedio.precios().get(kilo));

        // Si ya no queda nada, se promedian todos los lotes anteriores.
        vender(cajero, turno, "queso oaxaca", "10");
        promedio = admin.surtidos().preciosPromedio(matriz.id(), queso.id());
        assertEquals(3, promedio.lotes());
        assertTrue(!promedio.conExistencia());
        assertEquals(20167L, promedio.precios().get(kilo));

        // Sucursal sin lotes del producto: no hay promedio.
        admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        String sur = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("SUR")).findFirst()
                .orElseThrow().id();
        assertEquals(0, admin.surtidos().preciosPromedio(sur, queso.id()).lotes());
    }

    @Test
    void enLaCajaSeVendePrimeroAlPrecioAnteriorYLuegoAlNuevo() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        admin.surtidos().registrar(matriz, SurtidoService.FormaPago.EFECTIVO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("10"), Map.of(queso.principal().id(), 21000L))),
                null, administrador, null);
        assertEquals(19000, articulo("queso oaxaca").precioCentavos()); // lote anterior (25 kg a $190)

        Sesion cajero = entrar("cajero", "cajero123");
        Turno turno = caja.abrir(cajero, 0);
        var ticket = ventas.registrar(List.of(new VentaService.Renglon(articulo("queso oaxaca"), new BigDecimal("26"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 600000, null)), cajero, turno);
        assertEquals(25 * 19000 + 21000, ticket.totalCentavos());
        assertEquals(21000, articulo("queso oaxaca").precioCentavos()); // ya se agotó el lote viejo
    }

    @Test
    void alLlegarAlMinimoSeGeneraUnaOrdenYElAdminLaAprueba() {
        ProductoCatalogo leche = catalogoAdmin("Leche entera 1 L");
        LimitesService.Guardado guardado = admin.limites().guardar(matriz,
                List.of(new LimitesService.Cambio(leche, new BigDecimal("45"), new BigDecimal("60"))), administrador);
        assertEquals(0, guardado.ordenesGeneradas()); // hay 48

        Sesion cajero = entrar("cajero", "cajero123");
        Turno turno = caja.abrir(cajero, 0);
        vender(cajero, turno, "leche entera 1 l", "3");
        vender(cajero, turno, "leche entera 1 l", "1");
        List<OrdenRepository.Orden> pendientes = admin.ordenes().pendientes();
        assertEquals(1, pendientes.size()); // no se duplica
        OrdenRepository.Orden orden = pendientes.getFirst();
        assertTrue(orden.folio().startsWith("ORD-"));
        assertEquals(0, new BigDecimal("15").compareTo(orden.cantidadSugerida()));

        Map<String, Long> precios = admin.surtidos().preciosSugeridos(matriz.id(), leche.id());
        assertEquals(2800L, precios.get(leche.principal().id()));
        admin.surtidos().registrar(matriz, SurtidoService.FormaPago.EFECTIVO,
                List.of(new SurtidoService.Linea(leche, orden.cantidadSugerida(), precios)), null, administrador, orden.id());
        assertEquals(0, admin.ordenes().contarPendientes());
        assertEquals("APROBADA", admin.ordenes().atendidas().getFirst().estado());
    }

    @Test
    void laMermaSoloAplicaAProductosMarcados() {
        Sucursal almacen = admin.sucursales().almacen().orElseThrow();
        assertThrows(IllegalStateException.class, () -> admin.almacen().registrarMerma(catalogoAdmin("Leche entera 1 L"),
                almacen, BigDecimal.ONE, "Se rompió", administrador));
        String folio = admin.almacen().registrarMerma(catalogoAdmin("Jamón de pierna"), almacen, new BigDecimal("0.750"),
                "Se echó a perder", administrador);
        assertTrue(folio.startsWith("MER-"));
        assertEquals(0, new BigDecimal("49.250").compareTo(
                admin.almacen().existencia(catalogoAdmin("Jamón de pierna").id(), almacen.id())));
    }

    @Test
    void elHorarioBloqueaYSoloAdminOSupervisorDanAcceso() {
        Usuario cajero = admin.usuarios().listar().stream().map(f -> f.usuario())
                .filter(u -> u.usuario().equals("cajero")).findFirst().orElseThrow();
        List<HorarioDia> semana = new ArrayList<>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            semana.add(new HorarioDia(dia, true, LocalTime.of(9, 0), LocalTime.of(17, 0), LocalTime.of(13, 0),
                    LocalTime.of(14, 0)));
        }
        assertTrue(auth.accesos().guardarHorario(cajero, semana, administrador).startsWith("HOR-"));

        // Llega a las 10:00 y su entrada es a las 9:00.
        var rechazo = assertInstanceOf(ResultadoLogin.Rechazado.class,
                auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.OFFLINE));
        assertEquals(ResultadoLogin.Motivo.FUERA_DE_HORARIO, rechazo.motivo());
        assertNotNull(rechazo.bloqueoId());
        assertInstanceOf(ResultadoLogin.Rechazado.class,
                auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.OFFLINE));
        assertEquals(1, auth.accesos().pendientes().size());
        // Un supervisor solo ve (y puede aprobar) los bloqueos de su propia sucursal.
        assertEquals(1, auth.accesos().pendientesDeSucursal(matriz.id()).size());
        assertEquals(0, auth.accesos().pendientesDeSucursal(
                admin.sucursales().almacen().orElseThrow().id()).size());

        // Un cajero no puede autorizar; el administrador sí.
        assertThrows(IllegalStateException.class, () -> auth.accesos().autorizar(rechazo.bloqueoId(), cajero));
        auth.accesos().autorizar(rechazo.bloqueoId(), administrador);
        Sesion sesion = entrar("cajero", "cajero123");

        // Sale a las 11:00, antes de su descanso: su siguiente entrada queda bloqueada.
        reloj.ir(ZonedDateTime.of(2026, 9, 28, 11, 0, 0, 0, ZONA).toInstant());
        auth.cerrarSesion(sesion, "CIERRE_USUARIO");
        var otro = assertInstanceOf(ResultadoLogin.Rechazado.class,
                auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.OFFLINE));
        assertTrue(otro.mensaje().contains("salió antes de su hora de descanso"));

        // El supervisor de la misma sucursal también puede dar acceso.
        Usuario supervisor = entrar("supervisor", "super123").usuario();
        auth.accesos().autorizar(otro.bloqueoId(), supervisor);
        assertInstanceOf(ResultadoLogin.Exitoso.class,
                auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.OFFLINE));
    }

    @Test
    void reglasDeHorario() {
        HorarioDia h = new HorarioDia(DayOfWeek.MONDAY, true, LocalTime.of(9, 0), LocalTime.of(17, 0),
                LocalTime.of(13, 0), LocalTime.of(14, 0));
        assertTrue(ReglasHorario.alEntrar(h, LocalTime.of(8, 30), false, null).isEmpty());
        assertTrue(ReglasHorario.alEntrar(h, LocalTime.of(9, 0, 40), false, null).isEmpty());
        assertEquals(ReglasHorario.Motivo.LLEGADA_TARDE, ReglasHorario.alEntrar(h, LocalTime.of(9, 1), false, null).orElseThrow());
        assertTrue(ReglasHorario.alEntrar(h, LocalTime.of(12, 0), true, null).isEmpty());
        assertEquals(ReglasHorario.Motivo.REGRESO_TARDE_DESCANSO,
                ReglasHorario.alEntrar(h, LocalTime.of(14, 5), true, LocalTime.of(13, 2)).orElseThrow());
        assertTrue(ReglasHorario.alEntrar(h, LocalTime.of(13, 55), true, LocalTime.of(13, 2)).isEmpty());
        assertEquals(ReglasHorario.Motivo.DESPUES_DE_SALIDA, ReglasHorario.alEntrar(h, LocalTime.of(17, 1), true, null).orElseThrow());
        assertEquals(ReglasHorario.Motivo.DIA_SIN_HORARIO,
                ReglasHorario.alEntrar(HorarioDia.descanso(DayOfWeek.SUNDAY), LocalTime.of(10, 0), false, null).orElseThrow());

        assertEquals(ReglasHorario.Motivo.SALIDA_ANTES_DESCANSO, ReglasHorario.alSalir(h, LocalTime.of(12, 0)).orElseThrow());
        assertTrue(ReglasHorario.alSalir(h, LocalTime.of(13, 0)).isEmpty());
        assertEquals(ReglasHorario.Motivo.SALIDA_ANTICIPADA, ReglasHorario.alSalir(h, LocalTime.of(16, 0)).orElseThrow());
        assertTrue(ReglasHorario.alSalir(h, LocalTime.of(17, 0)).isEmpty());
    }

    @Test
    void comisionesPorMetaDeProductoPorCantidadYPorTotal() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        ProductoCatalogo leche = catalogoAdmin("Leche entera 1 L");
        // 1 kg de queso en gramos: se venden 1.5 kg × 980 g netos (20 g de merma por kilo) = 1,470 g.
        admin.comisiones().guardar(new ComisionService.Regla(null, null, "Meta queso", ComisionService.Tipo.META_CANTIDAD,
                ComisionService.Medida.GRAMOS, queso.id(), null, null, new BigDecimal("1000"), 0, 5000,
                ComisionService.Periodo.DIARIO, null, null, null, null, true), administrador);
        admin.comisiones().guardar(new ComisionService.Regla(null, null, "Leche", ComisionService.Tipo.POR_CANTIDAD,
                ComisionService.Medida.UNIDADES, leche.id(), null, null, new BigDecimal("2"), 0, 500,
                ComisionService.Periodo.DIARIO, null, null, null, null, true), administrador);
        admin.comisiones().guardar(new ComisionService.Regla(null, null, "Venta del día", ComisionService.Tipo.META_TOTAL,
                ComisionService.Medida.UNIDADES, null, null, null, null, 10000, 2000, ComisionService.Periodo.DIARIO,
                null, null, null, null, true), administrador);
        // Por gramaje de un producto por pieza: 5 leches × 1,030 g = 5,150 g → 2 veces 2 kg.
        admin.comisiones().guardar(new ComisionService.Regla(null, null, "Leche por gramaje",
                ComisionService.Tipo.POR_CANTIDAD, ComisionService.Medida.GRAMOS, leche.id(), null, null,
                new BigDecimal("2000"), 0, 300, ComisionService.Periodo.DIARIO, null, null, null, null, true),
                administrador);

        Sesion cajero = entrar("cajero", "cajero123");
        Turno turno = caja.abrir(cajero, 0);
        vender(cajero, turno, "queso oaxaca", "1.5");
        vender(cajero, turno, "leche entera 1 l", "5");

        LocalDate hoy = LocalDate.now();
        List<ComisionService.Ganada> ganadas = admin.comisiones().calcular(hoy.minusDays(1), hoy.plusDays(1));
        assertEquals(4, ganadas.size());
        assertEquals(5000 + 2 * 500 + 2000 + 2 * 300,
                ganadas.stream().mapToLong(ComisionService.Ganada::comisionCentavos).sum());
        assertEquals("1.47 kg", ganadas.stream().filter(g -> g.regla().nombre().equals("Meta queso"))
                .findFirst().orElseThrow().logrado());
    }

    @Test
    void todoProductoLlevaGramajeYLaMermaSeRestaDelGramaje() {
        var pieza = List.of(new com.cremerias.puntoventa.model.Presentacion(null, null, "Pieza", false, BigDecimal.ONE,
                null, true, true));
        // Sin gramaje no se puede guardar un producto por pieza.
        assertThrows(IllegalArgumentException.class, () -> admin.productos().guardar(new com.cremerias.puntoventa.service
                .admin.ProductoAdminService.Datos(null, "Lapicero", "Papelería", null, null, com.cremerias.puntoventa.model.Unidad.PZA,
                false, com.cremerias.puntoventa.model.Disponibilidad.REGULAR, pieza, List.of(), null, null, null), administrador));
        // La merma debe ser menor que el gramaje.
        assertThrows(IllegalArgumentException.class, () -> admin.productos().guardar(new com.cremerias.puntoventa.service
                .admin.ProductoAdminService.Datos(null, "Jabón", "Limpieza", null, null, com.cremerias.puntoventa.model.Unidad.PZA,
                true, com.cremerias.puntoventa.model.Disponibilidad.REGULAR, pieza, List.of(), new BigDecimal("150"),
                new BigDecimal("150"), null), administrador));

        String folio = admin.productos().guardar(new com.cremerias.puntoventa.service.admin.ProductoAdminService.Datos(
                null, "Queso de bola", "Quesos", null, null, com.cremerias.puntoventa.model.Unidad.PZA, true,
                com.cremerias.puntoventa.model.Disponibilidad.TEMPORADA, pieza, List.of(), new BigDecimal("1000"),
                new BigDecimal("50"), null), administrador);
        assertTrue(folio.startsWith("PRD-"));
        ProductoCatalogo bola = catalogoAdmin("Queso de bola");
        assertEquals(0, new BigDecimal("950").compareTo(bola.gramajeNeto()));
        // Admite microgramos (0.0005 g = 500 µg).
        admin.productos().guardar(new com.cremerias.puntoventa.service.admin.ProductoAdminService.Datos(
                null, "Vitamina", "Farmacia", null, null, com.cremerias.puntoventa.model.Unidad.PZA, false,
                com.cremerias.puntoventa.model.Disponibilidad.REGULAR, pieza, List.of(), new BigDecimal("0.0005"), null, null),
                administrador);
        assertEquals("500 µg", com.cremerias.puntoventa.util.Masa.formatear(catalogoAdmin("Vitamina").gramajeGramos()));
        assertEquals(0, new BigDecimal("1000").compareTo(catalogoAdmin("Queso Oaxaca").gramajeGramos()));
    }

    @Test
    void unProductoPorKiloPuedeCapturarSuPropioGramajeDeReferencia() {
        var granel = List.of(new com.cremerias.puntoventa.model.Presentacion(null, null, "Kilo", true, BigDecimal.ONE,
                null, true, true));
        // Sin gramaje tampoco se puede guardar un producto por kilo (ya no se fuerza a 1000 en automático).
        assertThrows(IllegalArgumentException.class, () -> admin.productos().guardar(new ProductoAdminService.Datos(
                null, "Salmón ahumado", "Carnes frías", null, null, com.cremerias.puntoventa.model.Unidad.KG, false,
                com.cremerias.puntoventa.model.Disponibilidad.REGULAR, granel, List.of(), null, null, null), administrador));

        // Con 1200 g de referencia por kilo y 60 g de merma, el neto es 1140 g por kilo (no 980 g de un 1000 fijo).
        String folio = admin.productos().guardar(new ProductoAdminService.Datos(
                null, "Salmón ahumado", "Carnes frías", null, null, com.cremerias.puntoventa.model.Unidad.KG, true,
                com.cremerias.puntoventa.model.Disponibilidad.REGULAR, granel, List.of(), new BigDecimal("1200"),
                new BigDecimal("60"), null), administrador);
        assertTrue(folio.startsWith("PRD-"));
        ProductoCatalogo salmon = catalogoAdmin("Salmón ahumado");
        assertEquals(0, new BigDecimal("1200").compareTo(salmon.gramajeGramos()));
        assertEquals(0, new BigDecimal("1140").compareTo(salmon.gramajeNeto()));
    }

    @Test
    void cadaAccionTieneFolioYQuedaEnElHistorial() {
        String a = admin.sucursales().guardar(null, "NORTE", "Cremería Norte", null, null, administrador);
        String b = admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        assertTrue(a.matches("SUC-[0-9A-F]{4}-000001"));
        assertTrue(b.endsWith("000002"));
        assertThrows(IllegalArgumentException.class,
                () -> admin.sucursales().guardar(null, "norte", "Otra", null, null, administrador));
        var registros = admin.bitacora().buscar(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), "Norte", null);
        assertEquals(1, registros.size());
        assertEquals(a, registros.getFirst().folio());
    }

    @Test
    void usuariosNoSeDuplicanYAlEliminarSeLiberaElNombre() {
        String folio = admin.usuarios().crear("Nuevo Cajero", "nuevo", "clave123".toCharArray(), Rol.CAJERO, matriz.id(),
                administrador);
        assertTrue(folio.startsWith("USR-"));
        assertThrows(IllegalArgumentException.class, () -> admin.usuarios().crear("Otro", "NUEVO",
                "clave123".toCharArray(), Rol.CAJERO, matriz.id(), administrador));
        Usuario nuevo = admin.usuarios().listar().stream().map(f -> f.usuario())
                .filter(u -> u.usuario().equals("nuevo")).findFirst().orElseThrow();
        admin.usuarios().eliminar(nuevo, administrador);
        admin.usuarios().crear("Otro", "nuevo", "clave123".toCharArray(), Rol.CAJERO, matriz.id(), administrador);
        assertThrows(IllegalStateException.class, () -> admin.usuarios().eliminar(administrador, administrador));
    }

    @Test
    void elSupervisorConsultaLosProductosDeSuSucursalYLaExistenciaBajaConLaVenta() {
        var inventario = new InventarioSucursalService(db);
        var leche = inventario.listar(matriz.id()).stream()
                .filter(f -> f.producto().nombre().equals("Leche entera 1 L")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("48").compareTo(leche.existencia()));
        // Precio de cada presentación en la sucursal, la principal primero.
        assertEquals(List.of("Pieza", "Caja 12 pzas"), leche.precios().stream().map(p -> p.presentacion().nombre()).toList());
        assertEquals(List.of(2800L, 32000L), leche.precios().stream().map(InventarioSucursalService.Precio::precioCentavos).toList());

        Sesion cajero = entrar("cajero", "cajero123");
        Turno turno = caja.abrir(cajero, 0);
        vender(cajero, turno, "leche entera 1 l caja", "1");
        leche = inventario.listar(matriz.id()).stream()
                .filter(f -> f.producto().nombre().equals("Leche entera 1 L")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("36").compareTo(leche.existencia()));
    }

    private String guardarConAviso(ProductoCatalogo p, AvisoSinVenta aviso) {
        return admin.productos().guardar(new ProductoAdminService.Datos(p.id(), p.nombre(), p.categoria(), p.clave(),
                p.codigoInventario(), p.unidad(), p.sujetoMerma(), p.disponibilidad(), p.presentaciones(), List.of(),
                p.gramajeGramos(), p.mermaGramos(), aviso), administrador);
    }

    private static List<String> productos(List<SinVentaService.SinVenta> lista) {
        return lista.stream().map(SinVentaService.SinVenta::producto).toList();
    }

    @Test
    void elAvisoPorProductoValidaPlazoYDestinatarios() {
        ProductoCatalogo leche = catalogoAdmin("Leche entera 1 L");
        assertThrows(IllegalArgumentException.class, () -> guardarConAviso(leche,
                new AvisoSinVenta(15, AvisoSinVenta.Unidad.MINUTOS, false, false, false)));
        assertThrows(IllegalArgumentException.class, () -> guardarConAviso(leche,
                new AvisoSinVenta(0, AvisoSinVenta.Unidad.HORAS, true, false, false)));
        assertThrows(IllegalArgumentException.class, () -> guardarConAviso(leche,
                new AvisoSinVenta(53, AvisoSinVenta.Unidad.SEMANAS, true, false, false)));

        guardarConAviso(leche, new AvisoSinVenta(2, AvisoSinVenta.Unidad.HORAS, false, true, true));
        AvisoSinVenta guardado = catalogoAdmin("Leche entera 1 L").avisoSinVenta();
        assertEquals("2 horas", guardado.describir());
        assertEquals("Supervisor y caja", guardado.destinatarios());
        // Se puede quitar: vuelve a usar el plazo en días de la sucursal.
        guardarConAviso(catalogoAdmin("Leche entera 1 L"), null);
        assertEquals(null, catalogoAdmin("Leche entera 1 L").avisoSinVenta());
    }

    @Test
    void elAvisoSinVentaLlegaSoloAQuienSeEligioYSeQuitaAlVender() {
        guardarConAviso(catalogoAdmin("Leche entera 1 L"),
                new AvisoSinVenta(15, AvisoSinVenta.Unidad.MINUTOS, false, true, true));
        Instant ahora = Instant.now();
        var a10 = new SinVentaService(db, Clock.fixed(ahora.plus(Duration.ofMinutes(10)), ZONA));
        var a16 = new SinVentaService(db, Clock.fixed(ahora.plus(Duration.ofMinutes(16)), ZONA));

        // A los 10 minutos sin venta todavía no se avisa; a los 16 sí, a la caja y al supervisor.
        assertEquals(List.of(), productos(a10.pendientes(Rol.CAJERO, matriz.id())));
        assertEquals(List.of("Leche entera 1 L"), productos(a16.pendientes(Rol.CAJERO, matriz.id())));
        assertEquals(List.of("Leche entera 1 L"), productos(a16.pendientes(Rol.SUPERVISOR, matriz.id())));
        assertEquals("15 minutos", a16.pendientes(Rol.CAJERO, matriz.id()).getFirst().plazo());
        // El administrador no lo pidió para este producto (y el plazo de la sucursal es de días).
        assertTrue(productos(a16.pendientes(Rol.ADMINISTRADOR, null)).isEmpty());
        // Otra sucursal no lo ve.
        admin.sucursales().guardar(null, "NORTE", "Cremería Norte", null, null, administrador);
        String norteId = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("NORTE")).findFirst()
                .orElseThrow().id();
        assertEquals(List.of(), productos(a16.pendientes(Rol.CAJERO, norteId)));

        // Al venderse se quita el aviso: el plazo vuelve a contar desde esa venta.
        Sesion cajero = entrar("cajero", "cajero123");
        Turno turno = caja.abrir(cajero, 0);
        vender(cajero, turno, "leche entera 1 l", "1");
        var despuesDeVender = new SinVentaService(db, Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZONA));
        assertEquals(List.of(), productos(despuesDeVender.pendientes(Rol.CAJERO, matriz.id())));
    }

    /** Reloj que se puede mover en las pruebas. */
    private static final class Reloj extends Clock {
        private Instant ahora;

        Reloj(Instant inicio) {
            this.ahora = inicio;
        }

        void ir(Instant nuevo) {
            ahora = nuevo;
        }

        @Override
        public ZoneId getZone() {
            return ZONA;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
