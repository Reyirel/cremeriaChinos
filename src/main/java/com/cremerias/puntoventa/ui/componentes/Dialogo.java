package com.cremerias.puntoventa.ui.componentes;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Tarjeta de diálogo que se muestra dentro de la ventana (ver {@link Dialogos}).
 * Enter confirma y Esc cancela.
 *
 * <p>Para guardar, {@link #enviar} hace el trabajo fuera del hilo de la interfaz con el diálogo
 * ocupado (botones desactivados: no hay doble envío). Si falla, el error se muestra arriba del
 * formulario y lo capturado se conserva. Con {@link #vigilarCambios} el diálogo pregunta antes de
 * descartar lo capturado.
 */
public class Dialogo extends VBox {

    public enum Tono { ACENTO, EXITO, PELIGRO, ADVERTENCIA, INFO }

    private final VBox cuerpo = new VBox(16);
    private final ScrollPane desplazable = new CuerpoDesplazable(cuerpo);
    private final HBox error = new HBox(8);
    private final Label textoError = new Label();
    private final HBox pie = new HBox(8);
    private final Button botonCerrar = new Button();
    private final List<Button> botones = new ArrayList<>();
    private Button principal;
    private Runnable alConfirmar;
    private Runnable alCancelar;
    private Runnable cerrador = () -> { };
    private Dialogos dialogos;
    private Node focoInicial;
    private boolean ocupado;
    private boolean cambios;
    /** Ya se pidió cerrar: mientras se desvanece no acepta más clics (no hay doble envío). */
    private boolean cerrado;

    public Dialogo(String titulo, String subtitulo, String icono, Tono tono) {
        getStyleClass().addAll("dialogo", "tono-" + tono.name().toLowerCase());
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPrefWidth(480);

        Label etiquetaTitulo = new Label(titulo);
        etiquetaTitulo.getStyleClass().add("dialogo-titulo");
        etiquetaTitulo.setWrapText(true);
        VBox textos = new VBox(3, etiquetaTitulo);
        if (subtitulo != null && !subtitulo.isBlank()) {
            Label etiquetaSubtitulo = new Label(subtitulo);
            etiquetaSubtitulo.getStyleClass().add("dialogo-subtitulo");
            etiquetaSubtitulo.setWrapText(true);
            textos.getChildren().add(etiquetaSubtitulo);
        }
        textos.setAlignment(Pos.CENTER_LEFT);
        textos.setMinWidth(0);
        HBox.setHgrow(textos, Priority.ALWAYS);

        botonCerrar.setGraphic(new FontIcon("mdi2c-close"));
        botonCerrar.getStyleClass().addAll("flat", "boton-icono");
        botonCerrar.setAccessibleText("Cerrar");
        botonCerrar.setTooltip(new Tooltip("Cerrar (Esc)"));
        botonCerrar.setFocusTraversable(false);
        botonCerrar.setOnAction(e -> cancelar());

        HBox encabezado = new HBox(12, textos, botonCerrar);
        encabezado.setAlignment(Pos.TOP_LEFT);
        encabezado.getStyleClass().add("dialogo-encabezado");
        // Solo los tonos que avisan algo llevan ícono; los formularios no lo necesitan.
        if (tono == Tono.PELIGRO || tono == Tono.ADVERTENCIA || tono == Tono.EXITO) {
            HBox caja = new HBox(new FontIcon(icono));
            caja.getStyleClass().add("dialogo-icono");
            encabezado.getChildren().addFirst(caja);
        }

        textoError.setWrapText(true);
        textoError.setMinWidth(0);
        HBox.setHgrow(textoError, Priority.ALWAYS);
        error.getStyleClass().add("dialogo-error");
        error.getChildren().addAll(new FontIcon("mdi2a-alert-circle-outline"), textoError);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        cuerpo.getStyleClass().add("dialogo-cuerpo");
        desplazable.setFitToWidth(true);
        desplazable.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        desplazable.getStyleClass().add("scroll-dialogo");
        // En pantallas bajas el formulario se desplaza en lugar de salirse de la ventana.
        sceneProperty().addListener((o, a, escena) -> {
            if (escena != null) {
                desplazable.maxHeightProperty().bind(escena.heightProperty().subtract(170));
            }
        });

        pie.getStyleClass().add("dialogo-pie");
        pie.setAlignment(Pos.CENTER_RIGHT);
        pie.managedProperty().bind(pie.visibleProperty());
        pie.setVisible(false);

        getChildren().addAll(encabezado, desplazable, pie);
        addEventFilter(KeyEvent.KEY_PRESSED, this::teclas);
        setAlCancelar(null);
    }

    void teclas(KeyEvent e) {
        if (listaAbierta()) {
            return; // Esc/Enter los maneja la lista desplegada (ComboBox, calendario...).
        }
        if ((ocupado || cerrado) && (e.getCode() == KeyCode.ESCAPE || e.getCode() == KeyCode.ENTER)) {
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            e.consume();
            cancelar();
        } else if (e.getCode() == KeyCode.ENTER) {
            Node foco = getScene() == null ? null : getScene().getFocusOwner();
            if (foco instanceof ButtonBase boton && isAncestor(boton)) {
                boton.fire();
                e.consume();
            } else if (foco instanceof javafx.scene.control.TextArea) {
                return; // Enter es un salto de línea
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
            if (n instanceof ComboBoxBase<?> combo && combo.isShowing()) {
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
        cuerpo.getChildren().setAll(error);
        cuerpo.getChildren().addAll(nodos);
    }

    public Button agregarBoton(String texto, String icono, Runnable accion, String... clases) {
        Button boton = new Button(texto);
        if (icono != null) {
            boton.setGraphic(new FontIcon(icono));
        }
        // Los botones nunca se recortan: si no caben, el diálogo se ensancha.
        boton.setMinWidth(Region.USE_PREF_SIZE);
        boton.getStyleClass().addAll(clases);
        boton.setOnAction(e -> {
            if (!ocupado && !cerrado) {
                accion.run();
            }
        });
        for (String c : clases) {
            if (c.equals("accent") || c.equals("danger") || c.equals("success")) {
                principal = boton;
            }
        }
        botones.add(boton);
        pie.getChildren().add(boton);
        pie.setVisible(true);
        return boton;
    }

    /** Espacio flexible en el pie: los botones siguientes quedan a la derecha. */
    public void separarBotones() {
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        pie.getChildren().add(espacio);
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
        if (ocupado || alCancelar == null) {
            return;
        }
        if (cambios && dialogos != null) {
            DialogoConfirmacion.mostrar(dialogos, "¿Descartar lo que capturaste?",
                    "Tienes cambios sin guardar en este formulario. Si sales ahora, se pierden.",
                    "Descartar cambios", true, alCancelar);
            return;
        }
        alCancelar.run();
    }

    void setCerrador(Runnable cerrador) {
        this.cerrador = cerrador;
    }

    void setDialogos(Dialogos dialogos) {
        this.dialogos = dialogos;
    }

    public void cerrar() {
        cerrado = true;
        cerrador.run();
    }

    void enfocar() {
        (focoInicial != null ? focoInicial : this).requestFocus();
    }

    // ------------------------------------------------------------------ error, ocupado y envío

    /** Error arriba del formulario; lo capturado se conserva para corregirlo. */
    public void mostrarError(String mensaje) {
        textoError.setText(mensaje);
        error.setVisible(mensaje != null && !mensaje.isBlank());
        if (!cuerpo.getChildren().contains(error)) {
            cuerpo.getChildren().addFirst(error);
        }
        desplazable.setVvalue(0);
    }

    public void limpiarError() {
        error.setVisible(false);
    }

    /** Desactiva los botones mientras se guarda (no hay doble envío). */
    public void ocupado(boolean ocupado) {
        this.ocupado = ocupado;
        botones.forEach(b -> b.setDisable(ocupado));
        botonCerrar.setDisable(ocupado);
        if (principal != null) {
            if (ocupado) {
                principal.getProperties().put("grafico", principal.getGraphic());
                ProgressIndicator progreso = new ProgressIndicator();
                progreso.setMaxSize(16, 16);
                progreso.setPrefSize(16, 16);
                progreso.setStyle("-fx-progress-color: white;");
                principal.setGraphic(progreso);
            } else if (principal.getProperties().containsKey("grafico")) {
                principal.setGraphic((Node) principal.getProperties().remove("grafico"));
            }
        }
    }

    public boolean estaOcupado() {
        return ocupado;
    }

    /**
     * Guarda en segundo plano. Al terminar bien se llama a {@code alTerminar} (normalmente cierra el
     * diálogo y avisa); si falla, se muestra el error en el diálogo y se puede corregir y reintentar.
     */
    public <R> void enviar(Callable<R> accion, Consumer<R> alTerminar) {
        if (ocupado || cerrado) {
            return;
        }
        limpiarError();
        ocupado(true);
        Cargador.ejecutar(accion, r -> {
            ocupado(false);
            cambios = false;
            alTerminar.accept(r);
        }, e -> {
            ocupado(false);
            mostrarError(Errores.mensaje(e));
        });
    }

    /**
     * Cuerpo que se desplaza solo cuando no cabe. Calcula su alto para el ancho real del diálogo:
     * así un texto que se ajusta en varias líneas no provoca una barra de desplazamiento de más.
     */
    private static final class CuerpoDesplazable extends ScrollPane {

        private final VBox contenido;

        CuerpoDesplazable(VBox contenido) {
            super(contenido);
            this.contenido = contenido;
        }

        @Override
        public javafx.geometry.Orientation getContentBias() {
            return javafx.geometry.Orientation.HORIZONTAL;
        }

        @Override
        protected double computePrefHeight(double ancho) {
            double w = ancho > 0 ? ancho : getWidth();
            double interior = w > 0 ? w - snappedLeftInset() - snappedRightInset() : -1;
            return contenido.prefHeight(interior) + snappedTopInset() + snappedBottomInset();
        }
    }

    /** Pregunta antes de cerrar si alguno de estos controles cambió después de abrir el diálogo. */
    public void vigilarCambios(Node... controles) {
        for (Node n : controles) {
            vigilar(n);
        }
    }

    public void marcarCambios() {
        cambios = true;
    }

    private void vigilar(Node n) {
        ObservableValue<?> valor = switch (n) {
            case TextInputControl t -> t.textProperty();
            case ComboBoxBase<?> c -> c.valueProperty();
            case CheckBox c -> c.selectedProperty();
            case Toggle t -> t.selectedProperty();
            default -> null;
        };
        if (valor != null) {
            valor.addListener((o, a, b) -> {
                if (getScene() != null) {
                    cambios = true;
                }
            });
        } else if (n instanceof Parent p) {
            for (Node h : p.getChildrenUnmodifiable()) {
                vigilar(h);
            }
        }
    }
}
