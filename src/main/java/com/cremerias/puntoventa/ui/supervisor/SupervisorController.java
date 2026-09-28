package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.Navegador;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

/**
 * Panel del supervisor: puede vender como un cajero y hacer el corte de las cajas de su sucursal.
 * Las vistas se crean una sola vez para no perder la venta en curso al cambiar de sección.
 */
public class SupervisorController {

    private final Navegador navegador;
    private final Sesion sesion;
    private final ToggleGroup menu = new ToggleGroup();
    private Parent vistaCaja;
    private Navegador.Vista<CortesController> vistaCortes;

    @FXML private ToggleButton opcionCaja;
    @FXML private ToggleButton opcionCortes;
    @FXML private StackPane contenido;
    @FXML private Label nombreSucursal;
    @FXML private Label contadorAbiertas;

    public SupervisorController(Navegador navegador) {
        this.navegador = navegador;
        this.sesion = navegador.contexto().sesionActual().obtener().orElseThrow();
    }

    @FXML
    private void initialize() {
        opcionCaja.setToggleGroup(menu);
        opcionCortes.setToggleGroup(menu);
        menu.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null && antes != null) {
                antes.setSelected(true);
            }
        });
        nombreSucursal.setText(navegador.contexto().nombreSucursal(sesion.usuario().sucursalId()));
        contadorAbiertas.managedProperty().bind(contadorAbiertas.visibleProperty());
        actualizarContador();
        onCaja();
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

    private void actualizarContador() {
        String sucursal = sesion.usuario().sucursalId();
        int abiertas = sucursal == null ? 0 : navegador.contexto().caja().abiertosDeSucursal(sucursal).size();
        contadorAbiertas.setText(String.valueOf(abiertas));
        contadorAbiertas.setVisible(abiertas > 0);
    }
}
