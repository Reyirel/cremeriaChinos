package com.cremerias.puntoventa.ui.componentes;

import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Impresión de tickets en la impresora predeterminada del sistema. */
public final class Impresion {

    private static final Logger log = LoggerFactory.getLogger(Impresion.class);

    private Impresion() {
    }

    /** @return mensaje de error, o {@code null} si se envió a imprimir. */
    public static String imprimir(String texto) {
        PrinterJob trabajo = PrinterJob.createPrinterJob();
        if (trabajo == null) {
            return "No hay ninguna impresora instalada en este equipo.";
        }
        Text contenido = new Text(texto);
        contenido.setFont(Font.font("Monospaced", 9));
        PageLayout pagina = trabajo.getJobSettings().getPageLayout();
        double escala = Math.min(1, pagina.getPrintableWidth() / contenido.getLayoutBounds().getWidth());
        contenido.setScaleX(escala);
        contenido.setScaleY(escala);
        try {
            if (trabajo.printPage(contenido)) {
                trabajo.endJob();
                return null;
            }
            trabajo.cancelJob();
            return "La impresora no respondió.";
        } catch (RuntimeException e) {
            log.error("Error al imprimir", e);
            return "Error al imprimir: " + e.getMessage();
        }
    }
}
