package com.cremerias.puntoventa.model;

import java.time.Instant;

public record Sesion(String id, Usuario usuario, String dispositivoId, Instant inicio, ModoConexion modo) {
}
