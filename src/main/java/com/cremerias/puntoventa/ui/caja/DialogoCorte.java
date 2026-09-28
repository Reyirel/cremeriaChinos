package com.cremerias.puntoventa.ui.caja;

import atlantafx.base.controls.Spacer;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Corte de caja: resumen del turno y conteo del efectivo por denominación. */
public final class DialogoCorte {

    public interface Resultado {
        void cerrar(long contado, String notas);
    }

    /** Billetes y monedas mexicanos, en centavos. */
    private static final long[] DENOMINACIONES = {100000, 50000, 20000, 10000, 5000, 2000, 1000, 500, 200, 100, 50};

    private static final PseudoClass FALTANTE = PseudoClass.getPseudoClass("faltante");
    private static final PseudoClass SOBRANTE = PseudoClass.getPseudoClass("sobrante");

    private DialogoCorte() {
    }

    public static void mostrar(Dialogos dialogos, ResumenTurno r, Resultado alCerrar) {
        Dialogo d = new Dialogo("Corte de caja", "Cuenta el efectivo del cajón y registra el cierre del turno.",
                "mdi2c-calculator-variant-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(900);

        // Resumen
        VBox resumen = new VBox(8);
        resumen.getStyleClass().add("corte-resumen");
        resumen.getChildren().addAll(
                titulo("Efectivo"),
                fila("Ventas en efectivo", r.ventasEfectivo(), false),
                fila("Entradas", r.entradas(), false),
                fila("Retiros", -r.retiros(), false),
                new Separator(),
                fila("Efectivo esperado", r.efectivoEsperado(), true),
                new Spacer(8),
                titulo("Ventas del turno"),
                fila("Con tarjeta", r.ventasTarjeta(), false),
                fila("Por transferencia", r.ventasTransferencia(), false),
                fila("Total vendido", r.totalVentas(), true),
                texto("Ventas realizadas", String.valueOf(r.numeroVentas())),
                texto("Ventas canceladas", r.numeroCanceladas() + "  (" + Dinero.formatear(r.totalCancelado()) + ")"));
        resumen.setPrefWidth(360);
        resumen.setMinWidth(320);

        // Conteo
        GridPane conteo = new GridPane(10, 6);
        conteo.getStyleClass().add("corte-conteo");
        List<TextField> campos = new ArrayList<>();
        List<Label> subtotales = new ArrayList<>();
        Label totalContado = new Label(Dinero.formatear(0));
        totalContado.getStyleClass().add("corte-contado-monto");
        Label diferencia = new Label();
        diferencia.getStyleClass().add("corte-diferencia");

        Runnable recalcular = () -> {
            long total = 0;
            for (int i = 0; i < DENOMINACIONES.length; i++) {
                String t = campos.get(i).getText();
                long piezas = t == null || t.isBlank() ? 0 : Long.parseLong(t);
                long sub = piezas * DENOMINACIONES[i];
                subtotales.get(i).setText(piezas == 0 ? "—" : Dinero.formatear(sub));
                total += sub;
            }
            totalContado.setText(Dinero.formatear(total));
            long dif = total - r.efectivoEsperado();
            diferencia.setText(dif == 0 ? "Cuadra exacto" : (dif < 0 ? "Faltan " : "Sobran ") + Dinero.formatear(Math.abs(dif)));
            diferencia.pseudoClassStateChanged(FALTANTE, dif < 0);
            diferencia.pseudoClassStateChanged(SOBRANTE, dif > 0);
            totalContado.setUserData(total);
        };

        for (int i = 0; i < DENOMINACIONES.length; i++) {
            long valor = DENOMINACIONES[i];
            Label nombre = new Label(valor >= 2000 ? "Billete " + Dinero.formatear(valor).replace(".00", "")
                    : "Moneda " + (valor < 100 ? "50¢" : Dinero.formatear(valor).replace(".00", "")));
            nombre.getStyleClass().add("corte-denominacion");
            TextField piezas = new TextField();
            piezas.setPrefColumnCount(4);
            piezas.setMinWidth(72);
            piezas.getStyleClass().add("corte-piezas");
            Campos.soloNumeros(piezas, 0);
            piezas.textProperty().addListener((o, a, b) -> recalcular.run());
            Label sub = new Label("—");
            sub.getStyleClass().add("corte-subtotal");
            sub.setMinWidth(96);
            sub.setAlignment(Pos.CENTER_RIGHT);
            campos.add(piezas);
            subtotales.add(sub);
            int columna = i < 6 ? 0 : 3;
            int renglon = i < 6 ? i : i - 6;
            conteo.add(nombre, columna, renglon);
            conteo.add(piezas, columna + 1, renglon);
            conteo.add(sub, columna + 2, renglon);
        }
        TextField notas = new TextField();
        notas.setPromptText("Notas del cierre (opcional)");

        Label etiquetaContado = new Label("EFECTIVO CONTADO");
        etiquetaContado.getStyleClass().add("corte-contado-etiqueta");
        VBox cajaContado = new VBox(2, etiquetaContado, totalContado, diferencia);
        cajaContado.getStyleClass().add("corte-contado");
        cajaContado.setAlignment(Pos.CENTER);

        VBox derecha = new VBox(14, titulo("Conteo de efectivo"), conteo, cajaContado, notas);
        HBox.setHgrow(derecha, Priority.ALWAYS);
        HBox cuerpo = new HBox(24, resumen, derecha);
        d.setContenido(cuerpo);
        recalcular.run();

        Runnable cerrar = () -> {
            d.cerrar();
            alCerrar.cerrar((Long) totalContado.getUserData(), notas.getText());
        };
        d.agregarBoton("Regresar", null, d::cerrar, "flat");
        javafx.scene.control.Button botonCerrar =
                d.agregarBoton("Cerrar turno", "mdi2c-cash-check", cerrar, "accent", "large");
        d.setAlCancelar(d::cerrar);
        // Enter lleva al botón; un segundo Enter confirma (evita cerrar el turno por accidente).
        d.setAlConfirmar(botonCerrar::requestFocus);
        d.setFocoInicial(campos.getFirst());
        dialogos.mostrar(d);
    }

    private static Label titulo(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("corte-titulo");
        return l;
    }

    private static HBox fila(String nombre, long centavos, boolean destacada) {
        return texto(nombre, Dinero.formatear(centavos), destacada);
    }

    private static HBox texto(String nombre, String valor) {
        return texto(nombre, valor, false);
    }

    private static HBox texto(String nombre, String valor, boolean destacada) {
        Label a = new Label(nombre);
        Label b = new Label(valor);
        HBox fila = new HBox(a, new Spacer(), b);
        fila.getStyleClass().add(destacada ? "corte-fila-destacada" : "corte-fila");
        return fila;
    }
}
