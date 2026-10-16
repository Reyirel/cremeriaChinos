package com.cremerias.puntoventa.ui.componentes;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Barra contextual de cambios sin guardar, con «Descartar» y «Guardar cambios». Va debajo de la
 * tabla y ocupa lugar solo mientras hay cambios: no tapa la última fila mientras se captura.
 */
public class BarraCambios extends HBox {

    private final Label texto = new Label();
    private final Label detalle = new Label();
    private final Button descartar = new Button("Descartar", new FontIcon("mdi2u-undo-variant"));
    private final Button guardar = new Button("Guardar cambios", new FontIcon("mdi2c-check"));

    public BarraCambios(Runnable alDescartar, Runnable alGuardar) {
        getStyleClass().add("barra-cambios");
        setAlignment(Pos.CENTER_LEFT);
        texto.getStyleClass().add("texto-cambios");
        detalle.getStyleClass().add("detalle-cambios");
        detalle.managedProperty().bind(detalle.visibleProperty());
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        descartar.getStyleClass().add("flat");
        guardar.getStyleClass().add("accent");
        // Si falta espacio se recorta el detalle (queda completo en su ayuda), nunca los botones.
        texto.setMinWidth(Region.USE_PREF_SIZE);
        descartar.setMinWidth(Region.USE_PREF_SIZE);
        guardar.setMinWidth(Region.USE_PREF_SIZE);
        detalle.setMinWidth(0);
        detalle.setTooltip(new Tooltip());
        detalle.getTooltip().textProperty().bind(detalle.textProperty());
        descartar.setOnAction(e -> alDescartar.run());
        guardar.setOnAction(e -> alGuardar.run());
        getChildren().addAll(new FontIcon("mdi2p-pencil-circle-outline"), texto, detalle, espacio, descartar, guardar);
        setMaxHeight(Region.USE_PREF_SIZE);
        managedProperty().bind(visibleProperty());
        setVisible(false);
    }

    /** Muestra u oculta la barra según cuántos cambios hay; {@code problema} explica qué falta corregir. */
    public void setCambios(int cambios, String problema) {
        boolean mostrar = cambios > 0;
        texto.setText(cambios == 1 ? "Tienes 1 cambio sin guardar" : "Tienes " + cambios + " cambios sin guardar");
        detalle.setText(problema == null ? "" : "· " + problema);
        detalle.setVisible(problema != null);
        guardar.setDisable(problema != null);
        if (mostrar && !isVisible()) {
            setVisible(true);
            Diseno.entrarDesdeAbajo(this);
        } else if (!mostrar) {
            setVisible(false);
        }
    }

    public void ocupado(boolean ocupado) {
        guardar.setDisable(ocupado);
        descartar.setDisable(ocupado);
        guardar.setText(ocupado ? "Guardando…" : "Guardar cambios");
    }
}
