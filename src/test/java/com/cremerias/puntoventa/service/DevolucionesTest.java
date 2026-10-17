package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.service.admin.PreciosService;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Devoluciones de clientes: parcial, total, cambio, estado del producto, caja y autorización. */
class DevolucionesTest extends EscenarioBase {

    private DevolucionService devoluciones;
    private Turno turno;

    @BeforeEach
    void caja() {
        devoluciones = new DevolucionService(db, dispositivo);
        turno = caja.abrir(cajero, 0);
    }

    private DevolucionService.VentaDevolvible venta(Ticket t) {
        return devoluciones.buscarVenta(t.folio(), matriz.id()).orElseThrow();
    }

    private DevolucionService.Resultado devolver(Ticket t, int renglon, String cantidad,
                                                 DevolucionService.EstadoProducto estado, DevolucionService.Metodo metodo) {
        String detalle = venta(t).renglones().get(renglon).ventaDetalleId();
        return devoluciones.registrar(Ids.nuevo(), t.ventaId(),
                List.of(new DevolucionService.LineaDevolucion(detalle, new BigDecimal(cantidad), estado)),
                DevolucionService.Tipo.DEVOLUCION, metodo, "El cliente no lo quiso", turno, cajero.usuario(), supervisor);
    }

    @Test
    void unaDevolucionParcialRegresaLoVendibleYSeRestaDelEfectivoDelCorte() {
        Producto refresco = articulo("Refresco de cola 600 ml");
        String productoId = refresco.productoId();
        BigDecimal antes = existencia(productoId, matriz.id());
        Ticket t = vender(turno, refresco, "5");
        mismo(antes.subtract(BigDecimal.valueOf(5)).toPlainString(), existencia(productoId, matriz.id()));

        DevolucionService.Resultado r = devolver(t, 0, "2", DevolucionService.EstadoProducto.VENDIBLE,
                DevolucionService.Metodo.EFECTIVO);

        assertTrue(r.folio().startsWith("DEV-"));
        assertEquals(4000, r.reembolsoCentavos());
        mismo(antes.subtract(BigDecimal.valueOf(3)).toPlainString(), existencia(productoId, matriz.id()));
        ResumenTurno resumen = caja.resumen(turno);
        assertEquals(1, resumen.numeroDevoluciones());
        assertEquals(4000, resumen.reembolsosEfectivo());
        assertEquals(10000 - 4000, resumen.efectivoEsperado());
        assertEquals(10000 - 4000, resumen.ventaNeta());
        // La venta original no se toca: sigue completa con sus 5 piezas.
        assertFalse(ventas.ticket(t.ventaId()).cancelada());
        assertEquals(10000, ventas.ticket(t.ventaId()).totalCentavos());
    }

    @Test
    void noSeDevuelveMasDeLoVendidoYElUltimoRestoDevuelveExactoLoCobrado() {
        Producto refresco = articulo("Refresco de cola 600 ml");
        Ticket t = vender(turno, refresco, "3");
        devolver(t, 0, "1", DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO);
        assertThrows(IllegalArgumentException.class, () -> devolver(t, 0, "3",
                DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO));
        devolver(t, 0, "2", DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO);
        assertEquals(6000, venta(t).devueltoCentavos());
        assertFalse(venta(t).algoPorDevolver());
        assertThrows(IllegalArgumentException.class, () -> devolver(t, 0, "1",
                DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO));
    }

    @Test
    void loDanadoOCaducadoNoRegresaAlInventarioVendibleYQuedaValuadoAlCosto() {
        Producto leche = articulo("Leche entera 1 L");
        BigDecimal antes = existencia(leche.productoId(), matriz.id());
        Ticket t = vender(turno, leche, "2");
        devolver(t, 0, "1", DevolucionService.EstadoProducto.CADUCADO, DevolucionService.Metodo.EFECTIVO);
        mismo(antes.subtract(BigDecimal.valueOf(2)).toPlainString(), existencia(leche.productoId(), matriz.id()));
        var lista = devoluciones.listar(matriz.id(), LocalDate.now(), LocalDate.now(), administrador);
        assertEquals(1, lista.size());
        assertEquals(1960, lista.getFirst().costoNoVendibleCentavos()); // costo del lote de ejemplo: $19.60
        assertEquals(2800, lista.getFirst().reembolsoCentavos());
    }

    @Test
    void seDevuelveLoQueSeCobroConMayoreoNoElPrecioDeLista() {
        admin.precios().guardarEscala(new PreciosService.DatosEscala(catalogoAdmin("Refresco de cola 600 ml").id(), null,
                null, Escala.Nivel.MAYOREO, new BigDecimal("12"), Escala.Tipo.DESCUENTO_PCT, new BigDecimal("15"), null,
                LocalDate.now().minusDays(1), null), administrador);
        Ticket t = vender(turno, articulo("Refresco de cola 600 ml"), "12");
        assertEquals(20400, t.totalCentavos());
        DevolucionService.Resultado r = devolver(t, 0, "6", DevolucionService.EstadoProducto.VENDIBLE,
                DevolucionService.Metodo.EFECTIVO);
        assertEquals(10200, r.reembolsoCentavos());
    }

    @Test
    void elEfectivoNoPasaDeLoQueHayEnLaCajaYConTarjetaNoTocaElCajon() {
        Producto queso = articulo("Queso Oaxaca");
        Ticket t = ventas.registrar(List.of(new VentaService.Renglon(queso, BigDecimal.ONE)),
                List.of(new Pago(MetodoPago.TARJETA, 19000, "1234")), cajero, turno);
        assertThrows(IllegalStateException.class, () -> devolver(t, 0, "0.5",
                DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO));
        DevolucionService.Resultado r = devolver(t, 0, "0.5", DevolucionService.EstadoProducto.VENDIBLE,
                DevolucionService.Metodo.TARJETA);
        assertEquals(9500, r.reembolsoCentavos());
        assertEquals(0, caja.resumen(turno).efectivoEsperado());
        assertEquals(9500, caja.resumen(turno).totalDevuelto());
    }

    @Test
    void autorizaUnSupervisorOAdministradorYElMotivoEsObligatorio() {
        Ticket t = vender(turno, articulo("Refresco de cola 600 ml"), "2");
        String detalle = venta(t).renglones().getFirst().ventaDetalleId();
        List<DevolucionService.LineaDevolucion> linea = List.of(new DevolucionService.LineaDevolucion(detalle,
                BigDecimal.ONE, DevolucionService.EstadoProducto.VENDIBLE));
        assertThrows(IllegalStateException.class, () -> devoluciones.registrar(Ids.nuevo(), t.ventaId(), linea,
                DevolucionService.Tipo.DEVOLUCION, DevolucionService.Metodo.EFECTIVO, "Error", turno, cajero.usuario(),
                cajero.usuario()));
        assertThrows(IllegalArgumentException.class, () -> devoluciones.registrar(Ids.nuevo(), t.ventaId(), linea,
                DevolucionService.Tipo.DEVOLUCION, DevolucionService.Metodo.EFECTIVO, " ", turno, cajero.usuario(),
                supervisor));
        assertEquals(0, numero("SELECT COUNT(*) FROM devoluciones_venta"));
    }

    @Test
    void unaVentaConDevolucionYaNoSeCancelaYUnaCanceladaNoSeDevuelve() {
        Ticket t = vender(turno, articulo("Refresco de cola 600 ml"), "2");
        devolver(t, 0, "1", DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO);
        assertThrows(IllegalStateException.class,
                () -> ventas.cancelar(t.ventaId(), turno, cajero.usuario(), supervisor, "Ya no"));

        Ticket otra = vender(turno, articulo("Refresco de cola 600 ml"), "1");
        ventas.cancelar(otra.ventaId(), turno, cajero.usuario(), supervisor, "Error");
        assertThrows(IllegalStateException.class, () -> devolver(otra, 0, "1",
                DevolucionService.EstadoProducto.VENDIBLE, DevolucionService.Metodo.EFECTIVO));
    }

    @Test
    void unCambioQuedaLigadoALaVentaDeLaMercanciaNueva() {
        Ticket t = vender(turno, articulo("Leche entera 1 L"), "1");
        String detalle = venta(t).renglones().getFirst().ventaDetalleId();
        String id = Ids.nuevo();
        devoluciones.registrar(id, t.ventaId(), List.of(new DevolucionService.LineaDevolucion(detalle, BigDecimal.ONE,
                        DevolucionService.EstadoProducto.VENDIBLE)), DevolucionService.Tipo.CAMBIO,
                DevolucionService.Metodo.EFECTIVO, "Quería deslactosada", turno, cajero.usuario(), supervisor);
        Ticket nueva = vender(turno, articulo("Leche deslactosada 1 L"), "1");
        devoluciones.ligarCambio(id, nueva.ventaId(), cajero.usuario());

        var lista = devoluciones.listar(matriz.id(), LocalDate.now(), LocalDate.now(), administrador);
        assertEquals(DevolucionService.Tipo.CAMBIO, lista.getFirst().tipo());
        assertEquals(nueva.folio(), lista.getFirst().ventaCambioFolio());
        assertThrows(IllegalStateException.class, () -> devoluciones.ligarCambio(id, nueva.ventaId(), cajero.usuario()));
        // En el cajón: entró la leche ($28), salió el reembolso ($28) y entró la deslactosada ($30).
        assertEquals(2800 - 2800 + 3000, caja.resumen(turno).efectivoEsperado());
    }

    @Test
    void repetirLaMismaDevolucionNoLaRegistraDosVeces() {
        Producto refresco = articulo("Refresco de cola 600 ml");
        BigDecimal antes = existencia(refresco.productoId(), matriz.id());
        Ticket t = vender(turno, refresco, "2");
        String detalle = venta(t).renglones().getFirst().ventaDetalleId();
        String id = Ids.nuevo();
        List<DevolucionService.LineaDevolucion> linea = List.of(new DevolucionService.LineaDevolucion(detalle,
                BigDecimal.ONE, DevolucionService.EstadoProducto.VENDIBLE));
        var primera = devoluciones.registrar(id, t.ventaId(), linea, DevolucionService.Tipo.DEVOLUCION,
                DevolucionService.Metodo.EFECTIVO, "Reintento", turno, cajero.usuario(), supervisor);
        var segunda = devoluciones.registrar(id, t.ventaId(), linea, DevolucionService.Tipo.DEVOLUCION,
                DevolucionService.Metodo.EFECTIVO, "Reintento", turno, cajero.usuario(), supervisor);
        assertEquals(primera.folio(), segunda.folio());
        assertEquals(1, numero("SELECT COUNT(*) FROM devoluciones_venta"));
        mismo(antes.subtract(BigDecimal.ONE).toPlainString(), existencia(refresco.productoId(), matriz.id()));
    }

    @Test
    void aGranelSeDevuelvenGramosConImporteProporcional() {
        Producto queso = articulo("Queso Oaxaca");
        BigDecimal antes = existencia(queso.productoId(), matriz.id());
        Ticket t = vender(turno, queso, "0.750");
        DevolucionService.Resultado r = devolver(t, 0, "0.250", DevolucionService.EstadoProducto.VENDIBLE,
                DevolucionService.Metodo.EFECTIVO);
        assertEquals(4750, r.reembolsoCentavos()); // 0.250 kg × $190
        mismo(antes.subtract(new BigDecimal("0.500")).toPlainString(), existencia(queso.productoId(), matriz.id()));
    }
}
