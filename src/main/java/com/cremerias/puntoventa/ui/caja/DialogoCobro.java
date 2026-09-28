package com.cremerias.puntoventa.ui.caja;

import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.service.VentaService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/** Cobro de la venta: efectivo (con cambio), tarjeta, transferencia o mixto. */
final class DialogoCobro {

    private enum Forma { EFECTIVO, TARJETA, TRANSFERENCIA, MIXTO }

    private static final PseudoClass FALTA = PseudoClass.getPseudoClass("falta");

    private final long total;
    private final Dialogo dialogo;
    private final ToggleGroup formas = new ToggleGroup();
    private final StackPane panelForma = new StackPane();

    private final CampoDinero recibido = new CampoDinero();
    private final TextField referenciaTarjeta = new TextField();
    private final TextField referenciaTransferencia = new TextField();
    private final CampoDinero mixtoEfectivo = new CampoDinero();
    private final CampoDinero mixtoTarjeta = new CampoDinero();
    private final CampoDinero mixtoTransferencia = new CampoDinero();

    private final Label etiquetaResultado = new Label();
    private final Label montoResultado = new Label();
    private final HBox resultado = new HBox();
    private final Button botonCobrar;
    private Forma forma = Forma.EFECTIVO;

    private DialogoCobro(long total, Consumer<List<Pago>> alCobrar) {
        this.total = total;
        dialogo = new Dialogo("Cobrar venta", "Elige la forma de pago · F1-F4 para cambiar", "mdi2c-cash-register",
                Dialogo.Tono.EXITO);
        dialogo.setPrefWidth(620);

        Label etiquetaTotal = new Label("TOTAL A COBRAR");
        etiquetaTotal.getStyleClass().add("cobro-total-etiqueta");
        Label montoTotal = new Label(Dinero.formatear(total));
        montoTotal.getStyleClass().add("cobro-total-monto");
        VBox cajaTotal = new VBox(2, etiquetaTotal, montoTotal);
        cajaTotal.getStyleClass().add("cobro-total");
        cajaTotal.setAlignment(Pos.CENTER);

        HBox selector = new HBox(10,
                opcion(Forma.EFECTIVO, "Efectivo", "mdi2c-cash", "F1"),
                opcion(Forma.TARJETA, "Tarjeta", "mdi2c-credit-card-outline", "F2"),
                opcion(Forma.TRANSFERENCIA, "Transferencia", "mdi2b-bank-transfer", "F3"),
                opcion(Forma.MIXTO, "Mixto", "mdi2c-cash-multiple", "F4"));
        selector.getStyleClass().add("cobro-formas");
        formas.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null) {
                antes.setSelected(true);
            } else {
                cambiarForma((Forma) ahora.getUserData());
            }
        });

        etiquetaResultado.getStyleClass().add("cobro-resultado-etiqueta");
        montoResultado.getStyleClass().add("cobro-resultado-monto");
        HBox.setHgrow(etiquetaResultado, Priority.ALWAYS);
        etiquetaResultado.setMaxWidth(Double.MAX_VALUE);
        resultado.getChildren().setAll(etiquetaResultado, montoResultado);
        resultado.getStyleClass().add("cobro-resultado");
        resultado.setAlignment(Pos.CENTER_LEFT);

        dialogo.setContenido(cajaTotal, selector, panelForma, resultado);

        for (CampoDinero campo : List.of(recibido, mixtoEfectivo, mixtoTarjeta, mixtoTransferencia)) {
            campo.textProperty().addListener((o, a, b) -> actualizar());
        }
        referenciaTarjeta.setPromptText("Número de autorización (opcional)");
        referenciaTransferencia.setPromptText("Referencia o clave de rastreo (opcional)");

        dialogo.agregarBoton("Regresar", null, dialogo::cerrar, "flat");
        botonCobrar = dialogo.agregarBoton("Cobrar", "mdi2c-check-circle", () -> confirmar(alCobrar),
                "success", "large", "boton-cobrar-final");
        dialogo.setAlCancelar(dialogo::cerrar);
        dialogo.setAlConfirmar(() -> confirmar(alCobrar));
        dialogo.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            Forma nueva = switch (e.getCode()) {
                case F1 -> Forma.EFECTIVO;
                case F2 -> Forma.TARJETA;
                case F3 -> Forma.TRANSFERENCIA;
                case F4 -> Forma.MIXTO;
                default -> null;
            };
            if (nueva != null) {
                formas.getToggles().stream().filter(t -> t.getUserData() == nueva).findFirst()
                        .ifPresent(t -> t.setSelected(true));
                e.consume();
            }
        });

        formas.getToggles().getFirst().setSelected(true);
    }

    static void mostrar(Dialogos dialogos, long total, Consumer<List<Pago>> alCobrar) {
        DialogoCobro cobro = new DialogoCobro(total, alCobrar);
        dialogos.mostrar(cobro.dialogo);
    }

    private ToggleButton opcion(Forma valor, String texto, String icono, String tecla) {
        FontIcon icon = new FontIcon(icono);
        Label nombre = new Label(texto);
        Label kbd = new Label(tecla);
        kbd.getStyleClass().add("kbd");
        VBox contenido = new VBox(6, icon, nombre, kbd);
        contenido.setAlignment(Pos.CENTER);
        ToggleButton boton = new ToggleButton(null, contenido);
        boton.getStyleClass().add("cobro-forma");
        boton.setUserData(valor);
        boton.setToggleGroup(formas);
        boton.setFocusTraversable(false);
        boton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(boton, Priority.ALWAYS);
        return boton;
    }

    private void cambiarForma(Forma nueva) {
        forma = nueva;
        Node contenido = switch (nueva) {
            case EFECTIVO -> panelEfectivo();
            case TARJETA -> panelReferencia("Cobra " + Dinero.formatear(total) + " en la terminal bancaria.",
                    referenciaTarjeta, "mdi2c-credit-card-outline");
            case TRANSFERENCIA -> panelReferencia("Verifica que la transferencia por " + Dinero.formatear(total)
                    + " se haya recibido.", referenciaTransferencia, "mdi2b-bank-transfer");
            case MIXTO -> panelMixto();
        };
        panelForma.getChildren().setAll(contenido);
        actualizar();
        Node foco = switch (nueva) {
            case EFECTIVO -> recibido;
            case TARJETA -> referenciaTarjeta;
            case TRANSFERENCIA -> referenciaTransferencia;
            case MIXTO -> mixtoEfectivo;
        };
        dialogo.setFocoInicial(foco);
        javafx.application.Platform.runLater(foco::requestFocus);
    }

    private Node panelEfectivo() {
        Label etiqueta = new Label("Efectivo recibido  (vacío = pago exacto)");
        etiqueta.getStyleClass().add("campo-etiqueta");
        recibido.setPromptText(Dinero.aPesos(total).toPlainString());

        FlowPane sugerencias = new FlowPane(8, 8);
        Button exacto = new Button("Exacto");
        exacto.getStyleClass().add("chip-rapido");
        exacto.setFocusTraversable(false);
        exacto.setOnAction(e -> {
            recibido.setCentavos(total);
            recibido.requestFocus();
        });
        sugerencias.getChildren().add(exacto);
        for (long billete : sugerenciasEfectivo(total)) {
            Button b = new Button(Dinero.formatear(billete).replace(".00", ""));
            b.getStyleClass().add("chip-rapido");
            b.setFocusTraversable(false);
            b.setOnAction(e -> {
                recibido.setCentavos(billete);
                recibido.requestFocus();
            });
            sugerencias.getChildren().add(b);
        }
        return new VBox(8, etiqueta, recibido, sugerencias);
    }

    /** Billetes/montos redondos con los que probablemente pague el cliente. */
    static List<Long> sugerenciasEfectivo(long total) {
        TreeSet<Long> montos = new TreeSet<>();
        for (long paso : new long[]{1000, 5000, 10000, 20000, 50000, 100000}) {
            long redondeado = ((total + paso - 1) / paso) * paso;
            if (redondeado > total) {
                montos.add(redondeado);
            }
        }
        return new ArrayList<>(montos).subList(0, Math.min(5, montos.size()));
    }

    private Node panelReferencia(String mensaje, TextField referencia, String icono) {
        Label texto = new Label(mensaje, new FontIcon(icono));
        texto.getStyleClass().add("cobro-instruccion");
        texto.setWrapText(true);
        Label etiqueta = new Label("Referencia");
        etiqueta.getStyleClass().add("campo-etiqueta");
        referencia.getStyleClass().add("campo-grande");
        return new VBox(10, texto, etiqueta, referencia);
    }

    private Node panelMixto() {
        GridPane grid = new GridPane(12, 10);
        String[][] filas = {{"Efectivo", "mdi2c-cash"}, {"Tarjeta", "mdi2c-credit-card-outline"},
                {"Transferencia", "mdi2b-bank-transfer"}};
        CampoDinero[] campos = {mixtoEfectivo, mixtoTarjeta, mixtoTransferencia};
        for (int i = 0; i < filas.length; i++) {
            Label etiqueta = new Label(filas[i][0], new FontIcon(filas[i][1]));
            etiqueta.getStyleClass().add("campo-etiqueta");
            etiqueta.setMinWidth(140);
            campos[i].setPromptText("0.00");
            GridPane.setHgrow(campos[i], Priority.ALWAYS);
            grid.addRow(i, etiqueta, campos[i]);
        }
        Button restante = new Button("Completar con tarjeta");
        restante.getStyleClass().add("chip-rapido");
        restante.setFocusTraversable(false);
        restante.setOnAction(e -> {
            long cubierto = mixtoEfectivo.centavosOCero() + mixtoTransferencia.centavosOCero();
            mixtoTarjeta.setCentavos(Math.max(0, total - cubierto));
        });
        grid.add(restante, 1, 3);
        return grid;
    }

    private List<Pago> pagos() {
        return switch (forma) {
            case EFECTIVO -> List.of(new Pago(MetodoPago.EFECTIVO, recibido.centavos().orElse(total), null));
            case TARJETA -> List.of(new Pago(MetodoPago.TARJETA, total, texto(referenciaTarjeta)));
            case TRANSFERENCIA -> List.of(new Pago(MetodoPago.TRANSFERENCIA, total, texto(referenciaTransferencia)));
            case MIXTO -> {
                List<Pago> lista = new ArrayList<>();
                if (mixtoEfectivo.centavosOCero() > 0) {
                    lista.add(new Pago(MetodoPago.EFECTIVO, mixtoEfectivo.centavosOCero(), null));
                }
                if (mixtoTarjeta.centavosOCero() > 0) {
                    lista.add(new Pago(MetodoPago.TARJETA, mixtoTarjeta.centavosOCero(), null));
                }
                if (mixtoTransferencia.centavosOCero() > 0) {
                    lista.add(new Pago(MetodoPago.TRANSFERENCIA, mixtoTransferencia.centavosOCero(), null));
                }
                yield lista;
            }
        };
    }

    private void actualizar() {
        List<Pago> pagos = pagos();
        long pagado = pagos.stream().mapToLong(Pago::recibidoCentavos).sum();
        boolean valido;
        try {
            long cambio = VentaService.calcularCambio(total, pagos);
            valido = true;
            etiquetaResultado.setText(cambio > 0 ? "Cambio a entregar" : "Pago completo");
            montoResultado.setText(Dinero.formatear(cambio));
        } catch (IllegalArgumentException e) {
            valido = false;
            if (pagado < total) {
                etiquetaResultado.setText("Falta por cubrir");
                montoResultado.setText(Dinero.formatear(total - pagado));
            } else {
                etiquetaResultado.setText(e.getMessage());
                montoResultado.setText("");
            }
        }
        resultado.pseudoClassStateChanged(FALTA, !valido);
        if (botonCobrar != null) {
            botonCobrar.setDisable(!valido);
        }
    }

    private void confirmar(Consumer<List<Pago>> alCobrar) {
        actualizar();
        if (botonCobrar.isDisabled()) {
            atlantafx.base.util.Animations.shakeX(resultado, 6).playFromStart();
            return;
        }
        List<Pago> pagos = pagos();
        dialogo.cerrar();
        alCobrar.accept(pagos);
    }

    private static String texto(TextField campo) {
        String t = campo.getText();
        return t == null || t.isBlank() ? null : t.strip();
    }
}
