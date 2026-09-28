package com.cremerias.puntoventa.ui;

import javafx.scene.text.Font;

import java.io.IOException;
import java.io.InputStream;

/** Carga la tipografía Inter incluida en la aplicación. */
public final class Fuentes {

    private static final int[] PESOS = {400, 500, 600, 700, 800};

    private Fuentes() {
    }

    public static void cargar() {
        for (int peso : PESOS) {
            try (InputStream in = Fuentes.class.getResourceAsStream(
                    "/com/cremerias/puntoventa/fonts/Inter-" + peso + ".ttf")) {
                if (in != null) {
                    Font.loadFont(in, 13);
                }
            } catch (IOException ignored) {
                // Si falla se usa la fuente del sistema.
            }
        }
    }
}
