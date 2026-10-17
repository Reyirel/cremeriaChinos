package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.admin.CompraService;
import com.cremerias.puntoventa.service.admin.CreditoService;
import com.cremerias.puntoventa.service.admin.PreciosService;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escenarios completos de punta a punta, con varios módulos a la vez (ver
 * docs/PRUEBAS_IMPLEMENTACION_2026.md). El escenario D (sin conexión, reintento sin duplicar) está en
 * {@code sync.SincronizacionModulosTest}.
 */
class EscenariosIntegralesTest extends EscenarioBase {

    private static final LocalDate HOY = LocalDate.now();

    private ProductoCatalogo productoNuevo(String nombre, List<Presentacion> presentaciones) {
        admin.productos().guardar(new ProductoAdminService.Datos(null, nombre, "Quesos", null, null, null, Unidad.KG, false,
                Disponibilidad.REGULAR, null, presentaciones, List.of(), new BigDecimal("1000"), BigDecimal.ZERO, null),
                administrador);
        return catalogoAdmin(nombre);
    }

    private CompraService.Proveedor proveedor(String nombre) {
        admin.compras().guardarProveedor(null, nombre, null, null, null, null, administrador);
        return admin.compras().proveedores().stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private IndicadoresService indicadores() {
        return new IndicadoresService(db, dispositivo, admin.gastos(), admin.nomina(), admin.credito());
    }

    /**
     * A. Compra con bonificación → surtido a crédito con margen de bodega → la sucursal recibe menos →
     * el administrador aclara y acredita → abonos hasta liquidar, sin saldo negativo.
     */
    @Test
    void escenarioA_compraSurtidoRecepcionCargoYAbonos() {
        ProductoCatalogo queso = productoNuevo("Queso de rancho escenario A",
                List.of(new Presentacion(null, null, "Kilo", true, BigDecimal.ONE, null, true, true)));
        // Compra: 20 kg pagados a $110 y 2 kg de regalo; con prorrateo el lote queda en $100 por kilo.
        CompraService.Detalle compra = admin.compras().registrar(Ids.nuevo(), proveedor("Lácteos del Valle"), "F-100", HOY,
                List.of(new CompraService.Linea(queso, null, new BigDecimal("20"), new BigDecimal("2"), 11000)), null,
                administrador);
        assertEquals(220000, compra.resumen().totalCentavos());
        assertEquals(10000, compra.lineas().getFirst().costoBaseCentavos());
        mismo("22", existencia(queso.id(), almacen.id()));

        // Margen de bodega a cremerías: 10 % sobre el costo (lo decide el negocio; aquí es dato de prueba).
        admin.precios().guardarRegla(new PreciosService.DatosRegla(null, ReglaPrecio.Canal.BODEGA_SUCURSAL,
                ReglaPrecio.Tipo.RECARGO_COSTO, BigDecimal.TEN, null, null, null, null, HOY.minusDays(1), null, null),
                administrador);
        SurtidoService.Detalle surtido = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("10"), Map.of(queso.principal().id(), 15000L))),
                null, administrador, null);
        assertEquals(100000, surtido.resumen().costoCentavos());
        assertEquals(110000, surtido.resumen().internoCentavos());
        assertEquals(110000, admin.credito().saldo(matriz.id()));
        mismo("12", existencia(queso.id(), almacen.id()));
        RecepcionSurtidoService recepciones = admin.recepciones();
        String surtidoId = surtido.resumen().id();
        assertEquals(RecepcionSurtidoService.Estado.ENVIADO, recepciones.estado(surtidoId));

        // La sucursal recibe 9.5 kg: el medio kilo que falta sale de su existencia y queda por aclarar.
        var porRecibir = recepciones.porRecibir(surtidoId);
        recepciones.recibir(Ids.nuevo(), surtidoId, List.of(new RecepcionSurtidoService.LineaRecibida(
                porRecibir.lineas().getFirst().surtidoDetalleId(), new BigDecimal("9.5"))), null, supervisor);
        assertEquals(RecepcionSurtidoService.Estado.CON_DIFERENCIA, recepciones.estado(surtidoId));
        mismo("9.5", existencia(queso.id(), matriz.id()));

        RecepcionSurtidoService.Diferencia dif = recepciones.diferencias(true, administrador).getFirst();
        assertEquals(5000, dif.costoCentavos());
        assertEquals(5500, dif.internoCentavos());
        recepciones.aclarar(Ids.nuevo(), dif, RecepcionSurtidoService.Decision.REINGRESO_ALMACEN, true,
                "Se quedó en la camioneta", administrador);
        assertEquals(RecepcionSurtidoService.Estado.ACLARADO, recepciones.estado(surtidoId));
        mismo("12.5", existencia(queso.id(), almacen.id()));
        assertEquals(104500, admin.credito().saldo(matriz.id()));

        // Abonos: el saldo baja; la nota queda con lo pendiente; un abono mayor al saldo no se acepta.
        CreditoService.Abono abono = admin.credito().abonar(matriz, 50000, "Transferencia", administrador);
        assertEquals(54500, abono.saldoNuevo());
        List<CreditoService.NotaPendiente> pendientes = admin.credito().notasPendientes(matriz.id(), HOY);
        assertEquals(1, pendientes.size());
        assertEquals(110000, pendientes.getFirst().importeCentavos());
        assertEquals(54500, pendientes.getFirst().pendienteCentavos());
        assertEquals(54500, admin.credito().antiguedad(matriz.id(), HOY).de0a30());
        assertThrows(RuntimeException.class, () -> admin.credito().abonar(matriz, 54501, null, administrador));
        admin.credito().abonar(matriz, 54500, "Liquida", administrador);
        assertEquals(0, admin.credito().saldo(matriz.id()));
        assertTrue(admin.credito().notasPendientes(matriz.id(), HOY).isEmpty());
        assertEquals(0, admin.credito().antiguedad(matriz.id(), HOY).total());
        // Historial de crédito con su concepto: cargo del surtido, nota por diferencia y dos abonos.
        assertEquals(List.of(CreditoService.Concepto.PAGO, CreditoService.Concepto.PAGO,
                        CreditoService.Concepto.DIFERENCIA_ENTREGA, CreditoService.Concepto.SURTIDO),
                admin.credito().movimientos(matriz.id()).stream().map(CreditoService.Movimiento::concepto).toList());
    }

    /**
     * B. 10 kg de queso; su presentación «Rallado» se prepara al venderse con 5 % de merma. Se venden
     * 350 g rallados: salen 350 g más su merma (18 g) del mismo queso, la merma queda registrada al
     * costo y la utilidad neta la descuenta.
     */
    @Test
    void escenarioB_quesoDerivadoConMermaCostoYUtilidad() {
        ProductoCatalogo queso = productoNuevo("Queso para rallar escenario B", List.of(
                new Presentacion(null, null, "Kilo", true, BigDecimal.ONE, null, true, true),
                new Presentacion(null, null, "Rallado", true, BigDecimal.ONE, "7509990000777", false, true,
                        new BigDecimal("5"))));
        String rallado = queso.presentaciones().stream().filter(p -> p.nombre().equals("Rallado")).findFirst()
                .orElseThrow().id();
        admin.compras().registrar(Ids.nuevo(), proveedor("Quesería Sierra"), null, HOY,
                List.of(new CompraService.Linea(queso, null, BigDecimal.TEN, BigDecimal.ZERO, 10000)), null, administrador);
        SurtidoService.Detalle surtido = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.EFECTIVO,
                List.of(new SurtidoService.Linea(queso, BigDecimal.TEN, Map.of(queso.principal().id(), 15000L,
                        rallado, 18000L))), null, administrador, null);
        var porRecibir = admin.recepciones().porRecibir(surtido.resumen().id());
        admin.recepciones().recibir(Ids.nuevo(), surtido.resumen().id(), List.of(new RecepcionSurtidoService.LineaRecibida(
                porRecibir.lineas().getFirst().surtidoDetalleId(), BigDecimal.TEN)), null, supervisor);

        Turno turno = caja.abrir(cajero, 0);
        catalogo.recargar();
        Producto articulo = catalogo.buscar("rallar escenario", 20).stream().filter(p -> p.id().equals(rallado))
                .findFirst().orElseThrow();
        Ticket ticket = vender(turno, articulo, "0.350");

        assertEquals(6300, ticket.totalCentavos()); // 0.350 kg × $180
        mismo("9.632", existencia(queso.id(), matriz.id())); // 10 − 0.350 − 0.018 de merma
        assertEquals("0.018", new BigDecimal(consultar("SELECT cantidad FROM mermas WHERE producto_id = '" + queso.id()
                + "'")).setScale(3).toPlainString());
        assertEquals(180, numero("SELECT costo_centavos FROM mermas WHERE producto_id = '" + queso.id() + "'"));

        IndicadoresService.Resumen r = indicadores().calcular(administrador, matriz.id(), HOY, HOY);
        assertEquals(6300, r.ventaNeta());
        assertEquals(3500, r.costoVenta()); // 0.350 kg al costo de su lote ($100)
        assertEquals(2800, r.utilidadBruta());
        IndicadoresService.UtilidadResumen u = indicadores().calcularUtilidad(administrador, matriz.id(), HOY, HOY);
        assertEquals(180, u.mermasCentavos());
        assertEquals(2800 - 180 - u.gastosCentavos() - u.nominaCentavos(), u.utilidadNeta());
    }

    /**
     * C. Una venta con mayoreo se devuelve en dos partes: cada reembolso es al precio que de verdad se
     * cobró, lo vendible regresa al inventario, el corte y los indicadores se ajustan y el ticket
     * original no cambia.
     */
    @Test
    void escenarioC_devolucionParcialDeUnaVentaConMayoreo() {
        ProductoCatalogo refresco = catalogoAdmin("Refresco de cola 600 ml");
        admin.precios().guardarEscala(new PreciosService.DatosEscala(refresco.id(), null, null, Escala.Nivel.MAYOREO,
                new BigDecimal("12"), Escala.Tipo.DESCUENTO_PCT, BigDecimal.TEN, null, HOY.minusDays(1), null),
                administrador);
        BigDecimal antes = existencia(refresco.id(), matriz.id());
        Turno turno = caja.abrir(cajero, 0);
        Ticket t = vender(turno, articulo("Refresco de cola 600 ml"), "12");
        assertEquals(21600, t.totalCentavos()); // 12 × $20 − 10 %
        long costoVendido = indicadores().calcular(administrador, matriz.id(), HOY, HOY).costoVenta();

        DevolucionService devoluciones = new DevolucionService(db, dispositivo);
        String renglon = devoluciones.buscarVenta(t.folio(), matriz.id()).orElseThrow().renglones().getFirst().ventaDetalleId();
        DevolucionService.Resultado primera = devoluciones.registrar(Ids.nuevo(), t.ventaId(),
                List.of(new DevolucionService.LineaDevolucion(renglon, new BigDecimal("3"),
                        DevolucionService.EstadoProducto.VENDIBLE)),
                DevolucionService.Tipo.DEVOLUCION, DevolucionService.Metodo.EFECTIVO, "Le sobraron", turno,
                cajero.usuario(), supervisor);
        assertEquals(5400, primera.reembolsoCentavos()); // a $18 cada uno, como se cobró
        mismo(antes.subtract(new BigDecimal("9")).toPlainString(), existencia(refresco.id(), matriz.id()));
        assertEquals(21600 - 5400, caja.resumen(turno).efectivoEsperado());
        IndicadoresService.Resumen r = indicadores().calcular(administrador, matriz.id(), HOY, HOY);
        assertEquals(16200, r.ventaNeta());
        assertEquals(costoVendido * 9 / 12, r.costoVenta());

        DevolucionService.Resultado resto = devoluciones.registrar(Ids.nuevo(), t.ventaId(),
                List.of(new DevolucionService.LineaDevolucion(renglon, new BigDecimal("9"),
                        DevolucionService.EstadoProducto.VENDIBLE)),
                DevolucionService.Tipo.DEVOLUCION, DevolucionService.Metodo.EFECTIVO, "Ya no lo quiso", turno,
                cajero.usuario(), supervisor);
        assertEquals(16200, resto.reembolsoCentavos());
        mismo(antes.toPlainString(), existencia(refresco.id(), matriz.id()));
        assertEquals(0, caja.resumen(turno).efectivoEsperado());
        assertEquals(0, indicadores().calcular(administrador, matriz.id(), HOY, HOY).ventaNeta());
        assertEquals(0, indicadores().calcular(administrador, matriz.id(), HOY, HOY).costoVenta());
        assertThrows(RuntimeException.class, () -> devoluciones.registrar(Ids.nuevo(), t.ventaId(),
                List.of(new DevolucionService.LineaDevolucion(renglon, BigDecimal.ONE, DevolucionService.EstadoProducto.VENDIBLE)),
                DevolucionService.Tipo.DEVOLUCION, DevolucionService.Metodo.EFECTIVO, "Otra", turno, cajero.usuario(),
                supervisor));
        // El ticket original queda como se cobró (el historial no se reescribe).
        Ticket original = ventas.ticket(t.ventaId());
        assertEquals(21600, original.totalCentavos());
        assertEquals(Escala.Nivel.MAYOREO, original.renglones().getFirst().nivel());
    }
}
