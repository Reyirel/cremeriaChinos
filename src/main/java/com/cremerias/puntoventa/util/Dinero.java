package com.cremerias.puntoventa.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/** Importes en centavos (long) y su formato para pantalla: $1,234.50 */
public final class Dinero {

    private static final DecimalFormatSymbols SIMBOLOS = DecimalFormatSymbols.getInstance(Locale.US);

    private Dinero() {
    }

    public static String formatear(long centavos) {
        DecimalFormat formato = new DecimalFormat("$#,##0.00", SIMBOLOS);
        String texto = formato.format(BigDecimal.valueOf(Math.abs(centavos), 2));
        return centavos < 0 ? "-" + texto : texto;
    }

    /** Sin signo de pesos, para columnas del ticket. */
    public static String formatearSinSigno(long centavos) {
        return new DecimalFormat("#,##0.00", SIMBOLOS).format(BigDecimal.valueOf(centavos, 2));
    }

    public static BigDecimal aPesos(long centavos) {
        return BigDecimal.valueOf(centavos, 2);
    }

    public static long aCentavos(BigDecimal pesos) {
        return pesos.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /** Importe de una cantidad por un precio unitario, redondeado al centavo. */
    public static long importe(long precioUnitarioCentavos, BigDecimal cantidad) {
        return cantidad.multiply(BigDecimal.valueOf(precioUnitarioCentavos))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Interpreta lo que escribe el usuario: "150", "150.5", "$1,500.00". */
    public static Optional<Long> parsear(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String limpio = texto.replace("$", "").replace(",", "").strip();
        if (limpio.isEmpty()) {
            return Optional.empty();
        }
        try {
            BigDecimal valor = new BigDecimal(limpio);
            return valor.signum() < 0 ? Optional.empty() : Optional.of(aCentavos(valor));
        } catch (NumberFormatException | ArithmeticException e) {
            return Optional.empty();
        }
    }
}
