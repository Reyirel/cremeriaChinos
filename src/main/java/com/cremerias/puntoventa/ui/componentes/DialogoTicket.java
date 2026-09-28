package com.cremerias.puntoventa.ui.componentes;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;

/** Vista previa de un ticket con opción de imprimirlo. */
public final class DialogoTicket {

    private DialogoTicket() {
    }

    public static void mostrar(Dialogos dialogos, Avisos avisos, String titulo, String subtitulo, String texto,
                               Runnable alCerrar) {
        Dialogo d = new Dialogo(titulo, subtitulo, "mdi2r-receipt-text-outline", Dialogo.Tono.INFO);
        d.setPrefWidth(460);
        Label papel = new Label(texto);
        papel.getStyleClass().add("ticket-texto");
        StackPane hoja = new StackPane(papel);
        hoja.getStyleClass().add("ticket-papel");
        ScrollPane scroll = new ScrollPane(hoja);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(460, 17 * texto.lines().count() + 48));
        scroll.getStyleClass().add("ticket-scroll");
        d.setContenido(scroll);

        Runnable cerrar = () -> {
            d.cerrar();
            if (alCerrar != null) {
                alCerrar.run();
            }
        };
        d.agregarBoton("Imprimir", "mdi2p-printer-outline", () -> {
            String error = Impresion.imprimir(texto);
            if (error == null) {
                avisos.exito("Ticket enviado a la impresora.");
            } else {
                avisos.error(error);
            }
        }, "");
        Button listo = d.agregarBoton("Listo", null, cerrar, "accent");
        d.setAlCancelar(cerrar);
        d.setAlConfirmar(cerrar);
        d.setFocoInicial(listo);
        dialogos.mostrar(d);
    }
}
