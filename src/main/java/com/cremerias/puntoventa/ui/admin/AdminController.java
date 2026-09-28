package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.Navegador;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Panel del administrador (almacén central). Menú lateral por secciones; cada página se crea
 * una sola vez y se recarga al mostrarse. Los avisos y órdenes pendientes se revisan cada 30 s.
 */
public class AdminController {

    private record Opcion(String clave, String titulo, String icono, Function<AdminContexto, Pagina> fabrica) {
    }

    private final Navegador navegador;
    private final Sesion sesion;
    private final ToggleGroup grupo = new ToggleGroup();
    private final Map<String, Opcion> opciones = new LinkedHashMap<>();
    private final Map<String, ToggleButton> botones = new LinkedHashMap<>();
    private final Map<String, Pagina> paginas = new LinkedHashMap<>();
    private final Map<String, Label> contadores = new LinkedHashMap<>();
    private AdminContexto contexto;
    private int ordenesAnteriores = -1;

    @FXML private VBox menu;
    @FXML private StackPane contenido;

    public AdminController(Navegador navegador) {
        this.navegador = navegador;
        this.sesion = navegador.contexto().sesionActual().obtener().orElseThrow();
    }

    @FXML
    private void initialize() {
        contexto = new AdminContexto(navegador, navegador.contexto(), navegador.contexto().admin(), sesion.usuario(),
                navegador.dialogos(), navegador.avisos(), this::actualizarContadores, this::ir);

        seccion("GENERAL");
        opcion("avisos", "Avisos", "mdi2b-bell-outline", AvisosPagina::new);
        seccion("ALMACÉN");
        opcion("productos", "Productos", "mdi2p-package-variant-closed", ProductosPagina::new);
        opcion("almacen", "Inventario del almacén", "mdi2w-warehouse", AlmacenPagina::new);
        opcion("surtidos", "Surtir sucursales", "mdi2t-truck-delivery-outline", SurtidosPagina::new);
        opcion("ordenes", "Órdenes de reabasto", "mdi2c-clipboard-list-outline", OrdenesPagina::new);
        seccion("SUCURSALES");
        opcion("sucursales", "Sucursales", "mdi2s-storefront-outline", SucursalesPagina::new);
        opcion("limites", "Mínimos y máximos", "mdi2t-tune-vertical", LimitesPagina::new);
        opcion("credito", "Crédito de sucursales", "mdi2c-credit-card-clock-outline", CreditoPagina::new);
        opcion("cortes", "Cortes de caja", "mdi2c-calculator-variant-outline", CortesPagina::new);
        seccion("PERSONAL");
        opcion("usuarios", "Usuarios y horarios", "mdi2a-account-group-outline", UsuariosPagina::new);
        opcion("comisiones", "Comisiones", "mdi2h-hand-coin-outline", ComisionesPagina::new);
        seccion("REGISTRO");
        opcion("historial", "Historial", "mdi2h-history", HistorialPagina::new);

        grupo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null && antes != null) {
                antes.setSelected(true);
            }
        });
        actualizarContadores();
        ir("avisos");

        Timeline revision = new Timeline(new KeyFrame(Duration.seconds(30), e -> actualizarContadores()));
        revision.setCycleCount(Timeline.INDEFINITE);
        revision.play();
        contenido.sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                revision.stop();
            }
        });
    }

    private void seccion(String titulo) {
        Label l = new Label(titulo);
        l.getStyleClass().add("menu-titulo");
        menu.getChildren().add(l);
    }

    private void opcion(String clave, String titulo, String icono, Function<AdminContexto, Pagina> fabrica) {
        opciones.put(clave, new Opcion(clave, titulo, icono, fabrica));
        Label texto = new Label(titulo);
        texto.getStyleClass().add("menu-opcion-titulo");
        Label contador = new Label();
        contador.getStyleClass().add("menu-contador");
        contador.setVisible(false);
        contador.managedProperty().bind(contador.visibleProperty());
        contadores.put(clave, contador);
        Region espacio = new Region();
        HBox.setHgrow(espacio, Priority.ALWAYS);
        HBox fila = new HBox(12, new FontIcon(icono), texto, espacio, contador);
        fila.setAlignment(Pos.CENTER_LEFT);
        ToggleButton boton = new ToggleButton(null, fila);
        boton.getStyleClass().add("menu-opcion");
        boton.setMaxWidth(Double.MAX_VALUE);
        boton.setMaxHeight(Region.USE_PREF_SIZE);
        boton.setToggleGroup(grupo);
        boton.setOnAction(e -> ir(clave));
        fila.prefWidthProperty().bind(boton.widthProperty().subtract(26));
        botones.put(clave, boton);
        menu.getChildren().add(boton);
    }

    /** Muestra una sección del panel. */
    private void ir(String clave) {
        Opcion opcion = opciones.get(clave);
        botones.get(clave).setSelected(true);
        Pagina pagina = paginas.computeIfAbsent(clave, k -> opcion.fabrica().apply(contexto));
        contenido.getChildren().setAll(pagina.vista());
        try {
            pagina.alMostrar();
        } catch (RuntimeException e) {
            contexto.avisos().error(AdminContexto.mensaje(e));
        }
    }

    private void actualizarContadores() {
        try {
            int ordenes = contexto.admin().ordenes().contarPendientes();
            int bloqueos = contexto.ctx().auth().accesos().contarPendientes();
            int sinVenta = contexto.admin().alertas().productosSinVenta().size();
            poner("ordenes", ordenes);
            poner("avisos", ordenes + bloqueos + sinVenta);
            if (ordenesAnteriores >= 0 && ordenes > ordenesAnteriores) {
                contexto.avisos().conAccion("Llegó una nueva orden de reabastecimiento.", "Ver", () -> ir("ordenes"));
            }
            ordenesAnteriores = ordenes;
        } catch (RuntimeException e) {
            // Los contadores no deben interrumpir el trabajo.
        }
    }

    private void poner(String clave, int valor) {
        Label l = contadores.get(clave);
        l.setText(String.valueOf(valor));
        l.setVisible(valor > 0);
    }
}
