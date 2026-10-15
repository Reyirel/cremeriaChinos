package com.cremerias.puntoventa.ui.componentes;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Medidas compartidas del sistema de diseño. Los colores y la tipografía viven en las hojas de
 * estilo (tema-claro.css, tema-oscuro.css y app.css); aquí están el espacio, las alturas y el
 * movimiento que se usan desde el código.
 */
public final class Diseno {

    /** Escala de espacio: 4 · 8 · 12 · 16 · 24 · 32. */
    public static final double ESPACIO_XS = 4;
    public static final double ESPACIO_S = 8;
    public static final double ESPACIO_M = 12;
    public static final double ESPACIO_L = 16;
    public static final double ESPACIO_XL = 24;
    public static final double ESPACIO_XXL = 32;

    public static final double RADIO_CONTROL = 6;
    public static final double RADIO_PANEL = 10;
    public static final double RADIO_DIALOGO = 12;

    public static final double ALTO_CONTROL = 36;

    /** Ancho del panel de detalle acoplado a la derecha de una tabla. */
    public static final double ANCHO_DETALLE = 352;

    /** Alto de las filas de tabla según su contenido. */
    public enum Densidad {
        COMPACTA(44), NORMAL(48), AMPLIA(56);

        private final double alto;

        Densidad(double alto) {
            this.alto = alto;
        }

        public double alto() {
            return alto;
        }
    }

    /** Transiciones breves: ayudan a seguir un cambio sin hacer esperar. */
    public static final Duration MOVIMIENTO_CORTO = Duration.millis(120);
    public static final Duration MOVIMIENTO = Duration.millis(160);

    private static final BooleanProperty MOVIMIENTO_REDUCIDO =
            new SimpleBooleanProperty(Boolean.getBoolean("cremerias.movimientoReducido"));

    private Diseno() {
    }

    /** Preferencia de movimiento reducido: sin animaciones de aparición ni deslizamiento. */
    public static BooleanProperty movimientoReducidoProperty() {
        return MOVIMIENTO_REDUCIDO;
    }

    public static boolean movimientoReducido() {
        return MOVIMIENTO_REDUCIDO.get();
    }

    /** Aparece con un desvanecido corto (o de inmediato con movimiento reducido). */
    public static void aparecer(Node nodo) {
        if (movimientoReducido()) {
            nodo.setOpacity(1);
            return;
        }
        FadeTransition fade = new FadeTransition(MOVIMIENTO, nodo);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    /** Entra deslizándose unos píxeles desde la derecha (paneles laterales). */
    public static void entrarDesdeDerecha(Node nodo) {
        if (movimientoReducido()) {
            nodo.setOpacity(1);
            nodo.setTranslateX(0);
            return;
        }
        TranslateTransition mover = new TranslateTransition(MOVIMIENTO, nodo);
        mover.setFromX(24);
        mover.setToX(0);
        mover.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition fade = new FadeTransition(MOVIMIENTO, nodo);
        fade.setFromValue(0);
        fade.setToValue(1);
        new ParallelTransition(mover, fade).play();
    }

    /** Entra desde abajo (barras contextuales). */
    public static void entrarDesdeAbajo(Node nodo) {
        if (movimientoReducido()) {
            nodo.setOpacity(1);
            nodo.setTranslateY(0);
            return;
        }
        TranslateTransition mover = new TranslateTransition(MOVIMIENTO, nodo);
        mover.setFromY(16);
        mover.setToY(0);
        mover.setInterpolator(Interpolator.EASE_OUT);
        FadeTransition fade = new FadeTransition(MOVIMIENTO, nodo);
        fade.setFromValue(0);
        fade.setToValue(1);
        new ParallelTransition(mover, fade).play();
    }

    /** Recorta el contenido a esquinas redondeadas (las tablas no salen del borde de su panel). */
    public static void recortarRedondeado(Region region, double radio) {
        Rectangle recorte = new Rectangle();
        recorte.setArcWidth(radio * 2);
        recorte.setArcHeight(radio * 2);
        recorte.widthProperty().bind(region.widthProperty());
        recorte.heightProperty().bind(region.heightProperty());
        region.setClip(recorte);
    }
}
