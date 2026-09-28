package com.cremerias.puntoventa.model;

public record Sucursal(String id, String codigo, String nombre, String direccion, String telefono, boolean activo,
                       boolean esAlmacen) {

    @Override
    public String toString() {
        return nombre;
    }
}
