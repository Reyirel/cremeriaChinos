package com.cremerias.puntoventa.ui.componentes;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Lista (tabla) con su panel de detalle. Con espacio suficiente el detalle se acopla a la derecha y
 * la tabla conserva un ancho legible; en ventanas angostas el detalle se abre flotando sobre la tabla
 * en lugar de comprimirla. Esc cierra el detalle.
 */
public class MaestroDetalle extends StackPane {

    private final PanelDetalle detalle;
    private final double anchoMinimoMaestro;
    private final HBox acoplado = new HBox(Diseno.ESPACIO_L);
    private final Region velo = new Region();
    private final StackPane flotante = new StackPane();
    private final ReadOnlyBooleanWrapper abierto = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper acopladoAbierto = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper tablaReducida = new ReadOnlyBooleanWrapper(false);
    private Runnable alCerrar = () -> { };
    private boolean modoAcoplado = true;
    private double anchoCompleto = Double.MAX_VALUE;

    /**
     * @param anchoMinimoMaestro ancho por debajo del cual la tabla ya no se lee bien junto al panel
     */
    public MaestroDetalle(Node maestro, PanelDetalle detalle, double anchoMinimoMaestro) {
        this.detalle = detalle;
        this.anchoMinimoMaestro = anchoMinimoMaestro;
        getStyleClass().add("maestro-detalle");
        HBox.setHgrow(maestro, Priority.ALWAYS);
        if (maestro instanceof Region r) {
            r.setMinWidth(0);
        }
        detalle.setPrefWidth(Diseno.ANCHO_DETALLE);
        detalle.setMinWidth(Diseno.ANCHO_DETALLE);
        detalle.setMaxWidth(Diseno.ANCHO_DETALLE);
        detalle.setAlCerrar(this::cerrar);

        velo.getStyleClass().add("velo-detalle");
        velo.setOnMouseClicked(e -> cerrar());
        flotante.setAlignment(Pos.CENTER_RIGHT);
        flotante.setPickOnBounds(false);

        acoplado.getChildren().add(maestro);
        getChildren().add(acoplado);
        widthProperty().addListener((o, a, b) -> reacomodar());
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && abierto.get()) {
                cerrar();
                e.consume();
            }
        });
    }

    public void setAlCerrar(Runnable accion) {
        this.alCerrar = accion;
    }

    public ReadOnlyBooleanProperty abiertoProperty() {
        return abierto.getReadOnlyProperty();
    }

    public boolean estaAbierto() {
        return abierto.get();
    }

    /**
     * El detalle está abierto junto a la tabla. Las páginas ocultan entonces las columnas que el
     * panel ya muestra (acciones, estado) para que lo importante de la tabla siga a la vista.
     */
    public ReadOnlyBooleanProperty acopladoAbiertoProperty() {
        return acopladoAbierto.getReadOnlyProperty();
    }

    /**
     * Ancho que necesita la tabla para mostrar todas sus columnas (ver {@link TablaDatos#anchoNecesario()}).
     * Sin indicarlo, la tabla se considera reducida siempre que el detalle está acoplado.
     */
    public void setAnchoCompleto(double ancho) {
        this.anchoCompleto = ancho;
        actualizarReducida();
    }

    /**
     * El detalle está acoplado y a la tabla ya no le caben todas sus columnas: las páginas ocultan
     * entonces las que el panel ya muestra (acciones, estado), para que lo importante siga a la vista.
     * En pantallas amplias la tabla conserva todo aunque el detalle esté abierto.
     */
    public ReadOnlyBooleanProperty tablaReducidaProperty() {
        return tablaReducida.getReadOnlyProperty();
    }

    public boolean tablaReducida() {
        return tablaReducida.get();
    }

    private void actualizarReducida() {
        double maestro = getWidth() - anchoDetalle() - Diseno.ESPACIO_L;
        tablaReducida.set(acopladoAbierto.get() && maestro < anchoCompleto);
    }

    public void abrir() {
        if (abierto.get()) {
            return;
        }
        abierto.set(true);
        reacomodar();
        Diseno.entrarDesdeDerecha(detalle);
    }

    public void cerrar() {
        if (!abierto.get()) {
            return;
        }
        abierto.set(false);
        acoplado.getChildren().remove(detalle);
        getChildren().removeAll(velo, flotante);
        flotante.getChildren().clear();
        detalle.getStyleClass().remove("flotante");
        acopladoAbierto.set(false);
        actualizarReducida();
        alCerrar.run();
    }

    /** En pantallas amplias el panel gana un poco de ancho para sus listas. */
    private double anchoDetalle() {
        return getWidth() >= 1300 ? Diseno.ANCHO_DETALLE + 48 : Diseno.ANCHO_DETALLE;
    }

    private boolean cabeAcoplado() {
        return getWidth() <= 0 || getWidth() - anchoDetalle() - Diseno.ESPACIO_L >= anchoMinimoMaestro;
    }

    private void reacomodar() {
        double ancho = anchoDetalle();
        detalle.setPrefWidth(ancho);
        detalle.setMinWidth(ancho);
        detalle.setMaxWidth(ancho);
        boolean acoplar = cabeAcoplado();
        if (!abierto.get()) {
            modoAcoplado = acoplar;
            actualizarReducida();
            return;
        }
        if (acoplar) {
            getChildren().removeAll(velo, flotante);
            flotante.getChildren().clear();
            detalle.getStyleClass().remove("flotante");
            if (!acoplado.getChildren().contains(detalle)) {
                acoplado.getChildren().add(detalle);
            }
        } else {
            acoplado.getChildren().remove(detalle);
            if (!detalle.getStyleClass().contains("flotante")) {
                detalle.getStyleClass().add("flotante");
            }
            if (!flotante.getChildren().contains(detalle)) {
                flotante.getChildren().setAll(detalle);
                StackPane.setMargin(detalle, new Insets(0));
            }
            if (!getChildren().contains(velo)) {
                getChildren().addAll(velo, flotante);
            }
        }
        modoAcoplado = acoplar;
        acopladoAbierto.set(acoplar);
        actualizarReducida();
    }

    public boolean acoplado() {
        return modoAcoplado;
    }
}
