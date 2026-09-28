package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Usuario;

public sealed interface ResultadoAutorizacion {

    record Autorizado(Usuario autorizador) implements ResultadoAutorizacion {
    }

    record Denegado(String mensaje) implements ResultadoAutorizacion {
    }
}
