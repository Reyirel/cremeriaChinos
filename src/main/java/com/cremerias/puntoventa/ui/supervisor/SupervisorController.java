package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.componentes.IndicadoresVista;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

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

    @FXML private ToggleButton opcionCaja;
    @FXML private ToggleButton opcionCortes;
    @FXML private ToggleButton opcionProductos;
    @FXML private ToggleButton opcionIndicadores;
    @FXML private ToggleButton opcionAccesos;
    @FXML private StackPane contenido;
    @FXML private Label nombreSucursal;
    @FXML private Label contadorAbiertas;
    @FXML private Label contadorOfertar;
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
    }
}
