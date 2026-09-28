package com.cremerias.puntoventa.util;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Utilidades de códigos EAN-13, incluidas las etiquetas que imprimen las básculas.
 *
 * <p>Formato de etiqueta de báscula soportado (el más común en básculas Torrey / Rhino):
 * <pre>
 *   2 X PPPPP GGGGG C
 *   │ │   │     │   └ dígito verificador
 *   │ │   │     └──── peso en gramos (00500 = 0.500 kg)
 *   │ │   └────────── PLU del producto (clave en el catálogo)
 *   └─┴────────────── prefijo 20-29 (código interno)
 * </pre>
 * Si la báscula de la tienda usa otro formato, solo hay que ajustar {@link #leerEtiquetaBascula}.
 */
public final class CodigoBarras {

    private CodigoBarras() {
    }

    public record EtiquetaBascula(String plu, BigDecimal kilos) {
    }

    public static boolean esEan13Valido(String codigo) {
        return codigo != null && codigo.matches("\\d{13}")
                && digitoVerificador(codigo.substring(0, 12)) == codigo.charAt(12) - '0';
    }

    /** Dígito verificador EAN para los primeros 12 dígitos. */
    public static int digitoVerificador(String doceDigitos) {
        int suma = 0;
        for (int i = 0; i < 12; i++) {
            int d = doceDigitos.charAt(i) - '0';
            suma += (i % 2 == 0) ? d : d * 3;
        }
        return (10 - suma % 10) % 10;
    }

    public static String completarEan13(String doceDigitos) {
        return doceDigitos + digitoVerificador(doceDigitos);
    }

    public static Optional<EtiquetaBascula> leerEtiquetaBascula(String codigo) {
        if (!esEan13Valido(codigo) || codigo.charAt(0) != '2') {
            return Optional.empty();
        }
        String plu = String.valueOf(Integer.parseInt(codigo.substring(2, 7)));
        int gramos = Integer.parseInt(codigo.substring(7, 12));
        if (gramos <= 0) {
            return Optional.empty();
        }
        return Optional.of(new EtiquetaBascula(plu, BigDecimal.valueOf(gramos, 3)));
    }
}
