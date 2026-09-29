package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.service.AccesoService;
import com.cremerias.puntoventa.service.SinVentaService;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Lo que requiere atención: órdenes por aprobar, accesos bloqueados y productos sin venta. */
public class AvisosPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private HBox indicadores;
    private TableView<OrdenRepository.Orden> ordenes;
    private TableView<AccesoService.Bloqueo> bloqueos;
    private TableView<SinVentaService.SinVenta> sinVenta;
    private ComboBox<Sucursal> sucursalAviso;
    private TextField dias;

    public AvisosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Avisos", "Lo que requiere tu atención. Se revisa automáticamente cada 30 segundos.");
        indicadores = new HBox(14);

        ordenes = tablaCorta("No hay órdenes por aprobar.");
        ordenes.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, OrdenRepository.Orden::folio),
                Ui.texto("Sucursal", 160, OrdenRepository.Orden::sucursal),
                Ui.texto("Producto", 220, OrdenRepository.Orden::producto),
                Ui.texto("Se pide", 130, o -> Cantidades.formatear(com.cremerias.puntoventa.model.Unidad.valueOf(o.unidad()),
                        o.cantidadSugerida())),
                Ui.texto("Generada", 140, o -> FECHA.format(o.creadoEn().atZone(ZoneId.systemDefault())))));
        Button verOrdenes = Ui.secundario("Ir a órdenes", "mdi2c-clipboard-list-outline");
        verOrdenes.setOnAction(e -> a.irA().accept("ordenes"));

        bloqueos = tablaCorta("Ningún empleado tiene el acceso bloqueado.");
        bloqueos.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, AccesoService.Bloqueo::folio),
                Ui.texto("Empleado", 190, AccesoService.Bloqueo::usuario),
                Ui.texto("Sucursal", 150, AccesoService.Bloqueo::sucursal),
                Ui.nodo("Motivo", 300, b -> Ui.dosLineas(b.motivo().descripcion(), b.detalle())),
                Ui.texto("Fecha", 140, b -> FECHA.format(b.fecha().atZone(ZoneId.systemDefault()))),
                Ui.nodo("", 150, b -> {
                    Button dar = Ui.boton("Dar acceso", "mdi2a-account-check-outline", "accent", "small");
                    dar.setOnAction(e -> darAcceso(b));
                    return dar;
                })));

        sucursalAviso = new ComboBox<>();
        sucursalAviso.setPromptText("Sucursal");
        sucursalAviso.valueProperty().addListener((o, x, y) -> cargarDias());
        dias = new TextField();
        Campos.soloNumeros(dias, 0);
        dias.setPrefColumnCount(4);
        Button guardarDias = Ui.secundario("Guardar", "mdi2c-check");
        guardarDias.setOnAction(e -> {
            Sucursal s = sucursalAviso.getValue();
            if (s == null) {
                a.avisos().error("Elige la sucursal.");
                return;
            }
            int valor = dias.getText().isBlank() ? 0 : Integer.parseInt(dias.getText());
            a.ejecutar("Configuración guardada", () -> a.admin().alertas().guardarDiasSinVenta(s, valor, a.usuario()));
            alMostrar();
        });
        HBox configuracion = new HBox(8, new Label("Avisar en"), sucursalAviso,
                new Label("si un producto con existencia no se vende en"), dias, new Label("días"), guardarDias);
        configuracion.setAlignment(Pos.CENTER_LEFT);
        Label ayudaAviso = Ui.texto("Los productos con aviso propio (se define al crear o editar el producto) usan su "
                + "plazo y se avisan solo a quien se eligió: administrador, supervisor y/o caja.", "texto-ayuda");
        sinVenta = tablaCorta("Todos los productos con existencia se han vendido recientemente.");
        sinVenta.getColumns().setAll(List.of(
                Ui.texto("Sucursal", 160, SinVentaService.SinVenta::sucursal),
                Ui.texto("Producto", 240, SinVentaService.SinVenta::producto),
                Ui.texto("Existencia", 140, s -> Cantidades.formatear(s.unidad(), s.existencia())),
                Ui.texto("Última venta", 150, s -> s.ultimaVenta() == null ? "Nunca"
                        : FECHA.format(s.ultimaVenta().atZone(ZoneId.systemDefault()))),
                Ui.texto("Plazo", 150, s -> s.plazo() + (s.avisoDelProducto() ? " (producto)" : " (sucursal)")),
                Ui.nodo("Sin vender", 130, s -> Ui.chip(AvisoSinVenta.formatear(s.sinVender()), "advertencia"))));

        pagina.getChildren().addAll(indicadores,
                Ui.tarjeta("Órdenes de reabastecimiento por aprobar", ordenes, verOrdenes),
                Ui.tarjeta("Accesos bloqueados por horario", Ui.texto("El empleado no puede entrar hasta que le des acceso "
                        + "(también puede autorizarlo ahí mismo un supervisor de su sucursal).", "texto-ayuda"), bloqueos),
                Ui.tarjeta("Productos sin venta", configuracion, ayudaAviso, sinVenta));
        ScrollPane scroll = new ScrollPane(pagina);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("cortes-scroll");
        return scroll;
    }

    private static <T> TableView<T> tablaCorta(String vacio) {
        TableView<T> t = Ui.tabla(vacio);
        t.setPrefHeight(230);
        t.setMinHeight(150);
        VBox.setVgrow(t, Priority.NEVER);
        return t;
    }

    @Override
    public void alMostrar() {
        List<OrdenRepository.Orden> o = a.admin().ordenes().pendientes();
        List<AccesoService.Bloqueo> b = a.ctx().auth().accesos().pendientes();
        List<SinVentaService.SinVenta> s = a.admin().alertas().productosSinVenta();
        ordenes.setItems(FXCollections.observableArrayList(o));
        bloqueos.setItems(FXCollections.observableArrayList(b));
        sinVenta.setItems(FXCollections.observableArrayList(s));

        Sucursal actual = sucursalAviso.getValue();
        List<Sucursal> activas = a.admin().sucursales().activas();
        sucursalAviso.setItems(FXCollections.observableArrayList(activas));
        if (actual != null) {
            activas.stream().filter(sc -> sc.id().equals(actual.id())).findFirst().ifPresent(sucursalAviso::setValue);
        } else if (!activas.isEmpty()) {
            sucursalAviso.setValue(activas.getFirst());
        }
        cargarDias();

        indicadores.getChildren().setAll(
                Ui.indicador("Órdenes por aprobar", String.valueOf(o.size()), "mdi2c-clipboard-list-outline"),
                Ui.indicador("Accesos bloqueados", String.valueOf(b.size()), "mdi2a-account-lock-outline"),
                Ui.indicador("Productos sin venta", String.valueOf(s.size()), "mdi2t-timer-sand"));
    }

    private void cargarDias() {
        Sucursal s = sucursalAviso.getValue();
        dias.setText(s == null ? "" : String.valueOf(a.admin().alertas().diasSinVenta(s.id())));
    }

    private void darAcceso(AccesoService.Bloqueo b) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Dar acceso a " + b.usuario() + "?",
                b.motivo().descripcion() + " (" + b.detalle() + "). Podrá iniciar sesión una vez.",
                "Dar acceso", false, () -> {
                    a.ejecutar("Acceso autorizado", () -> a.ctx().auth().accesos().autorizar(b.id(), a.usuario()));
                    alMostrar();
                });
    }
}
