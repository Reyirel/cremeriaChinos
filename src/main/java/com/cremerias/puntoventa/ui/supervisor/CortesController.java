package com.cremerias.puntoventa.ui.supervisor;

import atlantafx.base.controls.Spacer;
import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.CorteRealizado;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.caja.DialogoCorte;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Cajas abiertas y cortes realizados de la sucursal del supervisor. */
public class CortesController {

    private static final Logger log = LoggerFactory.getLogger(CortesController.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("d MMM, HH:mm",
            Locale.forLanguageTag("es-MX"));

    private final AppContext ctx;
    private final Sesion sesion;
    private final Dialogos dialogos;
    private final Avisos avisos;
    /** Sucursal que se muestra; nula = todas (solo el administrador). */
    private String sucursalId;
    private String nombreSucursal;
    private final boolean esAdministrador;
    private final java.util.Map<String, String> nombresSucursal = new java.util.HashMap<>();
    private Runnable alCambiar = () -> { };

    @FXML private Label subtitulo;
    @FXML private HBox indicadores;
    @FXML private Label etiquetaAbiertas;
    @FXML private FlowPane tarjetasAbiertas;
    @FXML private VBox sinAbiertas;
    @FXML private Label etiquetaCortes;
    @FXML private DatePicker fecha;
    @FXML private TableView<CorteRealizado> tablaCortes;
    @FXML private javafx.scene.control.ComboBox<com.cremerias.puntoventa.model.Sucursal> filtroSucursal;

    public CortesController(Navegador navegador) {
        this.ctx = navegador.contexto();
        this.sesion = ctx.sesionActual().obtener().orElseThrow();
        this.dialogos = navegador.dialogos();
        this.avisos = navegador.avisos();
        this.esAdministrador = sesion.usuario().rol() == com.cremerias.puntoventa.model.Rol.ADMINISTRADOR;
        this.sucursalId = esAdministrador ? null : sesion.usuario().sucursalId();
        this.nombreSucursal = esAdministrador ? "Todas las sucursales" : ctx.nombreSucursal(sucursalId);
    }

    public void setAlCambiar(Runnable alCambiar) {
        this.alCambiar = alCambiar;
    }

    @FXML
    private void initialize() {
        if (esAdministrador) {
            configurarFiltro();
        } else {
            subtitulo.setText(sucursalId == null
                    ? "Tu usuario no tiene una sucursal asignada."
                    : nombreSucursal + " · solo se muestran las cajas de esta sucursal");
        }
        sinAbiertas.managedProperty().bind(sinAbiertas.visibleProperty());
        fecha.setValue(LocalDate.now());
        fecha.valueProperty().addListener((o, a, b) -> cargarCortes());
        configurarTabla();
        actualizar();
    }

    @FXML
    private void onActualizar() {
        actualizar();
        avisos.info("Información actualizada.");
    }

    /** El administrador puede ver y cortar las cajas de todas las sucursales o de una. */
    private void configurarFiltro() {
        var todas = new com.cremerias.puntoventa.model.Sucursal(null, "", "Todas las sucursales", null, null, true, false);
        var opciones = new java.util.ArrayList<com.cremerias.puntoventa.model.Sucursal>();
        opciones.add(todas);
        for (var s : ctx.admin().sucursales().listar()) {
            opciones.add(s);
            nombresSucursal.put(s.id(), s.nombre());
        }
        filtroSucursal.setItems(FXCollections.observableArrayList(opciones));
        filtroSucursal.setValue(todas);
        filtroSucursal.setVisible(true);
        filtroSucursal.setManaged(true);
        filtroSucursal.valueProperty().addListener((o, x, s) -> {
            if (s == null) {
                return;
            }
            sucursalId = s.id();
            nombreSucursal = s.nombre();
            subtitulo.setText(sucursalId == null ? "Cajas de todas las sucursales" : "Cajas de " + nombreSucursal);
            actualizar();
        });
        subtitulo.setText("Cajas de todas las sucursales");
    }

    public void actualizar() {
        if (sucursalId == null && !esAdministrador) {
            sinAbiertas.setVisible(true);
            return;
        }
        List<ResumenTurno> abiertas = ctx.caja().abiertosDeSucursal(sucursalId);
        tarjetasAbiertas.getChildren().setAll(abiertas.stream().map(this::tarjeta).toList());
        sinAbiertas.setVisible(abiertas.isEmpty());
        ((Label) sinAbiertas.getChildren().get(1)).setText(sucursalId == null
                ? "No hay cajas abiertas en ninguna sucursal" : "No hay cajas abiertas en esta sucursal");
        etiquetaAbiertas.setText(String.valueOf(abiertas.size()));
        List<CorteRealizado> hoy = ctx.caja().cortesDelDia(sucursalId, LocalDate.now());
        actualizarIndicadores(abiertas, hoy);
        cargarCortes();
    }

    // ---------------------------------------------------------------------
    // Indicadores y tarjetas
    // ---------------------------------------------------------------------

    private void actualizarIndicadores(List<ResumenTurno> abiertas, List<CorteRealizado> cortesHoy) {
        long vendidoAbiertas = abiertas.stream().mapToLong(ResumenTurno::totalVentas).sum();
        long efectivoPorRecoger = abiertas.stream().mapToLong(ResumenTurno::efectivoEsperado).sum();
        long vendidoCortes = cortesHoy.stream().mapToLong(CorteRealizado::totalVendido).sum();
        long diferencias = cortesHoy.stream().mapToLong(CorteRealizado::diferencia).sum();
        indicadores.getChildren().setAll(
                indicador("Cajas abiertas", String.valueOf(abiertas.size()), "mdi2c-cash-register", null),
                indicador("Vendido en cajas abiertas", Dinero.formatear(vendidoAbiertas), "mdi2c-cart-outline", null),
                indicador("Efectivo por recoger", Dinero.formatear(efectivoPorRecoger), "mdi2c-cash", null),
                indicador("Cortes de hoy", cortesHoy.size() + " · " + Dinero.formatear(vendidoCortes),
                        "mdi2c-calculator-variant-outline",
                        diferencias == 0 ? null : (diferencias < 0 ? "Faltantes " : "Sobrantes ")
                                + Dinero.formatear(Math.abs(diferencias))));
    }

    private VBox indicador(String titulo, String valor, String icono, String nota) {
        return indicador(titulo, valor, icono, nota, nota != null && nota.startsWith("Faltantes"));
    }

    private VBox indicador(String titulo, String valor, String icono, String nota, boolean alerta) {
        Label etiqueta = new Label(titulo);
        etiqueta.getStyleClass().add("indicador-titulo");
        StackPane iconoCaja = new StackPane(new FontIcon(icono));
        iconoCaja.getStyleClass().add("indicador-icono");
        HBox arriba = new HBox(etiqueta, new Spacer(), iconoCaja);
        arriba.setAlignment(Pos.CENTER_LEFT);
        Label cifra = new Label(valor);
        cifra.getStyleClass().add("indicador-valor");
        VBox caja = new VBox(6, arriba, cifra);
        if (nota != null) {
            Label n = new Label(nota);
            n.getStyleClass().addAll("indicador-nota", alerta ? "alerta" : "positiva");
            caja.getChildren().add(n);
        }
        caja.getStyleClass().add("indicador");
        caja.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(caja, Priority.ALWAYS);
        return caja;
    }

    private VBox tarjeta(ResumenTurno r) {
        Turno t = r.turno();
        boolean estaCaja = t.dispositivoId().equals(ctx.caja().dispositivoId());

        StackPane icono = new StackPane(new FontIcon("mdi2c-cash-register"));
        icono.getStyleClass().add("tarjeta-caja-icono");
        Label nombre = new Label(t.dispositivoNombre()
                + (esAdministrador && sucursalId == null ? " · " + nombresSucursal.getOrDefault(t.sucursalId(), "") : ""));
        nombre.getStyleClass().add("tarjeta-caja-nombre");
        VBox titulo = new VBox(2, nombre);
        if (estaCaja) {
            Label chip = new Label("Esta caja");
            chip.getStyleClass().add("chip-esta-caja");
            titulo.getChildren().add(chip);
        }
        Label abierta = new Label("Abierta");
        abierta.getStyleClass().add("chip-abierta");
        HBox encabezado = new HBox(12, icono, titulo, new Spacer(), abierta);
        encabezado.setAlignment(Pos.CENTER_LEFT);

        GridPane datos = new GridPane(10, 8);
        datos.getStyleClass().add("tarjeta-caja-datos");
        fila(datos, 0, "Cajero", t.usuarioNombre());
        fila(datos, 1, "Desde", desde(t.abiertoEn()));
        fila(datos, 2, "Ventas", r.numeroVentas() + " · " + Dinero.formatear(r.totalVentas()));
        fila(datos, 3, "Tarjeta / transf.", Dinero.formatear(r.ventasTarjeta() + r.ventasTransferencia()));

        Label etiquetaEfectivo = new Label("Efectivo esperado en caja");
        etiquetaEfectivo.getStyleClass().add("tarjeta-caja-etiqueta");
        Label efectivo = new Label(Dinero.formatear(r.efectivoEsperado()));
        efectivo.getStyleClass().add("tarjeta-caja-efectivo");
        VBox bloqueEfectivo = new VBox(0, etiquetaEfectivo, efectivo);
        bloqueEfectivo.getStyleClass().add("tarjeta-caja-bloque");

        Button corte = new Button("Hacer corte", new FontIcon("mdi2c-calculator-variant-outline"));
        corte.getStyleClass().addAll("accent", "boton-corte");
        corte.setMaxWidth(Double.MAX_VALUE);
        corte.setOnAction(e -> hacerCorte(t, estaCaja));

        VBox tarjeta = new VBox(14, encabezado, datos, bloqueEfectivo, corte);
        tarjeta.getStyleClass().add("tarjeta-caja");
        tarjeta.setPrefWidth(330);
        return tarjeta;
    }

    private static void fila(GridPane grid, int renglon, String etiqueta, String valor) {
        Label a = new Label(etiqueta);
        a.getStyleClass().add("tarjeta-caja-etiqueta");
        Label b = new Label(valor);
        b.getStyleClass().add("tarjeta-caja-valor");
        grid.addRow(renglon, a, b);
    }

    private static String desde(Instant instante) {
        LocalDate dia = instante.atZone(ZoneId.systemDefault()).toLocalDate();
        return dia.equals(LocalDate.now())
                ? "Hoy, " + HORA.format(instante.atZone(ZoneId.systemDefault()))
                : FECHA_HORA.format(instante.atZone(ZoneId.systemDefault()));
    }

    // ---------------------------------------------------------------------
    // Corte
    // ---------------------------------------------------------------------

    private void hacerCorte(Turno turno, boolean estaCaja) {
        Optional<Turno> vigente = ctx.caja().abiertoPorId(turno.id());
        if (vigente.isEmpty()) {
            avisos.info("A esta caja ya se le hizo el corte.");
            actualizar();
            alCambiar.run();
            return;
        }
        Runnable abrirCorte = () -> DialogoCorte.mostrar(dialogos, ctx.caja().resumen(vigente.get()),
                (contado, notas) -> cerrar(vigente.get(), contado, notas));
        if (estaCaja) {
            abrirCorte.run();
        } else {
            DialogoConfirmacion.mostrar(dialogos, "Corte de " + turno.dispositivoNombre(),
                    "Asegúrate de que " + turno.usuarioNombre() + " haya terminado de vender en esa caja y cuenta "
                            + "el efectivo de su cajón antes de continuar.",
                    "Continuar", false, abrirCorte);
        }
    }

    private void cerrar(Turno turno, long contado, String notas) {
        ResumenTurno fin;
        try {
            fin = ctx.caja().cerrar(turno, contado, notas, sesion.usuario());
        } catch (RuntimeException e) {
            log.error("No se pudo hacer el corte", e);
            avisos.error(e.getMessage() == null ? "No se pudo hacer el corte." : e.getMessage());
            actualizar();
            return;
        }
        long diferencia = contado - fin.efectivoEsperado();
        String texto = TicketTexto.corte(fin, contado, nombreDe(turno), Instant.now(), notas,
                sesion.usuario().nombreCompleto());
        ctx.sincronizador().revisarAhora();
        fecha.setValue(LocalDate.now());
        actualizar();
        alCambiar.run();
        DialogoTicket.mostrar(dialogos, avisos, "Corte de " + turno.dispositivoNombre(),
                diferencia == 0 ? "La caja cuadró exacto." : (diferencia < 0 ? "Faltante de " : "Sobrante de ")
                        + Dinero.formatear(Math.abs(diferencia)), texto, null);
    }

    // ---------------------------------------------------------------------
    // Historial
    // ---------------------------------------------------------------------

    private void cargarCortes() {
        if ((sucursalId == null && !esAdministrador) || fecha.getValue() == null) {
            tablaCortes.getItems().clear();
            return;
        }
        List<CorteRealizado> cortes = ctx.caja().cortesDelDia(sucursalId, fecha.getValue());
        tablaCortes.setItems(FXCollections.observableArrayList(cortes));
        etiquetaCortes.setText(String.valueOf(cortes.size()));
    }

    private void configurarTabla() {
        tablaCortes.setPlaceholder(new Label("No hay cortes en esta fecha."));
        tablaCortes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tablaCortes.setFixedCellSize(48);
        tablaCortes.getColumns().setAll(List.of(
                texto("Caja", 190, c -> c.turno().dispositivoNombre()
                        + (esAdministrador ? " · " + nombresSucursal.getOrDefault(c.turno().sucursalId(), "") : "")),
                texto("Cajero", 160, c -> c.turno().usuarioNombre()),
                texto("Apertura", 80, c -> HORA.format(c.turno().abiertoEn().atZone(ZoneId.systemDefault()))),
                texto("Cierre", 80, c -> HORA.format(c.cerradoEn().atZone(ZoneId.systemDefault()))),
                texto("Ventas", 70, c -> String.valueOf(c.numeroVentas())),
                dinero("Vendido", CorteRealizado::totalVendido),
                dinero("Esperado", CorteRealizado::efectivoEsperado),
                dinero("Contado", CorteRealizado::efectivoContado),
                columnaDiferencia(),
                texto("Hizo el corte", 180, CorteRealizado::cerradoPor),
                columnaTicket()));
    }

    private static TableColumn<CorteRealizado, String> texto(String titulo, double ancho,
                                                           Function<CorteRealizado, String> valor) {
        TableColumn<CorteRealizado, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setPrefWidth(ancho);
        c.setSortable(false);
        return c;
    }

    private static TableColumn<CorteRealizado, String> dinero(String titulo, Function<CorteRealizado, Long> valor) {
        TableColumn<CorteRealizado, String> c = texto(titulo, 110, x -> Dinero.formatear(valor.apply(x)));
        c.getStyleClass().add("columna-derecha");
        return c;
    }

    private static TableColumn<CorteRealizado, CorteRealizado> columnaDiferencia() {
        TableColumn<CorteRealizado, CorteRealizado> c = new TableColumn<>("Diferencia");
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setPrefWidth(120);
        c.setSortable(false);
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(CorteRealizado item, boolean vacio) {
                super.updateItem(item, vacio);
                if (vacio || item == null) {
                    setGraphic(null);
                    return;
                }
                long d = item.diferencia();
                Label chip = new Label(d == 0 ? "Exacto" : (d < 0 ? "−" : "+") + Dinero.formatear(Math.abs(d)));
                chip.getStyleClass().addAll("chip-diferencia", d == 0 ? "exacto" : d < 0 ? "faltante" : "sobrante");
                setGraphic(chip);
            }
        });
        return c;
    }

    private TableColumn<CorteRealizado, CorteRealizado> columnaTicket() {
        TableColumn<CorteRealizado, CorteRealizado> c = new TableColumn<>("");
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setPrefWidth(110);
        c.setSortable(false);
        c.setCellFactory(col -> new TableCell<>() {
            private final Button boton = new Button("Ticket", new FontIcon("mdi2r-receipt-text-outline"));

            {
                boton.getStyleClass().addAll("flat", "small");
                boton.setOnAction(e -> verTicket(getItem()));
            }

            @Override
            protected void updateItem(CorteRealizado item, boolean vacio) {
                super.updateItem(item, vacio);
                setGraphic(vacio || item == null ? null : boton);
            }
        });
        return c;
    }

    private String nombreDe(Turno turno) {
        return esAdministrador ? nombresSucursal.getOrDefault(turno.sucursalId(), "") : nombreSucursal;
    }

    private void verTicket(CorteRealizado corte) {
        if (corte == null) {
            return;
        }
        ResumenTurno r = ctx.caja().resumen(corte.turno());
        String texto = TicketTexto.corte(r, corte.efectivoContado(), nombreDe(corte.turno()), corte.cerradoEn(),
                corte.notas(), corte.cerradoPor());
        DialogoTicket.mostrar(dialogos, avisos, "Corte de " + corte.turno().dispositivoNombre(),
                corte.turno().usuarioNombre(), texto, null);
    }
}
