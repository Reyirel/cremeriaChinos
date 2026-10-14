package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Nomina;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.NominaService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;

/** Salario y comida de cada empleado por semana, por sucursal. */
public class NominaPagina extends Pagina {

    private static final DateTimeFormatter SEMANA = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es-MX"));
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-MX"));

    private ComboBox<Sucursal> sucursal;
    private ComboBox<YearMonth> mes;
    private Button capturar;
    private TableView<Nomina> tabla;
    private Label total;

    public NominaPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        capturar = Ui.principal("Capturar nómina", "mdi2p-plus");
        capturar.setOnAction(e -> formulario(null));
        capturar.setDisable(true);
        VBox pagina = Ui.pagina("Nómina", "Salario y comida de cada empleado, por semana.", capturar);

        sucursal = new ComboBox<>();
        sucursal.setPromptText("Elige la sucursal");
        sucursal.valueProperty().addListener((o, x, y) -> cargar());

        mes = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(0, 11).mapToObj(i -> YearMonth.now().minusMonths(i)).toList()));
        mes.setValue(YearMonth.now());
        mes.setConverter(new StringConverter<>() {
            @Override
            public String toString(YearMonth m) {
                return m == null ? "" : capitalizar(MES.format(m));
            }

            @Override
            public YearMonth fromString(String s) {
                return null;
            }
        });
        mes.valueProperty().addListener((o, x, y) -> cargar());

        HBox filtros = new HBox(14, Ui.campo("Sucursal", sucursal), Ui.campo("Mes", mes));
        filtros.setAlignment(Pos.CENTER_LEFT);

        tabla = Ui.tabla("No hay nómina capturada en este mes.");
        tabla.setPrefHeight(320);
        tabla.getColumns().setAll(List.of(
                Ui.texto("Empleado", 220, Nomina::usuario),
                Ui.texto("Semana del", 130, n -> SEMANA.format(n.semanaInicio())),
                Ui.dinero("Salario", 120, Nomina::salarioCentavos),
                Ui.dinero("Comida", 120, Nomina::comidaCentavos),
                Ui.dinero("Total", 120, Nomina::total),
                Ui.nodo("", 70, n -> Ui.acciones(
                        Ui.accion("mdi2p-pencil-outline", "Editar", () -> formulario(n))))));

        total = new Label();
        total.getStyleClass().add("indicador-valor");

        pagina.getChildren().addAll(filtros, tabla, new HBox(8, Ui.texto("Total del mes:", "indicador-titulo"), total));
        return pagina;
    }

    @Override
    public void alMostrar() {
        Sucursal actual = sucursal.getValue();
        List<Sucursal> lista = a.admin().sucursales().listar();
        sucursal.setItems(FXCollections.observableArrayList(lista));
        if (actual != null) {
            lista.stream().filter(s -> s.id().equals(actual.id())).findFirst().ifPresent(sucursal::setValue);
        } else if (!lista.isEmpty()) {
            sucursal.setValue(lista.getFirst());
        }
        cargar();
    }

    private void cargar() {
        capturar.setDisable(sucursal.getValue() == null);
        if (sucursal.getValue() == null || mes.getValue() == null) {
            return;
        }
        List<Nomina> lista = a.admin().nomina().listar(a.usuario(), sucursal.getValue().id(), mes.getValue());
        tabla.setItems(FXCollections.observableArrayList(lista));
        total.setText(Dinero.formatear(lista.stream().mapToLong(Nomina::total).sum()));
    }

    private List<Usuario> empleadosDeSucursal(String sucursalId) {
        return a.admin().usuarios().listar().stream()
                .map(f -> f.usuario())
                .filter(u -> u.rol() != Rol.ADMINISTRADOR && sucursalId.equals(u.sucursalId()))
                .toList();
    }

    private void formulario(Nomina n) {
        boolean nueva = n == null;
        Sucursal s = sucursal.getValue();
        if (s == null) {
            return;
        }
        List<Usuario> empleados = empleadosDeSucursal(s.id());
        List<LocalDate> semanas = NominaService.semanasDelMes(mes.getValue());

        Dialogo d = new Dialogo(nueva ? "Capturar nómina" : "Editar nómina", s.nombre(), "mdi2h-hand-coin-outline",
                Dialogo.Tono.ACENTO);

        ComboBox<Usuario> empleado = new ComboBox<>(FXCollections.observableArrayList(empleados));
        empleado.setConverter(new StringConverter<>() {
            @Override
            public String toString(Usuario u) {
                return u == null ? "" : u.nombreCompleto();
            }

            @Override
            public Usuario fromString(String s) {
                return null;
            }
        });
        ComboBox<LocalDate> semana = new ComboBox<>(FXCollections.observableArrayList(semanas));
        semana.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate f) {
                return f == null ? "" : "Semana del " + SEMANA.format(f);
            }

            @Override
            public LocalDate fromString(String s) {
                return null;
            }
        });
        CampoDinero salario = new CampoDinero();
        CampoDinero comida = new CampoDinero();

        Runnable precargar = () -> {
            Usuario u = empleado.getValue();
            LocalDate sem = semana.getValue();
            if (u == null || sem == null) {
                return;
            }
            Optional<Nomina> existente = a.admin().nomina().capturada(a.usuario(), s.id(), u.id(), sem);
            salario.setCentavos(existente.map(Nomina::salarioCentavos).orElse(0L));
            comida.setCentavos(existente.map(Nomina::comidaCentavos).orElse(0L));
        };

        if (nueva) {
            empleado.valueProperty().addListener((o, x, y) -> precargar.run());
            semana.valueProperty().addListener((o, x, y) -> precargar.run());
            if (!empleados.isEmpty()) {
                empleado.setValue(empleados.getFirst());
            }
            if (!semanas.isEmpty()) {
                semana.setValue(semanas.getFirst());
            }
        } else {
            empleados.stream().filter(u -> u.id().equals(n.usuarioId())).findFirst().ifPresent(empleado::setValue);
            semana.setValue(n.semanaInicio());
            empleado.setDisable(true);
            semana.setDisable(true);
            salario.setCentavos(n.salarioCentavos());
            comida.setCentavos(n.comidaCentavos());
        }

        d.setContenido(Ui.fila(Ui.campo("Empleado", empleado), Ui.campo("Semana", semana)),
                Ui.fila(Ui.campo("Salario", salario), Ui.campo("Comida", comida)));

        Runnable guardar = () -> {
            Usuario u = empleado.getValue();
            LocalDate sem = semana.getValue();
            if (a.ejecutar(nueva ? "Nómina capturada" : "Nómina actualizada", () -> a.admin().nomina().guardar(
                    a.usuario(), s.id(), u == null ? null : u.id(), sem, salario.centavosOCero(), comida.centavosOCero()))) {
                d.cerrar();
                cargar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(salario);
        a.dialogos().mostrar(d);
    }

    private static String capitalizar(String texto) {
        return texto.isEmpty() ? texto : texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
    }
}
