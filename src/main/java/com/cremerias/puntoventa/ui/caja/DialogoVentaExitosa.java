package com.cremerias.puntoventa.ui.caja;

import atlantafx.base.util.Animations;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.util.Dinero;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Confirmación de la venta con el cambio en grande. */
final class DialogoVentaExitosa {

    private DialogoVentaExitosa() {
    }

    static void mostrar(Dialogos dialogos, Ticket ticket, Runnable alImprimir, Runnable alTerminar) {
        Dialogo d = new Dialogo("¡Venta registrada!", "Folio " + ticket.folio(), "mdi2c-check-circle",
                Dialogo.Tono.EXITO);
        d.setPrefWidth(460);

        VBox caja = new VBox(4);
        caja.setAlignment(Pos.CENTER);
        caja.getStyleClass().add("exito-cambio");
        Label etiqueta = new Label(ticket.cambioCentavos() > 0 ? "CAMBIO" : "TOTAL COBRADO");
        etiqueta.getStyleClass().add("exito-cambio-etiqueta");
        Label monto = new Label(Dinero.formatear(ticket.cambioCentavos() > 0 ? ticket.cambioCentavos()
                : ticket.totalCentavos()));
        monto.getStyleClass().add("exito-cambio-monto");
        caja.getChildren().addAll(etiqueta, monto);

        VBox detalle = new VBox(4);
        detalle.getStyleClass().add("exito-detalle");
        detalle.getChildren().add(fila("Total de la venta", Dinero.formatear(ticket.totalCentavos())));
        for (Ticket.PagoTicket p : ticket.pagos()) {
            detalle.getChildren().add(fila(p.metodo().nombre(), Dinero.formatear(p.recibidoCentavos())));
        }
        d.setContenido(caja, detalle);

        Runnable terminar = () -> {
            d.cerrar();
            alTerminar.run();
        };
        d.agregarBoton("Imprimir ticket", "mdi2p-printer-outline", alImprimir, "");
        Button nueva = d.agregarBoton("Nueva venta", "mdi2c-cart-outline", terminar, "accent", "large");
        d.setAlCancelar(terminar);
        d.setAlConfirmar(terminar);
        d.setFocoInicial(nueva);
        dialogos.mostrar(d);
        Animations.pulse(monto, 1.08).playFromStart();
        Animations.zoomIn(caja, Duration.millis(400)).playFromStart();
    }

    private static javafx.scene.layout.HBox fila(String izquierda, String derecha) {
        Label a = new Label(izquierda);
        Label b = new Label(derecha);
        b.getStyleClass().add("texto-negrita");
        javafx.scene.layout.HBox fila = new javafx.scene.layout.HBox(a, new atlantafx.base.controls.Spacer(), b);
        fila.setAlignment(Pos.CENTER_LEFT);
        return fila;
    }
}
