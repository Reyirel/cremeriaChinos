package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.service.InventarioSucursalService.Bajo;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.caja.CajaController;
import com.cremerias.puntoventa.ui.componentes.IndicadoresVista;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Panel del supervisor: puede vender como un cajero, hacer el corte de las cajas de su sucursal
 * y consultar (sin editar) los productos de su sucursal.
 * Las vistas se crean una sola vez para no perder la venta en curso al cambiar de sección.
 */
public class SupervisorController {

    private final Navegador navegador;
    private final Sesion sesion;
    private final ToggleGroup menu = new ToggleGroup();
    private Parent vistaCaja;
    private Navegador.Vista<CortesController> vistaCortes;
    private ProductosVista vistaProductos;
    private IndicadoresVista vistaIndicadores;
    private AccesosVista vistaAccesos;
    private int bloqueosAnteriores = -1;
    /** Productos en su mínimo de los que ya se avisó; nulo hasta la primera revisión. */
    private Set<String> minimosAvisados;

    @FXML private ToggleButton opcionCaja;
    @FXML private ToggleButton opcionCortes;
    @FXML private ToggleButton opcionProductos;
    @FXML private ToggleButton opcionIndicadores;
    @FXML private ToggleButton opcionAccesos;
    @FXML private StackPane contenido;
    @FXML private Label nombreSucursal;
    @FXML private Label contadorAbiertas;
    @FXML private Label contadorOfertar;
    @FXML private Label detalleProductos;
    @FXML private Label contadorAccesos;

    public SupervisorController(Navegador navegador) {
        this.navegador = navegador;
        this.sesion = navegador.contexto().sesionActual().obtener().orElseThrow();
    }

    @FXML
    private void initialize() {
        opcionCaja.setToggleGroup(menu);
        opcionCortes.setToggleGroup(menu);
        opcionProductos.setToggleGroup(menu);
        opcionIndicadores.setToggleGroup(menu);
        opcionAccesos.setToggleGroup(menu);
        menu.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null && antes != null) {
                antes.setSelected(true);
            }
        });
        nombreSucursal.setText(navegador.contexto().nombreSucursal(sesion.usuario().sucursalId()));
        contadorAbiertas.managedProperty().bind(contadorAbiertas.visibleProperty());
        contadorOfertar.managedProperty().bind(contadorOfertar.visibleProperty());
        contadorAccesos.managedProperty().bind(contadorAccesos.visibleProperty());
        contadorOfertar.setTooltip(new Tooltip("Productos que llevan tiempo sin venderse: ofértalos"));
        detalleProductos.setTooltip(new Tooltip("Existencias y precios de tu sucursal"));
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

    @FXML
    private void onCaja() {
        opcionCaja.setSelected(true);
        if (vistaCaja == null) {
            vistaCaja = navegador.cargar("caja-view.fxml");
        }
        contenido.getChildren().setAll(vistaCaja);
        actualizarContador();
    }

    @FXML
    private void onCortes() {
        opcionCortes.setSelected(true);
        if (vistaCortes == null) {
            vistaCortes = navegador.cargarVista("supervisor/cortes-view.fxml");
            vistaCortes.controlador().setAlCambiar(this::actualizarContador);
        } else {
            vistaCortes.controlador().actualizar();
        }
        contenido.getChildren().setAll(vistaCortes.nodo());
    }

    @FXML
    private void onProductos() {
        opcionProductos.setSelected(true);
        if (vistaProductos == null) {
            vistaProductos = new ProductosVista(navegador);
        }
        vistaProductos.actualizar();
        contenido.getChildren().setAll(vistaProductos.nodo());
        actualizarContador();
    }

    @FXML
    private void onIndicadores() {
        opcionIndicadores.setSelected(true);
        if (vistaIndicadores == null) {
            // sin selector de sucursal: siempre la propia del supervisor, también validado en el servicio.
            vistaIndicadores = new IndicadoresVista(navegador.contexto().indicadores(), sesion.usuario(), null,
                    navegador.contexto()::nombreSucursal, navegador.dialogos(), navegador.avisos());
        }
        vistaIndicadores.actualizar();
        contenido.getChildren().setAll(vistaIndicadores.nodo());
    }

    @FXML
    private void onAccesos() {
        opcionAccesos.setSelected(true);
        if (vistaAccesos == null) {
            vistaAccesos = new AccesosVista(navegador);
        }
        vistaAccesos.actualizar();
        contenido.getChildren().setAll(vistaAccesos.nodo());
    }

    private void actualizarContador() {
        String sucursal = sesion.usuario().sucursalId();
        int abiertas = sucursal == null ? 0 : navegador.contexto().caja().abiertosDeSucursal(sucursal).size();
        contadorAbiertas.setText(String.valueOf(abiertas));
        int ofertar = sucursal == null ? 0 : navegador.contexto().sinVenta().pendientes(Rol.SUPERVISOR, sucursal).size();
        contadorOfertar.setText(String.valueOf(ofertar));
        contadorOfertar.setVisible(ofertar > 0);
        contadorAbiertas.setVisible(abiertas > 0);
        int bloqueos = sucursal == null ? 0 : navegador.contexto().auth().accesos().pendientesDeSucursal(sucursal).size();
        contadorAccesos.setText(String.valueOf(bloqueos));
        contadorAccesos.setVisible(bloqueos > 0);
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
        detalleProductos.setText(bajos.isEmpty() ? "Existencias y precios" : bajos.size() + " en su mínimo");
        detalleProductos.getStyleClass().remove("menu-opcion-alerta");
        if (!bajos.isEmpty()) {
            detalleProductos.getStyleClass().add("menu-opcion-alerta");
        }
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
