package com.cremerias.puntoventa.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Fechas en UTC con formato ISO-8601, igual que las que generan los triggers de SQLite. */
public final class Tiempo {

    /** Siempre con milisegundos, para que las fechas se puedan comparar como texto en SQL. */
    private static final DateTimeFormatter FORMATO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Tiempo() {
    }

    public static String ahora() {
        return formatear(Instant.now());
    }

    public static String formatear(Instant instante) {
        return instante == null ? null : FORMATO.format(instante);
    }

    public static Instant leer(String texto) {
        return texto == null ? null : Instant.parse(texto);
    }
}
