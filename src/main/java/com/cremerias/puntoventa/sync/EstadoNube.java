package com.cremerias.puntoventa.sync;

/** Cómo está la caja respecto a Supabase. */
public enum EstadoNube {
    /** No hay {@code supabase.properties}: la caja trabaja solo con su base local. */
    SOLO_LOCAL,
    /** Sin internet: todo se guarda en la caja y se sube al volver la conexión. */
    SIN_CONEXION,
    SINCRONIZANDO,
    AL_DIA,
    /** Supabase rechazó algo (cuenta de la caja, permisos, datos): ver el detalle. */
    ERROR
}
