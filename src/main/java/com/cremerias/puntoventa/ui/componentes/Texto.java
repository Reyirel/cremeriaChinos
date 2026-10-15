package com.cremerias.puntoventa.ui.componentes;

import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;

/** Búsqueda sin acentos ni mayúsculas y orden alfabético en español. */
public final class Texto {

    private static final Locale ES = Locale.forLanguageTag("es-MX");
    private static final Collator COLLATOR = Collator.getInstance(ES);

    static {
        COLLATOR.setStrength(Collator.SECONDARY);
    }

    /** Orden alfabético en español: "Ácido" junto a "Aceite", sin distinguir mayúsculas. */
    public static final Comparator<String> ORDEN = (a, b) -> {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        return COLLATOR.compare(a, b);
    };

    private Texto() {
    }

    /** "  Jamón  " → "jamon". */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
    }

    /** ¿El campo contiene la búsqueda (ya normalizada)? */
    public static boolean contiene(String campo, String busquedaNormalizada) {
        return busquedaNormalizada.isEmpty() || (campo != null && normalizar(campo).contains(busquedaNormalizada));
    }

    /** ¿El campo empieza con la búsqueda (para claves y códigos)? */
    public static boolean empieza(String campo, String busquedaNormalizada) {
        return busquedaNormalizada.isEmpty() || (campo != null && normalizar(campo).startsWith(busquedaNormalizada));
    }

    public static String capitalizar(String texto) {
        return texto == null || texto.isEmpty() ? texto : texto.substring(0, 1).toUpperCase(ES) + texto.substring(1);
    }

    /** "1 producto" / "3 productos". */
    public static String plural(long n, String singular, String plural) {
        return String.format(ES, "%,d", n) + " " + (n == 1 ? singular : plural);
    }
}
