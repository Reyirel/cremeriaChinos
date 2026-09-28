package com.cremerias.puntoventa.model;

public enum MetodoPago {

    EFECTIVO("Efectivo", "mdi2c-cash"),
    TARJETA("Tarjeta", "mdi2c-credit-card-outline"),
    TRANSFERENCIA("Transferencia", "mdi2b-bank-transfer");

    private final String nombre;
    private final String icono;

    MetodoPago(String nombre, String icono) {
        this.nombre = nombre;
        this.icono = icono;
    }

    public String nombre() {
        return nombre;
    }

    public String icono() {
        return icono;
    }
}
