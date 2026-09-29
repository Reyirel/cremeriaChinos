package com.cremerias.puntoventa.ui.componentes;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.IndicadoresService;
import com.cremerias.puntoventa.ui.admin.AdminContexto;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Venta, costo, utilidad y avance de meta mensual de una sucursal, calculados directo de las
 * operaciones del POS. La usan tanto el panel de Administrador (con selector de sucursal) como
 * el de Supervisor (fija a la sucursal del usuario, sin selector).
 */
public class IndicadoresVista {

    private static final String[] MESES = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};

    private enum Modo { ANIO, MES, RANGO }

    private final IndicadoresService servicio;
    private final Usuario usuario;
    private final Supplier<List<Sucursal>> sucursalesSeleccionables;
    private final Function<String, String> nombreSucursal;
    private final Dialogos dialogos;
    private final Avisos avisos;

    private Modo modo = Modo.MES;
    private LocalDate desdeActual;
    private LocalDate hastaActual;

    private VBox raiz;
    private ComboBox<Sucursal> comboSucursal;
    private ToggleGroup grupoModo;
    private ToggleButton botonAnio;
    private ToggleButton botonMes;
    private ToggleButton botonRango;
    private ComboBox<Integer> comboAnio;
    private ComboBox<String> comboMes;
    private VBox campoMes;
    private HBox filaAnioMes;
    private DatePicker campoDesde;
    private DatePicker campoHasta;
    private HBox filaRango;

    private HBox tarjetasKpi;
    private VBox cuerpoMeta;
    private HBox tarjetasDiarias;

    public IndicadoresVista(IndicadoresService servicio, Usuario usuario,
                            Supplier<List<Sucursal>> sucursalesSeleccionables, Function<String, String> nombreSucursal,
                            Dialogos dialogos, Avisos avisos) {
        this.servicio = servicio;
        this.usuario = usuario;
        this.sucursalesSeleccionables = sucursalesSeleccionables;
        this.nombreSucursal = nombreSucursal;
        this.dialogos = dialogos;
        this.avisos = avisos;
    }

    public Node nodo() {
        if (raiz == null) {
            raiz = construir();
        }
        return raiz;
    }

    /** Recarga sucursales (si aplica) y los indicadores del periodo actual. */
    public void actualizar() {
        nodo();
        if (sucursalesSeleccionables != null) {
            Sucursal actual = comboSucursal.getValue();
            List<Sucursal> lista = sucursalesSeleccionables.get();
            comboSucursal.setItems(FXCollections.observableArrayList(lista));
            if (actual != null) {
                lista.stream().filter(s -> s.id().equals(actual.id())).findFirst().ifPresent(comboSucursal::setValue);
            } else if (!lista.isEmpty()) {
                comboSucursal.setValue(lista.getFirst());
            }
        }
        cargar();
    }

    private VBox construir() {
        VBox pagina = Ui.pagina("Indicadores", sucursalesSeleccionables != null
                ? "Venta, costo, utilidad y avance de meta, calculados de las ventas ya registradas."
                : "Sucursal: " + nombreSucursal.apply(usuario.sucursalId()));

        HBox filtros = construirFiltros();

        tarjetasKpi = new HBox(14);
        cuerpoMeta = new VBox(14);
        VBox tarjetaMeta = Ui.tarjeta("Meta mensual", cuerpoMeta);
        tarjetasDiarias = new HBox(14);

        pagina.getChildren().addAll(filtros, tarjetasKpi, tarjetaMeta, tarjetasDiarias);
        return pagina;
    }

    private HBox construirFiltros() {
        LocalDate hoy = LocalDate.now();

        botonAnio = new ToggleButton("Año");
        botonMes = new ToggleButton("Mes");
        botonRango = new ToggleButton("Rango personalizado");
        grupoModo = new ToggleGroup();
        botonAnio.setToggleGroup(grupoModo);
        botonMes.setToggleGroup(grupoModo);
        botonRango.setToggleGroup(grupoModo);
        botonMes.setSelected(true);
        HBox filaModo = new HBox(6, botonAnio, botonMes, botonRango);
        filaModo.getStyleClass().add("indicadores-modo");
        grupoModo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null) {
                if (antes != null) {
                    antes.setSelected(true);
                }
                return;
            }
            modo = ahora == botonAnio ? Modo.ANIO : ahora == botonMes ? Modo.MES : Modo.RANGO;
            actualizarVisibilidadFiltros();
            cargar();
        });

        comboAnio = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(hoy.getYear() - 4, hoy.getYear()).boxed()
                        .sorted(Comparator.reverseOrder()).toList()));
        comboAnio.setValue(hoy.getYear());
        comboMes = new ComboBox<>(FXCollections.observableArrayList(MESES));
        comboMes.getSelectionModel().select(hoy.getMonthValue() - 1);
        comboAnio.valueProperty().addListener((o, x, y) -> cargar());
        comboMes.valueProperty().addListener((o, x, y) -> cargar());
        campoMes = Ui.campo("Mes", comboMes);
        filaAnioMes = new HBox(10, Ui.campo("Año", comboAnio), campoMes);

        campoDesde = new DatePicker(hoy.withDayOfMonth(1));
        campoHasta = new DatePicker(hoy);
        campoDesde.setEditable(false);
        campoHasta.setEditable(false);
        campoDesde.valueProperty().addListener((o, x, y) -> cargar());
        campoHasta.valueProperty().addListener((o, x, y) -> cargar());
        filaRango = new HBox(10, Ui.campo("Desde", campoDesde), Ui.campo("Hasta", campoHasta));

        Button accesoHoy = accesoRapido("Hoy", () -> fijarRango(Modo.RANGO, hoy, hoy));
        Button accesoSemana = accesoRapido("Esta semana",
                () -> fijarRango(Modo.RANGO, hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), hoy));
        Button accesoMes = accesoRapido("Este mes", () -> fijarMes(hoy.getYear(), hoy.getMonthValue()));
        Button accesoMesAnterior = accesoRapido("Mes anterior", () -> {
            YearMonth anterior = YearMonth.from(hoy).minusMonths(1);
            fijarMes(anterior.getYear(), anterior.getMonthValue());
        });
        Button accesoAnio = accesoRapido("Año actual", () -> fijarRango(Modo.ANIO, null, null));
        HBox filaAccesos = new HBox(8, accesoHoy, accesoSemana, accesoMes, accesoMesAnterior, accesoAnio);
        filaAccesos.setAlignment(Pos.CENTER_LEFT);

        VBox columnaFiltros = new VBox(12, filaModo, filaAnioMes, filaRango, filaAccesos);

        HBox filtros = new HBox(20, columnaFiltros);
        filtros.getStyleClass().add("indicadores-filtros");
        filtros.setAlignment(Pos.CENTER_LEFT);

        if (sucursalesSeleccionables != null) {
            comboSucursal = new ComboBox<>();
            comboSucursal.setPromptText("Elige la sucursal");
            comboSucursal.valueProperty().addListener((o, x, y) -> cargar());
            HBox.setHgrow(columnaFiltros, Priority.ALWAYS);
            filtros.getChildren().addFirst(Ui.campo("Sucursal", comboSucursal));
        } else {
            Label fija = new Label(nombreSucursal.apply(usuario.sucursalId()));
            fija.getStyleClass().add("indicadores-sucursal-fija");
            HBox.setHgrow(columnaFiltros, Priority.ALWAYS);
            filtros.getChildren().addFirst(Ui.campo("Sucursal", fija));
        }

        actualizarVisibilidadFiltros();
        return filtros;
    }

    private Button accesoRapido(String texto, Runnable accion) {
        Button b = new Button(texto);
        b.getStyleClass().add("chip-rapido");
        b.setFocusTraversable(false);
        b.setOnAction(e -> accion.run());
        return b;
    }

    private void actualizarVisibilidadFiltros() {
        filaAnioMes.setVisible(modo != Modo.RANGO);
        filaAnioMes.setManaged(modo != Modo.RANGO);
        campoMes.setVisible(modo == Modo.MES);
        campoMes.setManaged(modo == Modo.MES);
        filaRango.setVisible(modo == Modo.RANGO);
        filaRango.setManaged(modo == Modo.RANGO);
    }

    /** Usado por los accesos rápidos de mes (Este mes / Mes anterior). */
    private void fijarMes(int anio, int mes) {
        botonMes.setSelected(true);
        modo = Modo.MES;
        comboAnio.setValue(anio);
        comboMes.getSelectionModel().select(mes - 1);
        actualizarVisibilidadFiltros();
        cargar();
    }

    /** Usado por Hoy/Esta semana (rango) y Año actual (sin fechas: se calculan en {@link #calcularRango()}). */
    private void fijarRango(Modo nuevoModo, LocalDate desde, LocalDate hasta) {
        modo = nuevoModo;
        (nuevoModo == Modo.ANIO ? botonAnio : botonRango).setSelected(true);
        if (nuevoModo == Modo.ANIO) {
            comboAnio.setValue(LocalDate.now().getYear());
        } else {
            campoDesde.setValue(desde);
            campoHasta.setValue(hasta);
        }
        actualizarVisibilidadFiltros();
        cargar();
    }

    private LocalDate[] calcularRango() {
        LocalDate hoy = LocalDate.now();
        return switch (modo) {
            case ANIO -> {
                int anio = comboAnio.getValue();
                LocalDate primero = LocalDate.of(anio, 1, 1);
                LocalDate ultimo = anio == hoy.getYear() ? hoy : LocalDate.of(anio, 12, 31);
                yield new LocalDate[]{primero, ultimo};
            }
            case MES -> {
                int anio = comboAnio.getValue();
                int mes = comboMes.getSelectionModel().getSelectedIndex() + 1;
                LocalDate primero = LocalDate.of(anio, mes, 1);
                LocalDate ultimoDelMes = primero.withDayOfMonth(primero.lengthOfMonth());
                LocalDate ultimo = (anio == hoy.getYear() && mes == hoy.getMonthValue() && ultimoDelMes.isAfter(hoy))
                        ? hoy : ultimoDelMes;
                yield new LocalDate[]{primero, ultimo};
            }
            case RANGO -> new LocalDate[]{campoDesde.getValue(), campoHasta.getValue()};
        };
    }

    private String sucursalActual() {
        return sucursalesSeleccionables != null
                ? (comboSucursal.getValue() == null ? null : comboSucursal.getValue().id())
                : usuario.sucursalId();
    }

    private void cargar() {
        if (raiz == null) {
            return;
        }
        String sucursalId = sucursalActual();
        LocalDate[] rango = calcularRango();
        if (sucursalId == null || rango[0] == null || rango[1] == null || rango[0].isAfter(rango[1])) {
            return;
        }
        desdeActual = rango[0];
        hastaActual = rango[1];
        try {
            IndicadoresService.Resumen r = servicio.calcular(usuario, sucursalId, desdeActual, hastaActual);
            pintar(r);
        } catch (RuntimeException e) {
            avisos.error(AdminContexto.mensaje(e));
        }
    }

    private void pintar(IndicadoresService.Resumen r) {
        String margen = formatearPorcentaje(r.margenPct());
        tarjetasKpi.getChildren().setAll(
                Ui.indicador("Venta total", Dinero.formatear(r.ventaNeta()), "mdi2c-cash-multiple"),
                Ui.indicador("Costo de mercancía vendida", Dinero.formatear(r.costoVenta()), "mdi2p-package-variant-closed"),
                Ui.indicador("Utilidad bruta", Dinero.formatear(r.utilidadBruta()), "mdi2t-trending-up"),
                Ui.indicador("Margen de utilidad", margen, "mdi2t-percent"));

        cuerpoMeta.getChildren().clear();
        boolean periodoEsUnMes = desdeActual.getYear() == hastaActual.getYear()
                && desdeActual.getMonthValue() == hastaActual.getMonthValue();
        if (!periodoEsUnMes) {
            cuerpoMeta.getChildren().add(Ui.texto(
                    "La meta mensual solo aplica dentro de un mismo mes. Cambia el filtro a \"Mes\" para verla.",
                    "texto-ayuda"));
        } else {
            int anio = desdeActual.getYear();
            int mes = desdeActual.getMonthValue();
            if (r.metaMensual().isEmpty()) {
                VBox sinMeta = new VBox(10, Ui.texto(
                        "Aún no se ha capturado la meta de " + MESES[mes - 1] + " " + anio + ".", "texto-ayuda"));
                if (usuario.rol() == Rol.ADMINISTRADOR) {
                    Button capturar = Ui.principal("Capturar meta", "mdi2t-target");
                    capturar.setOnAction(e -> capturarMeta(anio, mes, 0));
                    sinMeta.getChildren().add(capturar);
                }
                cuerpoMeta.getChildren().add(sinMeta);
            } else {
                cuerpoMeta.getChildren().add(pintarMeta(r, anio, mes));
            }
        }

        tarjetasDiarias.getChildren().setAll(
                Ui.indicador("Promedio diario de venta", Dinero.formatear(r.promedioDiario()), "mdi2c-calendar-month"),
                Ui.indicador("Meta diaria", r.metaDiaria().map(Dinero::formatear).orElse("—"), "mdi2c-calendar-range"),
                Ui.indicador("Diferencia contra meta", r.diferenciaVsMeta().map(Dinero::formatear).orElse("—"),
                        "mdi2f-flag-checkered"));
    }

    private Node pintarMeta(IndicadoresService.Resumen r, int anio, int mes) {
        double cumplimiento = r.cumplimientoPct().orElse(0.0);
        String estado = cumplimiento >= 100 ? "exito" : cumplimiento >= 80 ? "advertencia" : "peligro";
        String textoEstado = cumplimiento >= 100 ? "Meta alcanzada"
                : cumplimiento >= 80 ? "Cerca de la meta" : "Debajo de la meta";

        HBox montos = new HBox(24,
                montoMeta("Venta actual", Dinero.formatear(r.ventaNeta())),
                montoMeta("Meta", Dinero.formatear(r.metaMensual().orElse(0L))),
                montoMeta("Alcanzado", formatearPorcentaje(cumplimiento)),
                montoMeta("Faltante", Dinero.formatear(r.faltanteMeta())));

        ProgressBar barra = new ProgressBar(Math.max(0, Math.min(1.0, cumplimiento / 100.0)));
        barra.getStyleClass().addAll("barra-meta", estado);
        barra.setMaxWidth(Double.MAX_VALUE);
        Label etiquetaEstado = new Label(formatearPorcentaje(cumplimiento) + " de la meta mensual · " + textoEstado);
        etiquetaEstado.getStyleClass().addAll("indicadores-meta-estado", estado);

        VBox contenido = new VBox(10, montos, barra, etiquetaEstado);
        if (usuario.rol() == Rol.ADMINISTRADOR) {
            Button editar = Ui.secundario("Editar meta", "mdi2p-pencil-outline");
            editar.setOnAction(e -> capturarMeta(anio, mes, r.metaMensual().orElse(0L)));
            HBox pie = new HBox(editar);
            contenido.getChildren().add(pie);
        }
        return contenido;
    }

    private VBox montoMeta(String titulo, String valor) {
        Label t = Ui.texto(titulo, "indicador-titulo");
        Label v = new Label(valor);
        v.getStyleClass().add("indicador-valor");
        return new VBox(4, t, v);
    }

    private static String formatearPorcentaje(double valor) {
        return String.format(Locale.US, "%.1f%%", valor);
    }

    private void capturarMeta(int anio, int mes, long actualCentavos) {
        String sucursalId = sucursalActual();
        if (sucursalId == null) {
            return;
        }
        Dialogo d = new Dialogo("Meta de " + MESES[mes - 1] + " " + anio,
                sucursalesSeleccionables != null && comboSucursal.getValue() != null
                        ? comboSucursal.getValue().nombre() : nombreSucursal.apply(sucursalId),
                "mdi2t-target", Dialogo.Tono.ACENTO);
        CampoDinero campo = new CampoDinero();
        if (actualCentavos > 0) {
            campo.setCentavos(actualCentavos);
        }
        d.setContenido(Ui.campo("Meta mensual de venta", campo));
        Runnable guardar = () -> {
            try {
                servicio.guardarMeta(usuario, sucursalId, anio, mes, campo.centavosOCero());
                d.cerrar();
                avisos.exito("Meta actualizada.");
                cargar();
            } catch (RuntimeException ex) {
                avisos.error(AdminContexto.mensaje(ex));
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar meta", "mdi2c-check", guardar, "success");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(campo);
        dialogos.mostrar(d);
    }
}
