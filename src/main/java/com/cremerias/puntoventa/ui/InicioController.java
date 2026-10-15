package com.cremerias.puntoventa.ui;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.componentes.MenuLateral;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Marco común después de iniciar sesión: barra superior con la ubicación, la conexión y el menú de
 * usuario (tema, animaciones y cerrar sesión), y al centro la pantalla propia de cada rol. Si el rol
 * tiene menú lateral, va a la izquierda a toda la altura y la barra muestra la ruta actual.
 */
public class InicioController {

    private final Navegador navegador;

    @FXML private StackPane lateral;
    @FXML private HBox ruta;
    @FXML private Label rutaGrupo;
    @FXML private Label rutaPagina;
    @FXML private HBox marca;
    @FXML private Label marcaLugar;
    @FXML private HBox barraEstado;
    @FXML private MenuButton menuUsuario;
    @FXML private StackPane contenido;

    public InicioController(Navegador navegador) {
        this.navegador = navegador;
    }

    @FXML
    private void initialize() {
        Sesion sesion = navegador.contexto().sesionActual().obtener()
                .orElseThrow(() -> new IllegalStateException("No hay sesión activa"));
        Rol rol = sesion.usuario().rol();
        String sucursal = navegador.contexto().nombreSucursal(sesion.usuario().sucursalId());

        barraEstado.getChildren().add(new IndicadorConexion(navegador.contexto().sincronizador()));
        configurarMenuUsuario(sesion, rol);

        Navegador.Vista<Object> vista = navegador.cargarVista(vistaDe(rol));
        if (vista.controlador() instanceof ConMenuLateral conMenu) {
            MenuLateral menu = conMenu.menuLateral();
            lateral.getChildren().setAll(menu);
            lateral.setVisible(true);
            lateral.setManaged(true);
            ruta.setVisible(true);
            ruta.setManaged(true);
            rutaGrupo.textProperty().bind(menu.grupoActualProperty());
            rutaPagina.textProperty().bind(menu.paginaActualProperty());
            rutaGrupo.visibleProperty().bind(menu.grupoActualProperty().isNotEmpty());
            rutaGrupo.managedProperty().bind(rutaGrupo.visibleProperty());
            ruta.getChildren().get(1).visibleProperty().bind(rutaGrupo.visibleProperty());
            ruta.getChildren().get(1).managedProperty().bind(rutaGrupo.visibleProperty());
        } else {
            marca.setVisible(true);
            marca.setManaged(true);
            marcaLugar.setText("· " + sucursal);
        }
        contenido.getChildren().setAll(vista.nodo());
    }

    private static String vistaDe(Rol rol) {
        return switch (rol) {
            case ADMINISTRADOR -> "roles/administrador-view.fxml";
            case SUPERVISOR -> "roles/supervisor-view.fxml";
            case CAJERO -> "caja-view.fxml";
        };
    }

    /** Nombre y rol una sola vez; las acciones de la cuenta van dentro del menú. */
    private void configurarMenuUsuario(Sesion sesion, Rol rol) {
        Label avatar = new Label(sesion.usuario().iniciales());
        avatar.getStyleClass().add("avatar");
        Label nombre = new Label(sesion.usuario().nombreCompleto());
        nombre.getStyleClass().add("usuario-nombre");
        Label detalle = new Label(rol.nombre());
        detalle.getStyleClass().add("usuario-detalle");
        VBox textos = new VBox(0, nombre, detalle);
        textos.setAlignment(Pos.CENTER_LEFT);
        HBox grafico = new HBox(10, avatar, textos);
        grafico.setAlignment(Pos.CENTER_LEFT);
        menuUsuario.setGraphic(grafico);
        menuUsuario.setAccessibleText("Cuenta de " + sesion.usuario().nombreCompleto() + ", " + rol.nombre());

        Label cuenta = new Label("Usuario: " + sesion.usuario().usuario());
        cuenta.getStyleClass().add("usuario-detalle");
        Label sucursal = new Label(navegador.contexto().nombreSucursal(sesion.usuario().sucursalId()));
        sucursal.getStyleClass().add("usuario-detalle");
        VBox encabezado = new VBox(2, cuenta, sucursal);
        encabezado.getStyleClass().add("menu-usuario-encabezado");
        CustomMenuItem info = new CustomMenuItem(encabezado, false);
        info.setDisable(true);

        CheckMenuItem tema = new CheckMenuItem("Tema oscuro");
        tema.setGraphic(new FontIcon("mdi2w-weather-night"));
        tema.setSelected(navegador.temaOscuro());
        tema.setOnAction(e -> navegador.alternarTema());

        CheckMenuItem movimiento = new CheckMenuItem("Reducir animaciones");
        movimiento.setGraphic(new FontIcon("mdi2m-motion-pause-outline"));
        movimiento.setSelected(navegador.movimientoReducido());
        movimiento.setOnAction(e -> navegador.alternarMovimientoReducido());

        MenuItem salir = new MenuItem("Cerrar sesión", new FontIcon("mdi2l-logout"));
        salir.getStyleClass().add("peligro");
        salir.setOnAction(e -> navegador.cerrarSesion());

        menuUsuario.getItems().setAll(info, new SeparatorMenuItem(), tema, movimiento, new SeparatorMenuItem(), salir);
        menuUsuario.setOnShowing(e -> {
            tema.setSelected(navegador.temaOscuro());
            movimiento.setSelected(navegador.movimientoReducido());
        });
    }
}
