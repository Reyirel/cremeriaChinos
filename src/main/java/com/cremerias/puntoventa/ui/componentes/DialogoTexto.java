package com.cremerias.puntoventa.ui.componentes;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Pide un texto corto (nota, motivo...). */
public final class DialogoTexto {

    private DialogoTexto() {
    }

    public static void mostrar(Dialogos dialogos, String titulo, String subtitulo, String icono, String etiqueta,
                               String ejemplo, boolean requerido, String textoConfirmar, Consumer<String> alAceptar) {
        Dialogo d = new Dialogo(titulo, subtitulo, icono, Dialogo.Tono.ACENTO);
        TextField campo = new TextField();
        campo.setPromptText(ejemplo);
        campo.getStyleClass().add("campo-grande");
        Label error = new Label();
        error.getStyleClass().add("texto-error");
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);
        Label titulocampo = new Label(etiqueta);
        titulocampo.getStyleClass().add("campo-etiqueta");
        d.setContenido(new VBox(8, titulocampo, campo, error));

        Runnable aceptar = () -> {
            String texto = campo.getText().strip();
            if (requerido && texto.isEmpty()) {
                error.setText("Este dato es obligatorio.");
                error.setVisible(true);
                campo.requestFocus();
                return;
            }
            d.cerrar();
            alAceptar.accept(texto);
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton(textoConfirmar, null, aceptar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(aceptar);
        d.setFocoInicial(campo);
        dialogos.mostrar(d);
    }
}
