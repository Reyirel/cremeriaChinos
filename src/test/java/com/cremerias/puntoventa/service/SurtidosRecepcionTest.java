package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.admin.DevolucionSucursalService;
import com.cremerias.puntoventa.service.admin.PreciosService;
import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService;
import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService.Estado;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recepción de surtidos por la sucursal, diferencias, cancelación y devolución de sucursal a bodega. */
class SurtidosRecepcionTest extends EscenarioBase {

    private RecepcionSurtidoService recepciones;
    private ProductoCatalogo queso;

    @BeforeEach
    void datos() {
        recepciones = admin.recepciones();
        queso = catalogoAdmin("Queso Oaxaca");
        // Margen de bodega de 10 % para que el precio interno se distinga del costo.
        admin.precios().guardarRegla(new PreciosService.DatosRegla(null, ReglaPrecio.Canal.BODEGA_SUCURSAL,
                ReglaPrecio.Tipo.RECARGO_COSTO, BigDecimal.TEN, null, null, null, null, LocalDate.now().minusDays(1),
                null, null), administrador);
    }

    private SurtidoService.Detalle surtir(Sucursal destino, String kilos, SurtidoService.FormaPago forma) {
        return admin.surtidos().registrar(destino, forma, List.of(new SurtidoService.Linea(queso, new BigDecimal(kilos),
                Map.of(queso.principal().id(), 21000L))), null, administrador, null);
    }

    private String recibir(SurtidoService.Detalle surtido, String kilos, com.cremerias.puntoventa.model.Usuario quien) {
        var porRecibir = recepciones.porRecibir(surtido.resumen().id());
        return recepciones.recibir(Ids.nuevo(), surtido.resumen().id(), List.of(new RecepcionSurtidoService.LineaRecibida(
                porRecibir.lineas().getFirst().surtidoDetalleId(), new BigDecimal(kilos))), null, quien);
    }

    @Test
    void laSucursalConfirmaQueRecibioTodo() {
        var surtido = surtir(matriz, "10", SurtidoService.FormaPago.EFECTIVO);
        assertEquals(Estado.ENVIADO, recepciones.estado(surtido.resumen().id()));
        assertEquals(1, recepciones.pendientes(matriz.id(), supervisor).size());

        String folio = recibir(surtido, "10", supervisor);

        assertTrue(folio.startsWith("REC-"));
        assertEquals(Estado.RECIBIDO, recepciones.estado(surtido.resumen().id()));
        assertTrue(recepciones.pendientes(matriz.id(), supervisor).isEmpty());
        assertThrows(IllegalStateException.class, () -> recibir(surtido, "10", supervisor));
        assertThrows(IllegalStateException.class, () -> recibir(surtir(matriz, "1", SurtidoService.FormaPago.EFECTIVO),
                "1", cajero.usuario()));
    }

    @Test
    void loQueNoLlegoSaleDeLaSucursalYElAdministradorLoAclara() {
        BigDecimal enSucursal = existencia(queso.id(), matriz.id());
        BigDecimal enAlmacen = existencia(queso.id(), almacen.id());
        var surtido = surtir(matriz, "10", SurtidoService.FormaPago.CREDITO);
        long saldo = admin.credito().saldo(matriz.id());
        assertEquals(146300, saldo); // 10 kg × $133 + 10 %

        recibir(surtido, "9", supervisor);
        assertEquals(Estado.CON_DIFERENCIA, recepciones.estado(surtido.resumen().id()));
        // No se cuenta en dos lugares: la sucursal solo tiene lo que llegó.
        mismo(enSucursal.add(new BigDecimal("9")).toPlainString(), existencia(queso.id(), matriz.id()));

        List<RecepcionSurtidoService.Diferencia> pendientes = recepciones.diferencias(true, administrador);
        assertEquals(1, pendientes.size());
        RecepcionSurtidoService.Diferencia dif = pendientes.getFirst();
        mismo("1", dif.cantidad());
        assertEquals(13300, dif.costoCentavos());
        assertEquals(14630, dif.internoCentavos());

        recepciones.aclarar(Ids.nuevo(), dif, RecepcionSurtidoService.Decision.REINGRESO_ALMACEN, true, "Se quedó en la camioneta",
                administrador);

        assertEquals(Estado.ACLARADO, recepciones.estado(surtido.resumen().id()));
        mismo(enAlmacen.subtract(new BigDecimal("9")).toPlainString(), existencia(queso.id(), almacen.id()));
        assertEquals(saldo - 14630, admin.credito().saldo(matriz.id()));
        assertTrue(recepciones.diferencias(true, administrador).isEmpty());
        assertThrows(IllegalStateException.class, () -> recepciones.aclarar(Ids.nuevo(), dif,
                RecepcionSurtidoService.Decision.PERDIDA, false, null, administrador));
    }

    @Test
    void unaPerdidaEnElTrasladoNoRegresaAlAlmacenNiSeAcreditaSiNoSePide() {
        BigDecimal enAlmacen = existencia(queso.id(), almacen.id());
        var surtido = surtir(matriz, "5", SurtidoService.FormaPago.CREDITO);
        long saldo = admin.credito().saldo(matriz.id());
        recibir(surtido, "4.5", supervisor);
        var dif = recepciones.diferencias(true, administrador).getFirst();
        recepciones.aclarar(Ids.nuevo(), dif, RecepcionSurtidoService.Decision.PERDIDA, false, null, administrador);
        mismo(enAlmacen.subtract(new BigDecimal("5")).toPlainString(), existencia(queso.id(), almacen.id()));
        assertEquals(saldo, admin.credito().saldo(matriz.id()));
    }

    @Test
    void noPuedeFaltarLoQueLaSucursalYaVendioDeEseSurtido() {
        admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        Sucursal sur = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("SUR")).findFirst().orElseThrow();
        var surtido = surtir(sur, "2", SurtidoService.FormaPago.EFECTIVO);
        // Se registra una salida del lote de la sucursal (ajuste) antes de confirmar.
        new AjusteService(db, dispositivo).registrar(Ids.nuevo(), sur, queso, AjusteService.Motivo.DANO,
                new BigDecimal("-1.5"), null, null, null, administrador);
        assertThrows(IllegalStateException.class, () -> recibir(surtido, "0", administrador));
    }

    @Test
    void seCancelaUnSurtidoSinRecibirNiVenderYTodoRegresa() {
        BigDecimal enAlmacen = existencia(queso.id(), almacen.id());
        BigDecimal enSucursal = existencia(queso.id(), matriz.id());
        LoteRepository.Lote loteAlmacen = admin.almacen().lotes(queso.id(), almacen.id()).getFirst();
        var surtido = surtir(matriz, "3", SurtidoService.FormaPago.CREDITO);

        String folio = recepciones.cancelar(Ids.nuevo(), surtido.resumen().id(), "Se capturó a la sucursal equivocada",
                administrador);

        assertTrue(folio.startsWith("CSU-"));
        assertEquals(Estado.CANCELADO, recepciones.estado(surtido.resumen().id()));
        mismo(enAlmacen.toPlainString(), existencia(queso.id(), almacen.id()));
        mismo(enSucursal.toPlainString(), existencia(queso.id(), matriz.id()));
        // Regresó al mismo lote del almacén de donde salió.
        mismo(loteAlmacen.existencia().toPlainString(),
                admin.almacen().lotes(queso.id(), almacen.id()).getFirst().existencia());
        assertEquals(0, admin.credito().saldo(matriz.id()));
        assertTrue(recepciones.pendientes(matriz.id(), administrador).isEmpty());
        assertThrows(IllegalStateException.class, () -> recepciones.cancelar(Ids.nuevo(), surtido.resumen().id(), "Otra",
                administrador));
    }

    @Test
    void noSeCancelaLoQueYaSeRecibioOSeVendio() {
        var recibido = surtir(matriz, "2", SurtidoService.FormaPago.EFECTIVO);
        recibir(recibido, "2", supervisor);
        assertThrows(IllegalStateException.class, () -> recepciones.cancelar(Ids.nuevo(), recibido.resumen().id(),
                "Ya no", administrador));

        admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        Sucursal sur = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("SUR")).findFirst().orElseThrow();
        var vendido = surtir(sur, "2", SurtidoService.FormaPago.EFECTIVO);
        new AjusteService(db, dispositivo).registrar(Ids.nuevo(), sur, queso, AjusteService.Motivo.DANO,
                new BigDecimal("-0.5"), null, null, null, administrador);
        assertThrows(IllegalStateException.class, () -> recepciones.cancelar(Ids.nuevo(), vendido.resumen().id(),
                "Ya no", administrador));
    }

    @Test
    void laSucursalDevuelveALaBodegaYSeLeAcreditaElPrecioInterno() {
        admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        Sucursal sur = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("SUR")).findFirst().orElseThrow();
        surtir(sur, "10", SurtidoService.FormaPago.CREDITO);
        long saldo = admin.credito().saldo(sur.id());
        BigDecimal enAlmacen = existencia(queso.id(), almacen.id());

        String folio = admin.devolucionesSucursal().registrar(Ids.nuevo(), sur, List.of(
                        new DevolucionSucursalService.Linea(queso, new BigDecimal("2"), DevolucionService.EstadoProducto.VENDIBLE),
                        new DevolucionSucursalService.Linea(queso, BigDecimal.ONE, DevolucionService.EstadoProducto.DANADO)),
                "No se vendió", true, administrador);

        assertTrue(folio.startsWith("DVS-"));
        mismo("7", existencia(queso.id(), sur.id()));
        mismo(enAlmacen.add(new BigDecimal("2")).toPlainString(), existencia(queso.id(), almacen.id())); // lo dañado no
        assertEquals(saldo - 3 * 14630, admin.credito().saldo(sur.id()));
        var lista = admin.devolucionesSucursal().listar(LocalDate.now(), LocalDate.now(), null, administrador);
        assertEquals(3 * 13300, lista.getFirst().costoCentavos());
        assertEquals(3 * 14630, lista.getFirst().notaCreditoCentavos());
    }

    @Test
    void elSupervisorRecibeSoloSuSucursal() {
        admin.sucursales().guardar(null, "SUR", "Cremería Sur", null, null, administrador);
        Sucursal sur = admin.sucursales().listar().stream().filter(s -> s.codigo().equals("SUR")).findFirst().orElseThrow();
        var surtido = surtir(sur, "1", SurtidoService.FormaPago.EFECTIVO);
        assertThrows(IllegalStateException.class, () -> recibir(surtido, "1", supervisor));
        assertThrows(IllegalStateException.class, () -> recepciones.pendientes(sur.id(), supervisor));
        recibir(surtido, "1", administrador);
        assertEquals(Estado.RECIBIDO, recepciones.estado(surtido.resumen().id()));
    }
}
