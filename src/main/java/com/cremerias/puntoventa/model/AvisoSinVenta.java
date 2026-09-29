package com.cremerias.puntoventa.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Aviso de un producto cuando no se vende en cierto plazo (ej. 15 minutos o 1 semana), para que
 * la sucursal lo oferte. Cada sucursal lo mide con sus propias ventas.
 */
public record AvisoSinVenta(int plazo, Unidad unidad, boolean administrador, boolean supervisor, boolean caja) {

    /** Unidades del plazo. */
    public enum Unidad {
        MINUTOS("minuto", "minutos", Duration.ofMinutes(1)),
        HORAS("hora", "horas", Duration.ofHours(1)),
        DIAS("día", "días", Duration.ofDays(1)),
        SEMANAS("semana", "semanas", Duration.ofDays(7));

        private final String singular;
        private final String plural;
        private final Duration duracion;

        Unidad(String singular, String plural, Duration duracion) {
            this.singular = singular;
            this.plural = plural;
            this.duracion = duracion;
        }

        public String nombre(long cantidad) {
            return cantidad == 1 ? singular : plural;
        }

        @Override
        public String toString() {
            return plural;
        }
    }

    /** Plazo máximo: un año. */
    public static final Duration MAXIMO = Duration.ofDays(365);

    public Duration duracion() {
        return unidad.duracion.multipliedBy(plazo);
    }

    /** "15 minutos", "1 semana". */
    public String describir() {
        return plazo + " " + unidad.nombre(plazo);
    }

    /** "Admin, supervisor y caja". */
    public String destinatarios() {
        List<String> quienes = new ArrayList<>();
        if (administrador) {
            quienes.add("administrador");
        }
        if (supervisor) {
            quienes.add("supervisor");
        }
        if (caja) {
            quienes.add("caja");
        }
        if (quienes.isEmpty()) {
            return "";
        }
        String texto = quienes.size() == 1 ? quienes.getFirst()
                : String.join(", ", quienes.subList(0, quienes.size() - 1)) + " y " + quienes.getLast();
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    /** Si este rol debe recibir el aviso. */
    public boolean avisaA(Rol rol) {
        return switch (rol) {
            case ADMINISTRADOR -> administrador;
            case SUPERVISOR -> supervisor;
            case CAJERO -> caja;
        };
    }

    /** "45 min", "3 h 20 min", "2 días". */
    public static String formatear(Duration d) {
        long minutos = Math.max(0, d.toMinutes());
        if (minutos < 60) {
            return minutos + " min";
        }
        if (minutos < 24 * 60) {
            long m = minutos % 60;
            return minutos / 60 + " h" + (m == 0 ? "" : " " + m + " min");
        }
        long dias = minutos / (24 * 60);
        return dias + (dias == 1 ? " día" : " días");
    }
}
