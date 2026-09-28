package com.cremerias.puntoventa.ui.componentes;

import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

import java.util.regex.Pattern;

/** Ayudas para campos de captura numérica. */
public final class Campos {

    private Campos() {
    }

    /** Solo permite números con hasta {@code decimales} decimales. */
    public static void soloNumeros(TextField campo, int decimales) {
        Pattern patron = decimales == 0
                ? Pattern.compile("\\d{0,9}")
                : Pattern.compile("\\d{0,9}(\\.\\d{0," + decimales + "})?");
        campo.setTextFormatter(new TextFormatter<>(cambio ->
                patron.matcher(cambio.getControlNewText()).matches() ? cambio : null));
        seleccionarAlEnfocar(campo);
    }

    public static void seleccionarAlEnfocar(TextField campo) {
        campo.focusedProperty().addListener((o, antes, ahora) -> {
            if (ahora) {
                Platform.runLater(campo::selectAll);
            }
        });
    }
}
