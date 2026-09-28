package com.cremerias.puntoventa.ui.caja;

import com.cremerias.puntoventa.service.CajaService.TipoMovimiento;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** Entrada o retiro de efectivo de la caja (pago a proveedor, morralla, retiro parcial...). */
final class DialogoMovimientoCaja {

    interface Resultado {
        void aceptar(TipoMovimiento tipo, long monto, String concepto);
    }

    private DialogoMovimientoCaja() {
    }

    static void mostrar(Dialogos dialogos, long efectivoDisponible, Resultado alAceptar) {
        Dialogo d = new Dialogo("Entrada / retiro de efectivo",
                "Efectivo en caja: " + Dinero.formatear(efectivoDisponible), "mdi2s-swap-horizontal",
                Dialogo.Tono.ACENTO);

        ToggleGroup tipo = new ToggleGroup();
        ToggleButton entrada = new ToggleButton("Entrada", new FontIcon("mdi2t-tray-arrow-down"));
        ToggleButton retiro = new ToggleButton("Retiro", new FontIcon("mdi2t-tray-arrow-up"));
        entrada.getStyleClass().addAll("left-pill", "tipo-movimiento");
        retiro.getStyleClass().addAll("right-pill", "tipo-movimiento");
        entrada.setToggleGroup(tipo);
        retiro.setToggleGroup(tipo);
        retiro.setSelected(true);
        entrada.setMaxWidth(Double.MAX_VALUE);
        retiro.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(entrada, Priority.ALWAYS);
        HBox.setHgrow(retiro, Priority.ALWAYS);
        tipo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null) {
                antes.setSelected(true);
            }
        });

        CampoDinero monto = new CampoDinero();
        monto.setPromptText("0.00");
        ComboBox<String> concepto = new ComboBox<>();
        concepto.setEditable(true);
        concepto.setMaxWidth(Double.MAX_VALUE);
        concepto.getItems().addAll("Retiro parcial de efectivo", "Pago a proveedor", "Compra de insumos",
                "Cambio / morralla", "Gastos del local");
        concepto.setPromptText("Concepto");
        concepto.getStyleClass().add("campo-grande");

        Label nota = new Label("Los retiros requieren autorización de un supervisor.");
        nota.getStyleClass().add("texto-ayuda");
        nota.visibleProperty().bind(retiro.selectedProperty());
        Label error = new Label();
        error.getStyleClass().add("texto-error");
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        d.setContenido(new HBox(entrada, retiro),
                new VBox(8, etiqueta("Monto"), monto),
                new VBox(8, etiqueta("Concepto"), concepto),
                nota, error);

        Runnable aceptar = () -> {
            String texto = concepto.getEditor().getText();
            long valor = monto.centavosOCero();
            if (valor <= 0) {
                error.setText("Escribe un monto mayor a cero.");
                error.setVisible(true);
                monto.requestFocus();
                return;
            }
            if (texto == null || texto.isBlank()) {
                error.setText("Escribe el concepto.");
                error.setVisible(true);
                concepto.requestFocus();
                return;
            }
            d.cerrar();
            alAceptar.aceptar(retiro.isSelected() ? TipoMovimiento.RETIRO : TipoMovimiento.ENTRADA, valor, texto.strip());
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Registrar", "mdi2c-check", aceptar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(aceptar);
        d.setFocoInicial(monto);
        dialogos.mostrar(d);
    }

    private static Label etiqueta(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("campo-etiqueta");
        return l;
    }
}
