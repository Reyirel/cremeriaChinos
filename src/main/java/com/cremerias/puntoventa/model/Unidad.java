package com.cremerias.puntoventa.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public enum Unidad {

    PZA("pza", 0),
    KG("kg", 3);

    private final String abreviatura;
    private final int decimales;

    Unidad(String abreviatura, int decimales) {
        this.abreviatura = abreviatura;
        this.decimales = decimales;
    }

    public String abreviatura() {
        return abreviatura;
    }

    public int decimales() {
        return decimales;
    }

    public boolean esGranel() {
        return this == KG;
    }

    public BigDecimal normalizar(BigDecimal cantidad) {
        return cantidad.setScale(decimales, RoundingMode.HALF_UP);
    }

    /** "3", "0.250 kg" */
    public String formatear(BigDecimal cantidad) {
        return this == KG
                ? normalizar(cantidad).toPlainString() + " kg"
                : normalizar(cantidad).toPlainString();
    }
}
