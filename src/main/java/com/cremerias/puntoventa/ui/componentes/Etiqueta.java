package com.cremerias.puntoventa.ui.componentes;

import javafx.scene.control.Label;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Etiqueta de estado: texto corto con un punto de color. El texto siempre dice el estado; el color
 * solo lo refuerza (nunca es la única señal).
 */
public final class Etiqueta {

    public enum Tono {
        EXITO("exito"), AVISO("aviso"), PELIGRO("peligro"), INFO("info"), NEUTRO("neutro"), ACENTO("acento");

        private final String clase;

        Tono(String clase) {
            this.clase = clase;
        }

        public String clase() {
            return clase;
        }
    }

    private static final String[] CLASES = {"exito", "aviso", "peligro", "info", "neutro", "acento", "tachado"};

    private Etiqueta() {
    }

    public static Label de(String texto, Tono tono) {
        Label l = new Label();
        l.getStyleClass().add("etiqueta");
        Circle punto = new Circle(3.5);
        punto.getStyleClass().add("punto-etiqueta");
        l.setGraphic(punto);
        aplicar(l, texto, tono);
        return l;
    }

    /** Con ícono en lugar del punto (para estados que se reconocen mejor con un símbolo). */
    public static Label conIcono(String texto, String icono, Tono tono) {
        Label l = new Label();
        l.getStyleClass().add("etiqueta");
        l.setGraphic(new FontIcon(icono));
        aplicar(l, texto, tono);
        return l;
    }

    /** Sin punto ni ícono (presentaciones, categorías). */
    public static Label simple(String texto, Tono tono) {
        Label l = new Label();
        l.getStyleClass().add("etiqueta");
        aplicar(l, texto, tono);
        return l;
    }

    /** Cambia texto y tono de una etiqueta existente (celdas que se reciclan). */
    public static void aplicar(Label etiqueta, String texto, Tono tono) {
        etiqueta.setText(texto);
        etiqueta.getStyleClass().removeAll(CLASES);
        etiqueta.getStyleClass().add(tono.clase());
        etiqueta.setMinWidth(Label.USE_PREF_SIZE);
    }

    /** Presentación inactiva o dato que ya no aplica. */
    public static Label tachada(String texto) {
        Label l = simple(texto, Tono.NEUTRO);
        l.getStyleClass().add("tachado");
        return l;
    }
}
