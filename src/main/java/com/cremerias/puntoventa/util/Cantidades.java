package com.cremerias.puntoventa.util;

import com.cremerias.puntoventa.model.Unidad;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/**
 * Cantidades en unidad base. Los productos por kilo se capturan y muestran en gramos
 * (en la base de datos se guardan en kilos: 0.250 = 250 g).
 */
public final class Cantidades {

    private static final DecimalFormatSymbols SIMBOLOS = DecimalFormatSymbols.getInstance(Locale.US);

    private Cantidades() {
    }

    /** "1,250 g" o "12 pzas". */
    public static String formatear(Unidad unidadBase, BigDecimal cantidadBase) {
        if (unidadBase == Unidad.KG) {
            return new DecimalFormat("#,##0", SIMBOLOS).format(cantidadBase.movePointRight(3)) + " g";
        }
        String n = new DecimalFormat("#,##0.###", SIMBOLOS).format(cantidadBase);
        return n + (cantidadBase.compareTo(BigDecimal.ONE) == 0 ? " pza" : " pzas");
    }

    /** Número a capturar en pantalla: gramos para kilos, piezas para piezas. */
    public static String aCaptura(Unidad unidadBase, BigDecimal cantidadBase) {
        BigDecimal valor = unidadBase == Unidad.KG ? cantidadBase.movePointRight(3) : cantidadBase;
        return valor.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** Convierte lo capturado (gramos o piezas) a unidad base. */
    public static Optional<BigDecimal> desdeCaptura(Unidad unidadBase, String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal valor = new BigDecimal(texto.replace(",", "").strip());
            if (valor.signum() < 0) {
                return Optional.empty();
            }
            return Optional.of(unidadBase == Unidad.KG ? valor.movePointLeft(3).setScale(3, RoundingMode.HALF_UP)
                    : valor.setScale(0, RoundingMode.HALF_UP));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Etiqueta de la unidad de captura: "g" o "pzas". */
    public static String unidadCaptura(Unidad unidadBase) {
        return unidadBase == Unidad.KG ? "g" : "pzas";
    }
}
