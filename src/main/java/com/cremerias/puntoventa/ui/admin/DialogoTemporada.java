package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.ToggleSwitch;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

/** Captura una nueva ventana de fechas para reactivar un producto de temporada ya vencido. */
final class DialogoTemporada {

    private DialogoTemporada() {
    }

    static void mostrarRenovar(AdminContexto a, ProductoCatalogo p, Runnable alGuardar) {
        Temporada anterior = p.temporada();
        Dialogo d = new Dialogo("Nueva temporada",
                "La temporada de " + p.nombre() + " terminó el " + Formatos.FECHA_CORTA.format(anterior.fin())
                        + ". Elige la siguiente para volver a venderlo.",
                "mdi2c-calendar-range", Dialogo.Tono.ACENTO);
        d.setPrefWidth(480);

        DatePicker desde = new DatePicker();
        DatePicker hasta = new DatePicker();
        desde.setPromptText("Empieza");
        hasta.setPromptText("Termina");

        ToggleSwitch repetir = new ToggleSwitch("Se repite automáticamente");
        TextField plazo = new TextField();
        Campos.soloNumeros(plazo, 0);
        plazo.setAlignment(Pos.CENTER_RIGHT);
        plazo.setPromptText("Ej. 1");
        plazo.setPrefColumnCount(5);
        ComboBox<Temporada.Unidad> unidad = new ComboBox<>(FXCollections.observableArrayList(Temporada.Unidad.values()));
        unidad.setValue(Temporada.Unidad.ANIOS);
        unidad.setPrefWidth(130);
        if (anterior.repite()) {
            repetir.setSelected(true);
            plazo.setText(String.valueOf(anterior.repetirCada()));
            unidad.setValue(anterior.repetirUnidad());
            Temporada sugerida = anterior.siguienteVigente(LocalDate.now());
            desde.setValue(sugerida.desde());
            hasta.setValue(sugerida.fin());
        }
        HBox campoRepetir = new HBox(8, plazo, unidad);
        campoRepetir.setAlignment(Pos.CENTER_LEFT);
        Formulario.Campo cada = Formulario.campo("Cada", campoRepetir);
        cada.visibleProperty().bind(repetir.selectedProperty());
        cada.managedProperty().bind(cada.visibleProperty());

        Formulario.Campo campoDesde = Formulario.campo("Desde", desde);
        Formulario.Campo campoHasta = Formulario.campo("Hasta", hasta);
        VBox formulario = new VBox(16, Formulario.fila(campoDesde, campoHasta),
                Formulario.seccionSeparada("Repetición", "Si se repite, la próxima vez el sistema la vuelve a habilitar solo.",
                        repetir, cada));
        d.setContenido(formulario);

        Runnable guardar = () -> {
            boolean valido = true;
            if (desde.getValue() == null) {
                campoDesde.setError("Elige cuándo empieza.");
                valido = false;
            }
            if (hasta.getValue() == null) {
                campoHasta.setError("Elige cuándo termina.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            Temporada nueva = new Temporada(desde.getValue(), hasta.getValue(),
                    repetir.isSelected() ? entero(plazo.getText()) : null, repetir.isSelected() ? unidad.getValue() : null);
            a.guardar(d, "Producto habilitado: " + p.nombre(),
                    () -> a.admin().productos().renovarTemporada(p, nueva, a.usuario()), alGuardar);
        };
        desde.valueProperty().addListener((o, x, y) -> campoDesde.setError(null));
        hasta.valueProperty().addListener((o, x, y) -> campoHasta.setError(null));
        d.vigilarCambios(desde, hasta, repetir, plazo, unidad);
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Habilitar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(desde);
        a.dialogos().mostrar(d);
    }

    private static int entero(String texto) {
        if (texto == null || texto.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(texto.strip());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
