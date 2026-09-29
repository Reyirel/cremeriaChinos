package com.cremerias.puntoventa.model;

import java.time.LocalDate;
import java.time.Period;

/**
 * Ventana de fechas en la que se vende un producto "de temporada" (ej. rosca de reyes del 1 dic
 * al 6 ene). Si se repite, el sistema la reactiva y desactiva sola en cada ciclo; si no, hay que
 * volver a capturarla a mano cuando se vuelva a habilitar el producto.
 *
 * @param repetirCada  cada cuántas unidades se repite; nulo = no se repite
 * @param repetirUnidad unidad de la repetición; nulo = no se repite
 */
public record Temporada(LocalDate desde, LocalDate fin, Integer repetirCada, Unidad repetirUnidad) {

    /** Unidades de la repetición. */
    public enum Unidad {
        DIAS("día", "días"),
        SEMANAS("semana", "semanas"),
        MESES("mes", "meses"),
        ANIOS("año", "años");

        private final String singular;
        private final String plural;

        Unidad(String singular, String plural) {
            this.singular = singular;
            this.plural = plural;
        }

        public String nombre(long cantidad) {
            return cantidad == 1 ? singular : plural;
        }

        Period periodo(int cantidad) {
            return switch (this) {
                case DIAS -> Period.ofDays(cantidad);
                case SEMANAS -> Period.ofWeeks(cantidad);
                case MESES -> Period.ofMonths(cantidad);
                case ANIOS -> Period.ofYears(cantidad);
            };
        }

        @Override
        public String toString() {
            return plural;
        }
    }

    public boolean repite() {
        return repetirCada != null && repetirUnidad != null;
    }

    /** Si la fecha cae dentro de la ventana [desde, fin]. */
    public boolean vigente(LocalDate fecha) {
        return !fecha.isBefore(desde) && !fecha.isAfter(fin);
    }

    /** Si la ventana ya terminó para esa fecha. */
    public boolean vencida(LocalDate fecha) {
        return fecha.isAfter(fin);
    }

    /**
     * Siguiente ciclo de la temporada: avanza desde/fin por el periodo de repetición las veces
     * que hagan falta hasta que ya no esté vencida para {@code fecha} (por si la app estuvo
     * cerrada varios ciclos).
     */
    public Temporada siguienteVigente(LocalDate fecha) {
        Period paso = repetirUnidad.periodo(repetirCada);
        LocalDate nuevoDesde = desde;
        LocalDate nuevoFin = fin;
        while (!nuevoFin.isAfter(fecha)) {
            nuevoDesde = nuevoDesde.plus(paso);
            nuevoFin = nuevoFin.plus(paso);
        }
        return new Temporada(nuevoDesde, nuevoFin, repetirCada, repetirUnidad);
    }

    /** "cada 1 año", "cada 2 semanas". */
    public String describirRepeticion() {
        return repite() ? "cada " + repetirCada + " " + repetirUnidad.nombre(repetirCada) : "no se repite";
    }
}
