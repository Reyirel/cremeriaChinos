package com.cremerias.puntoventa.ui.componentes;

import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.math.BigDecimal;
import java.util.Optional;

/** Captura de un peso con su unidad (kg, g, mg, µg). Entrega el valor en gramos. */
public class CampoMasa extends HBox {

    private final TextField valor = new TextField();
    private final ComboBox<Masa> unidad = new ComboBox<>(FXCollections.observableArrayList(Masa.values()));

    public CampoMasa() {
        super(8);
        setAlignment(Pos.CENTER_LEFT);
        Campos.soloNumeros(valor, 6);
        valor.setAlignment(Pos.CENTER_RIGHT);
        valor.setPromptText("0");
        unidad.setValue(Masa.G);
        unidad.setPrefWidth(90);
        HBox.setHgrow(valor, Priority.ALWAYS);
        valor.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(valor, unidad);
    }

    public TextField campo() {
        return valor;
    }

    public ComboBox<Masa> selectorUnidad() {
        return unidad;
    }

    /** Valor en gramos, si se capturó un número. */
    public Optional<BigDecimal> gramos() {
        String t = valor.getText();
        if (t == null || t.isBlank() || t.equals(".")) {
            return Optional.empty();
        }
        return Optional.of(unidad.getValue().aGramos(new BigDecimal(t)));
    }

    public void setGramos(BigDecimal gramos) {
        if (gramos == null) {
            valor.clear();
            return;
        }
        Masa m = Masa.sugerida(gramos);
        unidad.setValue(m);
        valor.setText(m.desdeGramos(gramos).toPlainString());
    }
}
