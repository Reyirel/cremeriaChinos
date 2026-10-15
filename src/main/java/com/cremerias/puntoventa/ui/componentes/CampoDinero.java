package com.cremerias.puntoventa.ui.componentes;

import atlantafx.base.controls.CustomTextField;
import com.cremerias.puntoventa.util.Dinero;
import javafx.scene.control.Label;

import java.util.Optional;

/** Campo para capturar importes en pesos. */
public class CampoDinero extends CustomTextField {

    public CampoDinero() {
        Label signo = new Label("$");
        signo.getStyleClass().add("campo-dinero-signo");
        setLeft(signo);
        getStyleClass().add("campo-dinero");
        Campos.soloNumeros(this, 2);
    }

    /** Tamaño normal de formulario (la caja usa el grande para cobrar). */
    public CampoDinero normal() {
        getStyleClass().add("campo-dinero-normal");
        return this;
    }

    public Optional<Long> centavos() {
        return Dinero.parsear(getText());
    }

    public long centavosOCero() {
        return centavos().orElse(0L);
    }

    public void setCentavos(long centavos) {
        setText(Dinero.aPesos(centavos).stripTrailingZeros().toPlainString());
    }
}
