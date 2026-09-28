package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.Spacer;
import com.cremerias.puntoventa.util.Dinero;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.function.Function;

/** Piezas de interfaz comunes del panel del administrador. */
public final class Ui {

    private Ui() {
    }

    /** Contenedor de página con título, subtítulo y acciones a la derecha. */
    public static VBox pagina(String titulo, String subtitulo, Node... acciones) {
        Label t = new Label(titulo);
        t.getStyleClass().add("pagina-titulo");
        Label s = new Label(subtitulo);
        s.getStyleClass().add("pagina-subtitulo");
        s.setWrapText(true);
        VBox textos = new VBox(2, t, s);
        HBox encabezado = new HBox(10, textos, new Spacer());
        encabezado.getChildren().addAll(acciones);
        encabezado.setAlignment(Pos.CENTER_LEFT);
        VBox pagina = new VBox(16, encabezado);
        pagina.getStyleClass().add("pagina-admin");
        pagina.setPadding(new Insets(24, 28, 24, 28));
        return pagina;
    }

    public static Button boton(String texto, String icono, String... clases) {
        Button b = new Button(texto, icono == null ? null : new FontIcon(icono));
        b.getStyleClass().addAll(clases);
        return b;
    }

    public static Button principal(String texto, String icono) {
        return boton(texto, icono, "accent", "boton-principal");
    }

    public static Button secundario(String texto, String icono) {
        return boton(texto, icono, "boton-blanco");
    }

    /** Botón pequeño de ícono para acciones de una fila. */
    public static Button accion(String icono, String ayuda, Runnable accion, String... clases) {
        Button b = new Button(null, new FontIcon(icono));
        b.getStyleClass().addAll("button-icon", "flat", "accion-fila");
        b.getStyleClass().addAll(clases);
        b.setTooltip(new Tooltip(ayuda));
        b.setOnAction(e -> accion.run());
        return b;
    }

    public static HBox acciones(Node... botones) {
        HBox h = new HBox(2, botones);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Chip de estado. Tonos: exito, peligro, advertencia, acento, neutro. */
    public static Label chip(String texto, String tono) {
        Label l = new Label(texto);
        l.getStyleClass().addAll("chip", "chip-" + tono);
        return l;
    }

    public static TextField buscador(String ayuda) {
        TextField t = new TextField();
        t.setPromptText(ayuda);
        t.getStyleClass().add("buscador");
        t.setPrefWidth(300);
        return t;
    }

    public static <T> TableView<T> tabla(String vacio) {
        TableView<T> t = new TableView<>();
        t.getStyleClass().add("tabla-admin");
        t.setPlaceholder(new Label(vacio));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setFixedCellSize(50);
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    public static <T> TableColumn<T, String> texto(String titulo, double ancho, Function<T, String> valor) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setPrefWidth(ancho);
        c.setSortable(false);
        return c;
    }

    public static <T> TableColumn<T, String> dinero(String titulo, double ancho, Function<T, Long> valor) {
        TableColumn<T, String> c = texto(titulo, ancho, x -> Dinero.formatear(valor.apply(x)));
        c.getStyleClass().add("columna-derecha");
        return c;
    }

    public static <T> TableColumn<T, T> nodo(String titulo, double ancho, Function<T, Node> fabrica) {
        TableColumn<T, T> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setPrefWidth(ancho);
        c.setSortable(false);
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean vacio) {
                super.updateItem(item, vacio);
                setGraphic(vacio || item == null ? null : fabrica.apply(item));
            }
        });
        return c;
    }

    /** Columna con el id único (8 primeros caracteres; el completo en la ayuda). */
    public static <T> TableColumn<T, T> id(Function<T, String> valor) {
        return nodo("ID", 96, x -> {
            String id = valor.apply(x);
            Label l = new Label(id == null ? "" : id.substring(0, Math.min(8, id.length())));
            l.getStyleClass().add("texto-id");
            l.setTooltip(new Tooltip(id));
            return l;
        });
    }

    /** Nombre con texto secundario debajo. */
    public static VBox dosLineas(String principal, String secundario) {
        Label a = new Label(principal);
        a.getStyleClass().add("celda-principal");
        VBox v = new VBox(1, a);
        if (secundario != null && !secundario.isBlank()) {
            Label b = new Label(secundario);
            b.getStyleClass().add("celda-secundaria");
            v.getChildren().add(b);
        }
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    /** Campo con su etiqueta arriba. */
    public static VBox campo(String etiqueta, Node control) {
        Label l = new Label(etiqueta);
        l.getStyleClass().add("campo-etiqueta");
        VBox v = new VBox(6, l, control);
        if (control instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return v;
    }

    /** Fila de campos que se reparten el ancho. */
    public static HBox fila(Node... campos) {
        HBox h = new HBox(12, campos);
        for (Node n : campos) {
            HBox.setHgrow(n, Priority.ALWAYS);
            if (n instanceof Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
                r.setPrefWidth(10);
            }
        }
        return h;
    }

    /** Tarjeta blanca con título. */
    public static VBox tarjeta(String titulo, Node... contenido) {
        VBox v = new VBox(12);
        v.getStyleClass().add("tarjeta-admin");
        if (titulo != null) {
            Label t = new Label(titulo);
            t.getStyleClass().add("tarjeta-admin-titulo");
            v.getChildren().add(t);
        }
        v.getChildren().addAll(contenido);
        return v;
    }

    /** Indicador numérico (tarjeta blanca pequeña). */
    public static VBox indicador(String titulo, String valor, String icono) {
        Label t = new Label(titulo);
        t.getStyleClass().add("indicador-titulo");
        StackPane iconoCaja = new StackPane(new FontIcon(icono));
        iconoCaja.getStyleClass().add("indicador-icono");
        HBox arriba = new HBox(t, new Spacer(), iconoCaja);
        arriba.setAlignment(Pos.CENTER_LEFT);
        Label v = new Label(valor);
        v.getStyleClass().add("indicador-valor");
        VBox caja = new VBox(6, arriba, v);
        caja.getStyleClass().add("indicador");
        caja.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(caja, Priority.ALWAYS);
        return caja;
    }

    public static Label texto(String contenido, String clase) {
        Label l = new Label(contenido);
        l.getStyleClass().add(clase);
        l.setWrapText(true);
        return l;
    }
}
