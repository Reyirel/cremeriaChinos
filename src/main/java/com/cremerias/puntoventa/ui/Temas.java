package com.cremerias.puntoventa.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Parent;

/** Tema visual (AtlantaFX Primer) en modo claro u oscuro. */
public final class Temas {

    private static final String CLASE_OSCURO = "oscuro";

    private Temas() {
    }

    public static void aplicar(Parent raiz, boolean oscuro) {
        Application.setUserAgentStylesheet(oscuro
                ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
        raiz.getStyleClass().remove(CLASE_OSCURO);
        if (oscuro) {
            raiz.getStyleClass().add(CLASE_OSCURO);
        }
    }
}
