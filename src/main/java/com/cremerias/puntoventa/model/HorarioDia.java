package com.cremerias.puntoventa.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.Locale;

/** Horario de un día de la semana. El descanso es opcional. */
public record HorarioDia(DayOfWeek dia, boolean labora, LocalTime entrada, LocalTime salida,
                         LocalTime descansoInicio, LocalTime descansoFin) {

    public static HorarioDia descanso(DayOfWeek dia) {
        return new HorarioDia(dia, false, null, null, null, null);
    }

    public boolean tieneDescanso() {
        return labora && descansoInicio != null && descansoFin != null;
    }

    public String nombreDia() {
        String nombre = dia.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-MX"));
        return Character.toUpperCase(nombre.charAt(0)) + nombre.substring(1);
    }

    /** Valida el horario; devuelve el error o {@code null}. */
    public String validar() {
        if (!labora) {
            return null;
        }
        if (entrada == null || salida == null) {
            return nombreDia() + ": falta la hora de entrada o de salida.";
        }
        if (!salida.isAfter(entrada)) {
            return nombreDia() + ": la salida debe ser después de la entrada.";
        }
        if ((descansoInicio == null) != (descansoFin == null)) {
            return nombreDia() + ": indica el inicio y el fin del descanso.";
        }
        if (descansoInicio != null && (!descansoInicio.isAfter(entrada) || !descansoFin.isAfter(descansoInicio)
                || !salida.isAfter(descansoFin))) {
            return nombreDia() + ": el descanso debe quedar dentro del horario.";
        }
        return null;
    }
}
