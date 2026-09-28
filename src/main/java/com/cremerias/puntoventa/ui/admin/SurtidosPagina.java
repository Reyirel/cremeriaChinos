package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Surtidos enviados a las sucursales. */
public class SurtidosPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);

    private TableView<SurtidoService.Resumen> tabla;
    private DatePicker desde;
    private DatePicker hasta;
    private ComboBox<Sucursal> sucursal;
    private HBox indicadores;

    public SurtidosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        var nuevo = Ui.principal("Nuevo surtido", "mdi2t-truck-delivery-outline");
        nuevo.setOnAction(e -> DialogoSurtido.mostrar(a, null, null, null, null, this::alMostrar));
        VBox pagina = Ui.pagina("Surtir sucursales",
                "Envíos del almacén a las sucursales, en efectivo o a crédito.", nuevo);
        desde = new DatePicker(LocalDate.now().minusDays(30));
        hasta = new DatePicker(LocalDate.now());
        sucursal = new ComboBox<>();
        for (DatePicker p : List.of(desde, hasta)) {
            p.setEditable(false);
            p.valueProperty().addListener((o, x, y) -> cargar());
        }
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        HBox filtros = new HBox(10, Ui.campo("Desde", desde), Ui.campo("Hasta", hasta), Ui.campo("Sucursal", sucursal));
        filtros.setAlignment(Pos.BOTTOM_LEFT);
        indicadores = new HBox(14);
        tabla = Ui.tabla("No hay surtidos en estas fechas.");
        tabla.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, SurtidoService.Resumen::folio),
                Ui.texto("Fecha", 140, s -> FECHA.format(s.fecha().atZone(ZoneId.systemDefault()))),
                Ui.texto("Sucursal", 170, SurtidoService.Resumen::sucursal),
                Ui.nodo("Pago", 110, s -> s.formaPago() == SurtidoService.FormaPago.CREDITO
                        ? Ui.chip("Crédito", "advertencia") : Ui.chip("Efectivo", "exito")),
                Ui.texto("Productos", 90, s -> String.valueOf(s.productos())),
                Ui.dinero("Enviado (costo)", 140, SurtidoService.Resumen::costoCentavos),
                Ui.dinero("Valor de venta", 140, SurtidoService.Resumen::ventaCentavos),
                Ui.texto("Orden", 130, s -> s.ordenFolio() == null ? "" : s.ordenFolio()),
                Ui.texto("Surtió", 150, SurtidoService.Resumen::usuario),
                Ui.nodo("", 70, s -> Ui.acciones(Ui.accion("mdi2p-printer-outline", "Ver ticket", () -> ticket(s))))));
        tabla.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tabla.getSelectionModel().getSelectedItem() != null) {
                ticket(tabla.getSelectionModel().getSelectedItem());
            }
        });
        pagina.getChildren().addAll(filtros, indicadores, tabla);
        return pagina;
    }

    @Override
    public void alMostrar() {
        Sucursal actual = sucursal.getValue();
        List<Sucursal> opciones = new ArrayList<>();
        opciones.add(TODAS);
        opciones.addAll(a.admin().sucursales().listar());
        sucursal.setItems(FXCollections.observableArrayList(opciones));
        sucursal.setValue(actual == null ? TODAS : opciones.stream().filter(s -> s.nombre().equals(actual.nombre()))
                .findFirst().orElse(TODAS));
        cargar();
    }

    private void cargar() {
        if (desde.getValue() == null || hasta.getValue() == null || sucursal.getValue() == null) {
            return;
        }
        List<SurtidoService.Resumen> lista = a.admin().surtidos().listar(desde.getValue(), hasta.getValue(),
                sucursal.getValue().id());
        tabla.setItems(FXCollections.observableArrayList(lista));
        long costo = lista.stream().mapToLong(SurtidoService.Resumen::costoCentavos).sum();
        long credito = lista.stream().filter(s -> s.formaPago() == SurtidoService.FormaPago.CREDITO)
                .mapToLong(SurtidoService.Resumen::costoCentavos).sum();
        indicadores.getChildren().setAll(
                Ui.indicador("Surtidos", String.valueOf(lista.size()), "mdi2t-truck-delivery-outline"),
                Ui.indicador("Total enviado (costo)", Dinero.formatear(costo), "mdi2c-cash-multiple"),
                Ui.indicador("Enviado a crédito", Dinero.formatear(credito), "mdi2c-credit-card-clock-outline"));
    }

    private void ticket(SurtidoService.Resumen s) {
        SurtidoService.Detalle detalle = a.admin().surtidos().detalle(s.id());
        DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Surtido " + s.folio(),
                s.sucursal() + " · " + Dinero.formatear(s.costoCentavos()), TicketTexto.surtido(detalle), null);
    }
}
