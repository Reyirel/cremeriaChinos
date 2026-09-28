package com.cremerias.puntoventa.model;

import java.time.Instant;

public record VentaEspera(String id, String nota, Instant creadoEn, int renglones, long totalCentavos) {
}
