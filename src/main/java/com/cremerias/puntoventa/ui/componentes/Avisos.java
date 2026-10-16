package com.cremerias.puntoventa.ui.componentes;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Notificaciones del resultado de una acción (esquina inferior derecha). Desaparecen solas; las que
 * traen una acción duran más. Pasar el mouse encima las detiene mientras se leen.
 */
public class Avisos {

    private static final Duration DURACION = Duration.seconds(4);
    private static final Duration DURACION_CON_ACCION = Duration.seconds(7);
    private static final Duration DURACION_ERROR = Duration.seconds(7);
    private static final int MAXIMO = 4;

    private final VBox contenedor = new VBox(10);

    public Avisos() {
        contenedor.getStyleClass().add("avisos");
        contenedor.setAlignment(Pos.BOTTOM_RIGHT);
        contenedor.setPickOnBounds(false);
        contenedor.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        contenedor.setViewOrder(-100);
        StackPane.setAlignment(contenedor, Pos.BOTTOM_RIGHT);
        setMargenInferior(24);
    }

    public VBox contenedor() {
        return contenedor;
    }

    /** En la caja los avisos suben por encima del botón «Cobrar». */
    public void setMargenInferior(double margen) {
        StackPane.setMargin(contenedor, new Insets(0, 24, margen, 0));
    }

    public void exito(String mensaje) {
        mostrar(mensaje, "mdi2c-check-circle-outline", "exito", null, null, DURACION);
    }

    public void info(String mensaje) {
        mostrar(mensaje, "mdi2i-information-outline", "info", null, null, DURACION);
    }

    public void advertencia(String mensaje) {
        mostrar(mensaje, "mdi2a-alert-outline", "advertencia", null, null, DURACION_CON_ACCION);
    }

    public void error(String mensaje) {
        mostrar(mensaje, "mdi2a-alert-circle-outline", "error", null, null, DURACION_ERROR);
    }

    /** Aviso con un botón (ej. "Ver", "Deshacer"). */
    public void conAccion(String mensaje, String textoAccion, Runnable accion) {
        mostrar(mensaje, "mdi2i-information-outline", "info", textoAccion, accion, DURACION_CON_ACCION);
    }

    public void errorConAccion(String mensaje, String textoAccion, Runnable accion) {
        mostrar(mensaje, "mdi2a-alert-circle-outline", "error", textoAccion, accion, DURACION_ERROR);
    }

    private void mostrar(String mensaje, String icono, String tono, String textoAccion, Runnable accion,
                         Duration duracion) {
        Label texto = new Label(mensaje);
        texto.getStyleClass().add("notificacion-texto");
        texto.setWrapText(true);
        texto.setMinWidth(0);
        VBox cuerpo = new VBox(8, texto);
        HBox.setHgrow(cuerpo, Priority.ALWAYS);

        HBox aviso = new HBox(10);
        aviso.getStyleClass().addAll("notificacion", tono);
        aviso.setAccessibleRole(AccessibleRole.TEXT);
        aviso.setAccessibleText(mensaje);
        Runnable quitar = () -> contenedor.getChildren().remove(aviso);

        if (textoAccion != null) {
            Button boton = new Button(textoAccion);
            boton.getStyleClass().addAll("small", "flat", "accent");
            boton.setOnAction(e -> {
                accion.run();
                quitar.run();
            });
            HBox acciones = new HBox(boton);
            acciones.setTranslateX(-10);
            cuerpo.getChildren().add(acciones);
        }
        Button cerrar = new Button(null, new FontIcon("mdi2c-close"));
        cerrar.getStyleClass().addAll("flat", "boton-icono");
        cerrar.setAccessibleText("Cerrar aviso");
        cerrar.setTooltip(new Tooltip("Cerrar"));
        cerrar.setOnAction(e -> quitar.run());
        cerrar.setFocusTraversable(false);
        cerrar.setPrefSize(26, 26);
        cerrar.setMinSize(26, 26);
        cerrar.setMaxSize(26, 26);
        aviso.getChildren().addAll(new FontIcon(icono), cuerpo, cerrar);

        contenedor.getChildren().add(aviso);
        if (contenedor.getChildren().size() > MAXIMO) {
            contenedor.getChildren().removeFirst();
        }
        Diseno.entrarDesdeAbajo(aviso);

        PauseTransition espera = new PauseTransition(duracion);
        espera.setOnFinished(e -> {
            if (Diseno.movimientoReducido()) {
                quitar.run();
                return;
            }
            FadeTransition salida = new FadeTransition(Diseno.MOVIMIENTO, aviso);
            salida.setToValue(0);
            salida.setOnFinished(f -> quitar.run());
            salida.play();
        });
        aviso.setOnMouseEntered(e -> espera.pause());
        aviso.setOnMouseExited(e -> espera.play());
        espera.play();
    }
}
