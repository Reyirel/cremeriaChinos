package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.admin.CompraService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compras con varias líneas, bonificaciones, costos por lote, transacción y devolución a proveedor. */
class ComprasTest extends EscenarioBase {

    private CompraService.Proveedor lala;

    @BeforeEach
    void proveedor() {
        admin.compras().guardarProveedor(null, "Lácteos del Norte", "LNO010101AAA", "555 100 2000", "Ana", null,
                administrador);
        lala = admin.compras().proveedores().getFirst();
    }

    private Presentacion caja() {
        return catalogoAdmin("Leche entera 1 L").presentaciones().stream()
                .filter(p -> p.nombre().equals("Caja 12 pzas")).findFirst().orElseThrow();
    }

    private CompraService.Linea linea(String producto, Presentacion presentacion, String pagada, String bonificada,
                                      long costo) {
        return new CompraService.Linea(catalogoAdmin(producto), presentacion, new BigDecimal(pagada),
                new BigDecimal(bonificada), costo);
    }

    private CompraService.Detalle comprar(CompraService.Linea... lineas) {
        return admin.compras().registrar(Ids.nuevo(), lala, "F-1001", LocalDate.now(), List.of(lineas), null,
                administrador);
    }

    @Test
    void unaCompraConVariasLineasCreaUnLotePorLineaConSuCosto() {
        String leche = catalogoAdmin("Leche entera 1 L").id();
        String queso = catalogoAdmin("Queso Oaxaca").id();
        BigDecimal lecheAntes = existencia(leche, almacen.id());
        BigDecimal quesoAntes = existencia(queso, almacen.id());

        // 10 cajas de leche a $300 la caja ($25 por pieza) y 20 kg de queso a $135 el kilo.
        CompraService.Detalle compra = comprar(linea("Leche entera 1 L", caja(), "10", "0", 30000),
                linea("Queso Oaxaca", null, "20", "0", 13500));

        assertTrue(compra.resumen().folio().startsWith("CMP-"));
        assertEquals(30000 * 10 + 13500 * 20, compra.resumen().totalCentavos());
        assertEquals(2, compra.lineas().size());
        mismo(lecheAntes.add(new BigDecimal("120")).toPlainString(), existencia(leche, almacen.id()));
        mismo(quesoAntes.add(new BigDecimal("20")).toPlainString(), existencia(queso, almacen.id()));
        assertEquals(2500, compra.lineas().get(0).costoBaseCentavos());
        assertEquals(13500, compra.lineas().get(1).costoBaseCentavos());
        assertEquals("2", consultar("SELECT COUNT(*) FROM movimientos_inventario WHERE tipo = 'COMPRA' AND referencia_id = '"
                + compra.resumen().id() + "'"));
        assertEquals("2", consultar("SELECT COUNT(*) FROM lotes WHERE referencia_id = '" + compra.resumen().id() + "'"));
    }

    @Test
    void conProrrateoLaBonificacionBajaElCostoPorUnidad() {
        // 100 cajas pagadas a $300 y 5 de regalo: entran 1,260 piezas por $30,000 → $23.81 cada una.
        CompraService.Detalle compra = comprar(linea("Leche entera 1 L", caja(), "100", "5", 30000));
        CompraService.DetalleLinea l = compra.lineas().getFirst();
        assertEquals(3_000_000, l.importeCentavos());
        mismo("1260", l.recibidaBase());
        assertEquals(2381, l.costoBaseCentavos());
        assertNull(l.loteBonificadoId());
        assertTrue(compra.resumen().conBonificacion());
        assertEquals(CompraService.PoliticaBonificacion.PRORRATEAR, compra.resumen().politica());
    }

    @Test
    void conCostoCeroLoBonificadoEntraEnUnLoteAparte() {
        admin.compras().cambiarPolitica(CompraService.PoliticaBonificacion.COSTO_CERO, administrador);
        CompraService.Detalle compra = comprar(linea("Leche entera 1 L", caja(), "100", "5", 30000));
        CompraService.DetalleLinea l = compra.lineas().getFirst();
        assertEquals(2500, l.costoBaseCentavos());
        List<LoteRepository.Lote> lotes = admin.almacen().lotes(catalogoAdmin("Leche entera 1 L").id(), almacen.id());
        LoteRepository.Lote pagado = lotes.stream().filter(x -> x.id().equals(l.loteId())).findFirst().orElseThrow();
        LoteRepository.Lote regalo = lotes.stream().filter(x -> x.id().equals(l.loteBonificadoId())).findFirst()
                .orElseThrow();
        mismo("1200", pagado.cantidadInicial());
        assertEquals(2500, pagado.costoUnitarioCentavos());
        mismo("60", regalo.cantidadInicial());
        assertEquals(0, regalo.costoUnitarioCentavos());
        // El total pagado no cambia por la bonificación.
        assertEquals(3_000_000, compra.resumen().totalCentavos());
    }

    @Test
    void cadaCompraConservaElCostoRealDeSuLote() {
        // Refresco a $31 y después a $32: cada lote se queda con su costo y se surte primero el más viejo.
        ProductoCatalogo refresco = catalogoAdmin("Refresco de cola 600 ml");
        BigDecimal antes = existencia(refresco.id(), almacen.id());
        comprar(linea("Refresco de cola 600 ml", null, "10", "0", 3100));
        comprar(linea("Refresco de cola 600 ml", null, "10", "0", 3200));
        List<LoteRepository.Lote> lotes = admin.almacen().lotes(refresco.id(), almacen.id());
        assertTrue(lotes.stream().anyMatch(l -> l.costoUnitarioCentavos() == 3100));
        assertTrue(lotes.stream().anyMatch(l -> l.costoUnitarioCentavos() == 3200));
        mismo(antes.add(BigDecimal.valueOf(20)).toPlainString(), existencia(refresco.id(), almacen.id()));

        // Al surtir todo lo anterior + 15, salen primero los lotes viejos y luego 10 a $31 y 5 a $32.
        BigDecimal cantidad = antes.add(BigDecimal.valueOf(15));
        SurtidoService.Costeo costeo = admin.surtidos().costear(refresco, cantidad);
        long costoViejo = admin.surtidos().costear(refresco, antes).costoCentavos();
        assertEquals(costoViejo + 10 * 3100 + 5 * 3200, costeo.costoCentavos());
    }

    @Test
    void siFallaUnaLineaNoQuedaNadaDeLaCompra() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        ProductoCatalogo inexistente = new ProductoCatalogo("no-existe", "Fantasma", null, null, null, null, null,
                queso.unidad(), false, queso.disponibilidad(), null, true, List.of(), queso.gramajeGramos(),
                BigDecimal.ZERO, null);
        long lotesAntes = numero("SELECT COUNT(*) FROM lotes");
        long movimientosAntes = numero("SELECT COUNT(*) FROM movimientos_inventario");
        BigDecimal quesoAntes = existencia(queso.id(), almacen.id());

        assertThrows(RuntimeException.class, () -> comprar(linea("Queso Oaxaca", null, "20", "0", 13500),
                new CompraService.Linea(inexistente, null, BigDecimal.ONE, BigDecimal.ZERO, 100)));

        assertEquals(0, numero("SELECT COUNT(*) FROM compras"));
        assertEquals(lotesAntes, numero("SELECT COUNT(*) FROM lotes"));
        assertEquals(movimientosAntes, numero("SELECT COUNT(*) FROM movimientos_inventario"));
        mismo(quesoAntes.toPlainString(), existencia(queso.id(), almacen.id()));
    }

    @Test
    void repetirLaMismaCompraNoLaDuplica() {
        String id = Ids.nuevo();
        List<CompraService.Linea> lineas = List.of(linea("Queso Oaxaca", null, "5", "0", 13500));
        CompraService.Detalle primera = admin.compras().registrar(id, lala, "F-9", null, lineas, null, administrador);
        CompraService.Detalle otraVez = admin.compras().registrar(id, lala, "F-9", null, lineas, null, administrador);
        assertEquals(primera.resumen().folio(), otraVez.resumen().folio());
        assertEquals(1, numero("SELECT COUNT(*) FROM compras"));
        assertEquals(1, numero("SELECT COUNT(*) FROM lotes WHERE referencia_id = '" + id + "'"));
    }

    @Test
    void lasCantidadesPorPiezaDebenDarPiezasEnterasYSoloElAdministradorCompra() {
        assertThrows(IllegalArgumentException.class, () -> comprar(linea("Refresco de cola 600 ml", null, "1.5", "0", 3100)));
        assertThrows(IllegalArgumentException.class, () -> comprar(linea("Refresco de cola 600 ml", null, "0", "0", 3100)));
        assertThrows(IllegalStateException.class, () -> admin.compras().registrar(Ids.nuevo(), lala, null, null,
                List.of(linea("Queso Oaxaca", null, "1", "0", 100)), null, cajero.usuario()));
    }

    @Test
    void laDevolucionAlProveedorSaleDelLoteDeLaCompra() {
        CompraService.Detalle compra = comprar(linea("Leche entera 1 L", caja(), "10", "0", 30000));
        ProductoCatalogo leche = catalogoAdmin("Leche entera 1 L");
        BigDecimal antes = existencia(leche.id(), almacen.id());
        String id = Ids.nuevo();

        String folio = admin.compras().devolverAProveedor(id, lala, compra.resumen().id(),
                List.of(new CompraService.LineaDevolucion(leche, null, new BigDecimal("24"))), "Caducidad corta",
                CompraService.Tratamiento.NOTA_CREDITO, "Nota NC-77", administrador);

        assertTrue(folio.startsWith("DVP-"));
        mismo(antes.subtract(new BigDecimal("24")).toPlainString(), existencia(leche.id(), almacen.id()));
        assertEquals("-24", new BigDecimal(consultar("SELECT SUM(cantidad) FROM movimientos_inventario WHERE tipo = "
                + "'DEVOLUCION_PROVEEDOR' AND lote_id = '" + compra.lineas().getFirst().loteId() + "'"))
                .stripTrailingZeros().toPlainString());
        assertEquals(24 * 2500, numero("SELECT total_costo_centavos FROM devoluciones_proveedor WHERE id = '" + id + "'"));
        // Repetir la misma devolución no saca otra vez la mercancía.
        assertEquals(folio, admin.compras().devolverAProveedor(id, lala, compra.resumen().id(),
                List.of(new CompraService.LineaDevolucion(leche, null, new BigDecimal("24"))), "Caducidad corta",
                CompraService.Tratamiento.NOTA_CREDITO, null, administrador));
        mismo(antes.subtract(new BigDecimal("24")).toPlainString(), existencia(leche.id(), almacen.id()));
        // No se puede devolver más de lo que queda de esa compra.
        assertThrows(IllegalStateException.class, () -> admin.compras().devolverAProveedor(Ids.nuevo(), lala,
                compra.resumen().id(), List.of(new CompraService.LineaDevolucion(leche, null, new BigDecimal("97"))),
                "Otra", null, null, administrador));
    }

    @Test
    void elProveedorNoSeRepiteYUnoDeshabilitadoNoRecibeCompras() {
        assertThrows(IllegalArgumentException.class, () -> admin.compras().guardarProveedor(null, "lácteos del norte",
                null, null, null, null, administrador));
        admin.compras().cambiarActivoProveedor(lala, false, administrador);
        assertThrows(IllegalArgumentException.class, () -> comprar(linea("Queso Oaxaca", null, "1", "0", 100)));
        assertEquals(0, numero("SELECT COUNT(*) FROM compras"));
    }
}
