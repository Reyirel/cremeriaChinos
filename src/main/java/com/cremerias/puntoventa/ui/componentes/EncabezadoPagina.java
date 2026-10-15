package com.cremerias.puntoventa.ui.componentes;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Título de la pantalla con una línea de contexto y, a la derecha, sus acciones (la principal al
 * final). Los botones nunca se recortan: si falta espacio, se ajusta la descripción.
 */
public class EncabezadoPagina extends HBox {

    private final Label titulo = new Label();
    private final Label descripcion = new Label();
    private final HBox acciones = new HBox(8);

    public EncabezadoPagina(String titulo, String descripcion) {
        getStyleClass().add("encabezado-pagina");
        setAlignment(Pos.CENTER_LEFT);
        this.titulo.getStyleClass().add("pagina-titulo");
        this.titulo.setMinWidth(Region.USE_PREF_SIZE);
        this.descripcion.getStyleClass().add("pagina-descripcion");
        this.descripcion.setWrapText(true);
        this.descripcion.managedProperty().bind(this.descripcion.visibleProperty());
        VBox textos = new VBox(2, this.titulo, this.descripcion);
        textos.setMinWidth(0);
        textos.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textos, Priority.ALWAYS);
        acciones.setAlignment(Pos.CENTER_RIGHT);
        acciones.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(textos, acciones);
        setTitulo(titulo);
        setDescripcion(descripcion);
    }

    public EncabezadoPagina acciones(Node... nodos) {
        for (Node n : nodos) {
            if (n instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        acciones.getChildren().addAll(nodos);
        return this;
    }

    public HBox zonaAcciones() {
        return acciones;
    }

    public final void setTitulo(String texto) {
        titulo.setText(texto);
    }

    public final void setDescripcion(String texto) {
        descripcion.setText(texto == null ? "" : texto);
        descripcion.setVisible(texto != null && !texto.isBlank());
    }
}
