package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Sesion;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.util.Optional;

/** Sesión del usuario que está usando la terminal en este momento. */
public class SesionActual {

    private final ReadOnlyObjectWrapper<Sesion> sesion = new ReadOnlyObjectWrapper<>();

    public ReadOnlyObjectProperty<Sesion> sesionProperty() {
        return sesion.getReadOnlyProperty();
    }

    public Optional<Sesion> obtener() {
        return Optional.ofNullable(sesion.get());
    }

    public void establecer(Sesion nueva) {
        sesion.set(nueva);
    }

    public void limpiar() {
        sesion.set(null);
    }
}
