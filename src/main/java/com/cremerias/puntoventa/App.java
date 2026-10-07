package com.cremerias.puntoventa;

import com.cremerias.puntoventa.ui.Fuentes;
import com.cremerias.puntoventa.ui.Navegador;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private AppContext contexto;
    private Navegador navegador;
    private Exception errorInicio;

    @Override
    public void init() {
        try {
            contexto = AppContext.iniciar();
        } catch (Exception e) {
            log.error("No se pudo iniciar la aplicación", e);
            errorInicio = e;
        }
    }

    @Override
    public void start(Stage stage) {
        if (errorInicio != null) {
            Alert alerta = new Alert(Alert.AlertType.ERROR,
                    "No se pudo preparar la base de datos de la caja.\n\n" + errorInicio.getMessage());
            alerta.setHeaderText("Error al iniciar el punto de venta");
            alerta.showAndWait();
            Platform.exit();
            return;
        }
        Fuentes.cargar();
        navegador = new Navegador(stage, contexto);
        stage.setMinWidth(760);
        stage.setMinHeight(620);
        navegador.mostrarLogin();
        stage.centerOnScreen();
        stage.show();
    }

    @Override
    public void stop() {
        // Al conectar la caja a la nube el navegador pasa a un contexto nuevo.
        AppContext actual = navegador != null ? navegador.contexto() : contexto;
        if (actual != null) {
            actual.close();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
