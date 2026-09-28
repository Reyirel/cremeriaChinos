package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Sesion;

public sealed interface ResultadoLogin {

    record Exitoso(Sesion sesion) implements ResultadoLogin {
    }

    /** @param bloqueoId si el acceso quedó bloqueado por horario, para poder autorizarlo. */
    record Rechazado(Motivo motivo, String mensaje, String bloqueoId) implements ResultadoLogin {
        public Rechazado(Motivo motivo, String mensaje) {
            this(motivo, mensaje, null);
        }
    }

    enum Motivo {
        DATOS_INCOMPLETOS,
        CREDENCIALES_INVALIDAS,
        USUARIO_INACTIVO,
        USUARIO_BLOQUEADO,
        FUERA_DE_HORARIO
    }
}
