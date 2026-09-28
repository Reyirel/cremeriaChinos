package com.cremerias.puntoventa.ui.caja;

import atlantafx.base.controls.CustomTextField;
import atlantafx.base.controls.PasswordTextField;
import atlantafx.base.util.Animations;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.ResultadoAutorizacion;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.EnumSet;
import java.util.function.Consumer;

/** Un supervisor o administrador escribe su contraseña para autorizar una operación. */
public final class DialogoAutorizacion {

    private DialogoAutorizacion() {
    }

    public static void mostrar(Dialogos dialogos, AuthService auth, String operacion, Consumer<Usuario> alAutorizar) {
        Dialogo d = new Dialogo("Autorización requerida", "Un supervisor debe autorizar: " + operacion,
                "mdi2a-account-key-outline", Dialogo.Tono.ADVERTENCIA);

        CustomTextField usuario = new CustomTextField();
        usuario.setLeft(new FontIcon("mdi2a-account-outline"));
        usuario.setPromptText("Usuario del supervisor");
        usuario.getStyleClass().add("campo-grande");
        PasswordTextField password = new PasswordTextField();
        password.setLeft(new FontIcon("mdi2l-lock-outline"));
        password.setPromptText("Contraseña");
        password.getStyleClass().add("campo-grande");
        Label error = new Label();
        error.getStyleClass().add("texto-error");
        error.setWrapText(true);
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);
        VBox formulario = new VBox(10, usuario, password, error);
        d.setContenido(formulario);

        Button[] autorizar = new Button[1];
        Runnable validar = () -> {
            if (autorizar[0].isDisabled()) {
                return;
            }
            char[] clave = password.getPassword().toCharArray();
            String nombre = usuario.getText();
            autorizar[0].setDisable(true);
            Task<ResultadoAutorizacion> tarea = new Task<>() {
                @Override
                protected ResultadoAutorizacion call() {
                    return auth.autorizar(nombre, clave, EnumSet.of(Rol.SUPERVISOR, Rol.ADMINISTRADOR), operacion);
                }
            };
            tarea.setOnSucceeded(e -> {
                autorizar[0].setDisable(false);
                switch (tarea.getValue()) {
                    case ResultadoAutorizacion.Autorizado ok -> {
                        d.cerrar();
                        alAutorizar.accept(ok.autorizador());
                    }
                    case ResultadoAutorizacion.Denegado no -> {
                        error.setText(no.mensaje());
                        error.setVisible(true);
                        password.setText("");
                        password.requestFocus();
                        Animations.shakeX(formulario, 6).playFromStart();
                    }
                }
            });
            tarea.setOnFailed(e -> {
                autorizar[0].setDisable(false);
                error.setText("No se pudo validar la autorización.");
                error.setVisible(true);
            });
            Thread hilo = new Thread(tarea, "autorizacion");
            hilo.setDaemon(true);
            hilo.start();
        };

        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        autorizar[0] = d.agregarBoton("Autorizar", "mdi2c-check", validar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(() -> {
            if (usuario.isFocused() && password.getPassword().isEmpty()) {
                password.requestFocus();
            } else {
                validar.run();
            }
        });
        d.setFocoInicial(usuario);
        dialogos.mostrar(d);
    }
}
