package com.cremerias.puntoventa.ui.caja;

import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.service.SinVentaService.SinVenta;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/** Productos que llevan su plazo sin venderse en la sucursal: para ofrecerlos a los clientes. */
final class DialogoOfertar {

    private DialogoOfertar() {
    }

    static void mostrar(Dialogos dialogos, List<SinVenta> productos, Runnable alCerrar) {
        Dialogo d = new Dialogo("Productos para ofertar", productos.size() == 1
                ? "1 producto lleva tiempo sin venderse en esta sucursal"
                : productos.size() + " productos llevan tiempo sin venderse en esta sucursal",
                "mdi2t-tag-outline", Dialogo.Tono.ADVERTENCIA);
        d.setPrefWidth(620);

        ListView<SinVenta> lista = new ListView<>(FXCollections.observableArrayList(productos));
        lista.getStyleClass().add("lista-espera");
        lista.setPrefHeight(Math.min(380, productos.size() * 72 + 4));
        lista.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(SinVenta s, boolean vacio) {
                super.updateItem(s, vacio);
                if (vacio || s == null) {
                    setGraphic(null);
                    return;
                }
                StackPane icono = new StackPane(new FontIcon("mdi2t-tag-outline"));
                icono.getStyleClass().add("aviso-ofertar-icono");
                Label nombre = new Label(s.producto());
                nombre.getStyleClass().add("texto-negrita");
                Label detalle = new Label("Hay " + Cantidades.formatear(s.unidad(), s.existencia())
                        + " · plazo " + s.plazo() + (s.ultimaVenta() == null ? " · nunca vendido aquí" : ""));
                detalle.getStyleClass().add("texto-secundario");
                VBox textos = new VBox(2, nombre, detalle);
                textos.setMinWidth(0);
                HBox.setHgrow(textos, Priority.ALWAYS);
                Label tiempo = new Label(AvisoSinVenta.formatear(s.sinVender()) + " sin venta");
                tiempo.getStyleClass().addAll("chip", "chip-advertencia");
                tiempo.setMinWidth(Region.USE_PREF_SIZE);
                HBox fila = new HBox(12, icono, textos, tiempo);
                fila.setAlignment(Pos.CENTER_LEFT);
                // Que el renglón quepa en la lista: el texto se recorta antes que el tiempo.
                fila.setMinWidth(0);
                fila.prefWidthProperty().bind(lista.widthProperty().subtract(40));
                setGraphic(fila);
            }
        });
        d.setContenido(lista, textoAyuda("El aviso se quita solo en cuanto se vende el producto."));
        d.agregarBoton("Entendido", "mdi2c-check", () -> {
            d.cerrar();
            alCerrar.run();
        }, "accent");
        d.setAlCancelar(() -> {
            d.cerrar();
            alCerrar.run();
        });
        d.setAlConfirmar(() -> {
            d.cerrar();
            alCerrar.run();
        });
        dialogos.mostrar(d);
    }

    private static Label textoAyuda(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("texto-secundario");
        l.setWrapText(true);
        return l;
    }
}
