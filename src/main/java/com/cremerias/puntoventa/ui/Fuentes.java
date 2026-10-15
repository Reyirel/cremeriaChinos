package com.cremerias.puntoventa.ui;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Carga la tipografía Inter incluida en la aplicación (funciona sin internet).
 *
 * <p>Cada peso viene en su propio archivo y se registra con su propia familia: "Inter" (400 y la
 * negrita 700), "Inter Medium" (500), "Inter SemiBold" (600) e "Inter ExtraBold" (800). JavaFX solo
 * distingue normal y negrita al buscar una fuente por peso, así que en las hojas de estilo los pesos
 * intermedios se piden por familia (ver app.css), no con {@code -fx-font-weight}.
 */
public final class Fuentes {

    private static final Logger log = LoggerFactory.getLogger(Fuentes.class);
    private static final int[] PESOS = {400, 500, 600, 700, 800};

    private Fuentes() {
    }

    public static void cargar() {
        for (int peso : PESOS) {
            try (InputStream in = Fuentes.class.getResourceAsStream(
                    "/com/cremerias/puntoventa/fonts/Inter-" + peso + ".ttf")) {
                Font fuente = in == null ? null : Font.loadFont(in, 13);
                if (fuente == null) {
                    log.warn("No se pudo cargar Inter {}: se usará la fuente del sistema para ese peso", peso);
                }
            } catch (IOException e) {
                log.warn("No se pudo leer Inter {}", peso, e);
            }
        }
    }
}
