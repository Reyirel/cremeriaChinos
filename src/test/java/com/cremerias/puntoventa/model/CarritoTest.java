package com.cremerias.puntoventa.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarritoTest {

    private static Producto producto(String id, Unidad unidad, long precio) {
        Tarifa.Tramo lote = new Tarifa.Tramo("lote-" + id, BigDecimal.valueOf(100), precio);
        return new Producto(id, "prod-" + id, null, null, null, "P" + id, null, unidad, unidad, BigDecimal.ONE, precio,
                BigDecimal.TEN, BigDecimal.ZERO, java.util.List.of(lote), lote);
    }

    @Test
    void alAgotarseElLoteSeCobraAlPrecioDelSiguiente() {
        // 3 piezas a $10 (lote viejo) y el resto a $12 (lote nuevo).
        Tarifa.Tramo viejo = new Tarifa.Tramo("viejo", new BigDecimal("3"), 1000);
        Tarifa.Tramo nuevo = new Tarifa.Tramo("nuevo", new BigDecimal("10"), 1200);
        Producto p = new Producto("pz", "prod", null, null, null, "Leche", null, Unidad.PZA, Unidad.PZA, BigDecimal.ONE,
                1000, new BigDecimal("13"), BigDecimal.ZERO, java.util.List.of(viejo, nuevo), nuevo);
        Carrito carrito = new Carrito();
        carrito.agregar(p, new BigDecimal("2"));
        assertEquals(2000, carrito.total());
        carrito.lineas().getFirst().setCantidad(new BigDecimal("5"));
        assertEquals(3 * 1000 + 2 * 1200, carrito.total());
    }

    @Test
    void unaCajaConsumeDoceUnidadesDelLote() {
        Tarifa.Tramo lote = new Tarifa.Tramo("l", new BigDecimal("24"), 32000); // precio por caja
        Producto caja = new Producto("cj", "prod", null, null, null, "Leche · Caja", null, Unidad.PZA, Unidad.PZA,
                new BigDecimal("12"), 32000, new BigDecimal("24"), BigDecimal.ZERO, java.util.List.of(lote), lote);
        java.util.Map<String, BigDecimal> consumido = new java.util.HashMap<>();
        Tarifa.Cotizacion cot = caja.cotizar(consumido, new BigDecimal("2"));
        assertEquals(64000, cot.totalCentavos());
        assertEquals(0, new BigDecimal("24").compareTo(consumido.get("l")));
    }

    @Test
    void piezasIgualesSeSumanYGranelVaEnRenglonNuevo() {
        Carrito carrito = new Carrito();
        Producto leche = producto("1", Unidad.PZA, 2800);
        Producto queso = producto("2", Unidad.KG, 19000);
        carrito.agregar(leche, BigDecimal.ONE);
        carrito.agregar(leche, new BigDecimal("2"));
        carrito.agregar(queso, new BigDecimal("0.5"));
        carrito.agregar(queso, new BigDecimal("0.25"));

        assertEquals(3, carrito.lineas().size());
        assertEquals(3 * 2800 + 9500 + 4750, carrito.total());
        assertEquals(new BigDecimal("5"), carrito.articulosProperty().get());
    }

    @Test
    void cambiarCantidadACeroQuitaElRenglon() {
        Carrito carrito = new Carrito();
        LineaVenta linea = carrito.agregar(producto("1", Unidad.PZA, 1000), new BigDecimal("2"));
        carrito.cambiarCantidad(linea, new BigDecimal("5"));
        assertEquals(5000, carrito.total());
        carrito.cambiarCantidad(linea, BigDecimal.ZERO);
        assertEquals(0, carrito.lineas().size());
        assertEquals(0, carrito.total());
    }
}
