package com.cremerias.puntoventa.ui.componentes;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;

/** Control segmentado para elegir una vista o un periodo; siempre hay una opción elegida. */
public class Segmentado<T> extends HBox {

    private final ToggleGroup grupo = new ToggleGroup();
    private final Map<T, ToggleButton> botones = new LinkedHashMap<>();
    private final ObjectProperty<T> valor = new SimpleObjectProperty<>();
    private boolean sincronizando;

    public Segmentado() {
        getStyleClass().add("segmentado");
        setAlignment(Pos.CENTER_LEFT);
        setMinWidth(Region.USE_PREF_SIZE);
        setMaxWidth(Region.USE_PREF_SIZE);
        grupo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null) {
                if (antes != null) {
                    antes.setSelected(true);
                }
                return;
            }
            if (!sincronizando) {
                botones.forEach((v, b) -> {
                    if (b == ahora) {
                        valor.set(v);
                    }
                });
            }
        });
        valor.addListener((o, a, v) -> {
            ToggleButton b = botones.get(v);
            if (b != null && !b.isSelected()) {
                sincronizando = true;
                b.setSelected(true);
                sincronizando = false;
            }
        });
    }

    public Segmentado<T> opcion(T v, String texto) {
        return opcion(v, texto, null);
    }

    public Segmentado<T> opcion(T v, String texto, String icono) {
        ToggleButton b = new ToggleButton(texto, icono == null ? null : new FontIcon(icono));
        b.setToggleGroup(grupo);
        b.setMinWidth(Region.USE_PREF_SIZE);
        botones.put(v, b);
        getChildren().add(b);
        if (valor.get() == null) {
            valor.set(v);
        }
        return this;
    }

    public ObjectProperty<T> valorProperty() {
        return valor;
    }

    public T getValor() {
        return valor.get();
    }

    public void setValor(T v) {
        valor.set(v);
    }
}
