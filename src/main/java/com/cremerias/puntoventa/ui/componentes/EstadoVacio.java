package com.cremerias.puntoventa.ui.componentes;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Lo que se ve cuando no hay datos que mostrar: sin registros, sin resultados para los filtros,
 * un error recuperable o mientras se carga. Siempre dice qué pasó y, si se puede, qué hacer.
 */
public class EstadoVacio extends VBox {

    private final Label titulo = new Label();
    private final Label texto = new Label();
    private final HBox acciones = new HBox(8);
    private final StackPane icono;

    public EstadoVacio(String literalIcono, String titulo, String texto) {
        getStyleClass().add("estado-vacio");
        setAlignment(Pos.CENTER);
        setMaxWidth(440);
        icono = new StackPane(new FontIcon(literalIcono));
        icono.getStyleClass().add("estado-vacio-icono");
        this.titulo.getStyleClass().add("estado-vacio-titulo");
        this.titulo.setWrapText(true);
        this.texto.getStyleClass().add("estado-vacio-texto");
        this.texto.setWrapText(true);
        acciones.setAlignment(Pos.CENTER);
        acciones.managedProperty().bind(acciones.visibleProperty());
        acciones.setVisible(false);
        this.texto.managedProperty().bind(this.texto.visibleProperty());
        getChildren().addAll(icono, this.titulo, this.texto, acciones);
        setTextos(titulo, texto);
    }

    public final void setTextos(String titulo, String texto) {
        this.titulo.setText(titulo);
        this.texto.setText(texto == null ? "" : texto);
        this.texto.setVisible(texto != null && !texto.isBlank());
    }

    public EstadoVacio accion(String textoBoton, String literalIcono, Runnable accion) {
        Button b = new Button(textoBoton, literalIcono == null ? null : new FontIcon(literalIcono));
        b.setOnAction(e -> accion.run());
        acciones.getChildren().add(b);
        acciones.setVisible(true);
        return this;
    }

    public EstadoVacio compacto() {
        getStyleClass().add("compacto");
        return this;
    }

    public EstadoVacio tono(String clase) {
        getStyleClass().add(clase);
        return this;
    }

    /** "No encontramos productos con estos filtros." + Limpiar filtros. */
    public static EstadoVacio sinResultados(String titulo, Runnable limpiar) {
        EstadoVacio e = new EstadoVacio("mdi2f-filter-remove-outline", titulo,
                "Prueba con otra búsqueda o quita algún filtro.");
        if (limpiar != null) {
            e.accion("Limpiar filtros", "mdi2f-filter-off-outline", limpiar);
        }
        return e;
    }

    /** "No se pudo cargar…" + Reintentar. */
    public static EstadoVacio error(String titulo, String detalle, Runnable reintentar) {
        EstadoVacio e = new EstadoVacio("mdi2a-alert-circle-outline", titulo, detalle);
        e.tono("error");
        if (reintentar != null) {
            e.accion("Reintentar", "mdi2r-refresh", reintentar);
        }
        return e;
    }

    /** Indicador de carga con el nombre de lo que se está cargando. */
    public static VBox cargando(String mensaje) {
        ProgressIndicator progreso = new ProgressIndicator();
        progreso.setMaxSize(26, 26);
        Label l = new Label(mensaje);
        VBox v = new VBox(10, progreso, l);
        v.getStyleClass().add("estado-cargando");
        v.setAlignment(Pos.CENTER);
        return v;
    }
}
