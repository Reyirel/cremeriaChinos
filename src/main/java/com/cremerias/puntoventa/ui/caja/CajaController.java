package com.cremerias.puntoventa.ui.caja;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Spacer;
import atlantafx.base.util.Animations;
import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Carrito;
import com.cremerias.puntoventa.model.LineaVenta;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.model.VentaEspera;
import com.cremerias.puntoventa.model.VentaResumen;
import com.cremerias.puntoventa.repository.VentaEsperaRepository;
import com.cremerias.puntoventa.service.CajaService.TipoMovimiento;
import com.cremerias.puntoventa.service.CatalogoService;
import com.cremerias.puntoventa.service.VentaService;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.DialogoTexto;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.Impresion;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pantalla de caja: captura de productos (lector de código de barras, nombre o clave),
 * carrito, cobro y operaciones del turno.
 */
public class CajaController {

    private static final Logger log = LoggerFactory.getLogger(CajaController.class);
    private static final PseudoClass RESALTADA = PseudoClass.getPseudoClass("resaltada");
    /** "3*" o "3*7501000..." multiplica la cantidad del siguiente producto. */
    private static final Pattern MULTIPLICADOR = Pattern.compile("^(\\d{1,4}(?:\\.\\d{1,3})?)\\s*\\*\\s*(.*)$");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_RESULTADOS = 40;
    private static final String[] TOOLTIPS_ACCIONES = {"Cambiar cantidad o peso del producto seleccionado",
            "Quitar el producto seleccionado", "Pausar la venta para atender a otro cliente",
            "Retomar una venta en espera", "Ventas del turno: reimprimir o cancelar",
            "Entrada o retiro de efectivo", "Cancelar la venta en curso", "Corte de caja y cierre del turno"};

    private final Navegador navegador;
    private final AppContext ctx;
    private final Sesion sesion;
    private final Dialogos dialogos;
    private final Avisos avisos;
    private final Carrito carrito = new Carrito();
    private final ObjectProperty<LineaVenta> resaltada = new SimpleObjectProperty<>();
    private final DoubleProperty totalAnimado = new SimpleDoubleProperty(0);
    private final PauseTransition esperaBusqueda = new PauseTransition(Duration.millis(140));
    private final EventHandler<KeyEvent> filtroTeclas = this::teclas;
    private final EventHandler<KeyEvent> filtroEscritura = this::escritura;

    private Timeline animacionTotal;
    private Turno turno;
    private BigDecimal multiplicadorPendiente;
    private boolean restaurando;
    private Label textoRecuperar;

    @FXML private BorderPane raiz;
    @FXML private Label multiplicador;
    @FXML private CustomTextField campoBusqueda;
    @FXML private TableView<LineaVenta> tablaCarrito;
    @FXML private VBox panelResultados;
    @FXML private Label resultadosTitulo;
    @FXML private ListView<Producto> listaResultados;
    @FXML private FlowPane barraAtajos;
    @FXML private VBox tarjetaTotal;
    @FXML private Label etiquetaTotal;
    @FXML private Label etiquetaArticulos;
    @FXML private Label etiquetaRenglones;
    @FXML private VBox tarjetaUltimo;
    @FXML private Label ultimoNombre;
    @FXML private Label ultimoDetalle;
    @FXML private Label ultimoImporte;
    @FXML private Button botonCobrar;
    @FXML private GridPane gridAcciones;
    @FXML private Label infoTurno;
    @FXML private Label infoVentas;

    public CajaController(Navegador navegador) {
        this.navegador = navegador;
        this.ctx = navegador.contexto();
        this.sesion = ctx.sesionActual().obtener().orElseThrow();
        this.dialogos = navegador.dialogos();
        this.avisos = navegador.avisos();
    }

    @FXML
    private void initialize() {
        configurarTabla();
        configurarBusqueda();
        configurarTotales();
        construirAcciones();
        construirAtajos();

        tarjetaUltimo.setVisible(false);
        tarjetaUltimo.managedProperty().bind(tarjetaUltimo.visibleProperty());
        multiplicador.setVisible(false);
        multiplicador.managedProperty().bind(multiplicador.visibleProperty());

        carrito.lineas().addListener((ListChangeListener<LineaVenta>) c -> {
            if (!restaurando && turno != null) {
                autoguardar();
            }
        });

        raiz.sceneProperty().addListener((obs, anterior, nueva) -> {
            if (anterior != null) {
                anterior.removeEventFilter(KeyEvent.KEY_PRESSED, filtroTeclas);
                anterior.removeEventFilter(KeyEvent.KEY_TYPED, filtroEscritura);
            }
            if (nueva != null) {
                nueva.addEventFilter(KeyEvent.KEY_PRESSED, filtroTeclas);
                nueva.addEventFilter(KeyEvent.KEY_TYPED, filtroEscritura);
                if (anterior == null) {
                    revalidarTurno();
                }
            }
        });
        Platform.runLater(this::verificarTurno);
    }

    // =====================================================================
    // Configuración de la vista
    // =====================================================================

    private void configurarTabla() {
        tablaCarrito.setItems(carrito.lineas());
        tablaCarrito.setFixedCellSize(64);
        tablaCarrito.setFocusTraversable(false);

        VBox vacio = new VBox(10);
        vacio.setAlignment(Pos.CENTER);
        vacio.getStyleClass().add("carrito-vacio");
        StackPane icono = new StackPane(new FontIcon("mdi2b-barcode-scan"));
        icono.getStyleClass().add("carrito-vacio-icono");
        Label titulo = new Label("Escanea un producto para comenzar");
        titulo.getStyleClass().add("carrito-vacio-titulo");
        Label ayuda = new Label("También puedes escribir su nombre o clave. Escribe 3* antes del código para agregar 3 piezas.");
        ayuda.getStyleClass().add("carrito-vacio-ayuda");
        ayuda.setWrapText(true);
        ayuda.setMaxWidth(420);
        ayuda.setAlignment(Pos.CENTER);
        ayuda.setStyle("-fx-text-alignment: center;");
        vacio.getChildren().addAll(icono, titulo, ayuda);
        tablaCarrito.setPlaceholder(vacio);

        TableColumn<LineaVenta, LineaVenta> numero = columna("#", 48);
        numero.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LineaVenta item, boolean vacio) {
                super.updateItem(item, vacio);
                setText(vacio || item == null ? null : String.valueOf(getIndex() + 1));
                getStyleClass().add("celda-numero");
            }
        });

        TableColumn<LineaVenta, LineaVenta> producto = columna("Producto", 300);
        producto.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LineaVenta item, boolean vacio) {
                super.updateItem(item, vacio);
                if (vacio || item == null) {
                    setGraphic(null);
                    return;
                }
                Producto p = item.producto();
                Label nombre = new Label(p.nombre());
                nombre.getStyleClass().add("celda-producto-nombre");
                Label detalle = new Label(descripcionCorta(p));
                detalle.getStyleClass().add("celda-producto-detalle");
                setGraphic(new VBox(2, nombre, detalle));
            }
        });

        TableColumn<LineaVenta, LineaVenta> precio = columna("Precio", 120);
        precio.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(LineaVenta item, boolean vacio) {
                super.updateItem(item, vacio);
                setText(vacio || item == null ? null : Dinero.formatear(item.producto().precioCentavos())
                        + (item.producto().unidad() == Unidad.KG ? " /kg" : ""));
                getStyleClass().add("celda-precio");
            }
        });

        TableColumn<LineaVenta, LineaVenta> cantidad = columna("Cantidad", 170);
        cantidad.setCellFactory(c -> new CeldaCantidad());

        TableColumn<LineaVenta, LineaVenta> importe = columna("Importe", 130);
        importe.setCellFactory(c -> new TableCell<>() {
            private final Label etiqueta = new Label();

            {
                etiqueta.getStyleClass().add("celda-importe");
                setAlignment(Pos.CENTER_RIGHT);
            }

            @Override
            protected void updateItem(LineaVenta item, boolean vacio) {
                super.updateItem(item, vacio);
                etiqueta.textProperty().unbind();
                if (vacio || item == null) {
                    setGraphic(null);
                    return;
                }
                etiqueta.textProperty().bind(Bindings.createStringBinding(
                        () -> Dinero.formatear(item.getImporte()), item.importeProperty()));
                setGraphic(etiqueta);
            }
        });
        importe.getStyleClass().add("columna-derecha");

        TableColumn<LineaVenta, LineaVenta> quitar = columna("", 60);
        quitar.setCellFactory(c -> new TableCell<>() {
            private final Button boton = new Button(null, new FontIcon("mdi2t-trash-can-outline"));

            {
                boton.getStyleClass().addAll("button-icon", "flat", "danger", "boton-quitar");
                boton.setFocusTraversable(false);
                boton.setTooltip(new Tooltip("Quitar (Supr)"));
                boton.setOnAction(e -> quitar(getItem()));
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(LineaVenta item, boolean vacio) {
                super.updateItem(item, vacio);
                setGraphic(vacio || item == null ? null : boton);
            }
        });

        tablaCarrito.getColumns().setAll(List.of(numero, producto, precio, cantidad, importe, quitar));
        producto.prefWidthProperty().bind(tablaCarrito.widthProperty()
                .subtract(48 + 120 + 170 + 130 + 60 + 18));

        tablaCarrito.setRowFactory(t -> {
            TableRow<LineaVenta> fila = new TableRow<>();
            Runnable actualizar = () -> fila.pseudoClassStateChanged(RESALTADA,
                    fila.getItem() != null && fila.getItem() == resaltada.get());
            fila.itemProperty().addListener((o, a, b) -> actualizar.run());
            resaltada.addListener((o, a, b) -> actualizar.run());
            return fila;
        });
    }

    private static TableColumn<LineaVenta, LineaVenta> columna(String titulo, double ancho) {
        TableColumn<LineaVenta, LineaVenta> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setPrefWidth(ancho);
        c.setSortable(false);
        c.setReorderable(false);
        c.setResizable(false);
        return c;
    }

    /** Cantidad con botones − / + (piezas) o botón para capturar el peso (granel). */
    private final class CeldaCantidad extends TableCell<LineaVenta, LineaVenta> {
        private final Label valor = new Label();
        private final Button menos = botonCantidad("mdi2m-minus");
        private final Button mas = botonCantidad("mdi2p-plus");
        private final Button peso = new Button();
        private final HBox piezas = new HBox(6, menos, valor, mas);

        CeldaCantidad() {
            piezas.setAlignment(Pos.CENTER_LEFT);
            valor.getStyleClass().add("celda-cantidad-valor");
            peso.getStyleClass().addAll("boton-peso");
            peso.setGraphic(new FontIcon("mdi2s-scale-balance"));
            peso.setFocusTraversable(false);
            menos.setOnAction(e -> ajustar(getItem(), -1));
            mas.setOnAction(e -> ajustar(getItem(), 1));
            peso.setOnAction(e -> editarCantidad(getItem()));
        }

        @Override
        protected void updateItem(LineaVenta item, boolean vacio) {
            super.updateItem(item, vacio);
            valor.textProperty().unbind();
            peso.textProperty().unbind();
            if (vacio || item == null) {
                setGraphic(null);
                return;
            }
            Unidad unidad = item.producto().unidad();
            if (unidad == Unidad.KG) {
                peso.textProperty().bind(Bindings.createStringBinding(
                        () -> unidad.formatear(item.getCantidad()), item.cantidadProperty()));
                setGraphic(peso);
            } else {
                valor.textProperty().bind(Bindings.createStringBinding(
                        () -> unidad.formatear(item.getCantidad()), item.cantidadProperty()));
                setGraphic(piezas);
            }
        }

        private Button botonCantidad(String icono) {
            Button b = new Button(null, new FontIcon(icono));
            b.getStyleClass().addAll("button-icon", "rounded", "boton-cantidad");
            b.setFocusTraversable(false);
            return b;
        }
    }

    private void configurarBusqueda() {
        panelResultados.setVisible(false);
        listaResultados.setFixedCellSize(60);
        listaResultados.setCellFactory(l -> new CeldaResultado());
        listaResultados.setPlaceholder(new Label("No hay productos que coincidan."));
        listaResultados.setOnMouseClicked(e -> {
            Producto p = listaResultados.getSelectionModel().getSelectedItem();
            if (p != null) {
                agregar(p, null);
            }
        });
        listaResultados.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                Producto p = listaResultados.getSelectionModel().getSelectedItem();
                if (p != null) {
                    agregar(p, null);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                ocultarResultados();
                campoBusqueda.requestFocus();
                e.consume();
            }
        });

        esperaBusqueda.setOnFinished(e -> actualizarResultados());
        campoBusqueda.textProperty().addListener((o, antes, ahora) -> {
            if (ahora != null && ahora.matches("\\d{1,4}(\\.\\d{1,3})?\\s*\\*")) {
                Platform.runLater(() -> {
                    fijarMultiplicador(new BigDecimal(ahora.replace("*", "").strip()));
                    campoBusqueda.clear();
                });
                return;
            }
            esperaBusqueda.playFromStart();
        });
        campoBusqueda.setOnAction(e -> procesarCaptura());
        campoBusqueda.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case DOWN -> moverSeleccion(1);
                case UP -> moverSeleccion(-1);
                case ESCAPE -> {
                    if (panelResultados.isVisible()) {
                        ocultarResultados();
                    } else if (!campoBusqueda.getText().isEmpty()) {
                        campoBusqueda.clear();
                    } else if (multiplicadorPendiente != null) {
                        fijarMultiplicador(null);
                    } else {
                        return;
                    }
                }
                default -> {
                    return;
                }
            }
            e.consume();
        });
    }

    /** Resultado de búsqueda: nombre, clave, precio y existencia. */
    private static final class CeldaResultado extends ListCell<Producto> {
        @Override
        protected void updateItem(Producto p, boolean vacio) {
            super.updateItem(p, vacio);
            if (vacio || p == null) {
                setGraphic(null);
                return;
            }
            StackPane icono = new StackPane(new FontIcon(p.unidad() == Unidad.KG ? "mdi2s-scale-balance"
                    : "mdi2p-package-variant-closed"));
            icono.getStyleClass().addAll("resultado-icono", p.unidad() == Unidad.KG ? "granel" : "pieza");
            Label nombre = new Label(p.nombre());
            nombre.getStyleClass().add("resultado-nombre");
            Label detalle = new Label(descripcionCorta(p));
            detalle.getStyleClass().add("resultado-detalle");
            VBox textos = new VBox(2, nombre, detalle);

            Label precio = new Label(Dinero.formatear(p.precioCentavos()));
            precio.getStyleClass().add("resultado-precio");
            Label unidad = new Label(p.unidad() == Unidad.KG ? "por kg" : "por pieza");
            unidad.getStyleClass().add("resultado-unidad");
            VBox precios = new VBox(0, precio, unidad);
            precios.setAlignment(Pos.CENTER_RIGHT);

            Label existencia = new Label(p.sinExistencia() ? "Sin existencia"
                    : p.unidadBase().formatear(p.existencia()) + (p.unidadBase() == Unidad.PZA ? " pzas" : ""));
            existencia.getStyleClass().addAll("chip-existencia",
                    p.sinExistencia() ? "agotado" : p.existenciaBaja() ? "baja" : "normal");

            HBox fila = new HBox(12, icono, textos, new Spacer(), existencia, precios);
            fila.setAlignment(Pos.CENTER_LEFT);
            setGraphic(fila);
        }
    }

    private void configurarTotales() {
        etiquetaTotal.textProperty().bind(Bindings.createStringBinding(
                () -> Dinero.formatear(Math.round(totalAnimado.get())), totalAnimado));
        carrito.totalProperty().addListener((o, antes, ahora) -> animarTotal(antes.longValue(), ahora.longValue()));
        etiquetaArticulos.textProperty().bind(Bindings.createStringBinding(() -> {
            BigDecimal n = carrito.articulosProperty().get();
            String texto = n.stripTrailingZeros().toPlainString();
            return texto + (n.compareTo(BigDecimal.ONE) == 0 ? " artículo" : " artículos");
        }, carrito.articulosProperty()));
        etiquetaRenglones.textProperty().bind(Bindings.createStringBinding(() -> {
            int n = carrito.lineas().size();
            return n == 0 ? "Sin productos" : n == 1 ? "1 producto en la venta" : n + " productos en la venta";
        }, carrito.lineas()));
        botonCobrar.disableProperty().bind(Bindings.isEmpty(carrito.lineas()));
    }

    private void animarTotal(long antes, long ahora) {
        if (animacionTotal != null) {
            animacionTotal.stop();
        }
        animacionTotal = new Timeline(new KeyFrame(Duration.millis(420),
                new KeyValue(totalAnimado, ahora, Interpolator.EASE_OUT)));
        animacionTotal.play();
        if (ahora > antes) {
            ScaleTransition pulso = new ScaleTransition(Duration.millis(140), etiquetaTotal);
            pulso.setFromX(1);
            pulso.setFromY(1);
            pulso.setToX(1.07);
            pulso.setToY(1.07);
            pulso.setAutoReverse(true);
            pulso.setCycleCount(2);
            pulso.play();
        }
    }

    private void construirAcciones() {
        Object[][] acciones = {
                {"Cantidad", "mdi2n-numeric", "F2", (Runnable) this::editarCantidadSeleccionada},
                {"Quitar", "mdi2t-trash-can-outline", "Supr", (Runnable) this::quitarSeleccionada},
                {"En espera", "mdi2p-pause-circle-outline", "F3", (Runnable) this::ponerEnEspera},
                {"Recuperar", "mdi2p-playlist-play", "F4", (Runnable) this::recuperarEspera},
                {"Ventas", "mdi2h-history", "F5", (Runnable) this::verVentasTurno},
                {"Efectivo", "mdi2s-swap-horizontal", "F6", (Runnable) this::movimientoCaja},
                {"Cancelar", "mdi2c-cart-remove", "F8", (Runnable) this::cancelarVenta},
                {"Corte", "mdi2c-calculator-variant-outline", "F9", (Runnable) this::corteCaja},
        };
        ColumnConstraints mitad = new ColumnConstraints();
        mitad.setPercentWidth(50);
        gridAcciones.getColumnConstraints().setAll(mitad, mitad);
        for (int i = 0; i < acciones.length; i++) {
            Object[] a = acciones[i];
            Label texto = new Label((String) a[0]);
            texto.getStyleClass().add("accion-texto");
            texto.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            Label tecla = new Label((String) a[2]);
            tecla.getStyleClass().add("kbd");
            tecla.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            HBox contenido = new HBox(10, new FontIcon((String) a[1]), texto, new Spacer(), tecla);
            contenido.setAlignment(Pos.CENTER_LEFT);
            Button boton = new Button(null, contenido);
            boton.getStyleClass().add("boton-accion");
            if ("F8".equals(a[2])) {
                boton.getStyleClass().add("accion-peligro");
            }
            boton.setMaxWidth(Double.MAX_VALUE);
            boton.setFocusTraversable(false);
            boton.setTooltip(new Tooltip(TOOLTIPS_ACCIONES[i]));
            Runnable accion = (Runnable) a[3];
            boton.setOnAction(e -> accion.run());
            contenido.prefWidthProperty().bind(boton.widthProperty().subtract(28));
            if ("F4".equals(a[2])) {
                textoRecuperar = texto;
            }
            gridAcciones.add(boton, i % 2, i / 2);
        }
    }

    private void construirAtajos() {
        String[][] atajos = {{"F1", "Buscar"}, {"Enter", "Agregar"}, {"3*", "Multiplicar"}, {"+ / −", "Ajustar"},
                {"F2", "Cantidad"}, {"Supr", "Quitar"}, {"F12", "Cobrar"}};
        for (String[] a : atajos) {
            Label tecla = new Label(a[0]);
            tecla.getStyleClass().add("kbd");
            Label texto = new Label(a[1]);
            texto.getStyleClass().add("atajo-texto");
            HBox atajo = new HBox(6, tecla, texto);
            atajo.setAlignment(Pos.CENTER_LEFT);
            barraAtajos.getChildren().add(atajo);
        }
    }

    // =====================================================================
    // Teclado
    // =====================================================================

    private void teclas(KeyEvent e) {
        if (dialogos.hayAbierto()) {
            return;
        }
        boolean campoVacio = campoBusqueda.getText() == null || campoBusqueda.getText().isEmpty();
        switch (e.getCode()) {
            case F1 -> enfocarBusqueda();
            case F2 -> editarCantidadSeleccionada();
            case F3 -> ponerEnEspera();
            case F4 -> recuperarEspera();
            case F5 -> verVentasTurno();
            case F6 -> movimientoCaja();
            case F8 -> cancelarVenta();
            case F9 -> corteCaja();
            case F12 -> onCobrar();
            case DELETE -> {
                if (!campoVacio && campoBusqueda.isFocused()) {
                    return;
                }
                quitarSeleccionada();
            }
            case ADD, PLUS -> {
                if (!campoVacio) {
                    return;
                }
                ajustar(seleccionada(), 1);
            }
            case SUBTRACT, MINUS -> {
                if (!campoVacio) {
                    return;
                }
                ajustar(seleccionada(), -1);
            }
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Si el foco no está en un campo, lo que se teclea (o escanea) va a la búsqueda. */
    private void escritura(KeyEvent e) {
        if (dialogos.hayAbierto()) {
            return;
        }
        String caracter = e.getCharacter();
        boolean campoVacio = campoBusqueda.getText() == null || campoBusqueda.getText().isEmpty();
        if (campoVacio && ("+".equals(caracter) || "-".equals(caracter))) {
            e.consume();
            return;
        }
        Node foco = raiz.getScene() == null ? null : raiz.getScene().getFocusOwner();
        if (foco instanceof TextInputControl || caracter.isEmpty() || Character.isISOControl(caracter.charAt(0))) {
            return;
        }
        campoBusqueda.requestFocus();
        campoBusqueda.appendText(caracter);
        campoBusqueda.end();
        e.consume();
    }

    // =====================================================================
    // Captura de productos
    // =====================================================================

    @FXML
    private void onBuscar() {
        String texto = campoBusqueda.getText();
        if (texto == null || texto.isBlank()) {
            enfocarBusqueda();
            return;
        }
        procesarCaptura();
    }

    private void procesarCaptura() {
        if (!cajaAbierta()) {
            return;
        }
        String texto = campoBusqueda.getText() == null ? "" : campoBusqueda.getText().strip();
        if (texto.isEmpty()) {
            return;
        }
        BigDecimal cantidad = multiplicadorPendiente;
        Matcher m = MULTIPLICADOR.matcher(texto);
        if (m.matches()) {
            cantidad = new BigDecimal(m.group(1));
            texto = m.group(2).strip();
            if (texto.isEmpty()) {
                fijarMultiplicador(cantidad);
                campoBusqueda.clear();
                return;
            }
        }

        Optional<CatalogoService.Escaneo> escaneo = ctx.catalogo().resolverCodigo(texto);
        if (escaneo.isPresent()) {
            BigDecimal peso = escaneo.get().cantidad();
            agregar(escaneo.get().producto(), peso != null ? peso : cantidad);
            return;
        }

        Producto elegido = panelResultados.isVisible() ? listaResultados.getSelectionModel().getSelectedItem() : null;
        if (elegido != null) {
            agregar(elegido, cantidad);
            return;
        }

        List<Producto> encontrados = ctx.catalogo().buscar(texto, MAX_RESULTADOS);
        if (encontrados.size() == 1) {
            agregar(encontrados.getFirst(), cantidad);
        } else if (encontrados.isEmpty()) {
            avisos.error("No se encontró ningún producto con \"" + texto + "\".");
            Animations.shakeX(campoBusqueda, 6).playFromStart();
            campoBusqueda.selectAll();
        } else {
            mostrarResultados(texto, encontrados);
        }
    }

    private void actualizarResultados() {
        String texto = campoBusqueda.getText() == null ? "" : campoBusqueda.getText().strip();
        Matcher m = MULTIPLICADOR.matcher(texto);
        if (m.matches()) {
            texto = m.group(2).strip();
        }
        // Los códigos de barras (solo números largos) no abren la lista: se procesan con Enter.
        if (texto.length() < 2 || texto.matches("\\d{6,}")) {
            ocultarResultados();
            return;
        }
        mostrarResultados(texto, ctx.catalogo().buscar(texto, MAX_RESULTADOS));
    }

    private void mostrarResultados(String texto, List<Producto> productos) {
        listaResultados.getItems().setAll(productos);
        resultadosTitulo.setText(productos.isEmpty() ? "Sin resultados para \"" + texto + "\""
                : productos.size() == 1 ? "1 producto encontrado" : productos.size() + " productos encontrados");
        listaResultados.setPrefHeight(productos.isEmpty() ? 60 : Math.min(7, productos.size()) * 60 + 2);
        if (!productos.isEmpty()) {
            listaResultados.getSelectionModel().selectFirst();
            listaResultados.scrollTo(0);
        }
        if (!panelResultados.isVisible()) {
            panelResultados.setVisible(true);
            Animations.fadeIn(panelResultados, Duration.millis(120)).playFromStart();
        }
    }

    private void ocultarResultados() {
        esperaBusqueda.stop();
        panelResultados.setVisible(false);
    }

    private void moverSeleccion(int delta) {
        if (panelResultados.isVisible() && !listaResultados.getItems().isEmpty()) {
            int i = Math.max(0, Math.min(listaResultados.getItems().size() - 1,
                    listaResultados.getSelectionModel().getSelectedIndex() + delta));
            listaResultados.getSelectionModel().select(i);
            listaResultados.scrollTo(Math.max(0, i - 3));
        } else if (!carrito.vacio()) {
            int actual = tablaCarrito.getSelectionModel().getSelectedIndex();
            int i = Math.max(0, Math.min(carrito.lineas().size() - 1, (actual < 0 ? carrito.lineas().size() : actual) + delta));
            tablaCarrito.getSelectionModel().select(i);
            tablaCarrito.scrollTo(i);
        }
    }

    private void fijarMultiplicador(BigDecimal valor) {
        multiplicadorPendiente = valor != null && valor.signum() > 0 ? valor : null;
        multiplicador.setVisible(multiplicadorPendiente != null);
        if (multiplicadorPendiente != null) {
            multiplicador.setText("× " + multiplicadorPendiente.stripTrailingZeros().toPlainString());
            Animations.pulse(multiplicador).playFromStart();
        }
    }

    /** Agrega un producto; si es a granel y no trae peso, lo pide. */
    private void agregar(Producto producto, BigDecimal cantidad) {
        if (!cajaAbierta()) {
            return;
        }
        BigDecimal cantidadFinal = cantidad != null ? cantidad : multiplicadorPendiente;
        if (cantidadFinal == null && producto.unidad().esGranel()) {
            ocultarResultados();
            DialogoCantidad.mostrar(dialogos, producto, null, c -> confirmarAgregado(producto, c));
            return;
        }
        confirmarAgregado(producto, cantidadFinal == null ? BigDecimal.ONE : cantidadFinal);
    }

    private void confirmarAgregado(Producto producto, BigDecimal cantidad) {
        BigDecimal normalizada = producto.unidad().normalizar(cantidad);
        if (normalizada.signum() <= 0) {
            avisos.advertencia("La cantidad debe ser mayor a cero.");
            limpiarCaptura();
            return;
        }
        long totalAntes = carrito.total();
        LineaVenta linea = carrito.agregar(producto, normalizada);
        limpiarCaptura();
        resaltar(linea);
        mostrarUltimo(producto, normalizada, carrito.total() - totalAntes);
        if (producto.sinExistencia()) {
            avisos.advertencia(producto.nombre() + " no tiene existencia registrada.");
        }
    }

    private void limpiarCaptura() {
        campoBusqueda.clear();
        fijarMultiplicador(null);
        ocultarResultados();
        enfocarBusqueda();
    }

    private void enfocarBusqueda() {
        campoBusqueda.requestFocus();
        campoBusqueda.selectAll();
    }

    private void resaltar(LineaVenta linea) {
        resaltada.set(linea);
        tablaCarrito.getSelectionModel().select(linea);
        tablaCarrito.scrollTo(linea);
        PauseTransition quitar = new PauseTransition(Duration.millis(900));
        quitar.setOnFinished(e -> {
            if (resaltada.get() == linea) {
                resaltada.set(null);
            }
        });
        quitar.play();
    }

    private void mostrarUltimo(Producto producto, BigDecimal cantidad, long importe) {
        ultimoNombre.setText(producto.nombre());
        ultimoDetalle.setText(producto.unidad().formatear(cantidad)
                + (producto.unidad() == Unidad.PZA ? (cantidad.compareTo(BigDecimal.ONE) == 0 ? " pza" : " pzas") : "")
                + "  ×  " + Dinero.formatear(producto.precioCentavos())
                + (producto.unidad() == Unidad.KG ? " /kg" : ""));
        ultimoImporte.setText("+" + Dinero.formatear(importe));
        if (!tarjetaUltimo.isVisible()) {
            tarjetaUltimo.setVisible(true);
        }
        Animations.fadeInRight(tarjetaUltimo, Duration.millis(260)).playFromStart();
    }

    private void ocultarUltimo() {
        tarjetaUltimo.setVisible(false);
        tarjetaUltimo.managedProperty().bind(tarjetaUltimo.visibleProperty());
    }

    // =====================================================================
    // Edición del carrito
    // =====================================================================

    private LineaVenta seleccionada() {
        LineaVenta linea = tablaCarrito.getSelectionModel().getSelectedItem();
        return linea != null ? linea : (carrito.vacio() ? null : carrito.lineas().getLast());
    }

    private void ajustar(LineaVenta linea, int delta) {
        if (linea == null) {
            return;
        }
        if (linea.producto().unidad().esGranel()) {
            editarCantidad(linea);
            return;
        }
        BigDecimal nueva = linea.getCantidad().add(BigDecimal.valueOf(delta));
        if (nueva.signum() <= 0) {
            quitar(linea);
            return;
        }
        carrito.cambiarCantidad(linea, nueva);
        resaltar(linea);
        enfocarBusqueda();
    }

    private void editarCantidadSeleccionada() {
        LineaVenta linea = seleccionada();
        if (linea == null) {
            avisos.info("Primero agrega un producto.");
            return;
        }
        editarCantidad(linea);
    }

    private void editarCantidad(LineaVenta linea) {
        if (linea == null) {
            return;
        }
        DialogoCantidad.mostrar(dialogos, linea.producto(), linea.getCantidad(), c -> {
            carrito.cambiarCantidad(linea, c);
            resaltar(linea);
            enfocarBusqueda();
        });
    }

    private void quitarSeleccionada() {
        LineaVenta linea = tablaCarrito.getSelectionModel().getSelectedItem();
        if (linea == null && !carrito.vacio()) {
            linea = carrito.lineas().getLast();
        }
        quitar(linea);
    }

    private void quitar(LineaVenta linea) {
        if (linea == null) {
            return;
        }
        int indice = carrito.lineas().indexOf(linea);
        carrito.quitar(linea);
        if (carrito.vacio()) {
            ocultarUltimo();
        } else {
            tablaCarrito.getSelectionModel().select(Math.min(indice, carrito.lineas().size() - 1));
        }
        avisos.conAccion("Se quitó " + linea.producto().nombre() + ".", "Deshacer", () -> {
            carrito.lineas().add(Math.min(indice, carrito.lineas().size()), linea);
            resaltar(linea);
        });
        enfocarBusqueda();
    }

    private void cancelarVenta() {
        if (carrito.vacio()) {
            avisos.info("No hay una venta en curso.");
            return;
        }
        DialogoConfirmacion.mostrar(dialogos, "¿Cancelar la venta en curso?",
                "Se quitarán los " + carrito.lineas().size() + " productos capturados. Esta venta aún no se ha cobrado.",
                "Sí, cancelar venta", true, () -> {
                    carrito.vaciar();
                    ocultarUltimo();
                    avisos.info("Venta cancelada.");
                    enfocarBusqueda();
                });
    }

    // =====================================================================
    // Cobro
    // =====================================================================

    @FXML
    private void onCobrar() {
        if (!cajaAbierta()) {
            return;
        }
        if (carrito.vacio()) {
            avisos.advertencia("Agrega productos antes de cobrar.");
            enfocarBusqueda();
            return;
        }
        ocultarResultados();
        DialogoCobro.mostrar(dialogos, carrito.total(), this::registrarVenta);
    }

    private void registrarVenta(List<Pago> pagos) {
        List<VentaService.Renglon> renglones = carrito.lineas().stream()
                .map(l -> new VentaService.Renglon(l.producto(), l.getCantidad()))
                .toList();
        Ticket ticket;
        try {
            ticket = ctx.ventas().registrar(renglones, pagos, sesion, turno);
        } catch (RuntimeException e) {
            log.error("No se pudo registrar la venta", e);
            DialogoConfirmacion.aviso(dialogos, "No se pudo registrar la venta",
                    mensaje(e) + "\n\nLos productos siguen en pantalla; no se perdió nada.", true);
            return;
        }
        carrito.vaciar();
        ocultarUltimo();
        ctx.catalogo().recargar();
        ctx.sincronizador().revisarAhora();
        actualizarInfoTurno();
        DialogoVentaExitosa.mostrar(dialogos, ticket, () -> imprimir(TicketTexto.venta(ticket)), this::enfocarBusqueda);
    }

    private void imprimir(String texto) {
        String error = Impresion.imprimir(texto);
        if (error == null) {
            avisos.exito("Ticket enviado a la impresora.");
        } else {
            avisos.error(error);
        }
    }

    // =====================================================================
    // Ventas en espera
    // =====================================================================

    private void ponerEnEspera() {
        if (!cajaAbierta()) {
            return;
        }
        if (carrito.vacio()) {
            avisos.info("No hay una venta en curso para poner en espera.");
            return;
        }
        DialogoTexto.mostrar(dialogos, "Poner venta en espera", "Atiende a otro cliente y retómala después con F4.",
                "mdi2p-pause-circle-outline", "Nota para identificarla (opcional)", "Ej. Señora de la bolsa roja",
                false, "Poner en espera", nota -> {
                    try {
                        ctx.ventasEspera().ponerEnEspera(sesion.usuario().id(), nota.isBlank() ? null : nota,
                                List.copyOf(carrito.lineas()));
                    } catch (RuntimeException e) {
                        log.error("No se pudo guardar la venta en espera", e);
                        avisos.error("No se pudo poner en espera: " + mensaje(e));
                        return;
                    }
                    restaurando = true;
                    carrito.vaciar();
                    restaurando = false;
                    ocultarUltimo();
                    actualizarContadorEspera();
                    avisos.exito("Venta en espera. Recupérala con F4.");
                    enfocarBusqueda();
                });
    }

    private void recuperarEspera() {
        if (!cajaAbierta()) {
            return;
        }
        List<VentaEspera> ventas = ctx.ventasEspera().listar();
        if (ventas.isEmpty()) {
            avisos.info("No hay ventas en espera.");
            return;
        }
        if (!carrito.vacio()) {
            avisos.advertencia("Cobra o pon en espera la venta actual antes de recuperar otra.");
            return;
        }
        DialogoVentasEspera.mostrar(dialogos, ventas, v -> {
            cargarRenglones(ctx.ventasEspera().recuperar(v.id()));
            actualizarContadorEspera();
            avisos.exito("Venta recuperada.");
            enfocarBusqueda();
        }, v -> DialogoConfirmacion.mostrar(dialogos, "¿Descartar la venta en espera?",
                "Se eliminará la venta pausada" + (v.nota() != null ? " \"" + v.nota() + "\"" : "") + ".",
                "Descartar", true, () -> {
                    ctx.ventasEspera().descartar(v.id());
                    actualizarContadorEspera();
                    avisos.info("Venta en espera descartada.");
                }));
    }

    private void cargarRenglones(List<VentaEsperaRepository.Renglon> renglones) {
        List<LineaVenta> lineas = new ArrayList<>();
        int faltantes = 0;
        for (VentaEsperaRepository.Renglon r : renglones) {
            Optional<Producto> producto = ctx.catalogo().porId(r.presentacionId());
            if (producto.isPresent()) {
                lineas.add(new LineaVenta(producto.get(), r.cantidad()));
            } else {
                faltantes++;
            }
        }
        carrito.reemplazar(lineas);
        if (!lineas.isEmpty()) {
            LineaVenta ultima = lineas.getLast();
            mostrarUltimo(ultima.producto(), ultima.getCantidad(), ultima.getImporte());
        }
        if (faltantes > 0) {
            avisos.advertencia(faltantes + " producto(s) ya no están disponibles y no se agregaron.");
        }
    }

    private void autoguardar() {
        try {
            ctx.ventasEspera().autoguardar(sesion.usuario().id(), List.copyOf(carrito.lineas()));
        } catch (RuntimeException e) {
            log.warn("No se pudo autoguardar la venta en curso", e);
        }
    }

    private void actualizarContadorEspera() {
        int n = ctx.ventasEspera().contar();
        textoRecuperar.setText(n == 0 ? "Recuperar" : "Recuperar (" + n + ")");
        textoRecuperar.getParent().pseudoClassStateChanged(PseudoClass.getPseudoClass("con-pendientes"), n > 0);
    }

    // =====================================================================
    // Turno: ventas, movimientos y corte
    // =====================================================================

    private void verVentasTurno() {
        if (!cajaAbierta()) {
            return;
        }
        Runnable[] refrescar = new Runnable[1];
        refrescar[0] = DialogoVentasTurno.mostrar(dialogos, () -> ctx.ventas().delTurno(turno.id()),
                this::verTicket, v -> cancelarVentaRegistrada(v, refrescar[0]));
    }

    private void verTicket(VentaResumen venta) {
        Ticket ticket = ctx.ventas().ticket(venta.id());
        DialogoTicket.mostrar(dialogos, avisos, "Ticket " + ticket.folio(),
                ticket.cancelada() ? "Venta cancelada" : "Total " + Dinero.formatear(ticket.totalCentavos()),
                TicketTexto.venta(ticket), null);
    }

    private void cancelarVentaRegistrada(VentaResumen venta, Runnable refrescar) {
        DialogoTexto.mostrar(dialogos, "Cancelar venta " + venta.folio(),
                "Total " + Dinero.formatear(venta.totalCentavos()) + ". Los productos regresarán al inventario.",
                "mdi2c-close-circle-outline", "Motivo de la cancelación", "Ej. El cliente devolvió la mercancía",
                true, "Continuar", motivo -> autorizar("cancelar la venta " + venta.folio(), autorizador -> {
                            try {
                                ctx.ventas().cancelar(venta.id(), turno, sesion.usuario(), autorizador, motivo);
                            } catch (RuntimeException e) {
                                avisos.error(mensaje(e));
                                return;
                            }
                            refrescar.run();
                            ctx.catalogo().recargar();
                            actualizarInfoTurno();
                            ctx.sincronizador().revisarAhora();
                            avisos.exito("Venta " + venta.folio() + " cancelada. Devuelve "
                                    + Dinero.formatear(venta.totalCentavos()) + " al cliente.");
                        }));
    }

    private void movimientoCaja() {
        if (!cajaAbierta()) {
            return;
        }
        long disponible = ctx.caja().resumen(turno).efectivoEsperado();
        DialogoMovimientoCaja.mostrar(dialogos, disponible, (tipo, monto, concepto) -> {
            if (tipo == TipoMovimiento.RETIRO) {
                autorizar("retiro de " + Dinero.formatear(monto),
                        autorizador -> registrarMovimiento(tipo, monto, concepto, autorizador));
            } else {
                registrarMovimiento(tipo, monto, concepto, null);
            }
        });
    }

    private void registrarMovimiento(TipoMovimiento tipo, long monto, String concepto, Usuario autorizador) {
        try {
            ctx.caja().registrarMovimiento(turno, tipo, monto, concepto, sesion.usuario(), autorizador);
        } catch (RuntimeException e) {
            avisos.error(mensaje(e));
            return;
        }
        actualizarInfoTurno();
        ctx.sincronizador().revisarAhora();
        avisos.exito((tipo == TipoMovimiento.RETIRO ? "Retiro" : "Entrada") + " de " + Dinero.formatear(monto)
                + " registrado.");
    }

    private void corteCaja() {
        if (!cajaAbierta()) {
            return;
        }
        if (!carrito.vacio()) {
            avisos.advertencia("Cobra, cancela o pon en espera la venta actual antes del corte.");
            return;
        }
        ResumenTurno resumen = ctx.caja().resumen(turno);
        DialogoCorte.mostrar(dialogos, resumen, (contado, notas) -> {
            ResumenTurno final_;
            try {
                final_ = ctx.caja().cerrar(turno, contado, notas, sesion.usuario());
            } catch (RuntimeException e) {
                log.error("No se pudo cerrar el turno", e);
                avisos.error("No se pudo cerrar el turno: " + mensaje(e));
                return;
            }
            String sucursal = ctx.nombreSucursal(sesion.usuario().sucursalId());
            String texto = TicketTexto.corte(final_, contado, sucursal, Instant.now(), notas,
                    sesion.usuario().nombreCompleto());
            long diferencia = contado - final_.efectivoEsperado();
            turno = null;
            actualizarInfoTurno();
            ctx.sincronizador().revisarAhora();
            DialogoTicket.mostrar(dialogos, avisos, "Corte de caja realizado",
                    diferencia == 0 ? "La caja cuadró exacto." : (diferencia < 0 ? "Faltante de " : "Sobrante de ")
                            + Dinero.formatear(Math.abs(diferencia)),
                    texto, this::abrirTurno);
        });
    }

    private void verificarTurno() {
        Optional<Turno> abierto = ctx.caja().turnoAbierto();
        if (abierto.isEmpty()) {
            abrirTurno();
            return;
        }
        Turno t = abierto.get();
        if (t.usuarioId().equals(sesion.usuario().id())) {
            turno = t;
            iniciarTurno();
            return;
        }
        Dialogo d = new Dialogo("Caja con turno abierto",
                "El turno de " + t.usuarioNombre() + " sigue abierto desde las "
                        + HORA.format(t.abiertoEn().atZone(ZoneId.systemDefault())) + ".",
                "mdi2a-alert-outline", Dialogo.Tono.ADVERTENCIA);
        Label texto = new Label("Puedes hacer el corte de ese turno para abrir el tuyo, o continuar vendiendo en él.");
        texto.setWrapText(true);
        texto.getStyleClass().add("dialogo-texto");
        d.setContenido(texto);
        d.agregarBoton("Cerrar sesión", "mdi2l-logout", () -> {
            d.cerrar();
            navegador.cerrarSesion();
        }, "flat");
        d.separarBotones();
        d.agregarBoton("Hacer corte", "mdi2c-calculator-variant-outline", () -> {
            d.cerrar();
            turno = t;
            iniciarTurno();
            corteCaja();
        }, "");
        Button continuar = d.agregarBoton("Continuar turno", null, () -> {
            d.cerrar();
            turno = t;
            iniciarTurno();
        }, "accent");
        d.setFocoInicial(continuar);
        dialogos.mostrar(d);
    }

    /**
     * Operaciones delicadas: un cajero necesita que un supervisor autorice;
     * un supervisor o administrador ya tiene el permiso.
     */
    private void autorizar(String operacion, java.util.function.Consumer<Usuario> accion) {
        if (sesion.usuario().rol() == Rol.CAJERO) {
            DialogoAutorizacion.mostrar(dialogos, ctx.auth(), operacion, accion);
        } else {
            accion.accept(sesion.usuario());
        }
    }

    /**
     * Al volver a esta pantalla (ej. el supervisor regresa de "Cortes de caja"), confirma que
     * el turno siga abierto: pudo haberse hecho el corte desde otra pantalla.
     */
    private void revalidarTurno() {
        if (turno == null) {
            return;
        }
        if (ctx.caja().abiertoPorId(turno.id()).isEmpty()) {
            turno = null;
            if (!carrito.vacio()) {
                abrirTurno();
            } else {
                actualizarInfoTurno();
            }
            avisos.info("Se hizo el corte de esta caja; se abrirá un turno nuevo con la siguiente venta.");
        } else {
            actualizarInfoTurno();
        }
    }

    /** El turno se abre solo al entrar: no se captura fondo inicial. */
    private void abrirTurno() {
        try {
            turno = ctx.caja().abrir(sesion, 0);
        } catch (RuntimeException e) {
            log.error("No se pudo abrir el turno", e);
            avisos.error("No se pudo abrir el turno: " + mensaje(e));
            return;
        }
        ctx.sincronizador().revisarAhora();
        iniciarTurno();
    }

    private void iniciarTurno() {
        actualizarInfoTurno();
        actualizarContadorEspera();
        ctx.ventasEspera().autoguardado(sesion.usuario().id()).ifPresent(renglones -> {
            if (!renglones.isEmpty()) {
                restaurando = true;
                cargarRenglones(renglones);
                restaurando = false;
                avisos.info("Se recuperó la venta que estaba en curso.");
            }
        });
        enfocarBusqueda();
    }

    private boolean cajaAbierta() {
        if (turno == null) {
            abrirTurno();
        }
        return turno != null;
    }

    private void actualizarInfoTurno() {
        if (turno == null) {
            infoTurno.setText("Sin turno · se abre con la siguiente venta");
            infoVentas.setText("");
            return;
        }
        ResumenTurno r = ctx.caja().resumen(turno);
        infoTurno.setText("Turno desde las " + HORA.format(turno.abiertoEn().atZone(ZoneId.systemDefault())));
        infoVentas.setText((r.numeroVentas() == 1 ? "1 venta" : r.numeroVentas() + " ventas") + " · "
                + Dinero.formatear(r.totalVentas()) + " vendido");
    }

    private static String descripcionCorta(Producto p) {
        List<String> partes = new ArrayList<>();
        if (p.clave() != null) {
            partes.add("Clave " + p.clave());
        }
        if (p.codigoInventario() != null) {
            partes.add("Inv. " + p.codigoInventario());
        }
        if (p.codigoBarras() != null) {
            partes.add(p.codigoBarras());
        }
        if (p.categoria() != null) {
            partes.add(p.categoria());
        }
        return String.join("  ·  ", partes);
    }

    private static String mensaje(Throwable e) {
        Throwable causa = e;
        while (causa.getCause() != null && causa.getMessage() == null) {
            causa = causa.getCause();
        }
        return causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
    }
}
