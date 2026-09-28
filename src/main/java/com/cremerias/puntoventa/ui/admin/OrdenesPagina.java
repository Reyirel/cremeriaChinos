package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.ui.componentes.DialogoTexto;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Órdenes de reabastecimiento generadas por las sucursales al llegar a su mínimo. */
public class OrdenesPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TableView<OrdenRepository.Orden> pendientes;
    private TableView<OrdenRepository.Orden> atendidas;
    private ToggleButton verPendientes;
    private VBox pagina;

    public OrdenesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        pagina = Ui.pagina("Órdenes de reabastecimiento",
                "Se generan solas cuando una sucursal llega a su mínimo. Al aprobar se surte y se imprime el ticket.");
        ToggleGroup vista = new ToggleGroup();
        verPendientes = new ToggleButton("Por aprobar");
        ToggleButton verAtendidas = new ToggleButton("Atendidas");
        verPendientes.getStyleClass().add("left-pill");
        verAtendidas.getStyleClass().add("right-pill");
        verPendientes.setToggleGroup(vista);
        verAtendidas.setToggleGroup(vista);
        verPendientes.setSelected(true);

        pendientes = Ui.tabla("No hay órdenes por aprobar.");
        pendientes.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, OrdenRepository.Orden::folio),
                Ui.texto("Generada", 140, o -> FECHA.format(o.creadoEn().atZone(ZoneId.systemDefault()))),
                Ui.texto("Sucursal", 160, OrdenRepository.Orden::sucursal),
                Ui.texto("Producto", 220, OrdenRepository.Orden::producto),
                Ui.texto("Existencia", 120, o -> cantidad(o, o.existencia())),
                Ui.texto("Mínimo", 110, o -> cantidad(o, o.minimo())),
                Ui.texto("Máximo", 110, o -> cantidad(o, o.maximo())),
                Ui.nodo("Se pide", 130, o -> Ui.chip(cantidad(o, o.cantidadSugerida()), "acento")),
                Ui.nodo("", 220, o -> {
                    var aprobar = Ui.boton("Aprobar", "mdi2c-check-bold", "accent", "small");
                    aprobar.setOnAction(e -> aprobar(o));
                    var rechazar = Ui.boton("Rechazar", "mdi2c-close-thick", "flat", "danger", "small");
                    rechazar.setOnAction(e -> rechazar(o));
                    return new HBox(6, aprobar, rechazar);
                })));

        atendidas = Ui.tabla("Aún no se ha atendido ninguna orden.");
        atendidas.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, OrdenRepository.Orden::folio),
                Ui.texto("Generada", 140, o -> FECHA.format(o.creadoEn().atZone(ZoneId.systemDefault()))),
                Ui.texto("Sucursal", 150, OrdenRepository.Orden::sucursal),
                Ui.texto("Producto", 200, OrdenRepository.Orden::producto),
                Ui.texto("Pedía", 120, o -> cantidad(o, o.cantidadSugerida())),
                Ui.nodo("Estado", 120, o -> "APROBADA".equals(o.estado()) ? Ui.chip("Aprobada", "exito")
                        : Ui.chip("Rechazada", "peligro")),
                Ui.texto("Atendió", 150, OrdenRepository.Orden::resueltoPor),
                Ui.texto("Atendida", 140, o -> o.resueltoEn() == null ? ""
                        : FECHA.format(o.resueltoEn().atZone(ZoneId.systemDefault()))),
                Ui.texto("Motivo", 200, o -> o.motivoRechazo() == null ? "" : o.motivoRechazo())));

        vista.selectedToggleProperty().addListener((o, x, y) -> {
            if (y == null) {
                x.setSelected(true);
                return;
            }
            mostrarTabla();
        });
        pagina.getChildren().addAll(new HBox(verPendientes, verAtendidas), pendientes);
        return pagina;
    }

    private void mostrarTabla() {
        pagina.getChildren().set(2, verPendientes.isSelected() ? pendientes : atendidas);
        alMostrar();
    }

    @Override
    public void alMostrar() {
        if (verPendientes.isSelected()) {
            pendientes.setItems(FXCollections.observableArrayList(a.admin().ordenes().pendientes()));
        } else {
            atendidas.setItems(FXCollections.observableArrayList(a.admin().ordenes().atendidas()));
        }
    }

    private static String cantidad(OrdenRepository.Orden o, java.math.BigDecimal valor) {
        return Cantidades.formatear(Unidad.valueOf(o.unidad()), valor);
    }

    private void aprobar(OrdenRepository.Orden o) {
        ProductoCatalogo producto = a.admin().productos().listar().stream().filter(p -> p.id().equals(o.productoId()))
                .findFirst().orElse(null);
        Sucursal sucursal = a.admin().sucursales().listar().stream().filter(s -> s.id().equals(o.sucursalId()))
                .findFirst().orElse(null);
        if (producto == null || sucursal == null || !producto.activo() || !sucursal.activo()) {
            a.avisos().error("El producto o la sucursal ya no están activos; rechaza la orden.");
            return;
        }
        DialogoSurtido.mostrar(a, sucursal, producto, o.cantidadSugerida(), o.id(), () -> {
            alMostrar();
            a.refrescarContadores().run();
        });
    }

    private void rechazar(OrdenRepository.Orden o) {
        DialogoTexto.mostrar(a.dialogos(), "Rechazar orden " + o.folio(), o.sucursal() + " · " + o.producto(),
                "mdi2c-close-circle-outline", "Motivo del rechazo", "Ej. No hay existencia en el almacén", true,
                "Rechazar", motivo -> {
                    a.ejecutar("Orden rechazada", () -> a.admin().ordenes().rechazar(o, motivo, a.usuario()));
                    alMostrar();
                });
    }
}
