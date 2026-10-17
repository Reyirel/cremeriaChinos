package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.service.InventarioSucursalService.Bajo;
import com.cremerias.puntoventa.ui.ConMenuLateral;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.admin.AdminContexto;
import com.cremerias.puntoventa.ui.admin.AjustesPagina;
import com.cremerias.puntoventa.ui.admin.DevolucionesPagina;
import com.cremerias.puntoventa.ui.admin.Pagina;
import com.cremerias.puntoventa.ui.caja.CajaController;
import com.cremerias.puntoventa.ui.componentes.IndicadoresVista;
import com.cremerias.puntoventa.ui.componentes.MenuLateral;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Panel del supervisor: puede vender como un cajero, hacer el corte de las cajas de su sucursal,
 * confirmar los surtidos que llegan, ajustar el inventario de su sucursal, consultar sus
 * devoluciones y consultar (sin editar) los productos de su sucursal.
 * Las vistas se crean una sola vez para no perder la venta en curso al cambiar de sección.
 */
public class SupervisorController implements ConMenuLateral {

    private final Navegador navegador;
    private final Sesion sesion;
    private final MenuLateral menu;
    private Parent vistaCaja;
    private Navegador.Vista<CortesController> vistaCortes;
    private ProductosVista vistaProductos;
    private IndicadoresVista vistaIndicadores;
    private AccesosVista vistaAccesos;
    private RecepcionVista vistaRecepcion;
    private Pagina paginaAjustes;
    private Pagina paginaDevoluciones;
    private int bloqueosAnteriores = -1;
    /** Productos en su mínimo de los que ya se avisó; nulo hasta la primera revisión. */
    private Set<String> minimosAvisados;

    @FXML private StackPane contenido;

    public SupervisorController(Navegador navegador) {
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
        menu.opcion("caja", "Caja", "mdi2c-cash-register", this::onCaja);
        menu.setDetalle("caja", "Vender y cobrar", false);
        menu.opcion("cortes", "Cortes de caja", "mdi2c-calculator-variant-outline", this::onCortes);
        menu.setDetalle("cortes", "De tu sucursal", false);
        menu.opcion("recepcion", "Recepción", "mdi2t-truck-check-outline", this::onRecepcion);
        menu.setDetalle("recepcion", "Surtidos que llegan", false);
        menu.opcion("ajustes", "Ajustes", "mdi2c-clipboard-edit-outline", this::onAjustes);
        menu.setDetalle("ajustes", "Inventario de tu sucursal", false);
        menu.opcion("devoluciones", "Devoluciones", "mdi2k-keyboard-return", this::onDevoluciones);
        menu.setDetalle("devoluciones", "De tus clientes", false);
        menu.opcion("productos", "Productos", "mdi2p-package-variant-closed", this::onProductos);
        menu.setDetalle("productos", "Existencias y precios", false);
        menu.opcion("indicadores", "Indicadores", "mdi2c-chart-line", this::onIndicadores);
        menu.setDetalle("indicadores", "De tu sucursal", false);
        menu.opcion("accesos", "Accesos", "mdi2a-account-lock-outline", this::onAccesos);
        menu.setDetalle("accesos", "Bloqueados por horario", false);

        // En la caja los avisos quedan arriba del botón de cobrar; en las demás vistas, en la esquina.
        contenido.getChildren().addListener((ListChangeListener<Node>) c ->
                navegador.avisos().setMargenInferior(vistaCaja != null && contenido.getChildren().contains(vistaCaja)
                        ? 160 : 24));
        // Al cobrar en la caja se revisa enseguida si algún producto llegó a su mínimo.
        contenido.addEventHandler(CajaController.VENTA_REGISTRADA, e -> actualizarContador());
        // El aviso de mínimos espera a que el panel esté en pantalla (al mostrarlo se limpian los avisos).
        Platform.runLater(this::actualizarContador);
        actualizarContador();
        onCaja();

        // Los productos para ofertar cambian con el tiempo, no solo al navegar.
        Timeline revision = new Timeline(new KeyFrame(Duration.seconds(30), e -> actualizarContador()));
        revision.setCycleCount(Timeline.INDEFINITE);
        revision.play();
        contenido.sceneProperty().addListener((o, a, b) -> {
            if (b == null) {
                revision.stop();
            }
        });
    }

    private void onCaja() {
        menu.seleccionar("caja");
        if (vistaCaja == null) {
            vistaCaja = navegador.cargar("caja-view.fxml");
        }
        contenido.getChildren().setAll(vistaCaja);
        actualizarContador();
    }

    private void onCortes() {
        menu.seleccionar("cortes");
        if (vistaCortes == null) {
            vistaCortes = navegador.cargarVista("supervisor/cortes-view.fxml");
            vistaCortes.controlador().setAlCambiar(this::actualizarContador);
        } else {
            vistaCortes.controlador().actualizar();
        }
        contenido.getChildren().setAll(vistaCortes.nodo());
    }

    private void onProductos() {
        menu.seleccionar("productos");
        if (vistaProductos == null) {
            vistaProductos = new ProductosVista(navegador);
        }
        vistaProductos.actualizar();
        contenido.getChildren().setAll(vistaProductos.nodo());
        actualizarContador();
    }

    private void onRecepcion() {
        menu.seleccionar("recepcion");
        if (vistaRecepcion == null) {
            vistaRecepcion = new RecepcionVista(navegador, this::actualizarContador);
        }
        vistaRecepcion.actualizar();
        contenido.getChildren().setAll(vistaRecepcion.nodo());
        actualizarContador();
    }

    private void onAjustes() {
        menu.seleccionar("ajustes");
        if (paginaAjustes == null) {
            paginaAjustes = new AjustesPagina(contextoSucursal(), sucursal());
        }
        mostrar(paginaAjustes);
    }

    private void onDevoluciones() {
        menu.seleccionar("devoluciones");
        if (paginaDevoluciones == null) {
            paginaDevoluciones = new DevolucionesPagina(contextoSucursal(), sucursal());
        }
        mostrar(paginaDevoluciones);
    }

    private void mostrar(Pagina pagina) {
        contenido.getChildren().setAll(pagina.vista());
        try {
            pagina.alMostrar();
        } catch (RuntimeException e) {
            navegador.avisos().error(AdminContexto.mensaje(e));
        }
    }

    /** Las páginas compartidas con el administrador, limitadas a la sucursal del supervisor. */
    private AdminContexto contextoSucursal() {
        return new AdminContexto(navegador, navegador.contexto(), navegador.contexto().admin(), sesion.usuario(),
                navegador.dialogos(), navegador.avisos(), this::actualizarContador, clave -> { });
    }

    private com.cremerias.puntoventa.model.Sucursal sucursal() {
        String id = sesion.usuario().sucursalId();
        return navegador.contexto().admin().sucursales().listar().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Tu usuario no tiene una sucursal asignada."));
    }

    private void onIndicadores() {
        menu.seleccionar("indicadores");
        if (vistaIndicadores == null) {
            // sin selector de sucursal: siempre la propia del supervisor, también validado en el servicio.
            vistaIndicadores = new IndicadoresVista(navegador.contexto().indicadores(), sesion.usuario(), null,
                    navegador.contexto()::nombreSucursal, navegador.dialogos(), navegador.avisos());
        }
        vistaIndicadores.actualizar();
        contenido.getChildren().setAll(vistaIndicadores.nodo());
    }

    private void onAccesos() {
        menu.seleccionar("accesos");
        if (vistaAccesos == null) {
            vistaAccesos = new AccesosVista(navegador);
        }
        vistaAccesos.actualizar();
        contenido.getChildren().setAll(vistaAccesos.nodo());
    }

    private void actualizarContador() {
        String sucursal = sesion.usuario().sucursalId();
        int abiertas = sucursal == null ? 0 : navegador.contexto().caja().abiertosDeSucursal(sucursal).size();
        menu.setContador("cortes", abiertas);
        int ofertar = sucursal == null ? 0 : navegador.contexto().sinVenta().pendientes(Rol.SUPERVISOR, sucursal).size();
        menu.setContador("productos", ofertar);
        int bloqueos = sucursal == null ? 0 : navegador.contexto().auth().accesos().pendientesDeSucursal(sucursal).size();
        menu.setContador("accesos", bloqueos);
        int porRecibir = sucursal == null ? 0
                : navegador.contexto().admin().recepciones().pendientes(sucursal, sesion.usuario()).size();
        menu.setContador("recepcion", porRecibir);
        menu.setDetalle("recepcion", porRecibir == 0 ? "Surtidos que llegan"
                : porRecibir == 1 ? "1 surtido por confirmar" : porRecibir + " surtidos por confirmar", porRecibir > 0);
        if (bloqueosAnteriores >= 0 && bloqueos > bloqueosAnteriores) {
            navegador.avisos().conAccion("Un empleado de tu sucursal tiene el acceso bloqueado.", "Ver",
                    this::onAccesos);
        }
        bloqueosAnteriores = bloqueos;
        revisarMinimos(sucursal);
    }

    /**
     * Cuenta los productos en su mínimo y avisa de los que acaban de llegar. No detiene la venta:
     * lo que queda se sigue vendiendo y el aviso es para que el supervisor vea el reabastecimiento.
     */
    private void revisarMinimos(String sucursal) {
        List<Bajo> bajos = sucursal == null ? List.of() : navegador.contexto().inventarioSucursal().enMinimo(sucursal);
        menu.setDetalle("productos", bajos.isEmpty() ? "Existencias y precios" : bajos.size() + " en su mínimo",
                !bajos.isEmpty());
        if (contenido.getScene() == null) {
            return;
        }

        boolean primeraVez = minimosAvisados == null;
        List<Bajo> nuevos = bajos.stream()
                .filter(b -> primeraVez || !minimosAvisados.contains(b.productoId()))
                .toList();
        // Al surtirse sale de la lista; si vuelve a bajar a su mínimo se avisa otra vez.
        minimosAvisados = bajos.stream().map(Bajo::productoId).collect(Collectors.toSet());
        if (nuevos.isEmpty()) {
            return;
        }
        String mensaje;
        if (primeraVez) {
            mensaje = nuevos.size() == 1
                    ? "«" + nuevos.getFirst().nombre() + "» está en su mínimo: " + detalle(nuevos.getFirst())
                    : "Hay " + nuevos.size() + " productos en su mínimo. Ve tomando el reabastecimiento.";
        } else {
            mensaje = nuevos.size() == 1
                    ? "«" + nuevos.getFirst().nombre() + "» llegó a su mínimo: " + detalle(nuevos.getFirst())
                    : nuevos.size() + " productos llegaron a su mínimo. Ve tomando el reabastecimiento.";
        }
        navegador.avisos().conAccion(mensaje, "Ver", this::onProductos);
    }

    /** "quedan 8 pzas (mínimo 10). Ya se pidió al administrador (ORD-…)." */
    private static String detalle(Bajo b) {
        String queda = b.agotado() ? "ya no queda" : "quedan " + Cantidades.formatear(b.unidad(), b.existencia());
        String orden = b.ordenPendiente() != null
                ? " Ya se le pidió al administrador (" + b.ordenPendiente() + ")."
                : " Pide el reabastecimiento al administrador.";
        return queda + " (mínimo " + Cantidades.formatear(b.unidad(), b.minimo()) + ")." + orden;
    }
}
