package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.MaskTextField;
import com.cremerias.puntoventa.model.HorarioDia;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Horario semanal de un empleado: entrada, salida y descanso opcional por día. */
final class DialogoHorario {

    private record FilaDia(DayOfWeek dia, CheckBox labora, MaskTextField entrada, MaskTextField salida,
                           CheckBox descanso, MaskTextField inicio, MaskTextField fin) {
    }

    private DialogoHorario() {
    }

    static void mostrar(AdminContexto a, Usuario empleado, Runnable alGuardar) {
        Dialogo d = new Dialogo("Horario de " + empleado.nombreCompleto(),
                "Si llega tarde, sale antes o regresa tarde del descanso, su acceso se bloquea hasta que lo autorices.",
                "mdi2c-clock-edit-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(760);
        Map<DayOfWeek, HorarioDia> actual = a.ctx().auth().accesos().horario(empleado.id());

        GridPane grid = new GridPane(14, 10);
        grid.getStyleClass().add("grid-horario");
        String[] encabezados = {"Día", "Entrada", "Salida", "Descanso", "Inicio", "Fin"};
        for (int i = 0; i < encabezados.length; i++) {
            Label l = new Label(encabezados[i]);
            l.getStyleClass().add("campo-etiqueta");
            grid.add(l, i, 0);
        }
        List<FilaDia> filas = new ArrayList<>();
        int r = 1;
        for (DayOfWeek dia : DayOfWeek.values()) {
            HorarioDia h = actual.getOrDefault(dia, dia.getValue() <= 6
                    ? new HorarioDia(dia, true, LocalTime.of(9, 0), LocalTime.of(18, 0), LocalTime.of(14, 0), LocalTime.of(15, 0))
                    : HorarioDia.descanso(dia));
            if (actual.isEmpty() && dia == DayOfWeek.SUNDAY) {
                h = HorarioDia.descanso(dia);
            }
            CheckBox labora = new CheckBox(h.nombreDia());
            labora.setSelected(h.labora());
            MaskTextField entrada = hora(h.entrada());
            MaskTextField salida = hora(h.salida());
            CheckBox descanso = new CheckBox();
            descanso.setSelected(h.tieneDescanso());
            MaskTextField inicio = hora(h.descansoInicio());
            MaskTextField fin = hora(h.descansoFin());
            entrada.disableProperty().bind(labora.selectedProperty().not());
            salida.disableProperty().bind(labora.selectedProperty().not());
            descanso.disableProperty().bind(labora.selectedProperty().not());
            inicio.disableProperty().bind(labora.selectedProperty().not().or(descanso.selectedProperty().not()));
            fin.disableProperty().bind(inicio.disableProperty());
            grid.addRow(r++, labora, entrada, salida, descanso, inicio, fin);
            GridPane.setHalignment(descanso, javafx.geometry.HPos.CENTER);
            filas.add(new FilaDia(dia, labora, entrada, salida, descanso, inicio, fin));
        }
        Button copiar = Ui.secundario("Copiar el lunes a los demás días laborables", "mdi2c-content-copy");
        copiar.setOnAction(e -> {
            FilaDia lunes = filas.getFirst();
            for (FilaDia f : filas.subList(1, filas.size())) {
                if (f.labora().isSelected()) {
                    f.entrada().setText(lunes.entrada().getText());
                    f.salida().setText(lunes.salida().getText());
                    f.descanso().setSelected(lunes.descanso().isSelected());
                    f.inicio().setText(lunes.inicio().getText());
                    f.fin().setText(lunes.fin().getText());
                }
            }
        });
        Label ayuda = Ui.texto("Horas en formato de 24 h (ej. 09:00, 18:30). Los días sin marcar son de descanso: "
                + "si entra ese día, su acceso se bloquea.", "texto-ayuda");
        d.setContenido(grid, copiar, ayuda);

        Runnable guardar = () -> {
            List<HorarioDia> semana = new ArrayList<>();
            try {
                for (FilaDia f : filas) {
                    if (!f.labora().isSelected()) {
                        semana.add(HorarioDia.descanso(f.dia()));
                        continue;
                    }
                    boolean conDescanso = f.descanso().isSelected();
                    semana.add(new HorarioDia(f.dia(), true, leer(f.entrada()), leer(f.salida()),
                            conDescanso ? leer(f.inicio()) : null, conDescanso ? leer(f.fin()) : null));
                }
            } catch (DateTimeParseException ex) {
                a.avisos().error("Revisa las horas: usa el formato 24 h, por ejemplo 09:00.");
                return;
            }
            if (a.ejecutar("Horario guardado", () -> a.ctx().auth().accesos().guardarHorario(empleado, semana, a.usuario()))) {
                d.cerrar();
                alGuardar.run();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar horario", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        a.dialogos().mostrar(d);
    }

    private static MaskTextField hora(LocalTime valor) {
        MaskTextField campo = new MaskTextField("29:59");
        campo.setText(valor == null ? "" : valor.toString());
        campo.setPrefColumnCount(5);
        campo.setAlignment(Pos.CENTER);
        return campo;
    }

    private static LocalTime leer(MaskTextField campo) {
        String t = campo.getText() == null ? "" : campo.getText().strip();
        if (t.isEmpty() || t.contains("_")) {
            return null;
        }
        return LocalTime.parse(t);
    }
}
