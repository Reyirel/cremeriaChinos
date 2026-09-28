package com.cremerias.puntoventa.ui.componentes;

import atlantafx.base.controls.Notification;
import atlantafx.base.theme.Styles;
import atlantafx.base.util.Animations;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;


/** Notificaciones flotantes (esquina inferior derecha) que desaparecen solas. */
public class Avisos {

    private static final Duration DURACION = Duration.seconds(3.5);
    private static final int MAXIMO = 4;

    private final VBox contenedor = new VBox(10);

    public Avisos() {
        contenedor.getStyleClass().add("avisos");
        contenedor.setAlignment(Pos.BOTTOM_RIGHT);
        contenedor.setPickOnBounds(false);
        contenedor.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        contenedor.setViewOrder(-100);
        StackPane.setAlignment(contenedor, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(contenedor, new Insets(0, 24, 110, 0));
    }

    public VBox contenedor() {
        return contenedor;
    }

    public void exito(String mensaje) {
        mostrar(mensaje, "mdi2c-check-circle-outline", Styles.SUCCESS, null, null);
    }

    public void info(String mensaje) {
        mostrar(mensaje, "mdi2i-information-outline", Styles.ACCENT, null, null);
    }

    public void advertencia(String mensaje) {
        mostrar(mensaje, "mdi2a-alert-outline", Styles.WARNING, null, null);
    }

    public void error(String mensaje) {
        mostrar(mensaje, "mdi2a-alert-circle-outline", Styles.DANGER, null, null);
    }

    /** Aviso con un botón (ej. "Deshacer"). */
    public void conAccion(String mensaje, String textoAccion, Runnable accion) {
        mostrar(mensaje, "mdi2i-information-outline", Styles.ACCENT, textoAccion, accion);
    }

    private void mostrar(String mensaje, String icono, String estilo, String textoAccion, Runnable accion) {
        Notification aviso = new Notification(mensaje, new FontIcon(icono));
        aviso.getStyleClass().addAll(estilo, Styles.ELEVATED_1, "aviso");
        aviso.setPrefWidth(360);
        aviso.setMaxWidth(360);
        aviso.setOnClose(e -> contenedor.getChildren().remove(aviso));
        if (textoAccion != null) {
            Button boton = new Button(textoAccion);
            boton.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
            boton.setOnAction(e -> {
                accion.run();
                contenedor.getChildren().remove(aviso);
            });
            aviso.setPrimaryActions(boton);
        }
        contenedor.getChildren().add(aviso);
        if (contenedor.getChildren().size() > MAXIMO) {
            contenedor.getChildren().removeFirst();
        }
        Animations.slideInRight(aviso, Duration.millis(250)).playFromStart();

        PauseTransition espera = new PauseTransition(textoAccion != null ? Duration.seconds(6) : DURACION);
        espera.setOnFinished(e -> {
            var salida = Animations.fadeOut(aviso, Duration.millis(250));
            salida.setOnFinished(f -> contenedor.getChildren().remove(aviso));
            salida.playFromStart();
        });
        espera.play();
    }
}
