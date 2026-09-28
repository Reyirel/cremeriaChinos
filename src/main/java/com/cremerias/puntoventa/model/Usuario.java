package com.cremerias.puntoventa.model;

import java.time.Instant;

public record Usuario(
        String id,
        String sucursalId,
        String nombreCompleto,
        String usuario,
        String passwordHash,
        Rol rol,
        boolean activo,
        boolean debeCambiarPassword,
        int intentosFallidos,
        Instant bloqueadoHasta,
        Instant ultimoAcceso
) {

    public boolean bloqueado(Instant ahora) {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(ahora);
    }

    /** Iniciales para el avatar (ej. "María López" → "ML"). */
    public String iniciales() {
        String[] partes = nombreCompleto.trim().split("\\s+");
        String iniciales = partes.length == 1
                ? partes[0].substring(0, Math.min(2, partes[0].length()))
                : "" + partes[0].charAt(0) + partes[partes.length - 1].charAt(0);
        return iniciales.toUpperCase();
    }
}
