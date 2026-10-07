package com.cremerias.puntoventa.ui;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import javafx.animation.FadeTransition;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;

/** Cambia entre pantallas dentro de la única ventana de la aplicación. */
public class Navegador {

    private static final Logger log = LoggerFactory.getLogger(Navegador.class);
    private static final String VISTAS = "/com/cremerias/puntoventa/views/";

    private final Stage stage;
    private AppContext contexto;
    private final StackPane raiz = new StackPane();
    private final Dialogos dialogos = new Dialogos(raiz);
    private final Avisos avisos = new Avisos();
    private boolean temaOscuro;

    public Navegador(Stage stage, AppContext contexto) {
        this.stage = stage;
        this.contexto = contexto;
        raiz.getStyleClass().add("app-raiz");
        Scene scene = new Scene(raiz, 1200, 760);
        scene.getStylesheets().add(getClass().getResource("/com/cremerias/puntoventa/css/app.css").toExternalForm());
        stage.setScene(scene);
        temaOscuro = contexto.preferencias().temaOscuro();
        Temas.aplicar(raiz, temaOscuro);
    }

    public AppContext contexto() {
        return contexto;
    }

    /** La caja se acaba de conectar a la nube: las pantallas siguen con los servicios nuevos. */
    public void cambiarContexto(AppContext nuevo) {
        contexto = nuevo;
    }

    public Dialogos dialogos() {
        return dialogos;
    }

    public Avisos avisos() {
        return avisos;
    }

    public void mostrarLogin() {
        stage.setTitle("Cremería · Iniciar sesión");
        mostrar(cargar("login-view.fxml"));
    }

    public void mostrarInicio(Sesion sesion) {
        contexto.sesionActual().establecer(sesion);
        contexto.sincronizador().revisarAhora();
        stage.setTitle("Cremería · " + sesion.usuario().rol().nombre());
        stage.setMaximized(true);
        mostrar(cargar("inicio-view.fxml"));
    }

    public void cerrarSesion() {
        contexto.sesionActual().obtener().ifPresent(s -> contexto.auth().cerrarSesion(s, "CIERRE_USUARIO"));
        contexto.sesionActual().limpiar();
        contexto.sincronizador().revisarAhora();
        mostrarLogin();
    }

    public boolean temaOscuro() {
        return temaOscuro;
    }

    public void alternarTema() {
        temaOscuro = !temaOscuro;
        Temas.aplicar(raiz, temaOscuro);
        contexto.preferencias().guardarTemaOscuro(temaOscuro);
    }

    /** Vista cargada junto con su controlador. */
    public record Vista<C>(Parent nodo, C controlador) {
    }

    /** Carga una vista FXML; los controladores reciben este navegador en su constructor. */
    public Parent cargar(String vista) {
        return cargarVista(vista).nodo();
    }

    public <C> Vista<C> cargarVista(String vista) {
        FXMLLoader loader = new FXMLLoader(getClass().getResource(VISTAS + vista));
        loader.setControllerFactory(this::crearControlador);
        try {
            Parent nodo = loader.load();
            return new Vista<>(nodo, loader.getController());
        } catch (IOException e) {
            log.error("No se pudo cargar la vista {}", vista, e);
            throw new UncheckedIOException(e);
        }
    }

    private Object crearControlador(Class<?> tipo) {
        try {
            for (Constructor<?> constructor : tipo.getConstructors()) {
                if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0] == Navegador.class) {
                    return constructor.newInstance(this);
                }
            }
            return tipo.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No se pudo crear el controlador " + tipo.getName(), e);
        }
    }

    private void mostrar(Parent vista) {
        dialogos.limpiar();
        avisos.contenedor().getChildren().clear();
        raiz.getChildren().setAll(vista, avisos.contenedor());
        FadeTransition fade = new FadeTransition(Duration.millis(260), vista);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }
}
