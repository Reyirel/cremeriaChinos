package com.cremerias.puntoventa.ui;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Marco común después de iniciar sesión: barra superior con el usuario y la
 * conexión, y al centro la pantalla propia de cada rol.
 */
public class InicioController {

    private final Navegador navegador;

    @FXML private Label etiquetaRol;
    @FXML private Label etiquetaSucursal;
    @FXML private Label avatar;
    @FXML private Label nombreUsuario;
    @FXML private Label rolUsuario;
    @FXML private HBox barraEstado;
    @FXML private Button botonTema;
    @FXML private StackPane contenido;

    public InicioController(Navegador navegador) {
        this.navegador = navegador;
    }

    @FXML
    private void initialize() {
        Sesion sesion = navegador.contexto().sesionActual().obtener()
                .orElseThrow(() -> new IllegalStateException("No hay sesión activa"));
        Rol rol = sesion.usuario().rol();

        etiquetaRol.setText(rol.nombre());
        etiquetaRol.setGraphic(new FontIcon(rol.icono()));
        etiquetaRol.getStyleClass().add(rol.claseCss());
        etiquetaSucursal.setText(navegador.contexto().nombreSucursal(sesion.usuario().sucursalId()));

        avatar.setText(sesion.usuario().iniciales());
        avatar.getStyleClass().add(rol.claseCss());
        nombreUsuario.setText(sesion.usuario().nombreCompleto());
        rolUsuario.setText(rol.nombre());

        barraEstado.getChildren().add(new IndicadorConexion(navegador.contexto().sincronizador()));
        actualizarIconoTema();

        if (rol == Rol.CAJERO || rol == Rol.SUPERVISOR || rol == Rol.ADMINISTRADOR) {
            contenido.getStyleClass().add("sin-margen");
        }
        contenido.getChildren().setAll(navegador.cargar(vistaDe(rol)));
    }

    private static String vistaDe(Rol rol) {
        return switch (rol) {
            case ADMINISTRADOR -> "roles/administrador-view.fxml";
            case SUPERVISOR -> "roles/supervisor-view.fxml";
            case CAJERO -> "caja-view.fxml";
        };
    }

    @FXML
    private void onCerrarSesion() {
        navegador.cerrarSesion();
    }

    @FXML
    private void onAlternarTema() {
        navegador.alternarTema();
        actualizarIconoTema();
    }

    private void actualizarIconoTema() {
        botonTema.setGraphic(new FontIcon(navegador.temaOscuro() ? "mdi2w-white-balance-sunny" : "mdi2w-weather-night"));
    }
}
