package com.cremerias.puntoventa.model;

import java.time.LocalDate;

/** Salario y comida de un empleado en una semana (lunes de inicio) de una sucursal. */
public record Nomina(String sucursalId, String usuarioId, String usuario, LocalDate semanaInicio,
                     long salarioCentavos, long comidaCentavos) {

    public long total() {
        return salarioCentavos + comidaCentavos;
    }
}
