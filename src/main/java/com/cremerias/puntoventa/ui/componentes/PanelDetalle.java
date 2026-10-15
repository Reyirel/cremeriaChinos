package com.cremerias.puntoventa.ui.componentes;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * Panel con el detalle del registro elegido: título, contexto, secciones de datos y acciones al
 * pie. Se usa acoplado junto a la tabla o flotante en ventanas angostas (ver {@link MaestroDetalle}).
 */
public class PanelDetalle extends VBox {

    private static final PseudoClass ULTIMA = PseudoClass.getPseudoClass("ultima");

    private final Label titulo = new Label();
    private final Label subtitulo = new Label();
    private final HBox extra = new HBox(6);
    private final VBox cuerpo = new VBox();
    private final FlowPane pie = new FlowPane(8, 8);
    private final Button cerrar = new Button(null, new FontIcon("mdi2c-close"));

    public PanelDetalle() {
        getStyleClass().add("panel-detalle");
        titulo.getStyleClass().add("detalle-titulo");
        titulo.setWrapText(true);
        subtitulo.getStyleClass().add("detalle-subtitulo");
        subtitulo.setWrapText(true);
        subtitulo.managedProperty().bind(subtitulo.visibleProperty());
        extra.setAlignment(Pos.CENTER_LEFT);
        extra.managedProperty().bind(extra.visibleProperty());
        extra.setVisible(false);
        VBox textos = new VBox(4, titulo, subtitulo, extra);
        textos.setMinWidth(0);
        HBox.setHgrow(textos, Priority.ALWAYS);
        cerrar.getStyleClass().addAll("flat", "boton-icono");
        cerrar.setAccessibleText("Cerrar detalle");
        cerrar.setTooltip(new Tooltip("Cerrar (Esc)"));
        HBox encabezado = new HBox(8, textos, cerrar);
        encabezado.getStyleClass().add("detalle-encabezado");

        cuerpo.getStyleClass().add("detalle-cuerpo");
        ScrollPane desplazable = new ScrollPane(cuerpo);
        desplazable.setFitToWidth(true);
        desplazable.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(desplazable, Priority.ALWAYS);

        pie.getStyleClass().add("detalle-pie");
        pie.managedProperty().bind(pie.visibleProperty());
        pie.setVisible(false);

        getChildren().addAll(encabezado, desplazable, pie);
        setMinWidth(0);
    }

    public void setAlCerrar(Runnable accion) {
        cerrar.setOnAction(e -> accion.run());
    }

    public void setTitulo(String texto, String contexto) {
        titulo.setText(texto);
        subtitulo.setText(contexto == null ? "" : contexto);
        subtitulo.setVisible(contexto != null && !contexto.isBlank());
    }

    /** Etiquetas bajo el título (estado, tipo). */
    public void setEtiquetas(Node... etiquetas) {
        extra.getChildren().setAll(etiquetas);
        extra.setVisible(etiquetas.length > 0);
    }

    public void setContenido(Node... secciones) {
        cuerpo.getChildren().setAll(secciones);
    }

    public void setAcciones(Node... acciones) {
        for (Node n : acciones) {
            if (n instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        pie.getChildren().setAll(acciones);
        pie.setVisible(acciones.length > 0);
    }

    // ------------------------------------------------------------------ piezas del cuerpo

    /** Sección con título pequeño. */
    public static VBox seccion(String titulo, Node... contenido) {
        Label t = new Label(titulo);
        t.getStyleClass().add("detalle-seccion-titulo");
        VBox v = new VBox(8, t);
        v.getChildren().addAll(contenido);
        v.getStyleClass().add("detalle-seccion");
        return v;
    }

    /** Fila «etiqueta ........ valor». */
    public static HBox dato(String etiqueta, String valor) {
        Label e = new Label(etiqueta);
        e.getStyleClass().add("dato-etiqueta");
        e.setMinWidth(Region.USE_PREF_SIZE);
        Label v = new Label(valor == null || valor.isBlank() ? "—" : valor);
        v.getStyleClass().add("dato-valor");
        v.setWrapText(true);
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox h = new HBox(12, e, espacio, v);
        h.getStyleClass().add("dato");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Cifra destacada con su unidad (p. ej. existencia). */
    public static VBox cifra(String etiqueta, String valor, String unidad) {
        Label e = new Label(etiqueta);
        e.getStyleClass().add("dato-etiqueta");
        Label v = new Label(valor);
        v.getStyleClass().add("detalle-cifra");
        HBox fila = new HBox(6, v);
        fila.setAlignment(Pos.BASELINE_LEFT);
        if (unidad != null && !unidad.isBlank()) {
            Label u = new Label(unidad);
            u.getStyleClass().add("detalle-cifra-unidad");
            fila.getChildren().add(u);
        }
        VBox caja = new VBox(2, e, fila);
        caja.getStyleClass().add("detalle-destacado");
        return caja;
    }

    /** Lista con borde de filas simples (lotes, presentaciones, movimientos). */
    public static VBox lista(List<? extends Node> filas) {
        VBox v = new VBox();
        v.getStyleClass().add("lista-simple");
        for (int i = 0; i < filas.size(); i++) {
            Node f = filas.get(i);
            f.getStyleClass().add("lista-simple-fila");
            f.pseudoClassStateChanged(ULTIMA, i == filas.size() - 1);
            v.getChildren().add(f);
        }
        return v;
    }

    /** Fila de lista: texto principal y secundario a la izquierda, valor a la derecha. */
    public static HBox filaLista(String principal, String secundario, String valor, Node extraDerecha) {
        Label p = new Label(principal);
        p.getStyleClass().add("celda-principal");
        p.setWrapText(true);
        VBox textos = new VBox(1, p);
        if (secundario != null && !secundario.isBlank()) {
            Label s = new Label(secundario);
            s.getStyleClass().add("celda-secundaria");
            s.setWrapText(true);
            textos.getChildren().add(s);
        }
        textos.setMinWidth(0);
        HBox.setHgrow(textos, Priority.ALWAYS);
        HBox h = new HBox(10, textos);
        h.setAlignment(Pos.CENTER_LEFT);
        if (valor != null) {
            Label v = new Label(valor);
            v.getStyleClass().add("dato-valor");
            v.setMinWidth(Region.USE_PREF_SIZE);
            h.getChildren().add(v);
        }
        if (extraDerecha != null) {
            h.getChildren().add(extraDerecha);
        }
        return h;
    }
}
