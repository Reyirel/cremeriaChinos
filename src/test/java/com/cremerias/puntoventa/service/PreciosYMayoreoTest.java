package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Carrito;
import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.LineaVenta;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.service.admin.CreditoService;
import com.cremerias.puntoventa.service.admin.PreciosService;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reglas de precio por canal, precio interno del surtido en el crédito y escalas de mayoreo. */
class PreciosYMayoreoTest extends EscenarioBase {

    private static final LocalDate HOY = LocalDate.now();

    private PreciosService precios() {
        return admin.precios();
    }

    private String regla(ReglaPrecio.Canal canal, ReglaPrecio.Tipo tipo, String porcentaje, String categoriaId,
                         String productoId, String sucursalId) {
        return precios().guardarRegla(new PreciosService.DatosRegla(null, canal, tipo, new BigDecimal(porcentaje),
                categoriaId, productoId, null, sucursalId, HOY.minusDays(1), null, null), administrador);
    }

    private ReglaPrecio aplicable(String producto, String sucursalId) {
        ProductoCatalogo p = catalogoAdmin(producto);
        return precios().reglaAplicable(ReglaPrecio.Canal.BODEGA_SUCURSAL, p.id(), p.categoriaId(), null, sucursalId, HOY)
                .orElse(null);
    }

    // ------------------------------------------------------------------ reglas de precio

    @Test
    void recargoSobreCostoYMargenSobrePrecioSonCalculosDistintos() {
        assertEquals(12500, ReglaPrecio.precio(ReglaPrecio.Tipo.RECARGO_COSTO, new BigDecimal("25"), 10000));
        assertEquals(12500, ReglaPrecio.precio(ReglaPrecio.Tipo.MARGEN_PRECIO, new BigDecimal("20"), 10000));
        // 23 % sobre el costo no es 23 % de margen: deja 18.7 %.
        assertEquals(12300, ReglaPrecio.precio(ReglaPrecio.Tipo.RECARGO_COSTO, new BigDecimal("23"), 10000));
        assertEquals(12987, ReglaPrecio.precio(ReglaPrecio.Tipo.MARGEN_PRECIO, new BigDecimal("23"), 10000));
        ReglaPrecio r = new ReglaPrecio("x", "F", ReglaPrecio.Canal.PUBLICO, ReglaPrecio.Tipo.RECARGO_COSTO,
                new BigDecimal("25"), null, null, null, null, HOY, null, null, true);
        assertEquals(0, new BigDecimal("20.00").compareTo(r.margenEquivalente()));
    }

    /**
     * La lista de un canal interno cobra al costo lo que no tiene regla; al público, sin regla no hay
     * precio sugerido (el costo no es un precio de venta).
     */
    @Test
    void laListaDePreciosSoloSugierePrecioAlPublicoConRegla() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        PreciosService.FilaLista bodega = fila(precios().listaDePrecios(ReglaPrecio.Canal.BODEGA_SUCURSAL, matriz.id(), HOY),
                queso.id());
        assertEquals(13300, bodega.costoBaseCentavos());
        assertNull(bodega.regla());
        assertEquals(13300L, bodega.precios().get(queso.principal()));
        assertTrue(fila(precios().listaDePrecios(ReglaPrecio.Canal.PUBLICO, matriz.id(), HOY), queso.id()).precios().isEmpty());

        regla(ReglaPrecio.Canal.PUBLICO, ReglaPrecio.Tipo.RECARGO_COSTO, "23", null, queso.id(), null);
        PreciosService.FilaLista publico = fila(precios().listaDePrecios(ReglaPrecio.Canal.PUBLICO, matriz.id(), HOY),
                queso.id());
        assertEquals("23", publico.regla().porcentaje().toPlainString());
        assertEquals(16359L, publico.precios().get(queso.principal())); // 133.00 × 1.23
    }

    private static PreciosService.FilaLista fila(List<PreciosService.FilaLista> lista, String productoId) {
        return lista.stream().filter(f -> f.producto().id().equals(productoId)).findFirst().orElseThrow();
    }

    @Test
    void sinReglaElPrecioInternoEsElCostoYGanaLaReglaMasEspecifica() {
        assertNull(aplicable("Queso Oaxaca", matriz.id()));
        assertEquals(10000, PreciosService.precio(java.util.Optional.empty(), 10000));

        String quesos = catalogoAdmin("Queso Oaxaca").categoriaId();
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "10", null, null, null);
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "12", quesos, null, null);
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "23", null,
                catalogoAdmin("Queso Oaxaca").id(), null);
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "2", null, null, matriz.id());

        assertEquals("23", aplicable("Queso Oaxaca", matriz.id()).porcentaje().toPlainString()); // producto
        assertEquals("12", aplicable("Queso panela", null).porcentaje().toPlainString());       // categoría
        assertEquals("10", aplicable("Leche entera 1 L", null).porcentaje().toPlainString());   // general
        assertEquals("2", aplicable("Leche entera 1 L", matriz.id()).porcentaje().toPlainString()); // sucursal
        assertEquals("12", aplicable("Queso panela", matriz.id()).porcentaje().toPlainString()); // categoría > sucursal
    }

    @Test
    void dosReglasDelMismoAlcanceConFechasQueSeCruzanSeRechazan() {
        String oaxaca = catalogoAdmin("Queso Oaxaca").id();
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "23", null, oaxaca, null);
        assertThrows(IllegalArgumentException.class, () -> regla(ReglaPrecio.Canal.BODEGA_SUCURSAL,
                ReglaPrecio.Tipo.RECARGO_COSTO, "12", null, oaxaca, null));
        // Otro canal no choca.
        regla(ReglaPrecio.Canal.PUBLICO, ReglaPrecio.Tipo.RECARGO_COSTO, "40", null, oaxaca, null);
        // Un margen de 100 % no existe; el cajero no cambia precios.
        assertThrows(IllegalArgumentException.class, () -> regla(ReglaPrecio.Canal.PUBLICO,
                ReglaPrecio.Tipo.MARGEN_PRECIO, "100", null, null, null));
        assertThrows(IllegalStateException.class, () -> precios().guardarRegla(new PreciosService.DatosRegla(null,
                ReglaPrecio.Canal.PUBLICO, ReglaPrecio.Tipo.RECARGO_COSTO, BigDecimal.TEN, null, null, null, null, HOY,
                null, null), cajero.usuario()));
    }

    @Test
    void laReglaVieneConVigenciaYUnaFuturaNoAplicaHoy() {
        String oaxaca = catalogoAdmin("Queso Oaxaca").id();
        precios().guardarRegla(new PreciosService.DatosRegla(null, ReglaPrecio.Canal.BODEGA_SUCURSAL,
                ReglaPrecio.Tipo.RECARGO_COSTO, new BigDecimal("15"), null, oaxaca, null, null, HOY.plusDays(10), null,
                null), administrador);
        assertNull(aplicable("Queso Oaxaca", matriz.id()));
    }

    // ------------------------------------------------------------------ precio interno y crédito

    @Test
    void elSurtidoACreditoCargaElPrecioInternoNoElValorAlPublico() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "10", null, queso.id(), null);

        var detalle = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("5"), Map.of(queso.principal().id(), 21000L))),
                null, administrador, null);

        // Demo: el almacén tiene el queso a $133/kg. Costo 5 kg = $665; interno +10 % = $731.50.
        assertEquals(66500, detalle.resumen().costoCentavos());
        assertEquals(73150, detalle.resumen().internoCentavos());
        assertEquals(5 * 21000, detalle.resumen().ventaCentavos());
        assertEquals(73150, detalle.saldoCredito());
        assertEquals(73150, detalle.lineas().getFirst().internoCentavos());
        // El lote de la sucursal se queda con el costo de la empresa (no se cuenta dos veces el margen).
        assertEquals("13300", consultar("SELECT costo_unitario_centavos FROM lotes WHERE folio = '"
                + detalle.resumen().folio() + "'"));
        assertEquals("SURTIDO", consultar("SELECT concepto FROM movimientos_credito WHERE folio = '"
                + detalle.resumen().folio() + "'"));
    }

    @Test
    void sinReglaElCargoSigueSiendoElCostoComoAntes() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        var detalle = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("2"), Map.of(queso.principal().id(), 21000L))),
                null, administrador, null);
        assertEquals(26600, detalle.saldoCredito());
        assertEquals(detalle.resumen().costoCentavos(), detalle.resumen().internoCentavos());
    }

    @Test
    void unaSucursalDeLaRutaUsaLaListaDeLaSierra() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        regla(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Tipo.RECARGO_COSTO, "10", null, null, null);
        regla(ReglaPrecio.Canal.RUTA_SIERRA, ReglaPrecio.Tipo.RECARGO_COSTO, "20", null, null, null);
        admin.sucursales().cambiarCanal(matriz, ReglaPrecio.Canal.RUTA_SIERRA, administrador);

        var detalle = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("1"), Map.of(queso.principal().id(), 21000L))),
                null, administrador, null);
        assertEquals(15960, detalle.resumen().internoCentavos()); // 13300 × 1.20
    }

    @Test
    void lasNotasPendientesAplicanLosAbonosAlCargoMasAntiguo() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        Map<String, Long> precio = Map.of(queso.principal().id(), 21000L);
        var primero = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("2"), precio)), null, administrador, null);
        var segundo = admin.surtidos().registrar(matriz, SurtidoService.FormaPago.CREDITO,
                List.of(new SurtidoService.Linea(queso, new BigDecimal("3"), precio)), null, administrador, null);
        // $266 + $399 = $665 de deuda; se abonan $300: la primera nota queda pagada y la segunda debe $365.
        admin.credito().abonar(matriz, 30000, "Transferencia", administrador);

        List<CreditoService.NotaPendiente> pendientes = admin.credito().notasPendientes(matriz.id(), HOY);
        assertEquals(1, pendientes.size());
        assertEquals(segundo.resumen().folio(), pendientes.getFirst().folio());
        assertEquals(39900, pendientes.getFirst().importeCentavos());
        assertEquals(3400, pendientes.getFirst().abonadoCentavos());
        assertEquals(36500, pendientes.getFirst().pendienteCentavos());
        assertTrue(primero.resumen().folio().startsWith("SUR-"));

        CreditoService.Antiguedad antiguedad = admin.credito().antiguedad(matriz.id(), HOY);
        assertEquals(36500, antiguedad.de0a30());
        assertEquals(36500, antiguedad.total());
        assertEquals(admin.credito().saldo(matriz.id()), antiguedad.total());
        // A los 45 días la misma nota pasa al tramo de 31 a 60.
        assertEquals(36500, admin.credito().antiguedad(matriz.id(), HOY.plusDays(45)).de31a60());
        // Abonar más del saldo no se permite (no hay anticipos).
        assertThrows(IllegalArgumentException.class, () -> admin.credito().abonar(matriz, 40000, null, administrador));
    }

    // ------------------------------------------------------------------ mayoreo

    private void escala(String producto, String presentacion, Escala.Nivel nivel, String minima, Escala.Tipo tipo,
                        String descuento, Long precio) {
        ProductoCatalogo p = catalogoAdmin(producto);
        String presentacionId = presentacion == null ? null : p.presentaciones().stream()
                .filter(pr -> pr.nombre().equals(presentacion)).findFirst().orElseThrow().id();
        precios().guardarEscala(new PreciosService.DatosEscala(p.id(), presentacionId, null, nivel,
                new BigDecimal(minima), tipo, descuento == null ? null : new BigDecimal(descuento), precio,
                HOY.minusDays(1), null), administrador);
    }

    private Carrito carritoCon(String nombre, String cantidad) {
        Carrito carrito = new Carrito();
        carrito.agregar(articulo(nombre), new BigDecimal(cantidad));
        return carrito;
    }

    @Test
    void elMayoreoSeAplicaJustoEnLosLimitesCincoSeisOnceYDoce() {
        // Refresco a $20: mayoreo desde 6 piezas con 10 %, súper mayoreo desde 12 con 15 %.
        escala("Refresco de cola 600 ml", "Pieza", Escala.Nivel.MAYOREO, "6", Escala.Tipo.DESCUENTO_PCT, "10", null);
        escala("Refresco de cola 600 ml", "Pieza", Escala.Nivel.SUPER_MAYOREO, "12", Escala.Tipo.DESCUENTO_PCT, "15",
                null);
        String refresco = "Refresco de cola 600 ml";
        assertEquals(10000, carritoCon(refresco, "5").total());
        assertEquals(Escala.Nivel.MENUDEO, carritoCon(refresco, "5").lineas().getFirst().getNivel());
        assertEquals(10800, carritoCon(refresco, "6").total());
        assertEquals(Escala.Nivel.MAYOREO, carritoCon(refresco, "6").lineas().getFirst().getNivel());
        assertEquals(19800, carritoCon(refresco, "11").total());
        assertEquals(20400, carritoCon(refresco, "12").total());
        assertEquals(Escala.Nivel.SUPER_MAYOREO, carritoCon(refresco, "12").lineas().getFirst().getNivel());

        // Al cambiar la cantidad en el carrito el precio se recalcula.
        Carrito carrito = carritoCon(refresco, "5");
        LineaVenta linea = carrito.lineas().getFirst();
        carrito.cambiarCantidad(linea, new BigDecimal("12"));
        assertEquals(20400, carrito.total());
        assertEquals(1700, linea.precioAplicado());

        // El servicio cobra lo mismo que la pantalla y guarda el nivel y el precio de lista.
        Turno turno = caja.abrir(cajero, 0);
        Ticket ticket = vender(turno, articulo(refresco), "12");
        assertEquals(20400, ticket.totalCentavos());
        assertEquals(Escala.Nivel.SUPER_MAYOREO, ticket.renglones().getFirst().nivel());
        assertEquals("SUPER_MAYOREO", consultar("SELECT nivel_precio FROM venta_detalle WHERE venta_id = '"
                + ticket.ventaId() + "'"));
        assertEquals("2000", consultar("SELECT precio_lista_centavos FROM venta_detalle WHERE venta_id = '"
                + ticket.ventaId() + "'"));
        assertEquals("1700", consultar("SELECT precio_unitario_centavos FROM venta_detalle WHERE venta_id = '"
                + ticket.ventaId() + "'"));
    }

    @Test
    void unaCajaCuentaComoDocePiezasParaElMayoreo() {
        // Toda la leche (pieza y caja) tiene 5 % de descuento desde 12 piezas.
        escala("Leche entera 1 L", null, Escala.Nivel.MAYOREO, "12", Escala.Tipo.DESCUENTO_PCT, "5", null);
        assertEquals(30400, carritoCon("Leche entera 1 L · Caja 12 pzas", "1").total()); // 32000 − 5 %
        assertEquals(11 * 2800, carritoCon("Leche entera 1 L", "11").total());           // 11 piezas: menudeo

        // Una caja y tres piezas suman 15: las dos líneas van con mayoreo.
        Carrito carrito = new Carrito();
        carrito.agregar(articulo("Leche entera 1 L · Caja 12 pzas"), BigDecimal.ONE);
        carrito.agregar(articulo("Leche entera 1 L"), new BigDecimal("3"));
        assertEquals(30400 + 7980, carrito.total());

        Turno turno = caja.abrir(cajero, 0);
        Ticket ticket = vender(turno, List.of(
                new VentaService.Renglon(articulo("Leche entera 1 L · Caja 12 pzas"), BigDecimal.ONE),
                new VentaService.Renglon(articulo("Leche entera 1 L"), new BigDecimal("3"))));
        assertEquals(carrito.total(), ticket.totalCentavos());
        // Una caja descontó 12 unidades base, como siempre.
        mismo("33", existencia(catalogoAdmin("Leche entera 1 L").id(), matriz.id()));
    }

    @Test
    void unPrecioDeMayoreoNuncaCobraMasQueElMenudeo() {
        // Precio fijo de $25 cuando el lote está a $20: no se aplica.
        escala("Refresco de cola 600 ml", "Pieza", Escala.Nivel.MAYOREO, "6", Escala.Tipo.PRECIO_FIJO, null, 2500L);
        Carrito carrito = carritoCon("Refresco de cola 600 ml", "6");
        assertEquals(12000, carrito.total());
        assertEquals(Escala.Nivel.MENUDEO, carrito.lineas().getFirst().getNivel());
    }

    @Test
    void lasEscalasContradictoriasSeRechazan() {
        escala("Refresco de cola 600 ml", "Pieza", Escala.Nivel.MAYOREO, "6", Escala.Tipo.DESCUENTO_PCT, "10", null);
        // Súper mayoreo que empieza antes que mayoreo, o con menos descuento.
        assertThrows(IllegalArgumentException.class, () -> escala("Refresco de cola 600 ml", "Pieza",
                Escala.Nivel.SUPER_MAYOREO, "6", Escala.Tipo.DESCUENTO_PCT, "15", null));
        assertThrows(IllegalArgumentException.class, () -> escala("Refresco de cola 600 ml", "Pieza",
                Escala.Nivel.SUPER_MAYOREO, "12", Escala.Tipo.DESCUENTO_PCT, "5", null));
        // Dos mayoreos para lo mismo.
        assertThrows(IllegalArgumentException.class, () -> escala("Refresco de cola 600 ml", "Pieza",
                Escala.Nivel.MAYOREO, "8", Escala.Tipo.DESCUENTO_PCT, "12", null));
        // Un precio fijo necesita su presentación; un descuento no puede ser de 100 %.
        assertThrows(IllegalArgumentException.class, () -> escala("Refresco de cola 600 ml", null,
                Escala.Nivel.SUPER_MAYOREO, "12", Escala.Tipo.PRECIO_FIJO, null, 1500L));
        assertThrows(IllegalArgumentException.class, () -> escala("Refresco de cola 600 ml", "Pieza",
                Escala.Nivel.SUPER_MAYOREO, "12", Escala.Tipo.DESCUENTO_PCT, "100", null));
    }

    @Test
    void cambiarLasEscalasNoCambiaLosTicketsAnteriores() {
        escala("Refresco de cola 600 ml", "Pieza", Escala.Nivel.MAYOREO, "6", Escala.Tipo.DESCUENTO_PCT, "10", null);
        Turno turno = caja.abrir(cajero, 0);
        Ticket antes = vender(turno, articulo("Refresco de cola 600 ml"), "6");
        assertEquals(10800, antes.totalCentavos());

        for (Escala e : precios().escalas()) {
            precios().desactivarEscala(e, administrador);
        }
        assertEquals(12000, carritoCon("Refresco de cola 600 ml", "6").total());
        Ticket otraVez = ventas.ticket(antes.ventaId());
        assertEquals(10800, otraVez.totalCentavos());
        assertEquals(Escala.Nivel.MAYOREO, otraVez.renglones().getFirst().nivel());
    }

    // ------------------------------------------------------------------ derivado con merma

    @Test
    void unDerivadoConMermaDescuentaLoVendidoYSuMermaDelOriginal() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        // «Rebanado 300 g»: código y precio propios, consume 0.300 kg del queso y pierde 5 % al rebanar.
        List<Presentacion> presentaciones = new ArrayList<>(queso.presentaciones());
        presentaciones.add(new Presentacion(null, queso.id(), "Rebanado 300 g", false, new BigDecimal("0.300"),
                "7501000000300", false, true, new BigDecimal("5")));
        admin.productos().guardar(new ProductoAdminService.Datos(queso.id(), queso.nombre(), queso.categoria(),
                queso.marca(), queso.clave(), queso.codigoInventario(), queso.unidad(), queso.sujetoMerma(),
                queso.disponibilidad(), queso.temporada(), presentaciones, List.of(), queso.gramajeGramos(),
                queso.mermaGramos(), queso.avisoSinVenta()), administrador);
        queso = catalogoAdmin("Queso Oaxaca");
        String rebanado = queso.presentaciones().stream().filter(p -> p.nombre().equals("Rebanado 300 g")).findFirst()
                .orElseThrow().id();
        assertEquals(0, new BigDecimal("5").compareTo(queso.presentaciones().stream()
                .filter(p -> p.id().equals(rebanado)).findFirst().orElseThrow().mermaPct()));
        // Se surte con precio para el kilo y para el rebanado.
        admin.surtidos().registrar(matriz, SurtidoService.FormaPago.EFECTIVO, List.of(new SurtidoService.Linea(queso,
                new BigDecimal("10"), Map.of(queso.principal().id(), 21000L, rebanado, 6500L))), null, administrador, null);
        BigDecimal antes = existencia(queso.id(), matriz.id());

        Turno turno = caja.abrir(cajero, 0);
        catalogo.recargar();
        Producto articulo = catalogo.resolverCodigo("7501000000300").orElseThrow().producto();
        Ticket ticket = vender(turno, articulo, "1");

        assertEquals(6500, ticket.totalCentavos());
        // Sale lo vendido (0.300 kg) y la merma (5 % = 0.015 kg) del mismo queso: no hay doble inventario.
        mismo(antes.subtract(new BigDecimal("0.315")).toPlainString(), existencia(queso.id(), matriz.id()));
        assertEquals("0.015", new BigDecimal(consultar("SELECT cantidad FROM mermas WHERE motivo LIKE '%"
                + ticket.folio() + "%'")).setScale(3).toPlainString());
        assertTrue(numero("SELECT costo_centavos FROM mermas WHERE motivo LIKE '%" + ticket.folio() + "%'") > 0);
    }

    @Test
    void laMermaDeUnDerivadoCuentaParaNoVenderDeMas() {
        ProductoCatalogo queso = catalogoAdmin("Queso Oaxaca");
        // En la sucursal hay 25 kg: 83 rebanadas de 300 g con 5 % de merma piden 26.145 kg.
        Producto kilo = articulo("Queso Oaxaca");
        Producto falso = new Producto("x", queso.id(), null, null, null, "Rebanado", null,
                com.cremerias.puntoventa.model.Unidad.PZA, com.cremerias.puntoventa.model.Unidad.KG,
                new BigDecimal("0.300"), 6500, kilo.existencia(), BigDecimal.ZERO, kilo.tramos(), kilo.tramoExtra(),
                List.of(), new BigDecimal("5"));
        List<VentaService.Faltante> faltan = VentaService.faltantes(
                List.of(new VentaService.Renglon(falso, new BigDecimal("83"))), p -> kilo.existencia());
        assertEquals(1, faltan.size());
        mismo("26.145", faltan.getFirst().pedida());
    }
}
