package com.cremerias.puntoventa.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;

import java.math.BigDecimal;

/**
 * Un renglón del carrito de venta. El importe lo calcula el carrito con precios por lote y, si la
 * cantidad llega a una escala, con el precio de mayoreo.
 */
public class LineaVenta {

    private final Producto producto;
    private final ObjectProperty<BigDecimal> cantidad = new SimpleObjectProperty<>();
    private final ReadOnlyLongWrapper importe = new ReadOnlyLongWrapper();
    private final ReadOnlyLongWrapper importeLista = new ReadOnlyLongWrapper();
    private final ReadOnlyObjectWrapper<Escala.Nivel> nivel = new ReadOnlyObjectWrapper<>(Escala.Nivel.MENUDEO);

    public LineaVenta(Producto producto, BigDecimal cantidad) {
        this.producto = producto;
        this.cantidad.set(producto.unidad().normalizar(cantidad));
        this.importe.set(producto.importe(this.cantidad.get()));
        this.importeLista.set(importe.get());
    }

    public Producto producto() {
        return producto;
    }

    public BigDecimal getCantidad() {
        return cantidad.get();
    }

    public void setCantidad(BigDecimal nueva) {
        cantidad.set(producto.unidad().normalizar(nueva));
    }

    public ObjectProperty<BigDecimal> cantidadProperty() {
        return cantidad;
    }

    public long getImporte() {
        return importe.get();
    }

    public ReadOnlyLongProperty importeProperty() {
        return importe.getReadOnlyProperty();
    }

    /** Importe a precio de menudeo (lote), antes de la escala de mayoreo. */
    public long getImporteLista() {
        return importeLista.get();
    }

    public Escala.Nivel getNivel() {
        return nivel.get();
    }

    public ReadOnlyObjectProperty<Escala.Nivel> nivelProperty() {
        return nivel.getReadOnlyProperty();
    }

    /** Precio por unidad que se cobra (con mayoreo si aplica), redondeado al centavo. */
    public long precioAplicado() {
        BigDecimal c = cantidad.get();
        return c.signum() == 0 ? 0 : BigDecimal.valueOf(importe.get())
                .divide(c, 0, java.math.RoundingMode.HALF_UP).longValue();
    }

    void setImporte(long valor) {
        importe.set(valor);
    }

    void aplicar(Escalas.Aplicacion aplicacion) {
        importeLista.set(aplicacion.importeLista());
        nivel.set(aplicacion.nivel());
        importe.set(aplicacion.importe());
    }
}
