package com.cremerias.puntoventa.ui.componentes;

import atlantafx.base.controls.CustomTextField;
import javafx.beans.Observable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Barra de búsqueda y filtros de una tabla. Los filtros frecuentes quedan a la vista; los demás
 * van en «Más filtros». Los filtros activos se muestran como etiquetas que se pueden quitar una a
 * una, y «Limpiar filtros» los quita todos (también lo usa el estado «sin resultados»).
 */
public class BarraHerramientas extends VBox {

    /** Un filtro que la barra sabe describir y limpiar. */
    private record Filtro(String nombre, BooleanSupplier activo, Supplier<String> valor, Runnable limpiar,
                          boolean adicional) {
    }

    private final HBox principal = new HBox(8);
    private final FlowPane izquierda = new FlowPane(8, 8);
    private final HBox derecha = new HBox(12);
    private final FlowPane adicionales = new FlowPane();
    private final FlowPane activos = new FlowPane();
    private final Label resultados = new Label();
    private final List<Filtro> filtros = new ArrayList<>();
    private final Label contadorAdicionales = new Label();
    private ToggleButton masFiltros;
    private CustomTextField buscador;
    private Runnable alCambiar = () -> { };
    private boolean limpiando;

    public BarraHerramientas() {
        getStyleClass().add("barra-herramientas");
        setSpacing(0);
        izquierda.setAlignment(Pos.CENTER_LEFT);
        izquierda.setPrefWrapLength(320);
        derecha.setAlignment(Pos.CENTER_RIGHT);
        derecha.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(izquierda, Priority.ALWAYS);
        principal.setAlignment(Pos.CENTER_LEFT);
        principal.getChildren().addAll(izquierda, derecha);

        resultados.getStyleClass().add("texto-resultados");
        resultados.setMinWidth(Region.USE_PREF_SIZE);
        derecha.getChildren().add(resultados);

        adicionales.getStyleClass().add("filtros-adicionales");
        adicionales.setHgap(12);
        adicionales.setVgap(8);
        adicionales.managedProperty().bind(adicionales.visibleProperty());
        adicionales.setVisible(false);

        activos.getStyleClass().add("filtros-activos");
        activos.setHgap(6);
        activos.setVgap(6);
        activos.managedProperty().bind(activos.visibleProperty());
        activos.setVisible(false);

        getChildren().addAll(principal, adicionales, activos);
    }

    /** Se llama cada vez que cambia la búsqueda o algún filtro. */
    public void setAlCambiar(Runnable accion) {
        this.alCambiar = accion;
    }

    /** Campo de búsqueda con lupa y botón para borrar. Esc lo limpia. */
    public CustomTextField buscador(String ayuda) {
        buscador = new CustomTextField();
        buscador.setPromptText(ayuda);
        buscador.getStyleClass().add("campo-busqueda-admin");
        buscador.setLeft(new FontIcon("mdi2m-magnify"));
        Button borrar = new Button(null, new FontIcon("mdi2c-close"));
        borrar.getStyleClass().addAll("flat", "boton-icono", "boton-limpiar-busqueda");
        borrar.setFocusTraversable(false);
        borrar.setAccessibleText("Borrar búsqueda");
        borrar.setTooltip(new Tooltip("Borrar búsqueda (Esc)"));
        borrar.setOnAction(e -> buscador.clear());
        borrar.visibleProperty().bind(buscador.textProperty().isNotEmpty());
        borrar.setPrefSize(26, 26);
        borrar.setMinSize(26, 26);
        borrar.setMaxSize(26, 26);
        buscador.setRight(borrar);
        buscador.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && !buscador.getText().isEmpty()) {
                buscador.clear();
                e.consume();
            }
        });
        buscador.textProperty().addListener((o, a, b) -> cambio());
        buscador.setPrefWidth(300);
        buscador.setMinWidth(220);
        izquierda.getChildren().addFirst(buscador);
        filtros.add(new Filtro("Búsqueda", () -> !buscador.getText().isBlank(), () -> buscador.getText().strip(),
                buscador::clear, false));
        return buscador;
    }

    public TextField campoBusqueda() {
        return buscador;
    }

    /** Texto de búsqueda normalizado (sin espacios a los lados, en minúsculas y sin acentos). */
    public String busqueda() {
        return buscador == null ? "" : Texto.normalizar(buscador.getText());
    }

    /**
     * Filtro visible en la barra. {@code todos} es la opción que no filtra (ej. "Todas las
     * categorías"); cualquier otra se muestra además como etiqueta de filtro activo.
     */
    public <T> ComboBox<T> filtro(String nombre, ComboBox<T> combo, T todos, Function<T, String> texto) {
        configurarCombo(combo, texto);
        combo.valueProperty().addListener((o, a, b) -> cambio());
        izquierda.getChildren().add(combo);
        filtros.add(new Filtro(nombre, () -> combo.getValue() != null && !Objects.equals(combo.getValue(), todos),
                () -> texto.apply(combo.getValue()), () -> combo.setValue(todos), false));
        return combo;
    }

    /** Control libre en la barra (segmentado, fechas...) sin etiqueta de filtro activo. */
    public void agregar(Node control) {
        izquierda.getChildren().add(control);
    }

    /** Botones o datos a la derecha, antes del conteo de resultados. */
    public void agregarDerecha(Node control) {
        derecha.getChildren().add(derecha.getChildren().size() - 1, control);
    }

    /** Filtro dentro de «Más filtros», con su etiqueta. */
    public void filtroAdicional(String nombre, Node control, BooleanSupplier activo, Supplier<String> valor,
                                Runnable limpiar, Observable... cambia) {
        asegurarMasFiltros();
        Label etiqueta = new Label(nombre);
        etiqueta.getStyleClass().add("etiqueta-filtro");
        etiqueta.managedProperty().bind(etiqueta.visibleProperty());
        etiqueta.setVisible(!nombre.isBlank());
        HBox campo = new HBox(8, etiqueta, control);
        campo.setAlignment(Pos.CENTER_LEFT);
        adicionales.getChildren().add(campo);
        for (Observable o : cambia) {
            o.addListener(x -> cambio());
        }
        filtros.add(new Filtro(nombre, activo, valor, limpiar, true));
    }

    /** Combo dentro de «Más filtros». */
    public <T> ComboBox<T> filtroAdicional(String nombre, ComboBox<T> combo, T todos, Function<T, String> texto) {
        configurarCombo(combo, texto);
        filtroAdicional(nombre, combo, () -> combo.getValue() != null && !Objects.equals(combo.getValue(), todos),
                () -> texto.apply(combo.getValue()), () -> combo.setValue(todos), combo.valueProperty());
        return combo;
    }

    private void asegurarMasFiltros() {
        if (masFiltros != null) {
            return;
        }
        contadorAdicionales.getStyleClass().add("contador-filtros");
        contadorAdicionales.managedProperty().bind(contadorAdicionales.visibleProperty());
        contadorAdicionales.setVisible(false);
        masFiltros = new ToggleButton("Más filtros", new FontIcon("mdi2t-tune-variant"));
        masFiltros.getStyleClass().add("boton-filtros");
        HBox contenido = new HBox(6, new Label("Más filtros"), contadorAdicionales);
        contenido.setAlignment(Pos.CENTER_LEFT);
        masFiltros.setText(null);
        masFiltros.setGraphic(new HBox(8, new FontIcon("mdi2t-tune-variant"), contenido));
        ((HBox) masFiltros.getGraphic()).setAlignment(Pos.CENTER_LEFT);
        masFiltros.setMinWidth(Region.USE_PREF_SIZE);
        adicionales.visibleProperty().bind(masFiltros.selectedProperty());
        izquierda.getChildren().add(masFiltros);
    }

    private <T> void configurarCombo(ComboBox<T> combo, Function<T, String> texto) {
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(T valor) {
                return valor == null ? "" : texto.apply(valor);
            }

            @Override
            public T fromString(String s) {
                return combo.getValue();
            }
        });
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean vacio) {
                super.updateItem(item, vacio);
                setText(vacio || item == null ? combo.getPromptText() : texto.apply(item));
            }
        });
        combo.setMinWidth(Region.USE_PREF_SIZE);
    }

    public void setResultados(String texto) {
        resultados.setText(texto);
    }

    public Label resultados() {
        return resultados;
    }

    /** Quita todos los filtros (y la búsqueda). */
    public void limpiar() {
        limpiando = true;
        try {
            filtros.forEach(f -> f.limpiar().run());
        } finally {
            limpiando = false;
        }
        cambio();
    }

    public boolean hayFiltros() {
        return filtros.stream().anyMatch(f -> f.activo().getAsBoolean());
    }

    private void cambio() {
        if (limpiando) {
            return;
        }
        actualizarActivos();
        alCambiar.run();
    }

    private void actualizarActivos() {
        activos.getChildren().clear();
        int adicionalesActivos = 0;
        for (Filtro f : filtros) {
            if (!f.activo().getAsBoolean()) {
                continue;
            }
            if (f.adicional()) {
                adicionalesActivos++;
            }
            if (f.nombre().equals("Búsqueda")) {
                continue; // la búsqueda ya está a la vista en su campo
            }
            Label texto = new Label(f.nombre().isBlank() ? f.valor().get() : f.nombre() + ": " + f.valor().get());
            Button quitar = new Button(null, new FontIcon("mdi2c-close"));
            quitar.setAccessibleText("Quitar filtro " + f.nombre());
            quitar.setTooltip(new Tooltip("Quitar filtro"));
            quitar.setOnAction(e -> f.limpiar().run());
            HBox chip = new HBox(4, texto, quitar);
            chip.getStyleClass().add("chip-filtro");
            chip.setAlignment(Pos.CENTER_LEFT);
            activos.getChildren().add(chip);
        }
        if (!activos.getChildren().isEmpty()) {
            Button limpiarTodo = new Button("Limpiar filtros");
            limpiarTodo.getStyleClass().addAll("flat", "accent", "small");
            limpiarTodo.setOnAction(e -> limpiar());
            activos.getChildren().add(limpiarTodo);
        }
        activos.setVisible(!activos.getChildren().isEmpty());
        contadorAdicionales.setText(String.valueOf(adicionalesActivos));
        contadorAdicionales.setVisible(adicionalesActivos > 0);
    }
}
