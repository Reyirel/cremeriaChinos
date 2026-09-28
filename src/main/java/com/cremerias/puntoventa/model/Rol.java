package com.cremerias.puntoventa.model;

public enum Rol {

    ADMINISTRADOR("Administrador", "mdi2s-shield-crown-outline", "Control total del negocio"),
    SUPERVISOR("Supervisor", "mdi2a-account-tie-outline", "Supervisión de caja y sucursal"),
    CAJERO("Cajero", "mdi2c-cash-register", "Ventas y cobro en caja");

    private final String nombre;
    private final String icono;
    private final String descripcion;

    Rol(String nombre, String icono, String descripcion) {
        this.nombre = nombre;
        this.icono = icono;
        this.descripcion = descripcion;
    }

    public String nombre() {
        return nombre;
    }

    /** Literal de icono de Ikonli (Material Design 2). */
    public String icono() {
        return icono;
    }

    public String descripcion() {
        return descripcion;
    }

    /** Clase CSS para dar a cada rol su propio color. */
    public String claseCss() {
        return "rol-" + name().toLowerCase();
    }
}
