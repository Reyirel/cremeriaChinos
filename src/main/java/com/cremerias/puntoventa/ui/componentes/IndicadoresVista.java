package com.cremerias.puntoventa.ui.componentes;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.IndicadoresRepository;
import com.cremerias.puntoventa.service.IndicadoresService;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Indicadores de una sucursal calculados directo de las operaciones del POS: venta, utilidad,
 * margen y avance de la meta mensual, su evolución por día y, debajo, las métricas de detalle.
 * La usan el Administrador (con selector de sucursal y comparación entre sucursales) y el
 * Supervisor (fija a su sucursal; el servicio también lo valida).
 *
 * <p>Las fórmulas son las de {@link IndicadoresService}; aquí solo se presentan. No se calculan
 * comparaciones contra otros periodos.
 */
public class IndicadoresVista {

    private static final Locale ES = Locale.forLanguageTag("es-MX");
    private static final String[] MESES = {"Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};
    private static final DateTimeFormatter DIA_LARGO = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES);
    /** Con más días que esto la gráfica agrupa por mes. */
    private static final int DIAS_POR_DIA = 62;

    private enum Modo { MES, ANIO, RANGO }

    /** Todo lo que se pinta, calculado fuera del hilo de la interfaz. */
    private record Datos(String sucursalId, LocalDate desde, LocalDate hasta, IndicadoresService.Resumen resumen,
                         IndicadoresService.VentasDetalle ventas, IndicadoresService.KilosResumen kilos,
                         IndicadoresService.KpisResumen kpis, IndicadoresService.InventarioResumen inventario,
                         IndicadoresService.UtilidadResumen utilidad, List<IndicadoresService.VentaDia> dias) {
    }

    private final IndicadoresService servicio;
    private final Usuario usuario;
    private final Supplier<List<Sucursal>> sucursalesSeleccionables;
    private final Function<String, String> nombreSucursal;
    private final Dialogos dialogos;
    private final Avisos avisos;
    private final boolean esAdmin;
    private final Cargador carga = new Cargador();
    private final Cargador cargaConsolidado = new Cargador();

    private ScrollPane raiz;
    private ComboBox<Sucursal> comboSucursal;
    private Segmentado<Modo> modo;
    private ComboBox<Integer> comboAnio;
    private ComboBox<String> comboMes;
    private DatePicker campoDesde;
    private DatePicker campoHasta;
    private Label periodoTexto;
    private Label actualizando;
    private VBox contenido;
    private StackPane zonaContenido;
    private VBox estadoCarga;
    private boolean cargado;

    private Tarjeta tarjetaVenta;
    private Tarjeta tarjetaUtilidad;
    private Tarjeta tarjetaMargen;
    private Tarjeta tarjetaMeta;
    private GraficaVentas grafica;
    private StackPane zonaGrafica;
    private Label tituloGrafica;
    private VBox cuerpoMeta;
    private Grupo grupoVentas;
    private Grupo grupoKilos;
    private VBox marcas;
    private Grupo grupoUtilidad;
    private Grupo grupoInventario;
    private TablaDatos<IndicadoresService.Kpi> tablaKpis;
    private Label avanceTotal;
    private TablaDatos<IndicadoresService.ConsolidadoFila> tablaConsolidado;
    private BarChart<Number, String> graficaSucursales;
    private StackPane zonaComparacion;

    public IndicadoresVista(IndicadoresService servicio, Usuario usuario,
                            Supplier<List<Sucursal>> sucursalesSeleccionables, Function<String, String> nombreSucursal,
                            Dialogos dialogos, Avisos avisos) {
        this.servicio = servicio;
        this.usuario = usuario;
        this.sucursalesSeleccionables = sucursalesSeleccionables;
        this.nombreSucursal = nombreSucursal;
        this.dialogos = dialogos;
        this.avisos = avisos;
        this.esAdmin = usuario.rol() == Rol.ADMINISTRADOR;
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
            Cargador.ejecutar(sucursalesSeleccionables::get, lista -> {
                Sucursal actual = comboSucursal.getValue();
                comboSucursal.setItems(FXCollections.observableArrayList(lista));
                Sucursal elegida = actual == null ? (lista.isEmpty() ? null : lista.getFirst())
                        : lista.stream().filter(s -> s.id().equals(actual.id())).findFirst().orElse(null);
                if (comboSucursal.getValue() == elegida) {
                    cargar();
                } else {
                    comboSucursal.setValue(elegida);
                }
            }, e -> mostrarError(Errores.mensaje(e)));
        } else {
            cargar();
        }
    }

    // ------------------------------------------------------------------ construcción

    private ScrollPane construir() {
        EncabezadoPagina encabezado = new EncabezadoPagina("Indicadores", sucursalesSeleccionables != null
                ? "Venta, utilidad y avance de meta de cada sucursal, calculados de las ventas registradas."
                : nombreSucursal.apply(usuario.sucursalId()) + " · calculados de las ventas registradas.");
        VBox pagina = new VBox(Diseno.ESPACIO_L, encabezado, construirFiltros());
        pagina.getStyleClass().add("pagina");

        contenido = new VBox(Diseno.ESPACIO_L, construirTarjetas(), construirEvolucion(), construirDetalle());
        if (esAdmin && sucursalesSeleccionables != null) {
            contenido.getChildren().add(construirComparacion());
        }
        estadoCarga = EstadoVacio.cargando("Calculando indicadores…");
        estadoCarga.setMinHeight(320);
        zonaContenido = new StackPane(estadoCarga);
        pagina.getChildren().add(zonaContenido);

        ScrollPane scroll = new ScrollPane(pagina);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("pagina-desplazable");
        return scroll;
    }

    private Node construirFiltros() {
        LocalDate hoy = LocalDate.now();
        modo = new Segmentado<Modo>().opcion(Modo.MES, "Mes").opcion(Modo.ANIO, "Año").opcion(Modo.RANGO, "Rango");
        comboAnio = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(hoy.getYear() - 4, hoy.getYear()).boxed().sorted(Comparator.reverseOrder()).toList()));
        comboAnio.setValue(hoy.getYear());
        comboAnio.setAccessibleText("Año");
        comboMes = new ComboBox<>(FXCollections.observableArrayList(MESES));
        comboMes.getSelectionModel().select(hoy.getMonthValue() - 1);
        comboMes.setAccessibleText("Mes");
        campoDesde = new DatePicker(hoy.withDayOfMonth(1));
        campoHasta = new DatePicker(hoy);
        for (DatePicker p : List.of(campoDesde, campoHasta)) {
            p.setEditable(false);
            p.setPrefWidth(150);
        }
        Label al = new Label("al");
        al.getStyleClass().add("etiqueta-filtro");
        HBox rango = new HBox(8, campoDesde, al, campoHasta);
        rango.setAlignment(Pos.CENTER_LEFT);
        comboMes.managedProperty().bind(comboMes.visibleProperty());
        comboAnio.managedProperty().bind(comboAnio.visibleProperty());
        rango.managedProperty().bind(rango.visibleProperty());

        Button hoyBtn = acceso("Hoy", () -> fijarRango(hoy, hoy));
        Button semana = acceso("Esta semana", () -> fijarRango(hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), hoy));
        Button anterior = acceso("Mes anterior", () -> {
            YearMonth m = YearMonth.from(hoy).minusMonths(1);
            fijarMes(m.getYear(), m.getMonthValue());
        });
        Button esteMes = acceso("Este mes", () -> fijarMes(hoy.getYear(), hoy.getMonthValue()));

        HBox fila = new HBox(8, modo, comboMes, comboAnio, rango, new Region(), hoyBtn, semana, esteMes, anterior);
        HBox.setHgrow(fila.getChildren().get(4), Priority.ALWAYS);
        fila.setAlignment(Pos.CENTER_LEFT);

        if (sucursalesSeleccionables != null) {
            comboSucursal = new ComboBox<>();
            comboSucursal.setPromptText("Elige la sucursal");
            comboSucursal.setAccessibleText("Sucursal");
            comboSucursal.setConverter(new StringConverter<>() {
                @Override
                public String toString(Sucursal s) {
                    return s == null ? "" : s.nombre() + (s.codigo() == null || s.codigo().isBlank() ? "" : " (" + s.codigo() + ")");
                }

                @Override
                public Sucursal fromString(String t) {
                    return comboSucursal.getValue();
                }
            });
            comboSucursal.setPrefWidth(250);
            comboSucursal.valueProperty().addListener((o, x, y) -> cargar());
            fila.getChildren().addFirst(comboSucursal);
        }

        periodoTexto = new Label();
        periodoTexto.getStyleClass().add("nota-periodo");
        periodoTexto.setGraphic(new FontIcon("mdi2c-calendar-range"));
        periodoTexto.setWrapText(true);
        actualizando = new Label("Actualizando…");
        actualizando.getStyleClass().add("nota-periodo");
        actualizando.setVisible(false);
        HBox nota = new HBox(12, periodoTexto, actualizando);
        nota.setAlignment(Pos.CENTER_LEFT);

        modo.valorProperty().addListener((o, x, y) -> {
            actualizarControles();
            cargar();
        });
        comboAnio.valueProperty().addListener((o, x, y) -> cargar());
        comboMes.valueProperty().addListener((o, x, y) -> cargar());
        campoDesde.valueProperty().addListener((o, x, y) -> cargar());
        campoHasta.valueProperty().addListener((o, x, y) -> cargar());
        actualizarControles();

        VBox filtros = new VBox(10, fila, nota);
        filtros.getStyleClass().add("panel");
        filtros.setPadding(new Insets(12, 16, 12, 16));
        return filtros;
    }

    private Button acceso(String texto, Runnable accion) {
        Button b = new Button(texto);
        b.getStyleClass().addAll("flat", "small");
        b.setOnAction(e -> accion.run());
        return b;
    }

    private void actualizarControles() {
        Modo m = modo.getValor();
        comboMes.setVisible(m == Modo.MES);
        comboAnio.setVisible(m != Modo.RANGO);
        campoDesde.getParent().setVisible(m == Modo.RANGO);
    }

    private void fijarMes(int anio, int mes) {
        comboAnio.setValue(anio);
        comboMes.getSelectionModel().select(mes - 1);
        modo.setValor(Modo.MES);
        cargar();
    }

    private void fijarRango(LocalDate desde, LocalDate hasta) {
        campoDesde.setValue(desde);
        campoHasta.setValue(hasta);
        modo.setValor(Modo.RANGO);
        cargar();
    }

    private Node construirTarjetas() {
        tarjetaVenta = new Tarjeta("Venta neta").principal();
        tarjetaUtilidad = new Tarjeta("Utilidad bruta");
        tarjetaMargen = new Tarjeta("Margen bruto");
        tarjetaMeta = new Tarjeta("Avance de la meta del mes");
        tarjetaMeta.conBarra();
        HBox fila = new HBox(Diseno.ESPACIO_L, tarjetaVenta, tarjetaUtilidad, tarjetaMargen, tarjetaMeta);
        for (Node n : fila.getChildren()) {
            HBox.setHgrow(n, Priority.ALWAYS);
        }
        return fila;
    }

    private Node construirEvolucion() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return dineroCorto(n.longValue());
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        grafica = new GraficaVentas(x, y);
        grafica.setPrefHeight(300);
        grafica.setMinHeight(240);
        tituloGrafica = new Label("Venta por día");
        tituloGrafica.getStyleClass().add("panel-titulo");
        Region muestraVenta = new Region();
        muestraVenta.getStyleClass().addAll("leyenda-muestra", "serie-venta");
        Region muestraMeta = new Region();
        muestraMeta.getStyleClass().addAll("leyenda-muestra", "serie-meta");
        Label etiquetaVenta = new Label("Venta");
        Label etiquetaMeta = new Label("Meta diaria");
        etiquetaVenta.getStyleClass().add("texto-secundario");
        etiquetaMeta.getStyleClass().add("texto-secundario");
        HBox leyendaMeta = new HBox(6, muestraMeta, etiquetaMeta);
        leyendaMeta.setAlignment(Pos.CENTER_LEFT);
        leyendaMeta.visibleProperty().bind(grafica.conMetaProperty());
        leyendaMeta.managedProperty().bind(leyendaMeta.visibleProperty());
        HBox leyenda = new HBox(16, new HBox(6, muestraVenta, etiquetaVenta), leyendaMeta);
        leyenda.getStyleClass().add("leyenda-grafica");
        HBox encabezado = new HBox(12, tituloGrafica, new Region(), leyenda);
        HBox.setHgrow(encabezado.getChildren().get(1), Priority.ALWAYS);
        encabezado.setAlignment(Pos.CENTER_LEFT);
        encabezado.getStyleClass().add("panel-encabezado");
        zonaGrafica = new StackPane(grafica);
        zonaGrafica.setPadding(new Insets(0, 12, 8, 4));
        VBox panelGrafica = new VBox(encabezado, zonaGrafica);
        panelGrafica.getStyleClass().add("panel");
        HBox.setHgrow(panelGrafica, Priority.ALWAYS);
        panelGrafica.setMinWidth(0);

        cuerpoMeta = new VBox(10);
        VBox panelMeta = new VBox(cabeceraPanel("Meta del mes"), cuerpoMeta);
        cuerpoMeta.setPadding(new Insets(0, 16, 16, 16));
        panelMeta.getStyleClass().add("panel");
        panelMeta.setPrefWidth(320);
        panelMeta.setMinWidth(280);
        panelMeta.setMaxWidth(340);
        return new HBox(Diseno.ESPACIO_L, panelGrafica, panelMeta);
    }

    private static HBox cabeceraPanel(String titulo) {
        Label t = new Label(titulo);
        t.getStyleClass().add("panel-titulo");
        HBox h = new HBox(t);
        h.getStyleClass().add("panel-encabezado");
        return h;
    }

    private Node construirDetalle() {
        grupoVentas = new Grupo("Tickets y clientes");
        grupoVentas.agregar("tickets", "Número de tickets");
        grupoVentas.agregar("ticket", "Ticket promedio");
        grupoVentas.agregar("articulos", "Artículos por ticket");
        grupoVentas.agregar("articulo", "Valor promedio del artículo");
        grupoVentas.agregar("clientes", "Clientes por día");
        grupoVentas.agregar("empleado", "Clientes por empleado al día");
        grupoVentas.agregar("mayor", "Horas de mayor venta");
        grupoVentas.agregar("menor", "Horas de menor venta");

        grupoKilos = new Grupo("Kilos vendidos");
        grupoKilos.agregar("kilos", "Kilos vendidos (gramaje neto)");
        grupoKilos.agregar("meta", "Meta de kilos del mes");
        grupoKilos.agregar("diferencia", "Diferencia contra la meta");
        marcas = new VBox(6);
        grupoKilos.extra(marcas);

        grupoInventario = new Grupo("Inventario y crédito");
        grupoInventario.agregar("inicial", "Inventario inicial (al costo)");
        grupoInventario.agregar("final", "Inventario final (al costo)");
        grupoInventario.agregar("promedio", "Inventario promedio");
        grupoInventario.agregar("dias", "Días de inventario");
        grupoInventario.agregar("credito", "Saldo de crédito con el almacén");

        tablaKpis = new TablaDatos<>(Diseno.Densidad.COMPACTA);
        tablaKpis.texto("KPI", IndicadoresService.Kpi::nombre).flexible(160);
        tablaKpis.texto("Logrado", IndicadoresService.Kpi::logrado).ancho(130).numero();
        tablaKpis.texto("Meta", IndicadoresService.Kpi::meta).ancho(130).numero();
        tablaKpis.formato("Avance", k -> k.avancePct().orElse(-1.0), (k, v) -> v < 0 ? "Sin meta" : porcentaje(v)).numero().ancho(110);
        tablaKpis.numero("Peso", IndicadoresService.Kpi::ponderacion, v -> v + "%").ancho(80);
        tablaKpis.formato("Aporta", k -> k.avancePonderado().orElse(-1.0), (k, v) -> v < 0 ? "—" : porcentaje(v)).numero().ancho(100);
        tablaKpis.tabla().setPrefHeight(40 + 4 * 44 + 2);
        tablaKpis.tabla().setMinHeight(40 + 4 * 44 + 2);
        tablaKpis.setVacio(new EstadoVacio("mdi2c-chart-donut", "Sin KPIs para este periodo.", null).compacto());
        avanceTotal = new Label();
        avanceTotal.getStyleClass().add("panel-descripcion");
        VBox panelTabla = new VBox(tablaKpis.tabla());
        panelTabla.getStyleClass().add("panel-tabla");
        Diseno.recortarRedondeado(panelTabla, Diseno.RADIO_PANEL);
        VBox panelKpis = new VBox(Diseno.ESPACIO_S, cabeceraKpis(), panelTabla);

        GridPane grid = new GridPane(Diseno.ESPACIO_L, Diseno.ESPACIO_L);
        ColumnConstraints mitad = new ColumnConstraints();
        mitad.setPercentWidth(50);
        mitad.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(mitad, mitad);
        grid.add(grupoVentas, 0, 0);
        grid.add(grupoKilos, 1, 0);
        if (esAdmin) {
            grupoUtilidad = new Grupo("Utilidad neta");
            grupoUtilidad.agregar("bruta", "Utilidad bruta");
            grupoUtilidad.agregar("gastos", "Gastos del periodo");
            grupoUtilidad.agregar("nomina", "Nómina del periodo");
            grupoUtilidad.agregar("mermas", "Mermas (al costo)");
            grupoUtilidad.agregar("neta", "Utilidad neta");
            grupoUtilidad.agregar("margen", "Margen neto");
            grid.add(grupoUtilidad, 0, 1);
            grid.add(grupoInventario, 1, 1);
        } else {
            grid.add(grupoInventario, 0, 1, 2, 1);
        }
        grid.add(panelKpis, 0, 2, 2, 1);
        return grid;
    }

    private Node cabeceraKpis() {
        Label t = new Label("KPIs ponderados");
        t.getStyleClass().add("grupo-metricas-titulo");
        HBox h = new HBox(12, t, new Region(), avanceTotal);
        HBox.setHgrow(h.getChildren().get(1), Priority.ALWAYS);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private Node construirComparacion() {
        NumberAxis x = new NumberAxis();
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return dineroCorto(n.longValue());
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        CategoryAxis y = new CategoryAxis();
        graficaSucursales = new BarChart<>(x, y);
        graficaSucursales.setAnimated(false);
        graficaSucursales.setLegendVisible(false);
        graficaSucursales.getStyleClass().addAll("grafica", "barras-horizontales");
        graficaSucursales.setPrefHeight(220);
        graficaSucursales.setCategoryGap(14);

        tablaConsolidado = new TablaDatos<>(Diseno.Densidad.COMPACTA);
        tablaConsolidado.setClave(f -> f.sucursal().id());
        tablaConsolidado.dosLineas("Sucursal", f -> f.sucursal().nombre(), f -> f.sucursal().codigo()).flexible(170);
        tablaConsolidado.dinero("Venta", IndicadoresService.ConsolidadoFila::ventaNeta).ancho(120);
        tablaConsolidado.dinero("Costo", IndicadoresService.ConsolidadoFila::costoVenta).ancho(120);
        tablaConsolidado.dinero("Utilidad bruta", IndicadoresService.ConsolidadoFila::utilidadBruta).ancho(130);
        tablaConsolidado.dinero("Gastos", IndicadoresService.ConsolidadoFila::gastosCentavos).ancho(120);
        tablaConsolidado.dinero("Utilidad neta", IndicadoresService.ConsolidadoFila::utilidadNeta).ancho(130);
        tablaConsolidado.formato("Meta", f -> f.cumplimientoPct().orElse(-1.0), (f, v) -> v < 0 ? "—" : porcentaje(v))
                .numero().ancho(90).ayuda("Avance de la meta de venta del mes (solo si el periodo es un mes).");
        tablaConsolidado.formato("KPIs", f -> f.avanceTotalPonderado().orElse(-1.0), (f, v) -> v < 0 ? "—" : porcentaje(v))
                .numero().ancho(90).ayuda("Avance total ponderado de los KPIs.");
        tablaConsolidado.tabla().setPrefHeight(40 + 5 * 44 + 2);
        tablaConsolidado.setVacio(new EstadoVacio("mdi2s-storefront-outline", "No hay sucursales activas.", null).compacto());
        VBox panelTabla = new VBox(tablaConsolidado.tabla());
        panelTabla.getStyleClass().add("panel-tabla");
        Diseno.recortarRedondeado(panelTabla, Diseno.RADIO_PANEL);

        zonaComparacion = new StackPane(graficaSucursales);
        VBox panelGrafica = new VBox(cabeceraPanel("Venta neta por sucursal"), zonaComparacion);
        panelGrafica.getStyleClass().add("panel");
        zonaComparacion.setPadding(new Insets(0, 16, 8, 4));
        Label titulo = new Label("Comparación por sucursal");
        titulo.getStyleClass().add("seccion-titulo");
        Label nota = new Label("Mismo periodo para todas las sucursales activas; cada una con sus propias ventas, gastos y metas.");
        nota.getStyleClass().add("seccion-descripcion");
        return new VBox(Diseno.ESPACIO_S, new VBox(2, titulo, nota), panelGrafica, panelTabla);
    }

    // ------------------------------------------------------------------ carga

    private LocalDate[] calcularRango() {
        LocalDate hoy = LocalDate.now();
        return switch (modo.getValor()) {
            case ANIO -> {
                int anio = comboAnio.getValue();
                yield new LocalDate[]{LocalDate.of(anio, 1, 1), anio == hoy.getYear() ? hoy : LocalDate.of(anio, 12, 31)};
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
        if (raiz == null || comboAnio.getValue() == null) {
            return;
        }
        String sucursalId = sucursalActual();
        LocalDate[] rango = calcularRango();
        if (sucursalId == null || rango[0] == null || rango[1] == null) {
            return;
        }
        if (rango[0].isAfter(rango[1])) {
            periodoTexto.setText("El periodo no es válido: la fecha inicial es después de la final.");
            return;
        }
        LocalDate desde = rango[0];
        LocalDate hasta = rango[1];
        actualizando.setVisible(cargado);
        carga.cargar(() -> new Datos(sucursalId, desde, hasta,
                servicio.calcular(usuario, sucursalId, desde, hasta),
                servicio.calcularVentasDetalle(usuario, sucursalId, desde, hasta),
                servicio.calcularKilos(usuario, sucursalId, desde, hasta),
                servicio.calcularKpis(usuario, sucursalId, desde, hasta),
                servicio.calcularInventario(usuario, sucursalId, desde, hasta),
                esAdmin ? servicio.calcularUtilidad(usuario, sucursalId, desde, hasta) : null,
                servicio.ventaPorDia(usuario, sucursalId, desde, hasta)), d -> {
            actualizando.setVisible(false);
            cargado = true;
            zonaContenido.getChildren().setAll(contenido);
            pintar(d);
        }, e -> {
            actualizando.setVisible(false);
            if (cargado) {
                avisos.errorConAccion("No se pudieron actualizar los indicadores: " + Errores.mensaje(e), "Reintentar",
                        this::cargar);
            } else {
                mostrarError(Errores.mensaje(e));
            }
        });
        if (esAdmin && sucursalesSeleccionables != null) {
            cargarConsolidado(desde, hasta);
        }
    }

    private void mostrarError(String mensaje) {
        zonaContenido.getChildren().setAll(EstadoVacio.error("No se pudieron calcular los indicadores.", mensaje,
                this::cargar));
    }

    private void cargarConsolidado(LocalDate desde, LocalDate hasta) {
        tablaConsolidado.cargando("Calculando la comparación…");
        cargaConsolidado.cargar(() -> servicio.calcularConsolidado(usuario, sucursalesSeleccionables.get(), desde, hasta),
                filas -> {
                    tablaConsolidado.setDatos(filas);
                    boolean hayVentas = filas.stream().anyMatch(f -> f.ventaNeta() != 0);
                    if (!hayVentas) {
                        zonaComparacion.getChildren().setAll(new EstadoVacio("mdi2c-chart-bar",
                                "Sin ventas en ninguna sucursal en este periodo.", null).compacto());
                        return;
                    }
                    XYChart.Series<Number, String> serie = new XYChart.Series<>();
                    List<IndicadoresService.ConsolidadoFila> ordenadas = new ArrayList<>(filas);
                    ordenadas.sort(Comparator.comparingLong(IndicadoresService.ConsolidadoFila::ventaNeta));
                    for (IndicadoresService.ConsolidadoFila f : ordenadas) {
                        XYChart.Data<Number, String> dato = new XYChart.Data<>(f.ventaNeta() / 100.0, f.sucursal().nombre());
                        dato.nodeProperty().addListener((o, x, nodo) -> {
                            if (nodo != null) {
                                Tooltip.install(nodo, new Tooltip(f.sucursal().nombre() + ": " + Dinero.formatear(f.ventaNeta())));
                            }
                        });
                        serie.getData().add(dato);
                    }
                    graficaSucursales.getData().setAll(List.of(serie));
                    graficaSucursales.setPrefHeight(Math.max(160, 48 * filas.size() + 60));
                    zonaComparacion.getChildren().setAll(graficaSucursales);
                },
                e -> tablaConsolidado.error("No se pudo calcular la comparación.", Errores.mensaje(e), () -> cargarConsolidado(desde, hasta)));
    }

    // ------------------------------------------------------------------ pintar

    private void pintar(Datos d) {
        IndicadoresService.Resumen r = d.resumen();
        boolean sinVentas = d.ventas().tickets() == 0;
        periodoTexto.setText(textoPeriodo(d.desde(), d.hasta(), r.diasEnPeriodo())
                + ". Cuentan solo las ventas completadas; el costo es el real de cada lote vendido.");

        tarjetaVenta.valor(Dinero.formatear(r.ventaNeta()), sinVentas ? "Sin ventas registradas en el periodo"
                : Texto.plural(d.ventas().tickets(), "ticket", "tickets") + " · promedio diario " + Dinero.formatear(r.promedioDiario()));
        tarjetaUtilidad.valor(Dinero.formatear(r.utilidadBruta()), "Costo de lo vendido " + Dinero.formatear(r.costoVenta()));
        tarjetaMargen.valor(sinVentas ? "—" : porcentaje(r.margenPct()),
                sinVentas ? "Sin ventas: no hay margen que calcular" : "Utilidad bruta entre la venta neta");
        tarjetaMargen.sinDato(sinVentas);
        pintarMeta(d);
        pintarGrafica(d);

        IndicadoresService.VentasDetalle v = d.ventas();
        grupoVentas.valor("tickets", String.valueOf(v.tickets()));
        grupoVentas.valor("ticket", sinVentas ? "—" : Dinero.formatear(v.ticketPromedioCentavos()));
        grupoVentas.valor("articulos", sinVentas ? "—" : uno(v.articulosPorTicket()));
        grupoVentas.valor("articulo", sinVentas ? "—" : Dinero.formatear(v.valorPromedioArticuloCentavos()));
        grupoVentas.valor("clientes", uno(v.clientesPorDia()));
        grupoVentas.valor("empleado", v.clientesPorEmpleadoDiario().map(IndicadoresVista::uno).orElse("Sin empleados activos"));
        grupoVentas.valor("mayor", horas(v.horasMayores()));
        grupoVentas.valor("menor", horas(v.horasMenores()));

        IndicadoresService.KilosResumen k = d.kilos();
        grupoKilos.valor("kilos", Masa.formatear(k.kilosVendidosGramos()));
        grupoKilos.valor("meta", k.metaKilosGramos().map(Masa::formatear).orElse(mismoMes(d) ? "Sin capturar" : "Solo por mes"));
        grupoKilos.valor("diferencia", k.diferenciaGramos().map(g -> (g.signum() > 0 ? "+" : "") + Masa.formatear(g)).orElse("—"));
        marcas.getChildren().clear();
        if (!k.porMarca().isEmpty()) {
            Label t = new Label("Por marca");
            t.getStyleClass().add("detalle-seccion-titulo");
            List<Node> filas = new ArrayList<>();
            for (IndicadoresRepository.MarcaKilos m : k.porMarca().stream().limit(5).toList()) {
                filas.add(PanelDetalle.filaLista(m.marca() == null || m.marca().isBlank() ? "Sin marca" : m.marca(), null,
                        Masa.formatear(m.gramos()), null));
            }
            marcas.getChildren().addAll(t, PanelDetalle.lista(filas));
            if (k.porMarca().size() > 5) {
                marcas.getChildren().add(Etiqueta.simple("y " + (k.porMarca().size() - 5) + " marcas más", Etiqueta.Tono.NEUTRO));
            }
        }

        if (grupoUtilidad != null && d.utilidad() != null) {
            IndicadoresService.UtilidadResumen u = d.utilidad();
            grupoUtilidad.valor("bruta", Dinero.formatear(r.utilidadBruta()));
            grupoUtilidad.valor("gastos", "−" + Dinero.formatear(u.gastosCentavos()));
            grupoUtilidad.valor("nomina", "−" + Dinero.formatear(u.nominaCentavos()));
            grupoUtilidad.valor("mermas", "−" + Dinero.formatear(u.mermasCentavos()));
            grupoUtilidad.valor("neta", Dinero.formatear(u.utilidadNeta()));
            grupoUtilidad.valor("margen", sinVentas ? "—" : porcentaje(u.margenNetoPct()));
            grupoUtilidad.resaltar("neta", u.utilidadNeta() < 0);
        }

        IndicadoresService.InventarioResumen inv = d.inventario();
        grupoInventario.valor("inicial", Dinero.formatear(inv.inventarioInicialCentavos()));
        grupoInventario.valor("final", Dinero.formatear(inv.inventarioFinalCentavos()));
        grupoInventario.valor("promedio", Dinero.formatear(inv.inventarioPromedioCentavos()));
        grupoInventario.valor("dias", inv.diasInventario().map(x -> uno(x) + " días").orElse("Sin ventas al costo"));
        grupoInventario.valor("credito", Dinero.formatear(inv.saldoCreditoCentavos()));

        tablaKpis.setDatos(d.kpis().kpis());
        avanceTotal.setText(d.kpis().avanceTotalPonderado()
                .map(a -> "Avance total ponderado: " + porcentaje(a))
                .orElse(mismoMes(d) ? "Sin metas de KPI capturadas para este mes" : "Las metas de KPI son mensuales"));
    }

    private void pintarMeta(Datos d) {
        IndicadoresService.Resumen r = d.resumen();
        cuerpoMeta.getChildren().clear();
        if (!mismoMes(d)) {
            tarjetaMeta.valor("—", "La meta es mensual: elige un solo mes");
            tarjetaMeta.sinDato(true);
            tarjetaMeta.barra(-1, null);
            cuerpoMeta.getChildren().add(Ui.texto("La meta se captura por mes. Con un periodo de varios meses no se "
                    + "calcula el avance para no mezclar metas.", "campo-ayuda"));
            return;
        }
        int anio = d.desde().getYear();
        int mes = d.desde().getMonthValue();
        if (r.metaMensual().isEmpty()) {
            tarjetaMeta.valor("Sin meta", "No se ha capturado la meta de " + MESES[mes - 1].toLowerCase(ES));
            tarjetaMeta.sinDato(true);
            tarjetaMeta.barra(-1, null);
            cuerpoMeta.getChildren().add(Ui.texto("Aún no se ha capturado la meta de " + MESES[mes - 1] + " " + anio + ".",
                    "campo-ayuda"));
            if (esAdmin) {
                Button capturar = Ui.boton("Capturar meta", "mdi2t-target", "accent");
                capturar.setOnAction(e -> capturarMeta(d.sucursalId(), anio, mes));
                cuerpoMeta.getChildren().add(capturar);
            }
            return;
        }
        double cumplimiento = r.cumplimientoPct().orElse(0.0);
        String estado = cumplimiento >= 100 ? "exito" : cumplimiento >= 80 ? "advertencia" : "peligro";
        String textoEstado = cumplimiento >= 100 ? "Meta alcanzada" : cumplimiento >= 80 ? "Cerca de la meta" : "Debajo de la meta";
        tarjetaMeta.sinDato(false);
        tarjetaMeta.valor(porcentaje(cumplimiento), textoEstado + " · faltan " + Dinero.formatear(r.faltanteMeta()));
        tarjetaMeta.barra(Math.max(0, Math.min(1.0, cumplimiento / 100.0)), estado);

        Label estadoTexto = new Label(textoEstado);
        estadoTexto.getStyleClass().addAll("indicadores-meta-estado", estado);
        cuerpoMeta.getChildren().addAll(
                PanelDetalle.dato("Meta de " + MESES[mes - 1].toLowerCase(ES), Dinero.formatear(r.metaMensual().get())),
                PanelDetalle.dato("Vendido", Dinero.formatear(r.ventaNeta())),
                PanelDetalle.dato(r.faltanteMeta() > 0 ? "Falta" : "Arriba de la meta",
                        Dinero.formatear(r.faltanteMeta() > 0 ? r.faltanteMeta() : r.diferenciaVsMeta().orElse(0L))),
                estadoTexto,
                new javafx.scene.control.Separator(),
                PanelDetalle.dato("Meta diaria", r.metaDiaria().map(Dinero::formatear).orElse("—")),
                PanelDetalle.dato("Promedio diario vendido", Dinero.formatear(r.promedioDiario())),
                PanelDetalle.dato("Días del mes", r.diasDelMes().map(String::valueOf).orElse("—")));
        if (esAdmin) {
            Button editar = Ui.boton("Editar meta", "mdi2p-pencil-outline", "small");
            editar.setOnAction(e -> capturarMeta(d.sucursalId(), anio, mes));
            cuerpoMeta.getChildren().add(editar);
        }
    }

    private void pintarGrafica(Datos d) {
        List<IndicadoresService.VentaDia> dias = d.dias();
        boolean hayVentas = dias.stream().anyMatch(x -> x.totalCentavos() != 0);
        if (!hayVentas) {
            zonaGrafica.getChildren().setAll(new EstadoVacio("mdi2c-chart-bar", "Sin ventas en el periodo.",
                    "Cuando haya ventas completadas aparecerá aquí su evolución.").compacto());
            return;
        }
        boolean porMes = dias.size() > DIAS_POR_DIA;
        Map<String, Long> puntos = new LinkedHashMap<>();
        Map<String, String> ayudas = new LinkedHashMap<>();
        boolean mismoMesCalendario = YearMonth.from(d.desde()).equals(YearMonth.from(d.hasta()));
        if (porMes) {
            tituloGrafica.setText("Venta por mes");
            for (IndicadoresService.VentaDia v : dias) {
                YearMonth m = YearMonth.from(v.dia());
                String clave = m.getMonth().getDisplayName(TextStyle.SHORT, ES) + (d.desde().getYear() != d.hasta().getYear()
                        ? " " + (m.getYear() % 100) : "");
                puntos.merge(clave, v.totalCentavos(), Long::sum);
                ayudas.put(clave, Texto.capitalizar(m.getMonth().getDisplayName(TextStyle.FULL, ES)) + " " + m.getYear());
            }
        } else {
            tituloGrafica.setText("Venta por día");
            for (IndicadoresService.VentaDia v : dias) {
                String clave = mismoMesCalendario ? String.valueOf(v.dia().getDayOfMonth())
                        : v.dia().getDayOfMonth() + "/" + v.dia().getMonthValue();
                puntos.put(clave, v.totalCentavos());
                ayudas.put(clave, Texto.capitalizar(DIA_LARGO.format(v.dia())));
            }
        }
        XYChart.Series<String, Number> serie = new XYChart.Series<>();
        long maximo = 0;
        for (Map.Entry<String, Long> e : puntos.entrySet()) {
            XYChart.Data<String, Number> dato = new XYChart.Data<>(e.getKey(), e.getValue() / 100.0);
            String texto = ayudas.get(e.getKey()) + ": " + Dinero.formatear(e.getValue());
            dato.nodeProperty().addListener((o, x, nodo) -> {
                if (nodo != null) {
                    Tooltip.install(nodo, new Tooltip(texto));
                }
            });
            serie.getData().add(dato);
            maximo = Math.max(maximo, e.getValue());
        }
        Double metaDiaria = !porMes && d.resumen().metaDiaria().isPresent() ? d.resumen().metaDiaria().get() / 100.0 : null;
        grafica.getData().setAll(List.of(serie));
        grafica.setMeta(metaDiaria, Math.max(maximo / 100.0, metaDiaria == null ? 0 : metaDiaria));
        grafica.setCategoryGap(puntos.size() > 20 ? 3 : 10);
        zonaGrafica.getChildren().setAll(grafica);
    }

    private static boolean mismoMes(Datos d) {
        return d.desde().getYear() == d.hasta().getYear() && d.desde().getMonthValue() == d.hasta().getMonthValue();
    }

    private static String textoPeriodo(LocalDate desde, LocalDate hasta, int diasTranscurridos) {
        long diasTotales = ChronoUnit.DAYS.between(desde, hasta) + 1;
        String rango = desde.equals(hasta) ? "El " + FECHA.format(desde) : "Del " + FECHA.format(desde) + " al " + FECHA.format(hasta);
        return rango + " · " + Texto.plural(diasTranscurridos, "día transcurrido", "días transcurridos")
                + (diasTotales != diasTranscurridos ? " de " + diasTotales : "");
    }

    private static String horas(List<IndicadoresService.HoraVenta> horas) {
        if (horas.isEmpty()) {
            return "Sin ventas";
        }
        return String.join(", ", horas.stream().map(h -> String.format("%02d:00–%02d:00", h.hora(), h.hora() + 1)).toList());
    }

    private static String uno(double valor) {
        return String.format(Locale.US, "%.1f", valor);
    }

    private static String porcentaje(double valor) {
        return String.format(Locale.US, "%.1f%%", valor);
    }

    /** "$12.5k", "$1.2M" para los ejes de las gráficas. */
    private static String dineroCorto(long pesos) {
        double v = Math.abs(pesos);
        String signo = pesos < 0 ? "−" : "";
        if (v >= 1_000_000) {
            return signo + "$" + String.format(Locale.US, "%.1fM", v / 1_000_000);
        }
        if (v >= 1_000) {
            return signo + "$" + String.format(Locale.US, v >= 10_000 ? "%.0fk" : "%.1fk", v / 1_000);
        }
        return signo + "$" + (long) v;
    }

    // ------------------------------------------------------------------ meta

    private void capturarMeta(String sucursalId, int anio, int mes) {
        Optional<IndicadoresService.MetaCapturada> actual;
        try {
            actual = servicio.metaCapturada(usuario, sucursalId, anio, mes);
        } catch (RuntimeException e) {
            avisos.error(Errores.mensaje(e));
            return;
        }
        String nombre = sucursalesSeleccionables != null && comboSucursal.getValue() != null
                ? comboSucursal.getValue().nombre() : nombreSucursal.apply(sucursalId);
        Dialogo d = new Dialogo("Meta de " + MESES[mes - 1].toLowerCase(ES) + " " + anio, nombre, "mdi2t-target",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        CampoDinero campoVenta = new CampoDinero().normal();
        campoVenta.setPromptText("0.00");
        CampoMasa campoKilos = new CampoMasa();
        TextField campoArticulos = new TextField();
        Campos.soloNumeros(campoArticulos, 1);
        campoArticulos.setPromptText("0.0");
        campoArticulos.setAlignment(Pos.CENTER_RIGHT);
        TextField campoUtilidad = new TextField();
        Campos.soloNumeros(campoUtilidad, 1);
        campoUtilidad.setPromptText("0.0");
        campoUtilidad.setAlignment(Pos.CENTER_RIGHT);
        actual.ifPresent(m -> {
            if (m.ventaCentavos() > 0) {
                campoVenta.setCentavos(m.ventaCentavos());
            }
            m.kilosGramos().ifPresent(campoKilos::setGramos);
            m.articulosTicket().ifPresent(v -> campoArticulos.setText(v.stripTrailingZeros().toPlainString()));
            m.utilidadPct().ifPresent(v -> campoUtilidad.setText(String.valueOf(v)));
        });
        d.setContenido(
                Formulario.campo("Meta mensual de venta", campoVenta)
                        .ayuda("Con ella se calculan el avance, la meta diaria y lo que falta."),
                Formulario.seccionSeparada("Metas de los KPIs", "Opcionales; si se dejan vacías, ese KPI no cuenta en el avance ponderado.",
                        Formulario.fila(Formulario.opcional("Kilos vendidos", campoKilos),
                                Formulario.opcional("Artículos por ticket", campoArticulos)),
                        Formulario.opcional("Utilidad (%)", Formulario.conUnidad(campoUtilidad, "%"))));
        Runnable guardar = () -> {
            long venta = campoVenta.centavosOCero();
            BigDecimal kilos = campoKilos.gramos().orElse(null);
            BigDecimal articulos = decimal(campoArticulos);
            Double utilidad = decimal(campoUtilidad) == null ? null : decimal(campoUtilidad).doubleValue();
            d.enviar(() -> servicio.guardarMeta(usuario, sucursalId, anio, mes, venta, kilos, articulos, utilidad), folio -> {
                d.cerrar();
                avisos.exito("Meta de " + MESES[mes - 1].toLowerCase(ES) + " guardada · Folio " + folio);
                cargar();
            });
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Guardar meta", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(campoVenta);
        d.vigilarCambios(campoVenta, campoKilos, campoArticulos, campoUtilidad);
        dialogos.mostrar(d);
    }

    private static BigDecimal decimal(TextField campo) {
        String t = campo.getText();
        return (t == null || t.isBlank() || t.equals(".")) ? null : new BigDecimal(t);
    }

    // ------------------------------------------------------------------ piezas visuales

    /** Indicador principal: etiqueta, cifra y contexto; opcionalmente una barra de avance. */
    private static final class Tarjeta extends VBox {
        private final Label valor = new Label("—");
        private final Label contexto = new Label();
        private ProgressBar barra;

        Tarjeta(String titulo) {
            super(4);
            getStyleClass().add("kpi");
            Label etiqueta = new Label(titulo);
            etiqueta.getStyleClass().add("kpi-etiqueta");
            valor.getStyleClass().add("kpi-valor");
            valor.setMinWidth(Region.USE_PREF_SIZE);
            contexto.getStyleClass().add("kpi-contexto");
            contexto.setWrapText(true);
            setMinWidth(0);
            setMaxWidth(Double.MAX_VALUE);
            getChildren().addAll(etiqueta, valor, contexto);
        }

        Tarjeta principal() {
            getStyleClass().add("principal");
            return this;
        }

        void conBarra() {
            barra = new ProgressBar(0);
            barra.getStyleClass().add("barra-meta");
            barra.setMaxWidth(Double.MAX_VALUE);
            barra.managedProperty().bind(barra.visibleProperty());
            getChildren().add(2, barra);
        }

        void valor(String cifra, String texto) {
            valor.setText(cifra);
            contexto.setText(texto);
        }

        void sinDato(boolean sinDato) {
            getStyleClass().remove("sin-dato");
            if (sinDato) {
                getStyleClass().add("sin-dato");
            }
        }

        void barra(double avance, String estado) {
            if (barra == null) {
                return;
            }
            barra.setVisible(avance >= 0);
            barra.setProgress(Math.max(0, avance));
            barra.getStyleClass().removeAll("exito", "advertencia", "peligro");
            if (estado != null) {
                barra.getStyleClass().add(estado);
            }
        }
    }

    /** Grupo compacto de métricas secundarias en dos columnas. */
    private static final class Grupo extends VBox {
        private final GridPane rejilla = new GridPane(Diseno.ESPACIO_XL, Diseno.ESPACIO_M);
        private final Map<String, Label> valores = new LinkedHashMap<>();
        private int indice;

        Grupo(String titulo) {
            super(Diseno.ESPACIO_M);
            getStyleClass().add("panel");
            setPadding(new Insets(14, 16, 16, 16));
            Label t = new Label(titulo);
            t.getStyleClass().add("grupo-metricas-titulo");
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(50);
            rejilla.getColumnConstraints().addAll(c, c);
            getChildren().addAll(t, rejilla);
            setMaxWidth(Double.MAX_VALUE);
            setMaxHeight(Double.MAX_VALUE);
        }

        void agregar(String clave, String etiqueta) {
            Label e = new Label(etiqueta);
            e.getStyleClass().add("mini-metrica-etiqueta");
            e.setWrapText(true);
            Label v = new Label("—");
            v.getStyleClass().add("mini-metrica-valor");
            v.setWrapText(true);
            VBox caja = new VBox(2, e, v);
            caja.getStyleClass().add("mini-metrica");
            rejilla.add(caja, indice % 2, indice / 2);
            indice++;
            valores.put(clave, v);
        }

        void extra(Node nodo) {
            getChildren().add(nodo);
        }

        void valor(String clave, String texto) {
            valores.get(clave).setText(texto);
        }

        void resaltar(String clave, boolean negativo) {
            Label l = valores.get(clave);
            l.getStyleClass().remove("celda-peligro");
            if (negativo) {
                l.getStyleClass().add("celda-peligro");
            }
        }
    }

    /**
     * Barras de venta con una línea de referencia para la meta diaria. La línea se acomoda en
     * {@link #layoutPlotChildren()}, que es parte de la API pública para extender gráficas.
     */
    private static final class GraficaVentas extends BarChart<String, Number> {
        private final Line lineaMeta = new Line();
        private final javafx.beans.property.BooleanProperty conMeta = new javafx.beans.property.SimpleBooleanProperty(false);
        private Double meta;

        GraficaVentas(CategoryAxis x, NumberAxis y) {
            super(x, y);
            setAnimated(false);
            setLegendVisible(false);
            setBarGap(0);
            getStyleClass().add("grafica");
            lineaMeta.getStyleClass().add("linea-meta");
            lineaMeta.setManaged(false);
            y.setAutoRanging(false);
            y.setLowerBound(0);
            y.setMinorTickVisible(false);
            x.setTickLabelGap(6);
        }

        javafx.beans.property.BooleanProperty conMetaProperty() {
            return conMeta;
        }

        /** @param maximo valor más alto a mostrar (ventas o meta), para que la línea quepa en la escala */
        void setMeta(Double meta, double maximo) {
            this.meta = meta;
            conMeta.set(meta != null);
            NumberAxis y = (NumberAxis) getYAxis();
            double tope = escala(maximo * 1.08);
            y.setUpperBound(tope);
            y.setTickUnit(tope / 4);
            if (meta == null) {
                getPlotChildren().remove(lineaMeta);
            } else if (!getPlotChildren().contains(lineaMeta)) {
                getPlotChildren().add(lineaMeta);
            }
            requestChartLayout();
        }

        /** Tope "redondo" para el eje (1, 2, 2.5 o 5 por potencia de 10). */
        private static double escala(double valor) {
            if (valor <= 0) {
                return 100;
            }
            double potencia = Math.pow(10, Math.floor(Math.log10(valor)));
            for (double paso : new double[]{1, 2, 2.5, 5, 10}) {
                if (paso * potencia >= valor) {
                    return paso * potencia;
                }
            }
            return 10 * potencia;
        }

        @Override
        protected void layoutPlotChildren() {
            super.layoutPlotChildren();
            if (meta != null) {
                double y = getYAxis().getDisplayPosition(meta);
                lineaMeta.setStartX(0);
                lineaMeta.setEndX(getXAxis().getWidth());
                lineaMeta.setStartY(y);
                lineaMeta.setEndY(y);
                lineaMeta.toFront();
            }
        }
    }

    /** Atajo para textos con estilo (evita depender del paquete de administración). */
    private static final class Ui {
        static Label texto(String contenido, String clase) {
            Label l = new Label(contenido);
            l.getStyleClass().add(clase);
            l.setWrapText(true);
            return l;
        }

        static Button boton(String texto, String icono, String... clases) {
            Button b = new Button(texto, icono == null ? null : new FontIcon(icono));
            b.getStyleClass().addAll(clases);
            return b;
        }
    }
}
