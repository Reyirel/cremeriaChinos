package com.cremerias.puntoventa.model;

/** Categorías de gasto de una sucursal (renta, servicios, nómina patronal, etc.). */
public enum CategoriaGasto {

    RENTA("Renta"),
    ELECTRICIDAD("Electricidad"),
    INTERNET("Internet"),
    SEGURO_LOCAL("Seguro del local"),
    IMSS_INFONAVIT("IMSS/Infonavit"),
    IMPUESTO_NOMINA("Impuesto sobre nómina"),
    TRANSPORTE("Transporte"),
    PUBLICIDAD("Publicidad"),
    INDIRECTOS("Gastos indirectos");

    private final String nombre;

    CategoriaGasto(String nombre) {
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
