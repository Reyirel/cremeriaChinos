package com.cremerias.puntoventa.ui.componentes;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.IndicadoresRepository;
import com.cremerias.puntoventa.service.IndicadoresService;
import com.cremerias.puntoventa.ui.admin.AdminContexto;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Venta, costo, utilidad, kilos, KPIs y avance de meta mensual de una sucursal, calculados
 * directo de las operaciones del POS. La usan tanto el panel de Administrador (con selector de
 * sucursal) como el de Supervisor (fija a la sucursal del usuario, sin selector).
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
    private HBox tarjetasVentasDetalle;
    private HBox tarjetasClientes;
    private HBox cuerpoHoras;
    private HBox tarjetasKilos;
    private Label notaKilos;
    private TableView<IndicadoresRepository.MarcaKilos> tablaMarcas;
    private TableView<IndicadoresService.Kpi> tablaKpis;
    private HBox tarjetaAvanceTotal;
    private HBox tarjetasUtilidad;
    private HBox tarjetasInventario;
    private TableView<IndicadoresService.ConsolidadoFila> tablaConsolidado;

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
        TabPane pestanas = construirPestanas();

        pagina.getChildren().addAll(filtros, pestanas);
        return pagina;
    }

    private TabPane construirPestanas() {
        tarjetasKpi = new HBox(14);
        cuerpoMeta = new VBox(14);
        VBox tarjetaMeta = Ui.tarjeta("Meta mensual", cuerpoMeta);
        tarjetasDiarias = new HBox(14);
        tarjetasVentasDetalle = new HBox(14);
        tarjetasClientes = new HBox(14);
        cuerpoHoras = new HBox(14);
        VBox tabVentas = new VBox(14, tarjetasKpi, tarjetaMeta, tarjetasDiarias, tarjetasVentasDetalle,
                tarjetasClientes, cuerpoHoras);

        tarjetasKilos = new HBox(14);
        notaKilos = Ui.texto("La meta de kilos solo aplica dentro de un mismo mes. Cambia el filtro a \"Mes\" para verla.",
                "texto-ayuda");
        tablaMarcas = Ui.tabla("Sin ventas en el periodo.");
        tablaMarcas.getColumns().setAll(
                Ui.texto("Marca", 220, IndicadoresRepository.MarcaKilos::marca),
                Ui.texto("Kilos vendidos", 160, m -> Masa.formatear(m.gramos())));
        tablaMarcas.setPrefHeight(220);
        VBox tabKilos = new VBox(14, tarjetasKilos, notaKilos, Ui.tarjeta("Kilos por marca", tablaMarcas));

        tablaKpis = Ui.tabla("Sin datos para este periodo.");
        tablaKpis.getColumns().setAll(
                Ui.texto("KPI", 180, IndicadoresService.Kpi::nombre),
                Ui.texto("Logrado", 130, IndicadoresService.Kpi::logrado),
                Ui.texto("Meta", 130, IndicadoresService.Kpi::meta),
                Ui.texto("% avance", 110, k -> k.avancePct().map(IndicadoresVista::formatearPorcentaje).orElse("—")),
                Ui.texto("Ponderación", 110, k -> k.ponderacion() + "%"),
                Ui.texto("% ponderado", 120, k -> k.avancePonderado().map(IndicadoresVista::formatearPorcentaje).orElse("—")));
        tablaKpis.setPrefHeight(220);
        tarjetaAvanceTotal = new HBox(14);
        VBox tabKpis = new VBox(14, tablaKpis, tarjetaAvanceTotal);

        boolean esAdmin = usuario.rol() == Rol.ADMINISTRADOR;

        Node contenidoUtilidad;
        if (esAdmin) {
            tarjetasUtilidad = new HBox(14);
            contenidoUtilidad = new VBox(14, tarjetasUtilidad);
        } else {
            // Gastos y nómina son exclusivos del administrador: la utilidad neta se le queda oculta al supervisor.
            contenidoUtilidad = placeholder("Solo el administrador puede ver la utilidad neta.");
        }

        tarjetasInventario = new HBox(14);
        VBox tabInventario = new VBox(14, tarjetasInventario);

        Tab tabVentasT = pestana("Ventas", tabVentas);
        Tab tabKilosT = pestana("Kilos", tabKilos);
        Tab tabKpisT = pestana("KPIs", tabKpis);
        Tab tabUtilidadT = pestana("Utilidad", contenidoUtilidad);
        Tab tabInventarioT = pestana("Inventario", tabInventario);

        TabPane pestanas = new TabPane(tabVentasT, tabKilosT, tabKpisT, tabUtilidadT, tabInventarioT);
        if (esAdmin) {
            tablaConsolidado = Ui.tabla("Sin datos para este periodo.");
            tablaConsolidado.getColumns().setAll(
                    Ui.texto("Sucursal", 170, f -> f.sucursal().nombre()),
                    Ui.dinero("Venta", 110, IndicadoresService.ConsolidadoFila::ventaNeta),
                    Ui.dinero("Costo", 110, IndicadoresService.ConsolidadoFila::costoVenta),
                    Ui.dinero("Utilidad bruta", 120, IndicadoresService.ConsolidadoFila::utilidadBruta),
                    Ui.dinero("Gastos", 110, IndicadoresService.ConsolidadoFila::gastosCentavos),
                    Ui.dinero("Utilidad neta", 120, IndicadoresService.ConsolidadoFila::utilidadNeta),
                    Ui.texto("% cumplimiento", 120,
                            f -> f.cumplimientoPct().map(IndicadoresVista::formatearPorcentaje).orElse("—")),
                    Ui.texto("Avance ponderado", 130,
                            f -> f.avanceTotalPonderado().map(IndicadoresVista::formatearPorcentaje).orElse("—")));
            tablaConsolidado.setPrefHeight(320);
            pestanas.getTabs().add(pestana("Consolidado", new VBox(14, tablaConsolidado)));
        }
        pestanas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return pestanas;
    }

    private static Tab pestana(String titulo, Node contenido) {
        Tab tab = new Tab(titulo, contenido);
        tab.setClosable(false);
        return tab;
    }

    private static Node placeholder(String texto) {
        VBox caja = new VBox(Ui.texto(texto, "texto-ayuda"));
        caja.setPadding(new Insets(24));
        return caja;
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
            IndicadoresService.VentasDetalle vd =
                    servicio.calcularVentasDetalle(usuario, sucursalId, desdeActual, hastaActual);
            pintarVentasDetalle(vd);
            IndicadoresService.KilosResumen kr = servicio.calcularKilos(usuario, sucursalId, desdeActual, hastaActual);
            pintarKilos(kr);
            IndicadoresService.KpisResumen kpis = servicio.calcularKpis(usuario, sucursalId, desdeActual, hastaActual);
            pintarKpis(kpis);
            IndicadoresService.InventarioResumen ir =
                    servicio.calcularInventario(usuario, sucursalId, desdeActual, hastaActual);
            pintarInventario(ir);
            if (usuario.rol() == Rol.ADMINISTRADOR) {
                IndicadoresService.UtilidadResumen ur =
                        servicio.calcularUtilidad(usuario, sucursalId, desdeActual, hastaActual);
                pintarUtilidad(ur, r);
                List<Sucursal> todas = sucursalesSeleccionables != null ? sucursalesSeleccionables.get() : List.of();
                List<IndicadoresService.ConsolidadoFila> consolidado =
                        servicio.calcularConsolidado(usuario, todas, desdeActual, hastaActual);
                pintarConsolidado(consolidado);
            }
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
                Ui.indicador("Margen de utilidad", margen, "mdi2p-percent"));

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
                    capturar.setOnAction(e -> capturarMeta(anio, mes));
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

    private void pintarVentasDetalle(IndicadoresService.VentasDetalle vd) {
        tarjetasVentasDetalle.getChildren().setAll(
                Ui.indicador("Número de tickets", String.valueOf(vd.tickets()), "mdi2r-receipt"),
                Ui.indicador("Ticket promedio", Dinero.formatear(vd.ticketPromedioCentavos()), "mdi2c-cash"),
                Ui.indicador("Artículos por ticket", formatearUno(vd.articulosPorTicket()), "mdi2b-basket-outline"),
                Ui.indicador("Valor promedio del artículo", Dinero.formatear(vd.valorPromedioArticuloCentavos()),
                        "mdi2t-tag-outline"));

        tarjetasClientes.getChildren().setAll(
                Ui.indicador("Clientes por día", formatearUno(vd.clientesPorDia()), "mdi2a-account-multiple-outline"),
                Ui.indicador("Clientes por empleado diario",
                        vd.clientesPorEmpleadoDiario().map(IndicadoresVista::formatearUno).orElse("—"),
                        "mdi2a-account-group-outline"));

        VBox mayores = new VBox(6, Ui.texto("Horas de mayor venta", "indicador-titulo"));
        if (vd.horasMayores().isEmpty()) {
            mayores.getChildren().add(Ui.texto("Sin ventas en el periodo.", "texto-ayuda"));
        } else {
            vd.horasMayores().forEach(h -> mayores.getChildren().add(horaTexto(h)));
        }
        VBox menores = new VBox(6, Ui.texto("Horas de menor venta", "indicador-titulo"));
        if (vd.horasMenores().isEmpty()) {
            menores.getChildren().add(Ui.texto("Sin ventas en el periodo.", "texto-ayuda"));
        } else {
            vd.horasMenores().forEach(h -> menores.getChildren().add(horaTexto(h)));
        }
        cuerpoHoras.getChildren().setAll(Ui.tarjeta(null, mayores), Ui.tarjeta(null, menores));
    }

    private static Label horaTexto(IndicadoresService.HoraVenta h) {
        return new Label(String.format("%02d:00–%02d:00 · %s", h.hora(), h.hora() + 1, Dinero.formatear(h.totalCentavos())));
    }

    private void pintarKilos(IndicadoresService.KilosResumen kr) {
        tarjetasKilos.getChildren().setAll(
                Ui.indicador("Kilos vendidos", Masa.formatear(kr.kilosVendidosGramos()), "mdi2s-scale-balance"),
                Ui.indicador("Meta de kilos", kr.metaKilosGramos().map(Masa::formatear).orElse("—"), "mdi2t-target"),
                Ui.indicador("Diferencia", kr.diferenciaGramos().map(Masa::formatear).orElse("—"),
                        "mdi2f-flag-checkered"));

        boolean periodoEsUnMes = desdeActual.getYear() == hastaActual.getYear()
                && desdeActual.getMonthValue() == hastaActual.getMonthValue();
        notaKilos.setVisible(!periodoEsUnMes);
        notaKilos.setManaged(!periodoEsUnMes);

        tablaMarcas.setItems(FXCollections.observableArrayList(kr.porMarca()));
    }

    private void pintarKpis(IndicadoresService.KpisResumen kpis) {
        tablaKpis.setItems(FXCollections.observableArrayList(kpis.kpis()));
        tarjetaAvanceTotal.getChildren().setAll(
                Ui.indicador("Avance total ponderado",
                        kpis.avanceTotalPonderado().map(IndicadoresVista::formatearPorcentaje).orElse("—"),
                        "mdi2c-chart-donut"));
    }

    private void pintarUtilidad(IndicadoresService.UtilidadResumen u, IndicadoresService.Resumen r) {
        if (tarjetasUtilidad == null) {
            return;
        }
        tarjetasUtilidad.getChildren().setAll(
                Ui.indicador("Utilidad bruta", Dinero.formatear(r.utilidadBruta()), "mdi2t-trending-up"),
                Ui.indicador("Gastos del periodo", Dinero.formatear(u.gastosCentavos()), "mdi2c-cash-minus"),
                Ui.indicador("Nómina del periodo", Dinero.formatear(u.nominaCentavos()), "mdi2a-account-cash-outline"),
                Ui.indicador("Mermas (al costo)", Dinero.formatear(u.mermasCentavos()), "mdi2a-alert-outline"),
                Ui.indicador("Utilidad neta", Dinero.formatear(u.utilidadNeta()), "mdi2c-cash-multiple"),
                Ui.indicador("Margen neto", formatearPorcentaje(u.margenNetoPct()), "mdi2p-percent"));
    }

    private void pintarInventario(IndicadoresService.InventarioResumen ir) {
        tarjetasInventario.getChildren().setAll(
                Ui.indicador("Inventario inicial (costo)", Dinero.formatear(ir.inventarioInicialCentavos()),
                        "mdi2p-package-variant-closed"),
                Ui.indicador("Inventario final (costo)", Dinero.formatear(ir.inventarioFinalCentavos()),
                        "mdi2p-package-variant-closed"),
                Ui.indicador("Inventario promedio", Dinero.formatear(ir.inventarioPromedioCentavos()), "mdi2s-scale-balance"),
                Ui.indicador("Días de inventario", ir.diasInventario().map(IndicadoresVista::formatearUno).orElse("—"),
                        "mdi2c-calendar-range"),
                Ui.indicador("Saldo de crédito con el almacén", Dinero.formatear(ir.saldoCreditoCentavos()),
                        "mdi2c-credit-card-clock-outline"));
    }

    private void pintarConsolidado(List<IndicadoresService.ConsolidadoFila> filas) {
        if (tablaConsolidado == null) {
            return;
        }
        tablaConsolidado.setItems(FXCollections.observableArrayList(filas));
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
            editar.setOnAction(e -> capturarMeta(anio, mes));
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

    private static String formatearUno(double valor) {
        return String.format(Locale.US, "%.1f", valor);
    }

    private static String formatearPorcentaje(double valor) {
        return String.format(Locale.US, "%.1f%%", valor);
    }

    private void capturarMeta(int anio, int mes) {
        String sucursalId = sucursalActual();
        if (sucursalId == null) {
            return;
        }
        Optional<IndicadoresService.MetaCapturada> actual = servicio.metaCapturada(usuario, sucursalId, anio, mes);

        Dialogo d = new Dialogo("Meta de " + MESES[mes - 1] + " " + anio,
                sucursalesSeleccionables != null && comboSucursal.getValue() != null
                        ? comboSucursal.getValue().nombre() : nombreSucursal.apply(sucursalId),
                "mdi2t-target", Dialogo.Tono.ACENTO);

        CampoDinero campoVenta = new CampoDinero();
        CampoMasa campoKilos = new CampoMasa();
        TextField campoArticulos = new TextField();
        Campos.soloNumeros(campoArticulos, 1);
        campoArticulos.setPromptText("0.0");
        TextField campoUtilidad = new TextField();
        Campos.soloNumeros(campoUtilidad, 1);
        campoUtilidad.setPromptText("0.0");

        actual.ifPresent(m -> {
            if (m.ventaCentavos() > 0) {
                campoVenta.setCentavos(m.ventaCentavos());
            }
            m.kilosGramos().ifPresent(campoKilos::setGramos);
            m.articulosTicket().ifPresent(v -> campoArticulos.setText(v.stripTrailingZeros().toPlainString()));
            m.utilidadPct().ifPresent(v -> campoUtilidad.setText(String.valueOf(v)));
        });

        d.setContenido(
                Ui.campo("Meta mensual de venta", campoVenta),
                Ui.campo("Meta de kilos vendidos", campoKilos),
                Ui.campo("Meta de artículos por ticket", campoArticulos),
                Ui.campo("Meta de utilidad (%)", campoUtilidad));

        Runnable guardar = () -> {
            try {
                servicio.guardarMeta(usuario, sucursalId, anio, mes, campoVenta.centavosOCero(),
                        campoKilos.gramos().orElse(null), aBigDecimal(campoArticulos), aDouble(campoUtilidad));
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
        d.setFocoInicial(campoVenta);
        dialogos.mostrar(d);
    }

    private static BigDecimal aBigDecimal(TextField campo) {
        String t = campo.getText();
        return (t == null || t.isBlank() || t.equals(".")) ? null : new BigDecimal(t);
    }

    private static Double aDouble(TextField campo) {
        BigDecimal v = aBigDecimal(campo);
        return v == null ? null : v.doubleValue();
    }
}
