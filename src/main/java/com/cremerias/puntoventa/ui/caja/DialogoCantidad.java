package com.cremerias.puntoventa.ui.caja;

import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import atlantafx.base.controls.CustomTextField;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Captura la cantidad de un producto. A granel se puede indicar el peso (kg)
 * o el importe que pide el cliente ("dame $50 de queso").
 */
final class DialogoCantidad {

    private DialogoCantidad() {
    }

    static void mostrar(Dialogos dialogos, Producto producto, BigDecimal actual, Consumer<BigDecimal> alAceptar) {
        boolean granel = producto.unidad() == Unidad.KG;
        String precio = Dinero.formatear(producto.precioCentavos()) + (granel ? " por kg" : " por pieza");
        Dialogo d = new Dialogo(producto.nombre(), precio, granel ? "mdi2s-scale-balance" : "mdi2p-package-variant-closed",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(500);

        CustomTextField campo = new CustomTextField();
        campo.getStyleClass().addAll("campo-dinero", "campo-cantidad");
        Label sufijo = new Label(granel ? "kg" : "pzas");
        sufijo.getStyleClass().add("campo-dinero-signo");
        campo.setRight(sufijo);
        Campos.soloNumeros(campo, granel ? 3 : 0);
        if (actual != null) {
            campo.setText(producto.unidad().normalizar(actual).toPlainString());
        }

        Label vistaPrevia = new Label();
        vistaPrevia.getStyleClass().add("cantidad-vista-previa");
        Label error = new Label();
        error.getStyleClass().add("texto-error");
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);

        ToggleGroup modo = new ToggleGroup();
        ToggleButton porPeso = new ToggleButton("Por peso (kg)", new FontIcon("mdi2w-weight-kilogram"));
        ToggleButton porImporte = new ToggleButton("Por importe ($)", new FontIcon("mdi2c-currency-usd"));
        porPeso.getStyleClass().add("left-pill");
        porImporte.getStyleClass().add("right-pill");
        porPeso.setToggleGroup(modo);
        porImporte.setToggleGroup(modo);
        porPeso.setSelected(true);
        porPeso.setFocusTraversable(false);
        porImporte.setFocusTraversable(false);

        HBox rapidos = new HBox(8);
        Runnable llenarRapidos = () -> {
            rapidos.getChildren().clear();
            if (!granel) {
                for (int n : new int[]{1, 2, 3, 5, 10, 12}) {
                    rapidos.getChildren().add(chip(String.valueOf(n), () -> campo.setText(String.valueOf(n)), campo));
                }
            } else if (porPeso.isSelected()) {
                String[][] pesos = {{"¼ kg", "0.250"}, {"½ kg", "0.500"}, {"¾ kg", "0.750"}, {"1 kg", "1"}, {"2 kg", "2"}};
                for (String[] p : pesos) {
                    rapidos.getChildren().add(chip(p[0], () -> campo.setText(p[1]), campo));
                }
            } else {
                for (int pesos : new int[]{20, 50, 100, 150, 200}) {
                    rapidos.getChildren().add(chip("$" + pesos, () -> campo.setText(String.valueOf(pesos)), campo));
                }
            }
        };

        // Cantidad resultante según el modo.
        java.util.function.Supplier<Optional<BigDecimal>> cantidad = () -> {
            String texto = campo.getText();
            if (texto == null || texto.isBlank() || texto.equals(".")) {
                return Optional.empty();
            }
            BigDecimal valor = new BigDecimal(texto);
            if (granel && porImporte.isSelected()) {
                if (producto.precioCentavos() == 0) {
                    return Optional.empty();
                }
                valor = Dinero.aPesos(Dinero.aCentavos(valor))
                        .divide(Dinero.aPesos(producto.precioCentavos()), 3, RoundingMode.HALF_UP);
            }
            valor = producto.unidad().normalizar(valor);
            return valor.signum() > 0 ? Optional.of(valor) : Optional.empty();
        };

        Runnable actualizar = () -> {
            error.setVisible(false);
            vistaPrevia.setText(cantidad.get()
                    .map(c -> producto.unidad().formatear(c) + (granel ? "" : " pzas") + "  ·  "
                            + Dinero.formatear(producto.importe(c)))
                    .orElse(" "));
        };
        campo.textProperty().addListener((o, a, b) -> actualizar.run());
        modo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null) {
                antes.setSelected(true);
                return;
            }
            sufijo.setText(porPeso.isSelected() ? "kg" : "pesos");
            Campos.soloNumeros(campo, porPeso.isSelected() ? 3 : 2);
            campo.clear();
            llenarRapidos.run();
            campo.requestFocus();
        });
        llenarRapidos.run();
        actualizar.run();

        VBox contenido = new VBox(12);
        if (granel) {
            contenido.getChildren().add(new HBox(porPeso, porImporte));
        } else {
            Button menos = boton("mdi2m-minus", () -> ajustar(campo, -1));
            Button mas = boton("mdi2p-plus", () -> ajustar(campo, 1));
            HBox.setHgrow(campo, Priority.ALWAYS);
            campo.setMaxWidth(Double.MAX_VALUE);
            HBox fila = new HBox(8, menos, campo, mas);
            contenido.getChildren().addAll(fila, vistaPrevia, error, rapidos);
            d.setContenido(contenido);
        }
        if (granel) {
            contenido.getChildren().addAll(campo, vistaPrevia, error, rapidos);
            d.setContenido(contenido);
        }

        Runnable aceptar = () -> {
            Optional<BigDecimal> valor = cantidad.get();
            if (valor.isEmpty()) {
                error.setText(granel ? "Escribe un peso o importe mayor a cero." : "Escribe una cantidad mayor a cero.");
                error.setVisible(true);
                campo.requestFocus();
                return;
            }
            d.cerrar();
            alAceptar.accept(valor.get());
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton(actual == null ? "Agregar" : "Cambiar", "mdi2c-check", aceptar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(aceptar);
        d.setFocoInicial(campo);
        dialogos.mostrar(d);
    }

    private static void ajustar(CustomTextField campo, int delta) {
        int actual = campo.getText().isBlank() ? 0 : Integer.parseInt(campo.getText());
        campo.setText(String.valueOf(Math.max(1, actual + delta)));
        campo.requestFocus();
        campo.end();
    }

    private static Button boton(String icono, Runnable accion) {
        Button b = new Button(null, new FontIcon(icono));
        b.getStyleClass().addAll("button-icon", "boton-cantidad-grande");
        b.setFocusTraversable(false);
        b.setOnAction(e -> accion.run());
        return b;
    }

    private static Button chip(String texto, Runnable accion, CustomTextField campo) {
        Button b = new Button(texto);
        b.getStyleClass().add("chip-rapido");
        b.setFocusTraversable(false);
        b.setOnAction(e -> {
            accion.run();
            campo.requestFocus();
            campo.end();
        });
        return b;
    }
}
