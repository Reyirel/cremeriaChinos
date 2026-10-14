package com.cremerias.puntoventa.model;

/** Si el gasto se repite con el mismo monto cada periodo (FIJO) o varía (VARIABLE). */
public enum TipoGasto {

    FIJO("Fijo"),
    VARIABLE("Variable");

    private final String nombre;

    TipoGasto(String nombre) {
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
