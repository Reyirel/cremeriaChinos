package com.cremerias.puntoventa.ui.caja;

import atlantafx.base.controls.Spacer;
import com.cremerias.puntoventa.model.VentaEspera;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** Ventas puestas en espera para retomarlas. */
final class DialogoVentasEspera {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private DialogoVentasEspera() {
    }

    static void mostrar(Dialogos dialogos, List<VentaEspera> ventas, Consumer<VentaEspera> alRecuperar,
                        Consumer<VentaEspera> alDescartar) {
        Dialogo d = new Dialogo("Ventas en espera", ventas.size() == 1 ? "1 venta pausada" : ventas.size() + " ventas pausadas",
                "mdi2p-pause-circle-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);

        ListView<VentaEspera> lista = new ListView<>(FXCollections.observableArrayList(ventas));
        lista.getStyleClass().add("lista-espera");
        lista.setPrefHeight(Math.min(360, ventas.size() * 72 + 4));
        lista.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(VentaEspera v, boolean vacio) {
                super.updateItem(v, vacio);
                if (vacio || v == null) {
                    setGraphic(null);
                    return;
                }
                StackPane icono = new StackPane(new FontIcon("mdi2c-cart-outline"));
                icono.getStyleClass().add("espera-icono");
                Label nota = new Label(v.nota() == null || v.nota().isBlank() ? "Venta en espera" : v.nota());
                nota.getStyleClass().add("texto-negrita");
                Label detalle = new Label("Pausada a las " + HORA.format(v.creadoEn().atZone(ZoneId.systemDefault()))
                        + " · " + v.renglones() + (v.renglones() == 1 ? " producto" : " productos"));
                detalle.getStyleClass().add("texto-secundario");
                Label total = new Label(Dinero.formatear(v.totalCentavos()));
                total.getStyleClass().add("espera-total");
                HBox fila = new HBox(12, icono, new VBox(2, nota, detalle), new Spacer(), total);
                fila.setAlignment(Pos.CENTER_LEFT);
                setGraphic(fila);
            }
        });
        lista.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && lista.getSelectionModel().getSelectedItem() != null) {
                d.cerrar();
                alRecuperar.accept(lista.getSelectionModel().getSelectedItem());
            }
        });
        d.setContenido(lista);

        Button descartar = d.agregarBoton("Descartar", "mdi2t-trash-can-outline", () -> {
            VentaEspera v = lista.getSelectionModel().getSelectedItem();
            d.cerrar();
            alDescartar.accept(v);
        }, "danger", "flat");
        d.separarBotones();
        d.agregarBoton("Cerrar", null, d::cerrar, "flat");
        Button recuperar = d.agregarBoton("Recuperar", "mdi2p-playlist-play", () -> {
            VentaEspera v = lista.getSelectionModel().getSelectedItem();
            d.cerrar();
            alRecuperar.accept(v);
        }, "accent");
        descartar.disableProperty().bind(lista.getSelectionModel().selectedItemProperty().isNull());
        recuperar.disableProperty().bind(lista.getSelectionModel().selectedItemProperty().isNull());
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(recuperar::fire);
        d.setFocoInicial(lista);
        dialogos.mostrar(d);
        lista.getSelectionModel().selectFirst();
    }
}
