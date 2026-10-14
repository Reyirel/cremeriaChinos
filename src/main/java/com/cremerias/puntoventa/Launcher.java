package com.cremerias.puntoventa;

/**
 * Punto de entrada para el empaquetado nativo (jpackage).
 * No extiende {@code Application} para que el lanzador de Java no bloquee
 * la ejecución cuando JavaFX se distribuye en el classpath en vez del module-path.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
