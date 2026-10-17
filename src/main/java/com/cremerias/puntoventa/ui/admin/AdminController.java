package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.ConMenuLateral;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.MenuLateral;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Panel del administrador (almacén central). Menú lateral agrupado por tipo de trabajo; cada
 * página se crea una sola vez (conserva filtros, selección y posición) y se recarga al mostrarse.
 * Los avisos y órdenes pendientes se revisan cada 30 s fuera del hilo de la interfaz.
 *
 * <p>Atajos del panel (no llegan a la caja): Ctrl+F busca en la página y F5 la recarga.
 */
public class AdminController implements ConMenuLateral {

    private record Opcion(String clave, Function<AdminContexto, Pagina> fabrica) {
    }

    /** Lo que cuentan los avisos del menú. */
    private record Conteos(int ordenes, int bloqueos, int sinVenta, int inventario) {
    }

    private final Navegador navegador;
    private final Sesion sesion;
    private final Map<String, Opcion> opciones = new LinkedHashMap<>();
    private final Map<String, Pagina> paginas = new LinkedHashMap<>();
    private final Cargador conteos = new Cargador();
    private final MenuLateral menu;
    private AdminContexto contexto;
    private String actual;
    private int ordenesAnteriores = -1;
    /** Por debajo de este ancho de ventana el menú queda solo con íconos. */
    private static final double ANCHO_MENU_COMPACTO = 1180;
    private boolean ventanaAngosta;
    private boolean ajusteAutomatico;
    private int bloqueosAnteriores = -1;

    @FXML private StackPane contenido;

    public AdminController(Navegador navegador) {
        this.navegador = navegador;
        this.sesion = navegador.contexto().sesionActual().obtener().orElseThrow();
        this.menu = new MenuLateral(navegador.contexto().nombreSucursal(sesion.usuario().sucursalId()));
    }

    @Override
    public MenuLateral menuLateral() {
        return menu;
    }

    @FXML
    private void initialize() {
        contexto = new AdminContexto(navegador, navegador.contexto(), navegador.contexto().admin(), sesion.usuario(),
                navegador.dialogos(), navegador.avisos(), this::actualizarContadores, this::ir);

        opcion("avisos", "Avisos", "mdi2b-bell-outline", AvisosPagina::new);
        menu.grupo("Almacén");
        opcion("productos", "Productos", "mdi2p-package-variant-closed", ProductosPagina::new);
        opcion("precios", "Precios y márgenes", "mdi2p-percent-outline", PreciosPagina::new);
        opcion("almacen", "Inventario", "mdi2w-warehouse", AlmacenPagina::new);
        opcion("compras", "Compras", "mdi2c-cart-arrow-down", ComprasPagina::new);
        opcion("ajustes", "Ajustes de inventario", "mdi2c-clipboard-edit-outline", AjustesPagina::new);
        opcion("surtidos", "Surtidos", "mdi2t-truck-delivery-outline", SurtidosPagina::new);
        opcion("ordenes", "Órdenes de reabasto", "mdi2c-clipboard-list-outline", OrdenesPagina::new);
        menu.grupo("Sucursales");
        opcion("sucursales", "Sucursales", "mdi2s-storefront-outline", SucursalesPagina::new);
        opcion("productos-sucursal", "Productos por sucursal", "mdi2v-view-list-outline", ProductosSucursalPagina::new);
        opcion("limites", "Mínimos y máximos", "mdi2t-tune-vertical", LimitesPagina::new);
        opcion("devoluciones", "Devoluciones", "mdi2k-keyboard-return", DevolucionesPagina::new);
        menu.grupo("Finanzas");
        opcion("credito", "Crédito", "mdi2c-credit-card-clock-outline", CreditoPagina::new);
        opcion("gastos", "Gastos", "mdi2c-cash-minus", GastosPagina::new);
        opcion("cortes", "Cortes de caja", "mdi2c-calculator-variant-outline", CortesPagina::new);
        menu.grupo("Personal");
        opcion("usuarios", "Usuarios y horarios", "mdi2a-account-group-outline", UsuariosPagina::new);
        opcion("nomina", "Nómina", "mdi2a-account-cash-outline", NominaPagina::new);
        opcion("comisiones", "Comisiones", "mdi2h-hand-coin-outline", ComisionesPagina::new);
        menu.grupo("Análisis");
        opcion("indicadores", "Indicadores", "mdi2c-chart-line", IndicadoresPagina::new);
        opcion("historial", "Historial", "mdi2h-history", HistorialPagina::new);

        boolean preferencia = navegador.contexto().preferencias().menuCompacto();
        menu.compactoProperty().set(preferencia);
        menu.compactoProperty().addListener((o, a, b) -> {
            if (!ajusteAutomatico) {
                navegador.contexto().preferencias().guardarMenuCompacto(b);
            }
        });
        // En ventanas angostas (o con escalado alto) el menú se compacta solo, sin cambiar la preferencia.
        contenido.sceneProperty().addListener((o, a, escena) -> {
            if (escena != null) {
                escena.widthProperty().addListener((x, antes, ancho) -> ajustarMenu(ancho.doubleValue()));
                ajustarMenu(escena.getWidth());
            }
        });

        actualizarContadores();
        ir("avisos");

        contenido.addEventFilter(KeyEvent.KEY_PRESSED, this::atajos);
        Timeline revision = new Timeline(new KeyFrame(Duration.seconds(30), e -> actualizarContadores()));
        revision.setCycleCount(Timeline.INDEFINITE);
        revision.play();
        contenido.sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                revision.stop();
                conteos.cancelar();
            }
        });
    }

    private void ajustarMenu(double ancho) {
        boolean angosta = ancho > 0 && ancho < ANCHO_MENU_COMPACTO;
        if (angosta == ventanaAngosta) {
            return;
        }
        ventanaAngosta = angosta;
        ajusteAutomatico = true;
        menu.compactoProperty().set(angosta || navegador.contexto().preferencias().menuCompacto());
        ajusteAutomatico = false;
    }

    private void opcion(String clave, String titulo, String icono, Function<AdminContexto, Pagina> fabrica) {
        opciones.put(clave, new Opcion(clave, fabrica));
        menu.opcion(clave, titulo, icono, () -> ir(clave));
    }

    private void atajos(KeyEvent e) {
        if (navegador.dialogos().hayAbierto() || actual == null) {
            return;
        }
        Pagina pagina = paginas.get(actual);
        if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
            TextField busqueda = pagina.busqueda();
            if (busqueda != null) {
                busqueda.requestFocus();
                busqueda.selectAll();
                e.consume();
            }
        } else if (e.getCode() == KeyCode.F5) {
            mostrar(pagina);
            e.consume();
        }
    }

    /** Muestra una sección; si la actual tiene cambios sin guardar, pregunta antes de salir. */
    private void ir(String clave) {
        if (clave.equals(actual)) {
            menu.seleccionar(clave);
            return;
        }
        Pagina saliente = actual == null ? null : paginas.get(actual);
        if (saliente != null && saliente.cambiosPendientes()) {
            menu.seleccionar(actual);
            DialogoConfirmacion.mostrar(contexto.dialogos(), "¿Salir sin guardar?",
                    "Tienes cambios sin guardar en " + saliente.titulo() + ". Si sales ahora, se descartan.",
                    "Descartar y salir", true, () -> {
                        saliente.descartarCambios();
                        cambiarA(clave);
                    });
            return;
        }
        cambiarA(clave);
    }

    private void cambiarA(String clave) {
        Opcion opcion = opciones.get(clave);
        actual = clave;
        menu.seleccionar(clave);
        Pagina pagina = paginas.computeIfAbsent(clave, k -> opcion.fabrica().apply(contexto));
        contenido.getChildren().setAll(pagina.vista());
        mostrar(pagina);
    }

    private void mostrar(Pagina pagina) {
        try {
            pagina.alMostrar();
        } catch (RuntimeException e) {
            contexto.avisos().error(AdminContexto.mensaje(e));
        }
    }

    /** Cuenta los pendientes en segundo plano y avisa de lo nuevo. */
    private void actualizarContadores() {
        conteos.cargar(() -> {
            contexto.admin().productos().revisarTemporadas();
            return new Conteos(contexto.admin().ordenes().contarPendientes(),
                    contexto.ctx().auth().accesos().contarPendientes(),
                    contexto.admin().alertas().productosSinVenta().size(),
                    contexto.ctx().reconciliacion().negativos(contexto.usuario()).size()
                            + contexto.admin().recepciones().diferencias(true, contexto.usuario()).size());
        }, c -> {
            menu.setContador("ordenes", c.ordenes());
            menu.setContador("avisos", c.ordenes() + c.bloqueos() + c.sinVenta() + c.inventario());
            if (ordenesAnteriores >= 0 && c.ordenes() > ordenesAnteriores) {
                contexto.avisos().conAccion("Llegó una nueva orden de reabastecimiento.", "Ver", () -> ir("ordenes"));
            }
            ordenesAnteriores = c.ordenes();
            if (bloqueosAnteriores >= 0 && c.bloqueos() > bloqueosAnteriores) {
                contexto.avisos().conAccion("Un empleado tiene el acceso bloqueado por horario.", "Ver",
                        () -> ir("avisos"));
            }
            bloqueosAnteriores = c.bloqueos();
        }, e -> {
            // Los contadores no deben interrumpir el trabajo.
        });
    }
}
