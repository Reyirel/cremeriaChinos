package com.cremerias.puntoventa.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;

import java.util.List;

/**
 * Tema visual: AtlantaFX Primer como base y, encima, la hoja de la aplicación con los tokens de
 * color del modo claro u oscuro. Los tokens se cambian como hoja completa (no solo con una clase
 * en la raíz) para que también las ventanas emergentes (listas, menús, calendarios) los usen.
 */
public final class Temas {

    private static final String CSS = "/com/cremerias/puntoventa/css/";
    private static final String CLASE_OSCURO = "oscuro";

    private Temas() {
    }

    public static void aplicar(Scene escena, boolean oscuro) {
        Application.setUserAgentStylesheet(oscuro
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        escena.getStylesheets().setAll(List.of(
                recurso(oscuro ? "tema-oscuro.css" : "tema-claro.css"),
                recurso("app.css")));
        Parent raiz = escena.getRoot();
        raiz.getStyleClass().remove(CLASE_OSCURO);
        if (oscuro) {
            raiz.getStyleClass().add(CLASE_OSCURO);
        }
    }

    private static String recurso(String nombre) {
        return Temas.class.getResource(CSS + nombre).toExternalForm();
    }
}
