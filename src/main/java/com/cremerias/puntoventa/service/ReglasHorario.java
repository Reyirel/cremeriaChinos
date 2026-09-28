package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.HorarioDia;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Reglas de acceso por horario. Se compara a nivel de minuto (09:00:40 cuenta como 09:00).
 *
 * <ul>
 *   <li>Al entrar: se bloquea si es un día sin horario, si ya pasó su hora de salida, si es su
 *       primera entrada del día y llega después de su hora de entrada, o si salió a su descanso
 *       y regresa después de que terminó.</li>
 *   <li>Al salir: se bloquea (para su siguiente entrada) si sale antes de su descanso o, después
 *       del descanso, antes de su hora de salida.</li>
 * </ul>
 * El bloqueo se levanta solo cuando un administrador (o un supervisor) da el permiso.
 */
public final class ReglasHorario {

    public enum Motivo {
        DIA_SIN_HORARIO("No tiene horario de trabajo este día"),
        LLEGADA_TARDE("Llegó después de su hora de entrada"),
        REGRESO_TARDE_DESCANSO("Regresó tarde de su descanso"),
        DESPUES_DE_SALIDA("Intentó entrar después de su hora de salida"),
        SALIDA_ANTES_DESCANSO("Salió antes de su hora de descanso"),
        SALIDA_ANTICIPADA("Salió antes de su hora de salida");

        private final String descripcion;

        Motivo(String descripcion) {
            this.descripcion = descripcion;
        }

        public String descripcion() {
            return descripcion;
        }
    }

    private ReglasHorario() {
    }

    /**
     * @param horario          horario de hoy ({@code null} si no tiene)
     * @param yaEntroHoy       si ya inició sesión hoy
     * @param ultimaSalidaHoy  hora de su último cierre de sesión hoy ({@code null} si no ha salido)
     */
    public static Optional<Motivo> alEntrar(HorarioDia horario, LocalTime ahora, boolean yaEntroHoy,
                                            LocalTime ultimaSalidaHoy) {
        LocalTime t = ahora.truncatedTo(ChronoUnit.MINUTES);
        if (horario == null || !horario.labora()) {
            return Optional.of(Motivo.DIA_SIN_HORARIO);
        }
        if (t.isAfter(horario.salida())) {
            return Optional.of(Motivo.DESPUES_DE_SALIDA);
        }
        if (!yaEntroHoy && t.isAfter(horario.entrada())) {
            return Optional.of(Motivo.LLEGADA_TARDE);
        }
        if (horario.tieneDescanso() && ultimaSalidaHoy != null) {
            LocalTime salida = ultimaSalidaHoy.truncatedTo(ChronoUnit.MINUTES);
            boolean salioADescansar = !salida.isBefore(horario.descansoInicio()) && !salida.isAfter(horario.descansoFin());
            if (salioADescansar && t.isAfter(horario.descansoFin())) {
                return Optional.of(Motivo.REGRESO_TARDE_DESCANSO);
            }
        }
        return Optional.empty();
    }

    public static Optional<Motivo> alSalir(HorarioDia horario, LocalTime ahora) {
        LocalTime t = ahora.truncatedTo(ChronoUnit.MINUTES);
        if (horario == null || !horario.labora() || t.isBefore(horario.entrada()) || !t.isBefore(horario.salida())) {
            return Optional.empty();
        }
        if (horario.tieneDescanso()) {
            if (t.isBefore(horario.descansoInicio())) {
                return Optional.of(Motivo.SALIDA_ANTES_DESCANSO);
            }
            if (!t.isAfter(horario.descansoFin())) {
                return Optional.empty(); // Sale a su descanso.
            }
        }
        return Optional.of(Motivo.SALIDA_ANTICIPADA);
    }
}
