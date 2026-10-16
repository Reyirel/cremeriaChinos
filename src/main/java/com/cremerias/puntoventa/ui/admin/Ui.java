package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EncabezadoPagina;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Atajos para armar páginas del panel con los componentes compartidos (ver ui/componentes).
 * Solo composición: la apariencia está en app.css.
 */
public final class Ui {

    private Ui() {
    }

    /** Contenedor de página con su encabezado (título, contexto y acciones). */
    public static VBox pagina(String titulo, String descripcion, Node... acciones) {
        return pagina(new EncabezadoPagina(titulo, descripcion).acciones(acciones));
    }

    public static VBox pagina(EncabezadoPagina encabezado) {
        VBox pagina = new VBox(Diseno.ESPACIO_L, encabezado);
        pagina.getStyleClass().add("pagina");
        return pagina;
    }

    /**
     * Página cuya pieza principal (la tabla) llena el alto disponible; en ventanas bajas la página
     * entera se desplaza para que esa pieza conserve un alto útil en lugar de aplastarse.
     */
    public static ScrollPane ajustable(VBox pagina, Region principal, double altoMinimo) {
        principal.setMinHeight(altoMinimo);
        VBox.setVgrow(principal, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(pagina);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("pagina-desplazable");
        return scroll;
    }

    /** Página larga que se desplaza completa (varias secciones). */
    public static ScrollPane desplazable(VBox pagina) {
        ScrollPane scroll = new ScrollPane(pagina);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("pagina-desplazable");
        return scroll;
    }

    public static Button boton(String texto, String icono, String... clases) {
        Button b = new Button(texto, icono == null ? null : new FontIcon(icono));
        b.getStyleClass().addAll(clases);
        return b;
    }

    /** Acción principal de la pantalla (una por pantalla). */
    public static Button principal(String texto, String icono) {
        return boton(texto, icono, "accent");
    }

    public static Button secundario(String texto, String icono) {
        return boton(texto, icono);
    }

    /** Etiqueta de estado. Tonos: exito, peligro, advertencia, acento, neutro, info. */
    public static Label chip(String texto, String tono) {
        return switch (tono) {
            case "exito" -> Etiqueta.de(texto, Etiqueta.Tono.EXITO);
            case "peligro" -> Etiqueta.de(texto, Etiqueta.Tono.PELIGRO);
            case "advertencia" -> Etiqueta.de(texto, Etiqueta.Tono.AVISO);
            case "acento" -> Etiqueta.de(texto, Etiqueta.Tono.ACENTO);
            case "info", "azul" -> Etiqueta.de(texto, Etiqueta.Tono.INFO);
            case "tachado" -> Etiqueta.tachada(texto);
            default -> Etiqueta.de(texto, Etiqueta.Tono.NEUTRO);
        };
    }

    /** Campo con su etiqueta arriba. */
    public static Formulario.Campo campo(String etiqueta, Node control) {
        return Formulario.campo(etiqueta, control);
    }

    /** Campos que se reparten el ancho. */
    public static HBox fila(Node... campos) {
        return Formulario.fila(campos);
    }

    public static Label texto(String contenido, String clase) {
        Label l = new Label(contenido);
        l.getStyleClass().add(clase);
        l.setWrapText(true);
        return l;
    }

    /** Panel blanco con la barra de búsqueda y filtros arriba y la tabla debajo. */
    public static VBox panelTabla(BarraHerramientas barra, TablaDatos<?> tabla) {
        VBox panel = new VBox();
        panel.getStyleClass().add("panel-tabla");
        if (barra != null) {
            panel.getChildren().add(barra);
        }
        panel.getChildren().add(tabla.tabla());
        VBox.setVgrow(tabla.tabla(), Priority.ALWAYS);
        Diseno.recortarRedondeado(panel, Diseno.RADIO_PANEL);
        return panel;
    }

    /** Panel con título, una línea de contexto opcional, acciones a la derecha y su contenido. */
    public static VBox panel(String titulo, String descripcion, Node accion, Node... contenido) {
        Label t = new Label(titulo);
        t.getStyleClass().add("panel-titulo");
        VBox textos = new VBox(2, t);
        if (descripcion != null && !descripcion.isBlank()) {
            Label d = new Label(descripcion);
            d.getStyleClass().add("panel-descripcion");
            d.setWrapText(true);
            textos.getChildren().add(d);
        }
        textos.setMinWidth(0);
        HBox.setHgrow(textos, Priority.ALWAYS);
        HBox encabezado = new HBox(10, textos);
        encabezado.getStyleClass().add("panel-encabezado");
        encabezado.setAlignment(Pos.CENTER_LEFT);
        if (accion != null) {
            if (accion instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            encabezado.getChildren().add(accion);
        }
        VBox panel = new VBox(encabezado);
        panel.getStyleClass().add("panel");
        if (contenido.length > 0) {
            VBox cuerpo = new VBox(Diseno.ESPACIO_M, contenido);
            cuerpo.getStyleClass().add("panel-cuerpo");
            panel.getChildren().add(cuerpo);
        }
        return panel;
    }

    /** Espacio flexible en una fila. */
    public static Region espacio() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
