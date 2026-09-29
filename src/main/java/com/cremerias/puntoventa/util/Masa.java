package com.cremerias.puntoventa.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Unidades de peso para capturar gramaje y merma. Internamente todo se guarda en gramos. */
public enum Masa {

    TON("t", "1000000"),
    KG("kg", "1000"),
    G("g", "1"),
    MG("mg", "0.001"),
    UG("µg", "0.000001");

    private static final DecimalFormatSymbols SIMBOLOS = DecimalFormatSymbols.getInstance(Locale.US);

    private final String simbolo;
    private final BigDecimal gramos;

    Masa(String simbolo, String gramos) {
        this.simbolo = simbolo;
        this.gramos = new BigDecimal(gramos);
    }

    public String simbolo() {
        return simbolo;
    }

    public BigDecimal aGramos(BigDecimal valor) {
        return valor.multiply(gramos);
    }

    public BigDecimal desdeGramos(BigDecimal valorEnGramos) {
        return valorEnGramos.divide(gramos, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** La unidad más cómoda para mostrar una cantidad de gramos. */
    public static Masa sugerida(BigDecimal enGramos) {
        BigDecimal g = enGramos.abs();
        if (g.compareTo(new BigDecimal("1000000")) >= 0) {
            return TON;
        }
        if (g.compareTo(new BigDecimal("1000")) >= 0) {
            return KG;
        }
        if (g.compareTo(BigDecimal.ONE) >= 0 || g.signum() == 0) {
            return G;
        }
        if (g.compareTo(new BigDecimal("0.001")) >= 0) {
            return MG;
        }
        return UG;
    }

    /** "950 g", "1.25 kg", "300 mg". */
    public static String formatear(BigDecimal enGramos) {
        if (enGramos == null) {
            return "—";
        }
        Masa m = sugerida(enGramos);
        return new DecimalFormat("#,##0.###", SIMBOLOS).format(m.desdeGramos(enGramos)) + " " + m.simbolo;
    }

    @Override
    public String toString() {
        return simbolo;
    }
}
