package com.cremerias.puntoventa.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.SimpleObjectProperty;

import java.math.BigDecimal;

/** Un renglón del carrito de venta. El importe lo calcula el carrito con precios por lote. */
public class LineaVenta {

    private final Producto producto;
    private final ObjectProperty<BigDecimal> cantidad = new SimpleObjectProperty<>();
    private final ReadOnlyLongWrapper importe = new ReadOnlyLongWrapper();

    public LineaVenta(Producto producto, BigDecimal cantidad) {
        this.producto = producto;
        this.cantidad.set(producto.unidad().normalizar(cantidad));
        this.importe.set(producto.importe(this.cantidad.get()));
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

    void setImporte(long valor) {
        importe.set(valor);
    }
}
