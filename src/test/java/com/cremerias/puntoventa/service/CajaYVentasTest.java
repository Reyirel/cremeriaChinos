package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.SucursalRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.util.CodigoBarras;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CajaYVentasTest {

    @TempDir
    Path carpeta;

    private Database db;
    private AuthService auth;
    private CatalogoService catalogo;
    private CajaService caja;
    private VentaService ventas;
    private Sesion sesion;

    @BeforeEach
    void preparar() {
        db = new Database(carpeta.resolve("caja.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        String sucursal = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(sucursal);
        String dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, sucursal));
        auth = new AuthService(db, hasher, Clock.systemUTC(), dispositivo);
        catalogo = new CatalogoService(db, sucursal);
        catalogo.recargar();
        caja = new CajaService(db, dispositivo);
        ventas = new VentaService(db, dispositivo);
        sesion = ((ResultadoLogin.Exitoso) auth.iniciarSesion("cajero", "cajero123".toCharArray(),
                ModoConexion.OFFLINE)).sesion();
    }

    private Producto producto(String nombre) {
        return catalogo.buscar(nombre, 1).getFirst();
    }

    @Test
    void ventaEnEfectivoCalculaCambioYDescuentaInventario() {
        Turno turno = caja.abrir(sesion, 50000);
        Producto leche = producto("leche entera");
        Producto oaxaca = producto("queso oaxaca");
        BigDecimal existenciaLeche = leche.existencia();

        Ticket ticket = ventas.registrar(List.of(
                        new VentaService.Renglon(leche, new BigDecimal("2")),
                        new VentaService.Renglon(oaxaca, new BigDecimal("0.500"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 20000, null)), sesion, turno);

        assertEquals(2 * 2800 + 9500, ticket.totalCentavos());
        assertEquals(20000 - 15100, ticket.cambioCentavos());
        assertTrue(ticket.folio().startsWith("MATRIZ-"));
        catalogo.recargar();
        assertEquals(existenciaLeche.subtract(new BigDecimal("2")).setScale(3), producto("leche entera").existencia());

        ResumenTurno resumen = caja.resumen(turno);
        assertEquals(1, resumen.numeroVentas());
        assertEquals(15100, resumen.ventasEfectivo());
        assertEquals(50000 + 15100, resumen.efectivoEsperado());
    }

    @Test
    void losFoliosSonConsecutivos() {
        Turno turno = caja.abrir(sesion, 0);
        Producto p = producto("bolillo");
        Ticket a = ventas.registrar(List.of(new VentaService.Renglon(p, BigDecimal.ONE)),
                List.of(new Pago(MetodoPago.EFECTIVO, 300, null)), sesion, turno);
        Ticket b = ventas.registrar(List.of(new VentaService.Renglon(p, BigDecimal.ONE)),
                List.of(new Pago(MetodoPago.TARJETA, 300, "123")), sesion, turno);
        assertTrue(a.folio().endsWith("000001"));
        assertTrue(b.folio().endsWith("000002"));
        assertNotEquals(a.folio(), b.folio());
    }

    @Test
    void pagoMixtoYValidaciones() {
        assertEquals(0, VentaService.calcularCambio(10000, List.of(
                new Pago(MetodoPago.EFECTIVO, 4000, null), new Pago(MetodoPago.TARJETA, 6000, null))));
        assertThrows(IllegalArgumentException.class, () -> VentaService.calcularCambio(10000,
                List.of(new Pago(MetodoPago.EFECTIVO, 5000, null))));
        // No se da cambio de un pago con tarjeta.
        assertThrows(IllegalArgumentException.class, () -> VentaService.calcularCambio(10000,
                List.of(new Pago(MetodoPago.TARJETA, 12000, null))));
        assertEquals(500, VentaService.calcularCambio(10000, List.of(
                new Pago(MetodoPago.EFECTIVO, 5500, null), new Pago(MetodoPago.TARJETA, 5000, null))));
    }

    @Test
    void noSeVendeMasDeLoQueHay() {
        Turno turno = caja.abrir(sesion, 0);
        Producto leche = producto("leche entera");
        assertEquals(0, new BigDecimal("48").compareTo(leche.existencia()));
        List<Pago> pago = List.of(new Pago(MetodoPago.EFECTIVO, 10_000_000, null));

        var error = assertThrows(VentaService.ExistenciaInsuficienteException.class, () -> ventas.registrar(
                List.of(new VentaService.Renglon(leche, new BigDecimal("49"))), pago, sesion, turno));
        assertEquals(0, new BigDecimal("48").compareTo(error.faltantes().getFirst().existencia()));
        assertTrue(error.getMessage().contains("solo hay 48 pzas y se piden 49 pzas"), error.getMessage());

        // No se guardó nada: ni la venta ni la salida de inventario.
        assertEquals(0, caja.resumen(turno).numeroVentas());
        catalogo.recargar();
        assertEquals(0, new BigDecimal("48").compareTo(producto("leche entera").existencia()));
    }

    @Test
    void lasPresentacionesDeUnMismoProductoSeSumanContraLaExistencia() {
        Turno turno = caja.abrir(sesion, 0);
        Producto pieza = producto("leche entera");
        Producto cajaDe12 = producto("leche entera 1 l caja");
        List<Pago> pago = List.of(new Pago(MetodoPago.EFECTIVO, 10_000_000, null));

        // 4 cajas (48 piezas) + 1 pieza = 49 de 48.
        assertThrows(VentaService.ExistenciaInsuficienteException.class, () -> ventas.registrar(List.of(
                new VentaService.Renglon(cajaDe12, new BigDecimal("4")),
                new VentaService.Renglon(pieza, BigDecimal.ONE)), pago, sesion, turno));

        ventas.registrar(List.of(new VentaService.Renglon(cajaDe12, new BigDecimal("4"))), pago, sesion, turno);
        catalogo.recargar();
        Producto agotada = producto("leche entera");
        assertTrue(agotada.sinExistencia());
        var error = assertThrows(VentaService.ExistenciaInsuficienteException.class, () -> ventas.registrar(
                List.of(new VentaService.Renglon(agotada, BigDecimal.ONE)), pago, sesion, turno));
        assertTrue(error.getMessage().contains("ya no tiene existencia"), error.getMessage());
    }

    @Test
    void aGranelNoSeVendeMasDelPesoQueHay() {
        Turno turno = caja.abrir(sesion, 0);
        Producto oaxaca = producto("queso oaxaca");
        BigDecimal hay = oaxaca.existencia();
        List<Pago> pago = List.of(new Pago(MetodoPago.EFECTIVO, 10_000_000, null));

        assertThrows(VentaService.ExistenciaInsuficienteException.class, () -> ventas.registrar(
                List.of(new VentaService.Renglon(oaxaca, hay.add(new BigDecimal("0.001")))), pago, sesion, turno));
        ventas.registrar(List.of(new VentaService.Renglon(oaxaca, hay)), pago, sesion, turno);
        catalogo.recargar();
        assertEquals(0, BigDecimal.ZERO.compareTo(producto("queso oaxaca").existencia()));
    }

    @Test
    void cancelarVentaRegresaInventarioYNoCuentaEnElCorte() {
        Turno turno = caja.abrir(sesion, 10000);
        Producto p = producto("refresco");
        BigDecimal antes = p.existencia();
        Ticket t = ventas.registrar(List.of(new VentaService.Renglon(p, new BigDecimal("3"))),
                List.of(new Pago(MetodoPago.EFECTIVO, 6000, null)), sesion, turno);

        Usuario supervisor = ((ResultadoAutorizacion.Autorizado) auth.autorizar("supervisor", "super123".toCharArray(),
                EnumSet.of(Rol.SUPERVISOR, Rol.ADMINISTRADOR), "prueba")).autorizador();
        ventas.cancelar(t.ventaId(), turno, sesion.usuario(), supervisor, "Devolución");

        catalogo.recargar();
        assertEquals(antes, producto("refresco").existencia());
        ResumenTurno r = caja.resumen(turno);
        assertEquals(0, r.numeroVentas());
        assertEquals(1, r.numeroCanceladas());
        assertEquals(10000, r.efectivoEsperado());
        assertThrows(IllegalStateException.class,
                () -> ventas.cancelar(t.ventaId(), turno, sesion.usuario(), supervisor, "otra vez"));
    }

    @Test
    void unCajeroNoPuedeAutorizar() {
        var resultado = auth.autorizar("cajero", "cajero123".toCharArray(), EnumSet.of(Rol.SUPERVISOR), "prueba");
        assertInstanceOf(ResultadoAutorizacion.Denegado.class, resultado);
    }

    @Test
    void movimientosYCorteDeCaja() {
        Turno turno = caja.abrir(sesion, 50000);
        caja.registrarMovimiento(turno, CajaService.TipoMovimiento.ENTRADA, 10000, "Morralla", sesion.usuario(), null);
        caja.registrarMovimiento(turno, CajaService.TipoMovimiento.RETIRO, 30000, "Retiro parcial", sesion.usuario(), null);
        assertThrows(IllegalArgumentException.class, () -> caja.registrarMovimiento(turno,
                CajaService.TipoMovimiento.RETIRO, 999999, "Demasiado", sesion.usuario(), null));

        ResumenTurno cierre = caja.cerrar(turno, 29000, "Faltaron 10 pesos", sesion.usuario());
        assertEquals(30000, cierre.efectivoEsperado());
        assertTrue(caja.turnoAbierto().isEmpty());
        long diferencia = db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery("SELECT diferencia_centavos FROM turnos_caja");
            rs.next();
            return rs.getLong(1);
        });
        assertEquals(-1000, diferencia);
    }

    @Test
    void soloUnTurnoAbiertoPorCaja() {
        caja.abrir(sesion, 0);
        assertThrows(IllegalStateException.class, () -> caja.abrir(sesion, 0));
    }

    @Test
    void ventasEnEsperaYAutoguardado() {
        VentaEsperaService espera = new VentaEsperaService(db);
        var linea = new com.cremerias.puntoventa.model.LineaVenta(producto("bolillo"), new BigDecimal("4"));
        espera.autoguardar(sesion.usuario().id(), List.of(linea));
        assertEquals(1, espera.autoguardado(sesion.usuario().id()).orElseThrow().size());

        espera.ponerEnEspera(sesion.usuario().id(), "Señora", List.of(linea));
        assertTrue(espera.autoguardado(sesion.usuario().id()).isEmpty());
        assertEquals(1, espera.contar());
        assertEquals(1200, espera.listar().getFirst().totalCentavos());

        var renglones = espera.recuperar(espera.listar().getFirst().id());
        assertEquals(new BigDecimal("4.000"), renglones.getFirst().cantidad());
        assertEquals(0, espera.contar());
    }

    @Test
    void lasVentasSeEncolanParaSincronizar() {
        Turno turno = caja.abrir(sesion, 0);
        ventas.registrar(List.of(new VentaService.Renglon(producto("bolillo"), BigDecimal.ONE)),
                List.of(new Pago(MetodoPago.EFECTIVO, 300, null)), sesion, turno);
        int pendientes = db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM sync_outbox WHERE tabla IN ('ventas', 'turnos_caja', 'movimientos_inventario')"
                            + " AND enviado_en IS NULL AND registro_id NOT IN (SELECT id FROM movimientos_inventario WHERE tipo = 'ENTRADA')");
            rs.next();
            return rs.getInt(1);
        });
        assertEquals(3, pendientes);
    }

    @Test
    void busquedaSinAcentosYEtiquetaDeBascula() {
        assertEquals("Queso cotija añejo", catalogo.buscar("cotija anejo", 5).getFirst().nombre());
        assertEquals("Requesón", catalogo.buscar("REQUESON", 5).getFirst().nombre());
        assertTrue(catalogo.buscar("queso", 50).size() >= 7);

        String etiqueta = CodigoBarras.completarEan13("200010100750"); // PLU 101, 750 g
        var escaneo = catalogo.resolverCodigo(etiqueta).orElseThrow();
        assertEquals("Queso Oaxaca", escaneo.producto().nombre());
        assertEquals(new BigDecimal("0.750"), escaneo.cantidad());

        assertEquals("Queso Oaxaca", catalogo.resolverCodigo("101").orElseThrow().producto().nombre());
        Producto leche = producto("leche entera");
        assertEquals(leche.id(), catalogo.resolverCodigo(leche.codigoBarras()).orElseThrow().producto().id());
    }

    @Test
    void elCatalogoEsDeLaSucursalDeQuienEntraALaCaja() {
        // La terminal arranca sin sucursal (p. ej. antes de dar de alta la primera): no muestra nada.
        CatalogoService enCaja = new CatalogoService(db, null);
        enCaja.recargar();
        assertTrue(enCaja.buscar("leche", 5).isEmpty());

        enCaja.cargarSucursal(sesion.usuario().sucursalId());
        assertEquals("Leche entera 1 L", enCaja.buscar("leche entera", 5).getFirst().nombre());

        // Otra sucursal a la que todavía no se le surte no tiene productos con precio.
        String otra = Ids.nuevo();
        db.enTransaccion(c -> {
            new SucursalRepository().insertar(c, otra, "NORTE", "Cremería Norte");
            return null;
        });
        enCaja.cargarSucursal(otra);
        assertTrue(enCaja.buscar("leche", 5).isEmpty());
    }
}
