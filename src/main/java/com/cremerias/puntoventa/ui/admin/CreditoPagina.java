package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.service.admin.CreditoService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Crédito de cada sucursal con el almacén: cargos por surtidos a crédito, abonos y saldo. */
public class CreditoPagina extends Pagina {

    /** Movimiento con el saldo que dejó (se calcula en orden cronológico con los movimientos reales). */
    private record Linea(CreditoService.Movimiento movimiento, long saldoDespues) {
    }

    private final Cargador carga = new Cargador();
    private final Cargador cargaMovimientos = new Cargador();
    private TablaDatos<CreditoService.Saldo> saldos;
    private TablaDatos<Linea> movimientos;
    private ResumenMetricas.Metrica metricaTotal;
    private ResumenMetricas.Metrica metricaConAdeudo;
    private Label titulo;
    private Label subtitulo;
    private Label saldo;
    private Label detalleSaldo;
    private Label sinAdeudo;
    private Button abonar;

    public CreditoPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Crédito";
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Crédito de sucursales",
                "Los surtidos a crédito se cargan al saldo de la sucursal; los abonos lo reducen.");
        ResumenMetricas resumen = new ResumenMetricas();
        metricaTotal = resumen.agregar("Total por cobrar").principal();
        metricaConAdeudo = resumen.agregar("Sucursales con adeudo");

        saldos = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        saldos.setClave(s -> s.sucursal().id());
        saldos.dosLineas("Sucursal", s -> s.sucursal().nombre(), s -> s.sucursal().codigo()).flexible(150);
        saldos.nodo("Saldo", s -> {
            Label monto = new Label(Dinero.formatear(s.saldo()));
            monto.getStyleClass().add("celda-principal");
            VBox v = new VBox(3, monto, s.saldo() > 0 ? Etiqueta.de("Con adeudo", Etiqueta.Tono.AVISO)
                    : Etiqueta.de("Al corriente", Etiqueta.Tono.EXITO));
            v.setAlignment(Pos.CENTER_RIGHT);
            return v;
        }).ancho(130).numero().orden(Comparator.comparingLong(CreditoService.Saldo::saldo));
        saldos.setVacio(new EstadoVacio("mdi2s-storefront-outline", "No hay sucursales.", null).compacto());
        saldos.seleccionadoProperty().addListener((o, x, s) -> mostrar(s));
        VBox panelSaldos = Ui.panelTabla(null, saldos);
        panelSaldos.setPrefWidth(340);
        panelSaldos.setMinWidth(340);
        panelSaldos.setMaxWidth(340);

        // Detalle de la sucursal elegida
        titulo = new Label("Elige una sucursal");
        titulo.getStyleClass().add("detalle-titulo");
        subtitulo = new Label();
        subtitulo.getStyleClass().add("detalle-subtitulo");
        saldo = new Label("—");
        saldo.getStyleClass().add("detalle-cifra");
        detalleSaldo = new Label();
        detalleSaldo.getStyleClass().add("campo-ayuda");
        Label etiquetaSaldo = new Label("Saldo pendiente");
        etiquetaSaldo.getStyleClass().add("dato-etiqueta");
        abonar = Ui.principal("Registrar abono", "mdi2c-cash-plus");
        abonar.setOnAction(e -> abonar(saldos.seleccionado()));
        abonar.setDisable(true);
        sinAdeudo = new Label("Sin adeudo: no hay nada que abonar.");
        sinAdeudo.getStyleClass().add("campo-ayuda");
        sinAdeudo.managedProperty().bind(sinAdeudo.visibleProperty());
        sinAdeudo.setVisible(false);
        VBox accion = new VBox(6, abonar, sinAdeudo);
        accion.setAlignment(Pos.TOP_RIGHT);
        accion.setMinWidth(Region.USE_PREF_SIZE);
        VBox textos = new VBox(2, titulo, subtitulo);
        VBox cifra = new VBox(2, etiquetaSaldo, saldo, detalleSaldo);
        HBox cabecera = new HBox(16, new VBox(14, textos, cifra), Ui.espacio(), accion);
        cabecera.setStyle("-fx-padding: 18 20 16 20;");

        movimientos = new TablaDatos<>(Diseno.Densidad.NORMAL);
        movimientos.setClave(l -> l.movimiento().id());
        movimientos.fecha("Fecha", l -> l.movimiento().fecha(), Formatos.FECHA,
                l -> Formatos.HORA.format(l.movimiento().fecha().atZone(java.time.ZoneId.systemDefault()))).ancho(108);
        movimientos.dosLineas("Movimiento", l -> "CARGO".equals(l.movimiento().tipo()) ? "Surtido a crédito" : "Abono",
                l -> l.movimiento().folio()).ancho(164);
        movimientos.formato("Monto", l -> "CARGO".equals(l.movimiento().tipo()) ? l.movimiento().montoCentavos()
                        : -l.movimiento().montoCentavos(),
                (l, v) -> (v < 0 ? "−" : "+") + Dinero.formatear(Math.abs(v))).numero().ancho(116)
                .ayuda("Los cargos suben el saldo (+) y los abonos lo bajan (−).");
        movimientos.dinero("Saldo", Linea::saldoDespues).ancho(116).ayuda("Saldo que quedó después del movimiento.");
        movimientos.dosLineas("Nota", l -> l.movimiento().nota() == null || l.movimiento().nota().isBlank()
                ? "—" : l.movimiento().nota(), l -> "Registró " + l.movimiento().usuario()).flexible(100);
        movimientos.setVacio(new EstadoVacio("mdi2c-credit-card-clock-outline", "Sin movimientos de crédito.",
                "Aparecen al surtirle a crédito o al registrar abonos.").compacto());
        movimientos.setSinResultados(new EstadoVacio("mdi2c-credit-card-clock-outline", "Sin movimientos.", null));

        VBox panelDetalle = new VBox(cabecera, movimientos.tabla());
        panelDetalle.getStyleClass().add("panel-tabla");
        VBox.setVgrow(movimientos.tabla(), Priority.ALWAYS);
        Diseno.recortarRedondeado(panelDetalle, Diseno.RADIO_PANEL);
        HBox.setHgrow(panelDetalle, Priority.ALWAYS);
        panelDetalle.setMinWidth(0);

        HBox cuerpo = new HBox(Diseno.ESPACIO_L, panelSaldos, panelDetalle);
        pagina.getChildren().addAll(resumen, cuerpo);
        return Ui.ajustable(pagina, cuerpo, 340);
    }

    @Override
    public void alMostrar() {
        saldos.cargando("Cargando saldos…");
        carga.cargar(() -> a.admin().credito().saldos(a.admin().sucursales().listar()), lista -> {
            saldos.setDatos(lista);
            long total = lista.stream().mapToLong(CreditoService.Saldo::saldo).sum();
            long conAdeudo = lista.stream().filter(s -> s.saldo() > 0).count();
            metricaTotal.setValor(Dinero.formatear(total))
                    .setContexto(total > 0 ? "Suma de los saldos pendientes" : "Ninguna sucursal debe al almacén");
            metricaConAdeudo.setValor(String.valueOf(conAdeudo))
                    .setContexto("De " + Texto.plural(lista.size(), "sucursal", "sucursales"));
            if (saldos.seleccionado() == null && !lista.isEmpty()) {
                // La primera con adeudo, o la primera de la lista.
                CreditoService.Saldo inicial = lista.stream().filter(s -> s.saldo() > 0).findFirst().orElse(lista.getFirst());
                saldos.tabla().getSelectionModel().select(inicial);
            } else {
                mostrar(saldos.seleccionado());
            }
        }, e -> saldos.error("No se pudieron cargar los saldos.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void mostrar(CreditoService.Saldo s) {
        if (s == null) {
            titulo.setText("Elige una sucursal");
            subtitulo.setText("");
            saldo.setText("—");
            detalleSaldo.setText("");
            abonar.setDisable(true);
            sinAdeudo.setVisible(false);
            movimientos.setDatos(List.of());
            return;
        }
        titulo.setText(s.sucursal().nombre());
        subtitulo.setText("Código " + s.sucursal().codigo());
        saldo.setText(Dinero.formatear(s.saldo()));
        detalleSaldo.setText("Cargos " + Dinero.formatear(s.cargos()) + " · Abonos " + Dinero.formatear(s.abonos()));
        abonar.setDisable(s.saldo() <= 0);
        sinAdeudo.setVisible(s.saldo() <= 0);
        movimientos.cargando("Cargando movimientos…");
        cargaMovimientos.cargar(() -> a.admin().credito().movimientos(s.sucursal().id()),
                lista -> movimientos.setDatos(conSaldo(lista)),
                e -> movimientos.error("No se pudieron cargar los movimientos.", AdminContexto.mensaje(e), () -> mostrar(s)));
    }

    /** Saldo después de cada movimiento, recorriendo en orden cronológico. */
    private static List<Linea> conSaldo(List<CreditoService.Movimiento> lista) {
        List<CreditoService.Movimiento> cronologico = new ArrayList<>(lista);
        cronologico.sort(Comparator.comparing(CreditoService.Movimiento::fecha));
        Map<String, Long> saldoPorId = new HashMap<>();
        long acumulado = 0;
        for (CreditoService.Movimiento m : cronologico) {
            acumulado += "CARGO".equals(m.tipo()) ? m.montoCentavos() : -m.montoCentavos();
            saldoPorId.put(m.id(), acumulado);
        }
        return lista.stream().map(m -> new Linea(m, saldoPorId.get(m.id()))).toList();
    }

    private void abonar(CreditoService.Saldo s) {
        if (s == null || s.saldo() <= 0) {
            return;
        }
        Dialogo d = new Dialogo("Abono de " + s.sucursal().nombre(), "Código " + s.sucursal().codigo(),
                "mdi2c-cash-plus", Dialogo.Tono.ACENTO);
        d.setPrefWidth(500);
        CampoDinero monto = new CampoDinero().normal();
        monto.setPromptText("0.00");
        Formulario.Campo campoMonto = Formulario.campo("Monto del abono", monto)
                .ayuda("Saldo pendiente: " + Dinero.formatear(s.saldo()) + ". No puede ser mayor.");
        TextField nota = new TextField();
        nota.setPromptText("Ej. Transferencia, efectivo");
        Label despues = new Label(Dinero.formatear(s.saldo()));
        VBox resumen = Formulario.resumen("Saldo después del abono", despues);
        monto.textProperty().addListener((o, x, y) -> {
            campoMonto.setError(null);
            long restante = s.saldo() - monto.centavosOCero();
            // Un abono mayor que el saldo no se puede registrar: no se anticipa un saldo negativo.
            despues.setText(restante < 0 ? "—" : Dinero.formatear(restante));
            if (restante < 0) {
                campoMonto.setError("Es mayor que el saldo pendiente (" + Dinero.formatear(s.saldo()) + ").");
            }
        });
        d.setContenido(campoMonto, Formulario.opcional("Nota", nota), resumen);
        Runnable guardar = () -> {
            long centavos = monto.centavosOCero();
            if (centavos <= 0) {
                campoMonto.setError("Escribe un monto mayor a cero.");
                return;
            }
            if (centavos > s.saldo()) {
                campoMonto.setError("Es mayor que el saldo pendiente (" + Dinero.formatear(s.saldo()) + ").");
                return;
            }
            String textoNota = nota.getText();
            d.enviar(() -> a.admin().credito().abonar(s.sucursal(), centavos, textoNota, a.usuario()), abono -> {
                d.cerrar();
                a.avisos().exito("Abono de " + Dinero.formatear(abono.montoCentavos()) + " registrado. "
                        + s.sucursal().nombre() + " queda con " + Dinero.formatear(abono.saldoNuevo())
                        + " · Folio " + abono.folio());
                alMostrar();
                DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Abono " + abono.folio(),
                        s.sucursal().nombre() + " · " + Dinero.formatear(abono.montoCentavos()),
                        TicketTexto.lineasAbono(abono), null);
            });
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar abono", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(monto);
        d.vigilarCambios(monto, nota);
        a.dialogos().mostrar(d);
    }
}
