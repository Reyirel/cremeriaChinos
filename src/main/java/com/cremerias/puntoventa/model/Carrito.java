package com.cremerias.puntoventa.model;

import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.math.BigDecimal;
import java.util.List;

/** La venta en curso: renglones, total y número de artículos. */
public class Carrito {

    private final ObservableList<LineaVenta> lineas = FXCollections.observableArrayList(
            linea -> new javafx.beans.Observable[]{linea.cantidadProperty()});
    private final ReadOnlyLongWrapper total = new ReadOnlyLongWrapper(0);
    private final ReadOnlyObjectWrapper<BigDecimal> articulos = new ReadOnlyObjectWrapper<>(BigDecimal.ZERO);

    public Carrito() {
        lineas.addListener((javafx.collections.ListChangeListener<LineaVenta>) c -> recalcular());
    }

    public ObservableList<LineaVenta> lineas() {
        return lineas;
    }

    public ReadOnlyLongProperty totalProperty() {
        return total.getReadOnlyProperty();
    }

    public long total() {
        return total.get();
    }

    /** Piezas + 1 por cada renglón a granel. */
    public ReadOnlyObjectProperty<BigDecimal> articulosProperty() {
        return articulos.getReadOnlyProperty();
    }

    public boolean vacio() {
        return lineas.isEmpty();
    }

    /**
     * Agrega un producto. Si ya estaba y se vende por pieza se suma la cantidad al mismo renglón;
     * los productos a granel siempre van en renglón nuevo (cada pesada es distinta).
     */
    public LineaVenta agregar(Producto producto, BigDecimal cantidad) {
        if (!producto.unidad().esGranel()) {
            for (LineaVenta linea : lineas) {
                if (linea.producto().id().equals(producto.id())) {
                    linea.setCantidad(linea.getCantidad().add(cantidad));
                    return linea;
                }
            }
        }
        LineaVenta nueva = new LineaVenta(producto, cantidad);
        lineas.add(nueva);
        return nueva;
    }

    public void cambiarCantidad(LineaVenta linea, BigDecimal cantidad) {
        if (cantidad.signum() <= 0) {
            lineas.remove(linea);
        } else {
            linea.setCantidad(cantidad);
        }
    }

    public void quitar(LineaVenta linea) {
        lineas.remove(linea);
    }

    public void vaciar() {
        lineas.clear();
    }

    public void reemplazar(List<LineaVenta> nuevas) {
        lineas.setAll(nuevas);
    }

    /** Recalcula importes: los renglones consumen los lotes en orden, como se guardará la venta. */
    private void recalcular() {
        long suma = 0;
        BigDecimal piezas = BigDecimal.ZERO;
        java.util.Map<String, BigDecimal> consumido = new java.util.HashMap<>();
        for (LineaVenta linea : lineas) {
            linea.setImporte(linea.producto().cotizar(consumido, linea.getCantidad()).totalCentavos());
            suma += linea.getImporte();
            piezas = piezas.add(linea.producto().unidad().esGranel() ? BigDecimal.ONE : linea.getCantidad());
        }
        total.set(suma);
        articulos.set(piezas);
    }
}
