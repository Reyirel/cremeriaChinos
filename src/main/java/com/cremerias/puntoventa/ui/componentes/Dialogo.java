package com.cremerias.puntoventa.ui.componentes;

import atlantafx.base.controls.Spacer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Tarjeta de diálogo que se muestra dentro de la ventana (ver {@link Dialogos}).
 * Enter confirma y Esc cancela.
 */
public class Dialogo extends VBox {

    public enum Tono { ACENTO, EXITO, PELIGRO, ADVERTENCIA, INFO }

    private final VBox cuerpo = new VBox(16);
    private final HBox pie = new HBox(10);
    private final Button botonCerrar = new Button();
    private Runnable alConfirmar;
    private Runnable alCancelar;
    private Runnable cerrador = () -> { };
    private Node focoInicial;

    public Dialogo(String titulo, String subtitulo, String icono, Tono tono) {
        getStyleClass().addAll("dialogo", "tono-" + tono.name().toLowerCase());
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPrefWidth(480);

        StackPane iconoCaja = new StackPane(new FontIcon(icono));
        iconoCaja.getStyleClass().add("dialogo-icono");

        Label etiquetaTitulo = new Label(titulo);
        etiquetaTitulo.getStyleClass().add("dialogo-titulo");
        etiquetaTitulo.setWrapText(true);
        VBox textos = new VBox(2, etiquetaTitulo);
        if (subtitulo != null && !subtitulo.isBlank()) {
            Label etiquetaSubtitulo = new Label(subtitulo);
            etiquetaSubtitulo.getStyleClass().add("dialogo-subtitulo");
            etiquetaSubtitulo.setWrapText(true);
            textos.getChildren().add(etiquetaSubtitulo);
        }
        textos.setAlignment(Pos.CENTER_LEFT);

        botonCerrar.setGraphic(new FontIcon("mdi2c-close"));
        botonCerrar.getStyleClass().addAll("button-icon", "flat", "rounded");
        botonCerrar.setFocusTraversable(false);
        botonCerrar.setOnAction(e -> cancelar());

        HBox encabezado = new HBox(14, iconoCaja, textos, new Spacer(), botonCerrar);
        encabezado.setAlignment(Pos.CENTER_LEFT);
        encabezado.getStyleClass().add("dialogo-encabezado");

        cuerpo.getStyleClass().add("dialogo-cuerpo");
        pie.getStyleClass().add("dialogo-pie");
        pie.setAlignment(Pos.CENTER_RIGHT);
        pie.managedProperty().bind(pie.visibleProperty());
        pie.setVisible(false);

        getChildren().addAll(encabezado, cuerpo, pie);
        addEventFilter(KeyEvent.KEY_PRESSED, this::teclas);
        setAlCancelar(null);
    }

    void teclas(KeyEvent e) {
        if (listaAbierta()) {
            return; // Esc/Enter los maneja la lista desplegada (ComboBox, calendario...).
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            cancelar();
        } else if (e.getCode() == KeyCode.ENTER) {
            if (getScene() != null && getScene().getFocusOwner() instanceof Button boton && isAncestor(boton)) {
                boton.fire();
                e.consume();
            } else if (alConfirmar != null) {
                alConfirmar.run();
                e.consume();
            }
        }
    }

    private boolean listaAbierta() {
        if (getScene() == null) {
            return false;
        }
        for (Node n = getScene().getFocusOwner(); n != null && n != this; n = n.getParent()) {
            if (n instanceof javafx.scene.control.ComboBoxBase<?> combo && combo.isShowing()) {
                return true;
            }
        }
        return false;
    }

    boolean isAncestor(Node nodo) {
        for (Node n = nodo; n != null; n = n.getParent()) {
            if (n == this) {
                return true;
            }
        }
        return false;
    }

    public VBox cuerpo() {
        return cuerpo;
    }

    public void setContenido(Node... nodos) {
        cuerpo.getChildren().setAll(nodos);
    }

    public Button agregarBoton(String texto, String icono, Runnable accion, String... clases) {
        Button boton = new Button(texto);
        if (icono != null) {
            boton.setGraphic(new FontIcon(icono));
        }
        boton.getStyleClass().addAll(clases);
        boton.setOnAction(e -> accion.run());
        pie.getChildren().add(boton);
        pie.setVisible(true);
        return boton;
    }

    /** Espacio flexible en el pie: los botones siguientes quedan a la derecha. */
    public void separarBotones() {
        pie.getChildren().add(new Spacer());
    }

    /** Acción al presionar Enter. */
    public void setAlConfirmar(Runnable accion) {
        this.alConfirmar = accion;
    }

    /** Acción al presionar Esc o la X; {@code null} = el diálogo no se puede cerrar así. */
    public void setAlCancelar(Runnable accion) {
        this.alCancelar = accion;
        botonCerrar.setVisible(accion != null);
    }

    public void setFocoInicial(Node nodo) {
        this.focoInicial = nodo;
    }

    public void cancelar() {
        if (alCancelar != null) {
            alCancelar.run();
        }
    }

    void setCerrador(Runnable cerrador) {
        this.cerrador = cerrador;
    }

    public void cerrar() {
        cerrador.run();
    }

    void enfocar() {
        (focoInicial != null ? focoInicial : this).requestFocus();
    }
}
