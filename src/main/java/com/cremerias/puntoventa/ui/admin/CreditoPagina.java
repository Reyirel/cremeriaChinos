package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.service.admin.CreditoService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Saldo de crédito de cada sucursal con el almacén: cargos por surtidos a crédito y abonos. */
public class CreditoPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TableView<CreditoService.Saldo> saldos;
    private TableView<CreditoService.Movimiento> movimientos;
    private Label tituloMovimientos;
    private Button abonar;
    private HBox indicadores;

    public CreditoPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Crédito de sucursales",
                "Los surtidos a crédito se cargan al saldo de la sucursal; los abonos lo reducen.");
        indicadores = new HBox(14);
        saldos = Ui.tabla("No hay sucursales.");
        saldos.setPrefHeight(240);
        VBox.setVgrow(saldos, Priority.NEVER);
        saldos.getColumns().setAll(List.of(
                Ui.texto("Sucursal", 220, s -> s.sucursal().nombre()),
                Ui.dinero("Cargos", 140, CreditoService.Saldo::cargos),
                Ui.dinero("Abonos", 140, CreditoService.Saldo::abonos),
                Ui.nodo("Saldo", 160, s -> s.saldo() > 0 ? Ui.chip(Dinero.formatear(s.saldo()), "advertencia")
                        : Ui.chip("Sin adeudo", "exito"))));
        saldos.getSelectionModel().selectedItemProperty().addListener((o, x, s) -> cargarMovimientos(s));

        tituloMovimientos = Ui.texto("Movimientos", "seccion-titulo");
        abonar = Ui.principal("Registrar abono", "mdi2c-cash-plus");
        abonar.setOnAction(e -> abonar(saldos.getSelectionModel().getSelectedItem()));
        abonar.setDisable(true);
        HBox encabezado = new HBox(10, tituloMovimientos, new atlantafx.base.controls.Spacer(), abonar);
        encabezado.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        movimientos = Ui.tabla("Elige una sucursal para ver sus movimientos.");
        movimientos.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, CreditoService.Movimiento::folio),
                Ui.texto("Fecha", 140, m -> FECHA.format(m.fecha().atZone(ZoneId.systemDefault()))),
                Ui.nodo("Tipo", 120, m -> "CARGO".equals(m.tipo()) ? Ui.chip("Surtido a crédito", "advertencia")
                        : Ui.chip("Abono", "exito")),
                Ui.dinero("Monto", 140, CreditoService.Movimiento::montoCentavos),
                Ui.texto("Nota", 220, m -> m.nota() == null ? "" : m.nota()),
                Ui.texto("Registró", 160, CreditoService.Movimiento::usuario)));
        pagina.getChildren().addAll(indicadores, saldos, encabezado, movimientos);
        return pagina;
    }

    @Override
    public void alMostrar() {
        String seleccion = saldos.getSelectionModel().getSelectedItem() == null ? null
                : saldos.getSelectionModel().getSelectedItem().sucursal().id();
        List<CreditoService.Saldo> lista = a.admin().credito().saldos(a.admin().sucursales().listar());
        saldos.setItems(FXCollections.observableArrayList(lista));
        // Alto justo para ver todas las sucursales (hasta 5) sin que se recorte la última.
        saldos.setPrefHeight(46 + Math.max(1, Math.min(5, lista.size())) * saldos.getFixedCellSize() + 4);
        saldos.setMinHeight(Region.USE_PREF_SIZE);
        long total = lista.stream().mapToLong(CreditoService.Saldo::saldo).sum();
        long conAdeudo = lista.stream().filter(s -> s.saldo() > 0).count();
        indicadores.getChildren().setAll(
                Ui.indicador("Total por cobrar a sucursales", Dinero.formatear(total), "mdi2c-credit-card-clock-outline"),
                Ui.indicador("Sucursales con adeudo", String.valueOf(conAdeudo), "mdi2s-storefront-outline"));
        lista.stream().filter(s -> s.sucursal().id().equals(seleccion)).findFirst()
                .or(() -> lista.stream().findFirst())
                .ifPresentOrElse(s -> saldos.getSelectionModel().select(s), () -> cargarMovimientos(null));
    }

    private void cargarMovimientos(CreditoService.Saldo s) {
        abonar.setDisable(s == null || s.saldo() <= 0);
        tituloMovimientos.setText(s == null ? "Movimientos" : "Movimientos de " + s.sucursal().nombre());
        movimientos.setPlaceholder(new Label(s == null ? "Elige una sucursal para ver sus movimientos."
                : s.sucursal().nombre() + " no tiene movimientos de crédito."));
        movimientos.setItems(FXCollections.observableArrayList(s == null ? List.of()
                : a.admin().credito().movimientos(s.sucursal().id())));
    }

    private void abonar(CreditoService.Saldo s) {
        if (s == null) {
            return;
        }
        Dialogo d = new Dialogo("Abono de " + s.sucursal().nombre(), "Saldo actual: " + Dinero.formatear(s.saldo()),
                "mdi2c-cash-plus", Dialogo.Tono.EXITO);
        CampoDinero monto = new CampoDinero();
        TextField nota = new TextField();
        nota.setPromptText("Ej. Transferencia, efectivo (opcional)");
        Label nuevo = Ui.texto("", "texto-ayuda");
        monto.textProperty().addListener((o, x, y) -> nuevo.setText("Saldo después del abono: "
                + Dinero.formatear(s.saldo() - monto.centavosOCero())));
        d.setContenido(Ui.campo("Monto del abono", monto), nuevo, Ui.campo("Nota", nota));
        Runnable guardar = () -> {
            try {
                CreditoService.Abono abono = a.admin().credito().abonar(s.sucursal(), monto.centavosOCero(),
                        nota.getText(), a.usuario());
                d.cerrar();
                a.avisos().exito("Abono registrado · Folio " + abono.folio());
                alMostrar();
                DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Abono " + abono.folio(),
                        s.sucursal().nombre() + " · " + Dinero.formatear(abono.montoCentavos()),
                        TicketTexto.lineasAbono(abono), null);
            } catch (RuntimeException e) {
                a.avisos().error(AdminContexto.mensaje(e));
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Registrar abono", "mdi2c-check", guardar, "success");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(monto);
        a.dialogos().mostrar(d);
    }
}
