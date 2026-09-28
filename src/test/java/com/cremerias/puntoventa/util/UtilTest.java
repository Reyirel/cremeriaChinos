package com.cremerias.puntoventa.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @Test
    void formatoDeDinero() {
        assertEquals("$1,234.50", Dinero.formatear(123450));
        assertEquals("$0.00", Dinero.formatear(0));
        assertEquals("-$10.00", Dinero.formatear(-1000));
        assertEquals(Optional.of(150050L), Dinero.parsear("$1,500.50"));
        assertEquals(Optional.of(1000L), Dinero.parsear("10"));
        assertTrue(Dinero.parsear("abc").isEmpty());
        assertTrue(Dinero.parsear("-5").isEmpty());
    }

    @Test
    void importeRedondeaAlCentavo() {
        assertEquals(9500, Dinero.importe(19000, new BigDecimal("0.500")));
        assertEquals(4750, Dinero.importe(19000, new BigDecimal("0.250")));
        assertEquals(6667, Dinero.importe(20000, new BigDecimal("0.33335")));
    }

    @Test
    void codigosEan13() {
        assertTrue(CodigoBarras.esEan13Valido("7501055300075"));
        assertFalse(CodigoBarras.esEan13Valido("7501055300076"));
        var etiqueta = CodigoBarras.leerEtiquetaBascula(CodigoBarras.completarEan13("200010501250")).orElseThrow();
        assertEquals("105", etiqueta.plu());
        assertEquals(new BigDecimal("1.250"), etiqueta.kilos());
        assertTrue(CodigoBarras.leerEtiquetaBascula("7501055300075").isEmpty());
    }
}
