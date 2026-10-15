package com.cremerias.puntoventa.ui.componentes;

import atlantafx.base.theme.Styles;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Piezas de formulario: campo con etiqueta persistente, ayuda breve y error junto al campo;
 * secciones agrupadas por significado; unidades junto a los números.
 */
public final class Formulario {

    private Formulario() {
    }

    /** Campo con etiqueta arriba, ayuda y error debajo. */
    public static final class Campo extends VBox {

        private final Label etiqueta;
        private final Label ayuda = new Label();
        private final Label error = new Label();
        private final Node control;

        private Campo(String texto, Node control, boolean opcional) {
            super(6);
            this.control = control;
            getStyleClass().add("campo");
            etiqueta = new Label(texto);
            etiqueta.getStyleClass().add("campo-etiqueta");
            if (opcional) {
                Label o = new Label(" (opcional)");
                o.getStyleClass().add("opcional");
                etiqueta.setGraphic(o);
                etiqueta.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            }
            Node objetivo = control instanceof HBox h && !h.getChildren().isEmpty() ? h.getChildren().getFirst() : control;
            etiqueta.setLabelFor(objetivo);
            ayuda.getStyleClass().add("campo-ayuda");
            ayuda.managedProperty().bind(ayuda.visibleProperty());
            ayuda.setVisible(false);
            error.getStyleClass().add("campo-error");
            error.setGraphic(new FontIcon("mdi2a-alert-circle-outline"));
            error.managedProperty().bind(error.visibleProperty());
            error.setVisible(false);
            if (control instanceof Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
            }
            getChildren().addAll(etiqueta, control, ayuda, error);
        }

        public Campo ayuda(String texto) {
            ayuda.setText(texto == null ? "" : texto);
            ayuda.setVisible(texto != null && !texto.isBlank());
            return this;
        }

        public Label etiqueta() {
            return etiqueta;
        }

        /** Muestra el error junto al campo y lo marca; {@code null} lo quita. */
        public void setError(String texto) {
            boolean hay = texto != null && !texto.isBlank();
            error.setText(hay ? texto : "");
            error.setVisible(hay);
            marcar(control, hay);
        }

        public boolean tieneError() {
            return error.isVisible();
        }

        private static void marcar(Node n, boolean error) {
            if (n instanceof Control c) {
                c.pseudoClassStateChanged(Styles.STATE_DANGER, error);
            } else if (n instanceof HBox h) {
                h.getChildren().forEach(x -> marcar(x, error));
            }
        }
    }

    public static Campo campo(String etiqueta, Node control) {
        return new Campo(etiqueta, control, false);
    }

    public static Campo opcional(String etiqueta, Node control) {
        return new Campo(etiqueta, control, true);
    }

    /** Grupo de campos con título y explicación breve. */
    public static VBox seccion(String titulo, String ayuda, Node... contenido) {
        VBox v = new VBox(12);
        v.getStyleClass().add("seccion-formulario");
        if (titulo != null) {
            Label t = new Label(titulo);
            t.getStyleClass().add("seccion-formulario-titulo");
            VBox encabezado = new VBox(2, t);
            if (ayuda != null && !ayuda.isBlank()) {
                Label a = new Label(ayuda);
                a.getStyleClass().add("seccion-formulario-ayuda");
                a.setWrapText(true);
                encabezado.getChildren().add(a);
            }
            v.getChildren().add(encabezado);
        }
        v.getChildren().addAll(contenido);
        return v;
    }

    /** Sección separada de la anterior por una línea fina. */
    public static VBox seccionSeparada(String titulo, String ayuda, Node... contenido) {
        VBox v = seccion(titulo, ayuda, contenido);
        v.getStyleClass().add("con-separador");
        return v;
    }

    /** Campos lado a lado que se reparten el ancho. */
    public static HBox fila(Node... campos) {
        HBox h = new HBox(12, campos);
        h.setAlignment(Pos.TOP_LEFT);
        for (Node n : campos) {
            HBox.setHgrow(n, Priority.ALWAYS);
            if (n instanceof Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
                r.setPrefWidth(10);
            }
        }
        return h;
    }

    /** Número con su unidad a la derecha ("250 g", "12 pzas"). */
    public static HBox conUnidad(Region campo, String unidad) {
        Label u = new Label(unidad);
        u.getStyleClass().add("unidad-campo");
        u.setMinWidth(Region.USE_PREF_SIZE);
        HBox h = new HBox(8, campo, u);
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(campo, Priority.ALWAYS);
        campo.setMaxWidth(Double.MAX_VALUE);
        return h;
    }

    /** Nota que explica la consecuencia de una acción (afecta inventario, genera un envío...). */
    public static HBox nota(String icono, String texto, String tono) {
        Label l = new Label(texto);
        l.setWrapText(true);
        l.setMinWidth(0);
        HBox.setHgrow(l, Priority.ALWAYS);
        HBox h = new HBox(10, new FontIcon(icono), l);
        h.getStyleClass().add("nota-consecuencia");
        if (tono != null) {
            h.getStyleClass().add(tono);
        }
        return h;
    }

    /** Resumen destacado de un formulario (total de la entrada, saldo después del abono). */
    public static VBox resumen(String etiqueta, Label valor, Node... detalle) {
        Label e = new Label(etiqueta);
        e.getStyleClass().add("resumen-formulario-etiqueta");
        valor.getStyleClass().add("resumen-formulario-valor");
        VBox v = new VBox(2, e, valor);
        v.getChildren().addAll(detalle);
        v.getStyleClass().add("resumen-formulario");
        return v;
    }
}
