package com.cremerias.puntoventa.ui.roles;

import atlantafx.base.util.Animations;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.Navegador;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pantalla de bienvenida de cada rol. Por ahora las tres comparten este controlador;
 * cada rol tendrá el suyo conforme se agreguen sus módulos.
 */
public class PanelRolController {

    private static final DateTimeFormatter HORA = DateTimeFormatter
            .ofPattern("EEEE d 'de' MMMM, HH:mm", Locale.forLanguageTag("es-MX"));

    private final Navegador navegador;

    @FXML private VBox panel;
    @FXML private Label saludo;
    @FXML private Label detalleSesion;

    public PanelRolController(Navegador navegador) {
        this.navegador = navegador;
    }

    @FXML
    private void initialize() {
        Sesion sesion = navegador.contexto().sesionActual().obtener().orElseThrow();
        String nombre = sesion.usuario().nombreCompleto().split("\\s+")[0];
        saludo.setText("Hola, " + nombre);
        String inicio = HORA.format(sesion.inicio().atZone(ZoneId.systemDefault()));
        detalleSesion.setText("Sesión iniciada el " + inicio
                + (sesion.modo() == com.cremerias.puntoventa.model.ModoConexion.OFFLINE ? " · sin conexión" : ""));
        Animations.fadeInUp(panel, Duration.millis(450)).playFromStart();
    }
}
