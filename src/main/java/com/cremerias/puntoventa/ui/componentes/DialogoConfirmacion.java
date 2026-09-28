package com.cremerias.puntoventa.ui.componentes;

import javafx.scene.control.Button;
import javafx.scene.control.Label;

/** Pregunta de sí / no. */
public final class DialogoConfirmacion {

    private DialogoConfirmacion() {
    }

    public static void mostrar(Dialogos dialogos, String titulo, String mensaje, String textoConfirmar,
                               boolean peligroso, Runnable alConfirmar) {
        Dialogo d = new Dialogo(titulo, null,
                peligroso ? "mdi2a-alert-outline" : "mdi2h-help-circle-outline",
                peligroso ? Dialogo.Tono.PELIGRO : Dialogo.Tono.ACENTO);
        Label texto = new Label(mensaje);
        texto.setWrapText(true);
        texto.getStyleClass().add("dialogo-texto");
        d.setContenido(texto);
        d.agregarBoton("Regresar", null, d::cerrar, "flat");
        Button confirmar = d.agregarBoton(textoConfirmar, null, () -> {
            d.cerrar();
            alConfirmar.run();
        }, peligroso ? "danger" : "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(confirmar::fire);
        d.setFocoInicial(confirmar);
        dialogos.mostrar(d);
    }

    public static void aviso(Dialogos dialogos, String titulo, String mensaje, boolean error) {
        Dialogo d = new Dialogo(titulo, null, error ? "mdi2a-alert-circle-outline" : "mdi2i-information-outline",
                error ? Dialogo.Tono.PELIGRO : Dialogo.Tono.INFO);
        Label texto = new Label(mensaje);
        texto.setWrapText(true);
        texto.getStyleClass().add("dialogo-texto");
        d.setContenido(texto);
        Button ok = d.agregarBoton("Entendido", null, d::cerrar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(ok::fire);
        d.setFocoInicial(ok);
        dialogos.mostrar(d);
    }
}
