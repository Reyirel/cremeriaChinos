package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.CategoriaGasto;
import com.cremerias.puntoventa.model.Gasto;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.TipoGasto;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Venta, costo real por lote, alcance por sucursal y meta mensual del módulo Indicadores. */
class IndicadoresServiceTest {

    @TempDir
    Path carpeta;

    private Database db;
    private AuthService auth;
    private CatalogoService catalogo;
    private CajaService caja;
    private VentaService ventas;
    private Administracion adm;
    private IndicadoresService indicadores;
    private Sesion sesionCajero;
    private Usuario admin;
    private Usuario supervisor;
    private String matriz;

    @BeforeEach
    void preparar() {
        db = new Database(carpeta.resolve("indicadores.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        matriz = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(matriz);
        String dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, matriz));
        auth = new AuthService(db, hasher, Clock.systemUTC(), dispositivo);
        catalogo = new CatalogoService(db, matriz);
        catalogo.recargar();
        caja = new CajaService(db, dispositivo);
        ventas = new VentaService(db, dispositivo);
        adm = new Administracion(db, hasher, Clock.systemUTC(), dispositivo);
        indicadores = new IndicadoresService(db, dispositivo, adm.gastos(), adm.nomina(), adm.credito());
        sesionCajero = ((ResultadoLogin.Exitoso) auth.iniciarSesion("cajero", "cajero123".toCharArray(),
                ModoConexion.OFFLINE)).sesion();
        admin = ((ResultadoLogin.Exitoso) auth.iniciarSesion("admin", "admin123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
        supervisor = ((ResultadoLogin.Exitoso) auth.iniciarSesion("supervisor", "super123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
    }

    private ProductoCatalogo productoAdmin(String nombre) {
        return adm.productos().listar().stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private Sucursal sucursalMatriz() {
        return adm.sucursales().listar().stream().filter(s -> s.id().equals(matriz)).findFirst().orElseThrow();
    }

    /** El bolillo de DatosDemo cuesta 210 (precio 300 · 70%) y se vende en 300: costo real, no precio actual. */
    @Test
    void calcularUsaVentaNetaYCostoRealDelLote() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        IndicadoresService.Resumen r = indicadores.calcular(admin, matriz, hoy, hoy);

        assertEquals(3000, r.ventaNeta());
        assertEquals(2100, r.costoVenta());
        assertEquals(900, r.utilidadBruta());
        assertEquals(1, r.diasEnPeriodo());
        assertEquals(3000, r.promedioDiario());
    }

    @Test
    void laVentaCanceladaNoCuentaEnVentaNiCosto() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        Ticket t = ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        Usuario autorizador = ((ResultadoAutorizacion.Autorizado) auth.autorizar("supervisor", "super123".toCharArray(),
                EnumSet.of(Rol.SUPERVISOR, Rol.ADMINISTRADOR), "prueba")).autorizador();
        ventas.cancelar(t.ventaId(), turno, sesionCajero.usuario(), autorizador, "Prueba");

        LocalDate hoy = LocalDate.now();
        IndicadoresService.Resumen r = indicadores.calcular(admin, matriz, hoy, hoy);
        assertEquals(0, r.ventaNeta());
        assertEquals(0, r.costoVenta());
    }

    @Test
    void soloElAdministradorPuedeConsultarOtraSucursal() {
        LocalDate hoy = LocalDate.now();
        // El supervisor es de "matriz": puede consultarla...
        assertEquals(0, indicadores.calcular(supervisor, matriz, hoy, hoy).ventaNeta());
        // ...pero no la del almacén (donde trabaja el administrador).
        assertThrows(IllegalStateException.class, () -> indicadores.calcular(supervisor, admin.sucursalId(), hoy, hoy));
        // El administrador (Superadministrador) sí puede consultar cualquier sucursal.
        assertEquals(0, indicadores.calcular(admin, matriz, hoy, hoy).ventaNeta());
        assertEquals(0, indicadores.calcular(admin, admin.sucursalId(), hoy, hoy).ventaNeta());
    }

    @Test
    void soloElAdministradorPuedeGuardarLaMeta() {
        LocalDate hoy = LocalDate.now();
        assertThrows(IllegalStateException.class, () -> indicadores.guardarMeta(supervisor, matriz, hoy.getYear(),
                hoy.getMonthValue(), 100000, null, null, null));
        String folio = indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 100000, null, null, null);
        assertTrue(folio.startsWith("MET-"));
        assertEquals(100000L, indicadores.metaCapturada(admin, matriz, hoy.getYear(), hoy.getMonthValue())
                .orElseThrow().ventaCentavos());
    }

    @Test
    void laMetaDeKilosArticulosYUtilidadSeCapturanJuntoConLaDeVenta() {
        LocalDate hoy = LocalDate.now();
        indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 100000,
                new BigDecimal("50000"), new BigDecimal("3.5"), 25.0);

        IndicadoresService.MetaCapturada m = indicadores.metaCapturada(admin, matriz, hoy.getYear(), hoy.getMonthValue())
                .orElseThrow();
        assertEquals(100000L, m.ventaCentavos());
        assertEquals(0, new BigDecimal("50000").compareTo(m.kilosGramos().orElseThrow()));
        assertEquals(0, new BigDecimal("3.5").compareTo(m.articulosTicket().orElseThrow()));
        assertEquals(25.0, m.utilidadPct().orElseThrow(), 0.001);
    }

    @Test
    void laMetaDeUtilidadFueraDeRangoSeRechaza() {
        LocalDate hoy = LocalDate.now();
        assertThrows(IllegalArgumentException.class, () -> indicadores.guardarMeta(admin, matriz, hoy.getYear(),
                hoy.getMonthValue(), 100000, null, null, 150.0));
    }

    @Test
    void calculaCumplimientoYMetaDiariaConLaMetaCapturada() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 6000, null, null, null);

        IndicadoresService.Resumen r = indicadores.calcular(admin, matriz, hoy.withDayOfMonth(1), hoy);
        assertEquals(3000, r.ventaNeta());
        assertEquals(6000L, r.metaMensual().orElseThrow());
        assertEquals(50.0, r.cumplimientoPct().orElseThrow(), 0.001);
        assertEquals(-3000L, r.diferenciaVsMeta().orElseThrow());
        assertEquals(3000L, r.faltanteMeta());
        int diasDelMes = YearMonth.from(hoy).lengthOfMonth();
        assertEquals(diasDelMes, r.diasDelMes().orElseThrow());
        assertEquals(6000L / diasDelMes, r.metaDiaria().orElseThrow());
    }

    @Test
    void laMetaQuedaVaciaCuandoElPeriodoCruzaDosMeses() {
        LocalDate hoy = LocalDate.now();
        indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 6000, null, null, null);

        LocalDate desde = hoy.withDayOfMonth(1).minusMonths(1);
        LocalDate hasta = hoy.withDayOfMonth(1);
        IndicadoresService.Resumen r = indicadores.calcular(admin, matriz, desde, hasta);

        assertTrue(r.metaMensual().isEmpty());
        assertTrue(r.cumplimientoPct().isEmpty());
        assertTrue(r.diferenciaVsMeta().isEmpty());
        assertTrue(r.diasDelMes().isEmpty());
        assertTrue(r.metaDiaria().isEmpty());
        assertFalse(desde.getMonthValue() == hasta.getMonthValue() && desde.getYear() == hasta.getYear());
    }

    /** El bolillo pesa 70 g (sin merma); 10 piezas vendidas dejan 700 g netos vendidos. */
    @Test
    void calculaKilosVendidosConElGramajeNetoDelProducto() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        IndicadoresService.KilosResumen kr = indicadores.calcularKilos(admin, matriz, hoy, hoy);
        assertEquals(0, new BigDecimal("700").compareTo(kr.kilosVendidosGramos()));
        assertTrue(kr.metaKilosGramos().isEmpty());
        assertTrue(kr.diferenciaGramos().isEmpty());
    }

    @Test
    void calculaVentasDetalleDeTicketsYArticulosPorTicket() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        IndicadoresService.VentasDetalle vd = indicadores.calcularVentasDetalle(admin, matriz, hoy, hoy);
        assertEquals(1, vd.tickets());
        assertEquals(3000L, vd.ticketPromedioCentavos());
        assertEquals(1.0, vd.articulosPorTicket(), 0.001);
        assertEquals(3000L, vd.valorPromedioArticuloCentavos());
        assertEquals(1.0, vd.clientesPorDia(), 0.001);
    }

    @Test
    void calculaElAvanceDeKpisConLasMetasCapturadas() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 6000,
                new BigDecimal("20000"), new BigDecimal("2"), 50.0);

        IndicadoresService.KpisResumen kpis = indicadores.calcularKpis(admin, matriz, hoy.withDayOfMonth(1), hoy);
        assertEquals(4, kpis.kpis().size());
        IndicadoresService.Kpi venta = kpis.kpis().get(0);
        assertEquals("Venta", venta.nombre());
        assertEquals(50.0, venta.avancePct().orElseThrow(), 0.001);
        assertEquals(15.0, venta.avancePonderado().orElseThrow(), 0.001); // 50% de un peso de 30
        assertTrue(kpis.avanceTotalPonderado().isPresent());
    }

    @Test
    void soloElAdministradorPuedeVerLaUtilidadNetaYElConsolidado() {
        LocalDate hoy = LocalDate.now();
        assertThrows(IllegalStateException.class, () -> indicadores.calcularUtilidad(supervisor, matriz, hoy, hoy));
        assertThrows(IllegalStateException.class,
                () -> indicadores.calcularConsolidado(supervisor, adm.sucursales().listar(), hoy, hoy));
    }

    /**
     * Venta de 10 bolillos (utilidad bruta 900), más una renta de $500, una semana de nómina de
     * $1,700 y una merma de 1 kg de queso Oaxaca (costo real 13300) dejan una utilidad neta negativa,
     * exactamente bruta − gastos − nómina − mermas.
     */
    @Test
    void calculaLaUtilidadNetaRestandoGastosNominaYMermas() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        Instant ahora = hoy.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Gasto gasto = new Gasto(null, null, matriz, ahora, CategoriaGasto.RENTA, TipoGasto.FIJO, "Renta", 50000,
                null, null, null);
        adm.gastos().guardar(gasto, admin);

        LocalDate lunes = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        adm.nomina().guardar(admin, matriz, supervisor.id(), lunes, 150000, 20000);

        adm.almacen().registrarMerma(productoAdmin("Queso Oaxaca"), sucursalMatriz(), new BigDecimal("1"),
                "Prueba", admin);

        // El periodo cubre toda la semana (lunes a domingo) para que incluya la nómina capturada en el lunes.
        IndicadoresService.UtilidadResumen u = indicadores.calcularUtilidad(admin, matriz, lunes, lunes.plusDays(6));
        assertEquals(50000L, u.gastosCentavos());
        assertEquals(170000L, u.nominaCentavos());
        assertEquals(13300L, u.mermasCentavos());
        assertEquals(900L - 50000L - 170000L - 13300L, u.utilidadNeta());
    }

    /**
     * Los datos de ejemplo (DatosDemo) se siembran "ahora", igual que la venta de la prueba: no se
     * puede comparar contra un inventario inicial de antes de hoy. En vez de eso, se mide el valor
     * del inventario de hoy antes y después de la venta, y se compara la diferencia.
     */
    @Test
    void calculaInventarioAlCostoComoLaDiferenciaDelCostoDeVenta() {
        LocalDate hoy = LocalDate.now();
        long antesDeVender = indicadores.calcularInventario(admin, matriz, hoy, hoy).inventarioFinalCentavos();

        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        IndicadoresService.InventarioResumen inv = indicadores.calcularInventario(admin, matriz, hoy, hoy);
        assertEquals(antesDeVender - 2100, inv.inventarioFinalCentavos());
        assertEquals((inv.inventarioInicialCentavos() + inv.inventarioFinalCentavos()) / 2,
                inv.inventarioPromedioCentavos());
        assertTrue(inv.diasInventario().isPresent());
        assertEquals(0L, inv.saldoCreditoCentavos());
    }

    @Test
    void calculaElConsolidadoConUnaFilaPorSucursal() {
        LocalDate hoy = LocalDate.now();
        List<Sucursal> sucursales = adm.sucursales().listar();
        List<IndicadoresService.ConsolidadoFila> filas = indicadores.calcularConsolidado(admin, sucursales, hoy, hoy);
        assertEquals(sucursales.size(), filas.size());
        assertTrue(filas.stream().anyMatch(f -> f.sucursal().id().equals(matriz)));
    }

    /** Le pone marca "Bimbo" al bolillo y lo vende: los kilos deben agruparse bajo esa marca, no "Sin marca". */
    @Test
    void calculaElDesgloseDeKilosPorMarca() {
        ProductoCatalogo bolilloPc = productoAdmin("Bolillo");
        adm.productos().guardar(new ProductoAdminService.Datos(bolilloPc.id(), bolilloPc.nombre(),
                bolilloPc.categoria(), "Bimbo", bolilloPc.clave(), bolilloPc.codigoInventario(), bolilloPc.unidad(),
                bolilloPc.sujetoMerma(), bolilloPc.disponibilidad(), bolilloPc.temporada(), bolilloPc.presentaciones(),
                List.of(), bolilloPc.gramajeGramos(), bolilloPc.mermaGramos(), bolilloPc.avisoSinVenta()), admin);
        catalogo.recargar();

        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        IndicadoresService.KilosResumen kr = indicadores.calcularKilos(admin, matriz, hoy, hoy);
        assertEquals(1, kr.porMarca().size());
        assertEquals("Bimbo", kr.porMarca().getFirst().marca());
        assertEquals(0, new BigDecimal("700").compareTo(kr.porMarca().getFirst().gramos()));
    }
}
