package com.cremerias.puntoventa.model;

/** Si el producto es de línea o solo por un tiempo (se puede deshabilitar después sin borrarlo). */
public enum Disponibilidad {

    REGULAR("Regular"),
    EDICION_ESPECIAL("Edición especial"),
    TEMPORADA("De temporada");

    private final String nombre;

    Disponibilidad(String nombre) {
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
