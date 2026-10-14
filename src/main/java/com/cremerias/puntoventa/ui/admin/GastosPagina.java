package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.CategoriaGasto;
import com.cremerias.puntoventa.model.Gasto;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.TipoGasto;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Gastos fijos y variables de cada sucursal (renta, servicios, nómina patronal, etc.). */
public class GastosPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MES = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-MX"));

    private ComboBox<Sucursal> sucursal;
    private ComboBox<YearMonth> mes;
    private Button nuevo;
    private TableView<Gasto> fijos;
    private TableView<Gasto> variables;
    private Label totalFijos;
    private Label totalVariables;
    private Label totalGeneral;

    public GastosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        nuevo = Ui.principal("Nuevo gasto", "mdi2p-plus");
        nuevo.setOnAction(e -> formulario(null));
        nuevo.setDisable(true);
        VBox pagina = Ui.pagina("Gastos", "Gastos fijos y variables de cada sucursal, para calcular la utilidad neta.",
                nuevo);

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

        fijos = tablaGastos("No hay gastos fijos en este mes.");
        totalFijos = new Label();
        totalFijos.getStyleClass().add("indicador-valor");
        variables = tablaGastos("No hay gastos variables en este mes.");
        totalVariables = new Label();
        totalVariables.getStyleClass().add("indicador-valor");
        totalGeneral = new Label();
        totalGeneral.getStyleClass().add("indicador-valor");

        VBox tarjetaFijos = Ui.tarjeta("Gastos fijos", fijos, new HBox(8, Ui.texto("Total:", "indicador-titulo"), totalFijos));
        VBox tarjetaVariables = Ui.tarjeta("Gastos variables", variables,
                new HBox(8, Ui.texto("Total:", "indicador-titulo"), totalVariables));

        pagina.getChildren().addAll(filtros, tarjetaFijos, tarjetaVariables,
                new HBox(8, Ui.texto("Total del mes:", "indicador-titulo"), totalGeneral));
        return pagina;
    }

    private TableView<Gasto> tablaGastos(String vacio) {
        TableView<Gasto> t = Ui.tabla(vacio);
        t.setPrefHeight(220);
        t.getColumns().setAll(List.of(
                Ui.texto("Fecha", 100, g -> FECHA.format(g.fecha().atZone(ZoneId.systemDefault()))),
                Ui.texto("Categoría", 160, g -> g.categoria().nombre()),
                Ui.nodo("Concepto", 220, g -> Ui.dosLineas(g.concepto(), g.nota() == null ? "" : g.nota())),
                Ui.dinero("Monto", 120, Gasto::montoCentavos),
                Ui.texto("Registró", 150, Gasto::usuario),
                Ui.nodo("", 90, g -> Ui.acciones(
                        Ui.accion("mdi2p-pencil-outline", "Editar", () -> formulario(g)),
                        Ui.accion("mdi2t-trash-can-outline", "Eliminar", () -> eliminar(g), "danger")))));
        return t;
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
        nuevo.setDisable(sucursal.getValue() == null);
        if (sucursal.getValue() == null || mes.getValue() == null) {
            return;
        }
        List<Gasto> lista = a.admin().gastos().listar(a.usuario(), sucursal.getValue().id(), mes.getValue());
        List<Gasto> deFijos = lista.stream().filter(g -> g.tipo() == TipoGasto.FIJO).collect(Collectors.toList());
        List<Gasto> deVariables = lista.stream().filter(g -> g.tipo() == TipoGasto.VARIABLE).collect(Collectors.toList());
        fijos.setItems(FXCollections.observableArrayList(deFijos));
        variables.setItems(FXCollections.observableArrayList(deVariables));
        long sumaFijos = deFijos.stream().mapToLong(Gasto::montoCentavos).sum();
        long sumaVariables = deVariables.stream().mapToLong(Gasto::montoCentavos).sum();
        totalFijos.setText(Dinero.formatear(sumaFijos));
        totalVariables.setText(Dinero.formatear(sumaVariables));
        totalGeneral.setText(Dinero.formatear(sumaFijos + sumaVariables));
    }

    private void eliminar(Gasto g) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar este gasto?",
                g.categoria().nombre() + ": " + g.concepto() + ", " + Dinero.formatear(g.montoCentavos())
                        + ". Queda registrado en el historial.", "Eliminar", true, () -> {
                    a.ejecutar("Gasto eliminado", () -> a.admin().gastos().eliminar(g, a.usuario()));
                    cargar();
                });
    }

    private void formulario(Gasto g) {
        boolean nuevoGasto = g == null;
        Sucursal s = sucursal.getValue();
        if (s == null) {
            return;
        }
        Dialogo d = new Dialogo(nuevoGasto ? "Nuevo gasto" : "Editar gasto", s.nombre(), "mdi2c-cash-minus",
                Dialogo.Tono.ACENTO);

        DatePicker fecha = new DatePicker(nuevoGasto ? LocalDate.now() : g.fecha().atZone(ZoneId.systemDefault()).toLocalDate());
        fecha.setEditable(false);
        ComboBox<CategoriaGasto> categoria = new ComboBox<>(FXCollections.observableArrayList(CategoriaGasto.values()));
        categoria.setValue(nuevoGasto ? CategoriaGasto.RENTA : g.categoria());
        ComboBox<TipoGasto> tipo = new ComboBox<>(FXCollections.observableArrayList(TipoGasto.values()));
        tipo.setValue(nuevoGasto ? TipoGasto.FIJO : g.tipo());
        TextField concepto = new TextField(nuevoGasto ? "" : g.concepto());
        concepto.setPromptText("Ej. Renta de octubre");
        CampoDinero monto = new CampoDinero();
        if (!nuevoGasto) {
            monto.setCentavos(g.montoCentavos());
        }
        TextField nota = new TextField(nuevoGasto || g.nota() == null ? "" : g.nota());
        nota.setPromptText("Opcional");

        d.setContenido(Ui.fila(Ui.campo("Fecha", fecha), Ui.campo("Tipo", tipo)),
                Ui.campo("Categoría", categoria), Ui.campo("Concepto", concepto), Ui.campo("Monto", monto),
                Ui.campo("Nota", nota));

        Runnable guardar = () -> {
            Instant instante = fecha.getValue() == null ? null
                    : fecha.getValue().atStartOfDay(ZoneId.systemDefault()).toInstant();
            Gasto aGuardar = new Gasto(nuevoGasto ? null : g.id(), nuevoGasto ? null : g.folio(), s.id(), instante,
                    categoria.getValue(), tipo.getValue(), concepto.getText(), monto.centavosOCero(),
                    nota.getText().isBlank() ? null : nota.getText(), nuevoGasto ? null : g.usuarioId(),
                    nuevoGasto ? null : g.usuario());
            if (a.ejecutar(nuevoGasto ? "Gasto creado" : "Gasto actualizado",
                    () -> a.admin().gastos().guardar(aGuardar, a.usuario()))) {
                d.cerrar();
                cargar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar gasto", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(concepto);
        a.dialogos().mostrar(d);
    }

    private static String capitalizar(String texto) {
        return texto.isEmpty() ? texto : texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
    }
}
