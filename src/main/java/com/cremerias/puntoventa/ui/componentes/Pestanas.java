package com.cremerias.puntoventa.ui.componentes;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/** Pestañas con su conteo real ("Por aprobar 3"). Siempre hay una seleccionada. */
public class Pestanas extends HBox {

    private final ToggleGroup grupo = new ToggleGroup();

    public Pestanas() {
        getStyleClass().add("pestanas");
        setAlignment(Pos.BOTTOM_LEFT);
        grupo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null && antes != null) {
                antes.setSelected(true);
            }
        });
    }

    public ToggleButton agregar(String texto) {
        ToggleButton t = new ToggleButton(texto);
        t.getStyleClass().setAll("pestana");
        t.setToggleGroup(grupo);
        Label contador = new Label();
        contador.getStyleClass().add("contador-pestana");
        contador.managedProperty().bind(contador.visibleProperty());
        contador.setVisible(false);
        t.setGraphic(contador);
        if (grupo.getSelectedToggle() == null) {
            t.setSelected(true);
        }
        getChildren().add(t);
        return t;
    }

    /** Conteo junto al nombre; {@code alerta} lo resalta (pendientes por atender). */
    public static void setContador(ToggleButton pestana, int n, boolean alerta) {
        if (pestana.getGraphic() instanceof Label l) {
            l.setText(String.valueOf(n));
            l.setVisible(n >= 0);
            l.getStyleClass().remove("alerta");
            if (alerta && n > 0) {
                l.getStyleClass().add("alerta");
            }
        }
        pestana.setAccessibleText(pestana.getText() + (n >= 0 ? ", " + n : ""));
    }

    public ReadOnlyObjectProperty<Toggle> seleccionProperty() {
        return grupo.selectedToggleProperty();
    }
}
