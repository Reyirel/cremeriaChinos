package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** ComboBox de productos que filtra mientras se escribe (sin importar acentos). */
final class SelectorProducto {

    private SelectorProducto() {
    }

    static ComboBox<ProductoCatalogo> crear(List<ProductoCatalogo> productos) {
        FilteredList<ProductoCatalogo> filtrados = new FilteredList<>(FXCollections.observableArrayList(productos));
        ComboBox<ProductoCatalogo> combo = new ComboBox<>(filtrados);
        combo.setEditable(true);
        combo.setPromptText("Escribe para buscar un producto");
        combo.setVisibleRowCount(12);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ProductoCatalogo p) {
                return p == null ? "" : p.nombre();
            }

            @Override
            public ProductoCatalogo fromString(String texto) {
                return productos.stream().filter(p -> p.nombre().equals(texto)).findFirst().orElse(null);
            }
        });
        combo.getEditor().textProperty().addListener((o, antes, texto) -> {
            ProductoCatalogo elegido = combo.getSelectionModel().getSelectedItem();
            if (elegido != null && elegido.nombre().equals(texto)) {
                return;
            }
            String t = normalizar(texto);
            Platform.runLater(() -> {
                filtrados.setPredicate(p -> t.isEmpty() || normalizar(p.nombre()).contains(t)
                        || (p.clave() != null && p.clave().equalsIgnoreCase(t)));
                if (!t.isEmpty() && !combo.isShowing() && combo.isFocused() && !filtrados.isEmpty()) {
                    combo.show();
                }
            });
        });
        return combo;
    }

    /** Producto elegido (aunque el usuario solo haya escrito el nombre completo). */
    static ProductoCatalogo valor(ComboBox<ProductoCatalogo> combo) {
        ProductoCatalogo p = combo.getValue();
        if (p == null) {
            p = combo.getConverter().fromString(combo.getEditor().getText());
        }
        return p;
    }

    private static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
}
