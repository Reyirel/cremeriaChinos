package com.cremerias.puntoventa.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Fechas en UTC con formato ISO-8601, igual que las que generan los triggers de SQLite. */
public final class Tiempo {

    /** Siempre con milisegundos, para que las fechas se puedan comparar como texto en SQL. */
    private static final DateTimeFormatter FORMATO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Reloj con el que se fechan los registros; solo se cambia para generar datos de prueba con fechas pasadas. */
    private static volatile Clock reloj = Clock.systemUTC();

    private Tiempo() {
    }

    public static String ahora() {
        return formatear(reloj.instant());
    }

    /** Fija el reloj de {@link #ahora()} (nulo = reloj del sistema). Solo para pruebas y datos de prueba. */
    public static void usarReloj(Clock nuevo) {
        reloj = nuevo == null ? Clock.systemUTC() : nuevo;
    }

    public static String formatear(Instant instante) {
        return instante == null ? null : FORMATO.format(instante);
    }

    public static Instant leer(String texto) {
        return texto == null ? null : Instant.parse(texto);
    }
}
