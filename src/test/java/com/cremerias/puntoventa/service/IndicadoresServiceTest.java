package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
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
        indicadores = new IndicadoresService(db, dispositivo);
        sesionCajero = ((ResultadoLogin.Exitoso) auth.iniciarSesion("cajero", "cajero123".toCharArray(),
                ModoConexion.OFFLINE)).sesion();
        admin = ((ResultadoLogin.Exitoso) auth.iniciarSesion("admin", "admin123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
        supervisor = ((ResultadoLogin.Exitoso) auth.iniciarSesion("supervisor", "super123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
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
        assertThrows(IllegalStateException.class,
                () -> indicadores.guardarMeta(supervisor, matriz, hoy.getYear(), hoy.getMonthValue(), 100000));
        String folio = indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 100000);
        assertTrue(folio.startsWith("MET-"));
        assertEquals(100000L, indicadores.metaMensual(admin, matriz, hoy.getYear(), hoy.getMonthValue()).orElseThrow());
    }

    @Test
    void calculaCumplimientoYMetaDiariaConLaMetaCapturada() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 6000);

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
        indicadores.guardarMeta(admin, matriz, hoy.getYear(), hoy.getMonthValue(), 6000);

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
}
