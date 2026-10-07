package com.cremerias.puntoventa.ui.componentes;

import javafx.geometry.VPos;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Impresión de tickets en la impresora predeterminada del sistema.
 *
 * <p>Pensada para impresoras térmicas de recibo de 80 mm (p. ej. Epson TM-T20IV-SP): en vez
 * de usar el papel por defecto del controlador (a veces carta, pensado para hojas sueltas),
 * se busca entre los papeles que reporta la propia impresora el que corresponde al rollo, para
 * que el ticket no salga recortado por un lado ni se imprima más chico de lo necesario.</p>
 *
 * <p>Una impresora térmica solo tiene un color (el del papel al quemarse), así que el
 * contraste se logra con negritas: lo que marca {@link TicketTexto.Enfasis#DESTACADO} sale en
 * negrita y el resto en regular, ambos en negro puro, en vez de un gris parejo y desvaído.</p>
 */
public final class Impresion {

    private static final Logger log = LoggerFactory.getLogger(Impresion.class);

    private static final double PUNTOS_POR_PULGADA = 72.0;
    private static final double PX_POR_PULGADA = 96.0;
    private static final double ANCHO_ROLLO_MM = 80;
    private static final double TOLERANCIA_ANCHO_MM = 15;
    private static final double TAMANO_FUENTE = 9;

    private Impresion() {
    }

    /** @return mensaje de error, o {@code null} si se envió a imprimir. */
    public static String imprimir(List<TicketTexto.Linea> lineas) {
        PrinterJob trabajo = PrinterJob.createPrinterJob();
        if (trabajo == null) {
            return "No hay ninguna impresora instalada en este equipo.";
        }
        Contenido contenido = construirContenido(lineas);
        PageLayout pagina = paginaDeRollo(trabajo.getPrinter());
        // El ancho del nodo está en px (1/96"); el de la página, en puntos (1/72"). Si no se
        // convierte a la misma unidad, el ticket se imprime más chico (o más grande) de lo debido.
        double anchoContenidoPt = contenido.anchoPx * PUNTOS_POR_PULGADA / PX_POR_PULGADA;
        double escala = Math.min(1, pagina.getPrintableWidth() / anchoContenidoPt);
        contenido.grupo.setScaleX(escala);
        contenido.grupo.setScaleY(escala);
        try {
            if (trabajo.printPage(pagina, contenido.grupo)) {
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

    private record Contenido(Group grupo, double anchoPx) {
    }

    /** Un nodo de texto por línea (en vez de uno solo con todo el ticket) para poder variar la
     *  negrita línea por línea; se posicionan a mano, acumulando el alto de cada una. */
    private static Contenido construirContenido(List<TicketTexto.Linea> lineas) {
        List<Node> nodos = new ArrayList<>();
        double y = 0;
        double anchoMaximo = 0;
        for (TicketTexto.Linea linea : lineas) {
            Text t = new Text(linea.texto());
            t.setFont(fuente(linea.enfasis()));
            t.setFill(Color.BLACK);
            // El origen por defecto es la línea base: si no se fija arriba, la parte superior
            // de cada línea queda fuera del área imprimible y se corta al imprimir.
            t.setTextOrigin(VPos.TOP);
            t.setY(y);
            y += t.getLayoutBounds().getHeight();
            anchoMaximo = Math.max(anchoMaximo, t.getLayoutBounds().getWidth());
            nodos.add(t);
        }
        return new Contenido(new Group(nodos), anchoMaximo);
    }

    private static Font fuente(TicketTexto.Enfasis enfasis) {
        return Font.font("Monospaced", enfasis == TicketTexto.Enfasis.DESTACADO ? FontWeight.BOLD : FontWeight.NORMAL,
                TAMANO_FUENTE);
    }

    /** Busca, entre los papeles que reporta la impresora, el más parecido al rollo térmico de
     *  80 mm y arma una página con el menor margen posible. Si no hay ninguno parecido (por
     *  ejemplo, una impresora normal sin rollo), se usa el papel por defecto de la impresora. */
    private static PageLayout paginaDeRollo(Printer impresora) {
        try {
            double anchoObjetivoPt = ANCHO_ROLLO_MM * PUNTOS_POR_PULGADA / 25.4;
            double toleranciaPt = TOLERANCIA_ANCHO_MM * PUNTOS_POR_PULGADA / 25.4;
            Paper mejor = null;
            double mejorDiferencia = Double.MAX_VALUE;
            for (Paper candidato : impresora.getPrinterAttributes().getSupportedPapers()) {
                double diferencia = Math.abs(candidato.getWidth() - anchoObjetivoPt);
                if (diferencia < mejorDiferencia) {
                    mejorDiferencia = diferencia;
                    mejor = candidato;
                }
            }
            if (mejor != null && mejorDiferencia <= toleranciaPt) {
                return impresora.createPageLayout(mejor, PageOrientation.PORTRAIT, Printer.MarginType.HARDWARE_MINIMUM);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo buscar el papel del rollo térmico; se usa el de la impresora", e);
        }
        return impresora.getDefaultPageLayout();
    }
}
