package com.cremerias.puntoventa.ui.caja;

import com.cremerias.puntoventa.model.VentaResumen;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Ventas del turno con opción de reimprimir o cancelar. */
final class DialogoVentasTurno {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private DialogoVentasTurno() {
    }

    /** @return acción para refrescar el listado después de cancelar. */
    static Runnable mostrar(Dialogos dialogos, Supplier<List<VentaResumen>> cargar, Consumer<VentaResumen> verTicket,
                            Consumer<VentaResumen> cancelar) {
        Dialogo d = new Dialogo("Ventas del turno", "Doble clic para ver el ticket", "mdi2h-history",
                Dialogo.Tono.INFO);
        d.setPrefWidth(820);

        TableView<VentaResumen> tabla = new TableView<>();
        tabla.getStyleClass().addAll("tabla-ventas", "striped");
        tabla.setPrefHeight(420);
        tabla.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tabla.setPlaceholder(new Label("Aún no hay ventas en este turno."));

        TableColumn<VentaResumen, String> folio = columna("Folio", v -> v.folio());
        folio.setPrefWidth(220);
        TableColumn<VentaResumen, String> hora = columna("Hora", v -> HORA.format(v.fecha().atZone(ZoneId.systemDefault())));
        hora.setPrefWidth(70);
        TableColumn<VentaResumen, String> articulos = columna("Artículos",
                v -> v.articulos().stripTrailingZeros().toPlainString());
        articulos.setPrefWidth(90);
        TableColumn<VentaResumen, String> metodo = columna("Pago", VentaResumen::metodos);
        metodo.setPrefWidth(160);
        TableColumn<VentaResumen, String> total = columna("Total", v -> Dinero.formatear(v.totalCentavos()));
        total.setPrefWidth(110);
        total.getStyleClass().add("columna-derecha");
        TableColumn<VentaResumen, String> estado = columna("Estado", v -> v.cancelada() ? "Cancelada" : "Pagada");
        estado.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean vacio) {
                super.updateItem(item, vacio);
                if (vacio || item == null) {
                    setGraphic(null);
                    return;
                }
                Label chip = new Label(item);
                chip.getStyleClass().addAll("chip-estado-venta", "Cancelada".equals(item) ? "cancelada" : "pagada");
                setGraphic(chip);
            }
        });
        tabla.getColumns().addAll(List.of(folio, hora, articulos, metodo, total, estado));

        Runnable refrescar = () -> tabla.setItems(FXCollections.observableArrayList(cargar.get()));
        refrescar.run();
        tabla.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && tabla.getSelectionModel().getSelectedItem() != null) {
                verTicket.accept(tabla.getSelectionModel().getSelectedItem());
            }
        });
        d.setContenido(tabla);

        Button cancelarVenta = d.agregarBoton("Cancelar venta", "mdi2c-close-circle-outline",
                () -> cancelar.accept(tabla.getSelectionModel().getSelectedItem()), "danger", "flat");
        d.separarBotones();
        Button ver = d.agregarBoton("Ver ticket", "mdi2r-receipt-text-outline",
                () -> verTicket.accept(tabla.getSelectionModel().getSelectedItem()), "");
        d.agregarBoton("Cerrar", null, d::cerrar, "accent");
        cancelarVenta.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull()
                .or(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
                    VentaResumen v = tabla.getSelectionModel().getSelectedItem();
                    return v != null && v.cancelada();
                }, tabla.getSelectionModel().selectedItemProperty())));
        ver.disableProperty().bind(tabla.getSelectionModel().selectedItemProperty().isNull());
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(() -> {
            if (tabla.getSelectionModel().getSelectedItem() != null) {
                verTicket.accept(tabla.getSelectionModel().getSelectedItem());
            }
        });
        d.setFocoInicial(tabla);
        dialogos.mostrar(d);
        if (!tabla.getItems().isEmpty()) {
            tabla.getSelectionModel().selectFirst();
        }
        return refrescar;
    }

    private static TableColumn<VentaResumen, String> columna(String titulo,
                                                             java.util.function.Function<VentaResumen, String> valor) {
        TableColumn<VentaResumen, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new SimpleStringProperty(valor.apply(f.getValue())));
        c.setSortable(false);
        return c;
    }
}
