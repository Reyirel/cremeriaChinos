package com.cremerias.puntoventa.ui;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.Message;
import atlantafx.base.controls.PasswordTextField;
import atlantafx.base.util.Animations;
import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.service.ResultadoLogin;
import com.cremerias.puntoventa.sync.ErrorNube;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);
    /** Por debajo de este ancho se oculta el panel de marca. */
    private static final double ANCHO_MINIMO_MARCA = 920;

    private final Navegador navegador;

    @FXML private HBox raiz;
    @FXML private StackPane panelMarca;
    @FXML private VBox contenidoMarca;
    @FXML private VBox formulario;
    @FXML private CustomTextField campoUsuario;
    @FXML private PasswordTextField campoPassword;
    @FXML private CheckBox recordarUsuario;
    @FXML private Label avisoMayusculas;
    @FXML private Message mensajeError;
    @FXML private Button botonEntrar;
    @FXML private Button botonTema;
    @FXML private HBox barraEstado;
    private IndicadorConexion indicador;

    public LoginController(Navegador navegador) {
        this.navegador = navegador;
    }

    @FXML
    private void initialize() {
        panelMarca.visibleProperty().bind(raiz.widthProperty().greaterThan(ANCHO_MINIMO_MARCA));
        panelMarca.managedProperty().bind(panelMarca.visibleProperty());

        configurarMostrarPassword();
        configurarAvisoMayusculas();
        actualizarIconoTema();
        indicador = new IndicadorConexion(navegador.contexto().sincronizador());
        barraEstado.getChildren().add(indicador);

        mensajeError.setVisible(false);
        mensajeError.managedProperty().bind(mensajeError.visibleProperty());
        campoUsuario.textProperty().addListener((o, a, b) -> ocultarError());
        campoPassword.passwordProperty().addListener((o, a, b) -> ocultarError());

        navegador.contexto().preferencias().ultimoUsuario().ifPresent(usuario -> {
            campoUsuario.setText(usuario);
            recordarUsuario.setSelected(true);
        });

        Platform.runLater(() -> {
            (campoUsuario.getText().isBlank() ? campoUsuario : campoPassword).requestFocus();
            Animations.fadeInUp(formulario, Duration.millis(450)).playFromStart();
            if (panelMarca.isVisible()) {
                Animations.fadeInLeft(contenidoMarca, Duration.millis(600)).playFromStart();
            }
        });
    }

    private void configurarMostrarPassword() {
        FontIcon ojo = new FontIcon("mdi2e-eye-outline");
        ojo.setCursor(Cursor.HAND);
        ojo.getStyleClass().add("icono-accion");
        ojo.setOnMouseClicked(e -> {
            boolean mostrar = !campoPassword.getRevealPassword();
            campoPassword.setRevealPassword(mostrar);
            ojo.setIconLiteral(mostrar ? "mdi2e-eye-off-outline" : "mdi2e-eye-outline");
        });
        campoPassword.setRight(ojo);
    }

    private void configurarAvisoMayusculas() {
        avisoMayusculas.setVisible(false);
        avisoMayusculas.managedProperty().bind(avisoMayusculas.visibleProperty());
        Runnable revisar = () -> avisoMayusculas.setVisible(campoPassword.isFocused()
                && Platform.isKeyLocked(KeyCode.CAPS).orElse(false));
        campoPassword.setOnKeyReleased(e -> revisar.run());
        campoPassword.focusedProperty().addListener((o, a, b) -> revisar.run());
    }

    @FXML
    private void onEntrar() {
        String usuario = campoUsuario.getText();
        char[] password = campoPassword.getPassword().toCharArray();

        if (usuario.isBlank()) {
            mostrarError("Escribe tu usuario.");
            campoUsuario.requestFocus();
            return;
        }
        if (password.length == 0) {
            mostrarError("Escribe tu contraseña.");
            campoPassword.requestFocus();
            return;
        }

        AppContext contexto = navegador.contexto();
        ModoConexion modo = contexto.sincronizador().enLinea() ? ModoConexion.ONLINE : ModoConexion.OFFLINE;
        Task<Ingreso> tarea = new Task<>() {
            @Override
            protected Ingreso call() {
                // iniciarSesion borra la contraseña al terminar: la nube necesita su propia copia.
                char[] paraLaNube = contexto.porConectarALaNube() ? password.clone() : new char[0];
                try {
                    ResultadoLogin local = contexto.auth().iniciarSesion(usuario, password, modo);
                    if (local instanceof ResultadoLogin.Rechazado rechazado && contexto.porConectarALaNube()) {
                        updateMessage("Conectando con la nube…");
                        return entrarConLaNube(contexto, usuario, paraLaNube, rechazado);
                    }
                    return new Ingreso(contexto, local, null);
                } finally {
                    Arrays.fill(paraLaNube, '\0');
                }
            }
        };
        tarea.messageProperty().addListener((o, a, mensaje) -> {
            if (!mensaje.isBlank()) {
                botonEntrar.setText(mensaje);
            }
        });
        tarea.setOnSucceeded(e -> alTerminar(usuario, tarea.getValue()));
        tarea.setOnFailed(e -> {
            log.error("Error al iniciar sesión", tarea.getException());
            ocupado(false);
            mostrarError("No se pudo acceder a la base de datos local. Revisa el registro de errores.");
        });

        ocupado(true);
        Thread hilo = new Thread(tarea, "login");
        hilo.setDaemon(true);
        hilo.start();
    }

    /**
     * Esta computadora no está conectada a la nube y el usuario no entró con los datos locales: si
     * es un administrador de la nube, se conecta la caja (trae usuarios, catálogo y existencias) y
     * entra con los datos recién bajados.
     */
    private static Ingreso entrarConLaNube(AppContext contexto, String usuario, char[] password,
                                           ResultadoLogin.Rechazado local) {
        AppContext nuevo;
        try {
            nuevo = contexto.conectarALaNube(usuario, password);
        } catch (ErrorNube e) {
            // Si tampoco sirven en la nube, el aviso de la caja es el que aplica.
            return new Ingreso(contexto, local, e.estado() == 401 ? null : e.getMessage());
        } catch (IOException e) {
            log.warn("No se pudo conectar la caja a la nube", e);
            return new Ingreso(contexto, local, "Esta computadora todavía no está conectada a la nube y no se pudo"
                    + " llegar a Supabase. Revisa el internet: la primera vez se necesita para traer los usuarios.");
        } catch (RuntimeException e) {
            log.error("La caja se conectó a la nube, pero no se pudieron preparar los datos", e);
            return new Ingreso(contexto, local, "La caja se conectó a la nube, pero no se pudieron preparar los datos ("
                    + e.getMessage() + "). Cierra la app y vuelve a abrirla.");
        }
        return new Ingreso(nuevo, nuevo.auth().iniciarSesion(usuario, password, ModoConexion.ONLINE), null);
    }

    private void alTerminar(String usuario, Ingreso ingreso) {
        if (ingreso.contexto() != navegador.contexto()) {
            // La caja se acaba de conectar a la nube: todo sigue con los servicios nuevos.
            navegador.cambiarContexto(ingreso.contexto());
            IndicadorConexion nuevo = new IndicadorConexion(ingreso.contexto().sincronizador());
            barraEstado.getChildren().set(barraEstado.getChildren().indexOf(indicador), nuevo);
            indicador = nuevo;
        }
        switch (ingreso.resultado()) {
            case ResultadoLogin.Exitoso exitoso -> {
                navegador.contexto().preferencias().recordarUsuario(recordarUsuario.isSelected() ? usuario.strip() : null);
                navegador.mostrarInicio(exitoso.sesion());
            }
            case ResultadoLogin.Rechazado rechazado -> {
                ocupado(false);
                // Primero se limpia: al cambiar el campo se oculta el error.
                campoPassword.setText("");
                mostrarError(ingreso.aviso() != null ? ingreso.aviso() : rechazado.mensaje());
                campoPassword.requestFocus();
            }
        }
    }

    /** Resultado de entrar; el contexto es otro si la caja se acaba de conectar a la nube. */
    private record Ingreso(AppContext contexto, ResultadoLogin resultado, String aviso) {
    }

    @FXML
    private void onAlternarTema() {
        navegador.alternarTema();
        actualizarIconoTema();
    }

    private void actualizarIconoTema() {
        FontIcon icono = new FontIcon(navegador.temaOscuro() ? "mdi2w-white-balance-sunny" : "mdi2w-weather-night");
        botonTema.setGraphic(icono);
    }

    private void ocupado(boolean ocupado) {
        formulario.setDisable(ocupado);
        if (ocupado) {
            ProgressIndicator progreso = new ProgressIndicator();
            progreso.setPrefSize(18, 18);
            progreso.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            botonEntrar.setGraphic(progreso);
            botonEntrar.setText("Verificando…");
        } else {
            botonEntrar.setGraphic(new FontIcon("mdi2l-login"));
            botonEntrar.setText("Iniciar sesión");
        }
    }

    private void mostrarError(String texto) {
        mensajeError.setDescription(texto);
        if (!mensajeError.isVisible()) {
            mensajeError.setVisible(true);
            Animations.fadeIn(mensajeError, Duration.millis(200)).playFromStart();
        }
        Animations.shakeX(formulario, 6).playFromStart();
    }

    private void ocultarError() {
        mensajeError.setVisible(false);
    }
}
