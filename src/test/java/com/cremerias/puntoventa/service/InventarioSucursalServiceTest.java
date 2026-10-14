package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.InventarioSucursalService.EstadoInventario;
import com.cremerias.puntoventa.service.InventarioSucursalService.FilaDetallada;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.admin.LimitesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detalle de productos por sucursal (costo, valor de inventario, vendido, estado), solo para administrador. */
class InventarioSucursalServiceTest {

    @TempDir
    Path carpeta;

    private Database db;
    private AuthService auth;
    private CatalogoService catalogo;
    private CajaService caja;
    private VentaService ventas;
    private Administracion adm;
    private InventarioSucursalService inventario;
    private Sesion sesionCajero;
    private Usuario admin;
    private Usuario supervisor;
    private String matriz;

    @BeforeEach
    void preparar() {
        db = new Database(carpeta.resolve("inventario.db"));
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
        inventario = new InventarioSucursalService(db);
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

    private FilaDetallada filaDe(String nombreProducto, LocalDate hoy) {
        String id = productoAdmin(nombreProducto).id();
        return inventario.listarDetallado(admin, sucursalMatriz(), hoy, hoy).stream()
                .filter(f -> f.fila().producto().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void soloElAdministradorPuedeVerElDetalleLosLotesYLosMovimientos() {
        LocalDate hoy = LocalDate.now();
        Sucursal s = sucursalMatriz();
        String bolilloId = productoAdmin("Bolillo").id();
        assertThrows(IllegalStateException.class, () -> inventario.listarDetallado(supervisor, s, hoy, hoy));
        assertThrows(IllegalStateException.class, () -> inventario.lotesDe(supervisor, bolilloId, matriz));
        assertThrows(IllegalStateException.class, () -> inventario.movimientos(supervisor, bolilloId, matriz, 10));
    }

    /** El bolillo (DatosDemo) arranca con 60 piezas a 210 centavos cada una (precio 300 · 70%). */
    @Test
    void calculaCostoPromedioYValorDelInventarioAlCosto() {
        LocalDate hoy = LocalDate.now();
        FilaDetallada f = filaDe("Bolillo", hoy);
        assertEquals(210L, f.costoPromedioCentavos());
        assertEquals(60 * 210L, f.valorInventarioCentavos());
        assertEquals(EstadoInventario.OK, f.estado());
    }

    @Test
    void calculaLoVendidoEnElPeriodoYLaUltimaVenta() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        FilaDetallada f = filaDe("Bolillo", hoy);
        assertEquals(0, new BigDecimal("10").compareTo(f.cantidadVendida()));
        assertEquals(3000L, f.importeVendidoCentavos());
        assertEquals(0L, f.diasSinVenta());
        // El valor del inventario ya refleja la venta: 50 piezas restantes a 210 c/u.
        assertEquals(50 * 210L, f.valorInventarioCentavos());
    }

    @Test
    void sinVentasNuncaLaUltimaVentaYLosDiasSinVentaQuedanVacios() {
        FilaDetallada f = filaDe("Bolillo", LocalDate.now());
        assertNull(f.ultimaVenta());
        assertNull(f.diasSinVenta());
        assertEquals(0, BigDecimal.ZERO.compareTo(f.cantidadVendida()));
    }

    /**
     * Con mínimo 55 el bolillo (60 piezas) empieza "Con existencia"; al vender 10 la existencia
     * (50) cae por debajo del mínimo y el sistema solo genera una orden de reabastecimiento,
     * así que el estado pasa a "Por reabastecer" (no simplemente "En mínimo").
     */
    @Test
    void elEstadoPasaAPorReabastecerCuandoSeGeneraUnaOrdenSola() {
        ProductoCatalogo bolilloPc = productoAdmin("Bolillo");
        Sucursal matrizSucursal = sucursalMatriz();
        adm.limites().guardar(matrizSucursal,
                List.of(new LimitesService.Cambio(bolilloPc, new BigDecimal("55"), new BigDecimal("100"))), admin);

        LocalDate hoy = LocalDate.now();
        assertEquals(EstadoInventario.OK, filaDe("Bolillo", hoy).estado());

        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        assertEquals(EstadoInventario.POR_REABASTECER, filaDe("Bolillo", hoy).estado());
    }

    @Test
    void elEstadoEsAgotadoSinExistencia() {
        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("60"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 18000, null)), sesionCajero, turno);

        LocalDate hoy = LocalDate.now();
        FilaDetallada f = filaDe("Bolillo", hoy);
        assertEquals(EstadoInventario.AGOTADO, f.estado());
        assertEquals(0L, f.valorInventarioCentavos());
    }

    @Test
    void lotesDeYMovimientosDevuelvenLosRegistrosDeEsaSucursal() {
        String bolilloId = productoAdmin("Bolillo").id();
        var lotes = inventario.lotesDe(admin, bolilloId, matriz);
        assertTrue(lotes.stream().allMatch(l -> l.sucursalId().equals(matriz)));
        assertEquals(1, lotes.size());

        Turno turno = caja.abrir(sesionCajero, 0);
        Producto bolillo = catalogo.buscar("bolillo", 1).getFirst();
        ventas.registrar(List.of(new VentaService.Renglon(bolillo, new BigDecimal("10"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), sesionCajero, turno);

        var movimientos = inventario.movimientos(admin, bolilloId, matriz, 10);
        assertTrue(movimientos.stream().anyMatch(m -> "VENTA".equals(m.tipo())));
    }
}
