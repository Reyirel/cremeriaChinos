package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.ui.componentes.Texto;
import javafx.util.StringConverter;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/** Cómo se muestran los objetos dentro de las listas desplegables. */
final class Combos {

    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", Formatos.ES);

    private Combos() {
    }

    /** Sucursal con su código: distingue sucursales con el mismo nombre. */
    static StringConverter<Sucursal> sucursal() {
        return de(s -> s.id() == null ? s.nombre() : Formatos.sucursal(s));
    }

    static StringConverter<Usuario> usuario() {
        return de(Usuario::nombreCompleto);
    }

    static StringConverter<YearMonth> mes() {
        return de(m -> Texto.capitalizar(MES.format(m)));
    }

    static <T> StringConverter<T> de(Function<T, String> texto) {
        return new StringConverter<>() {
            @Override
            public String toString(T valor) {
                return valor == null ? "" : texto.apply(valor);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }
}
