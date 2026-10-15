package com.cremerias.puntoventa.ui.componentes;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Navegación lateral grafito, agrupada por tipo de trabajo. La opción activa se distingue por
 * fondo, una barra cobriza a la izquierda y texto en negrita (no solo por color). Se puede compactar
 * a solo íconos para dejar más espacio a las tablas.
 */
public class MenuLateral extends VBox {

    private record Opcion(String clave, String texto, String grupo, ToggleButton boton, Label nombre,
                          Label detalle, Label contador, Tooltip ayuda, javafx.scene.shape.Circle punto) {
    }

    private final ToggleGroup grupo = new ToggleGroup();
    private final VBox opciones = new VBox();
    private final Map<String, Opcion> porClave = new LinkedHashMap<>();
    private final Map<Label, String> titulosGrupo = new LinkedHashMap<>();
    private final ReadOnlyStringWrapper grupoActual = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper paginaActual = new ReadOnlyStringWrapper("");
    private final BooleanProperty compacto = new SimpleBooleanProperty(false);
    private final VBox textosMarca;
    private final Button alternar = new Button();
    private final StackPane logo;
    private String grupoEnCurso = "";
    private static final Tooltip AYUDA_LOGO = new Tooltip("Mostrar los nombres del menú");

    public MenuLateral(String lugar) {
        getStyleClass().add("nav-lateral");

        logo = new StackPane(new FontIcon("mdi2c-cheese"));
        logo.getStyleClass().add("marca-icono");
        // Con el menú compacto, el logo lo vuelve a expandir.
        logo.setOnMouseClicked(e -> compacto.set(false));
        Label nombre = new Label("Cremería");
        nombre.getStyleClass().add("marca-nombre");
        Label sitio = new Label(lugar);
        sitio.getStyleClass().add("marca-lugar");
        textosMarca = new VBox(0, nombre, sitio);
        textosMarca.setAlignment(Pos.CENTER_LEFT);
        textosMarca.managedProperty().bind(textosMarca.visibleProperty());
        Region espacioMarca = new Region();
        HBox.setHgrow(espacioMarca, Priority.ALWAYS);
        espacioMarca.managedProperty().bind(textosMarca.visibleProperty());
        HBox marca = new HBox(10, logo, textosMarca, espacioMarca, alternar);
        marca.getStyleClass().add("nav-marca");

        opciones.getStyleClass().add("nav-contenido");
        ScrollPane desplazable = new ScrollPane(opciones);
        desplazable.getStyleClass().add("nav-scroll");
        desplazable.setFitToWidth(true);
        desplazable.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(desplazable, Priority.ALWAYS);

        alternar.getStyleClass().addAll("flat", "boton-icono", "nav-alternar");
        alternar.setOnAction(e -> compacto.set(!compacto.get()));
        alternar.managedProperty().bind(alternar.visibleProperty());

        getChildren().addAll(marca, desplazable);

        grupo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null && antes != null) {
                antes.setSelected(true);
            }
        });
        compacto.addListener((o, a, b) -> aplicarCompacto());
        aplicarCompacto();
    }

    /** Título de un grupo de opciones (Almacén, Finanzas...). */
    public void grupo(String titulo) {
        grupoEnCurso = titulo;
        Label l = new Label(titulo);
        l.getStyleClass().add("nav-grupo");
        l.setMaxWidth(Double.MAX_VALUE);
        titulosGrupo.put(l, titulo);
        opciones.getChildren().add(l);
    }

    /** Opción del menú; la clave también es su id ({@code #nav-clave}) para pruebas y accesos. */
    public ToggleButton opcion(String clave, String texto, String icono, Runnable accion) {
        Region indicador = new Region();
        indicador.getStyleClass().add("nav-indicador");
        FontIcon ico = new FontIcon(icono);
        Label nombre = new Label(texto);
        nombre.getStyleClass().add("nav-texto");
        nombre.setMinWidth(0);
        Label detalle = new Label();
        detalle.getStyleClass().add("nav-detalle");
        detalle.managedProperty().bind(detalle.visibleProperty());
        detalle.setVisible(false);
        VBox textos = new VBox(0, nombre, detalle);
        textos.setAlignment(Pos.CENTER_LEFT);
        textos.setMinWidth(0);
        textos.managedProperty().bind(textos.visibleProperty());
        HBox.setHgrow(textos, Priority.ALWAYS);
        Label contador = new Label();
        contador.getStyleClass().add("nav-contador");
        contador.managedProperty().bind(contador.visibleProperty());
        contador.setVisible(false);
        contador.setMinWidth(Region.USE_PREF_SIZE);
        // Con el menú compacto el número no cabe: un punto sobre el ícono avisa que hay pendientes.
        javafx.scene.shape.Circle punto = new javafx.scene.shape.Circle(4);
        punto.getStyleClass().add("nav-punto");
        punto.setVisible(false);
        StackPane conPunto = new StackPane(ico, punto);
        StackPane.setAlignment(punto, Pos.TOP_RIGHT);
        punto.setTranslateX(4);
        punto.setTranslateY(-3);
        HBox fila = new HBox(9, indicador, conPunto, textos, contador);
        fila.setAlignment(Pos.CENTER_LEFT);

        ToggleButton boton = new ToggleButton(null, fila);
        boton.setId("nav-" + clave);
        boton.getStyleClass().setAll("nav-opcion");
        boton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        boton.setMaxWidth(Double.MAX_VALUE);
        boton.setToggleGroup(grupo);
        boton.setAccessibleText(texto);
        fila.prefWidthProperty().bind(boton.widthProperty().subtract(8));
        Tooltip ayuda = new Tooltip(texto);
        boton.setOnAction(e -> accion.run());
        opciones.getChildren().add(boton);
        porClave.put(clave, new Opcion(clave, texto, grupoEnCurso, boton, nombre, detalle, contador, ayuda, punto));
        aplicarCompacto();
        return boton;
    }

    /** Marca la opción como activa y actualiza la ruta de la barra superior. */
    public void seleccionar(String clave) {
        Opcion o = porClave.get(clave);
        if (o == null) {
            return;
        }
        o.boton().setSelected(true);
        grupoActual.set(o.grupo());
        paginaActual.set(o.texto());
    }

    /** Número de pendientes junto a la opción (0 lo oculta). */
    public void setContador(String clave, int n) {
        Opcion o = porClave.get(clave);
        if (o == null) {
            return;
        }
        o.contador().setText(n > 99 ? "99+" : String.valueOf(n));
        o.contador().setVisible(n > 0 && !compacto.get());
        o.punto().setVisible(n > 0 && compacto.get());
        String accesible = o.texto() + (n > 0 ? ", " + n + " pendientes" : "");
        o.boton().setAccessibleText(accesible);
        o.ayuda().setText(n > 0 ? o.texto() + " (" + n + ")" : o.texto());
    }

    /** Segunda línea de una opción ("2 en su mínimo"); {@code alerta} la resalta. */
    public void setDetalle(String clave, String texto, boolean alerta) {
        Opcion o = porClave.get(clave);
        if (o == null) {
            return;
        }
        o.detalle().setText(texto == null ? "" : texto);
        o.detalle().setVisible(texto != null && !texto.isBlank() && !compacto.get());
        o.detalle().getStyleClass().remove("nav-alerta");
        if (alerta) {
            o.detalle().getStyleClass().add("nav-alerta");
        }
        altoSegunDetalle(o);
    }

    /** Con segunda línea la opción es más alta (por clase CSS: la hoja de estilos manda sobre el código). */
    private void altoSegunDetalle(Opcion o) {
        boolean dosLineas = o.detalle().isVisible();
        o.boton().getStyleClass().remove("con-detalle");
        if (dosLineas) {
            o.boton().getStyleClass().add("con-detalle");
        }
    }

    public ReadOnlyStringProperty grupoActualProperty() {
        return grupoActual.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty paginaActualProperty() {
        return paginaActual.getReadOnlyProperty();
    }

    public BooleanProperty compactoProperty() {
        return compacto;
    }

    private void aplicarCompacto() {
        boolean c = compacto.get();
        getStyleClass().remove("compacta");
        if (c) {
            getStyleClass().add("compacta");
        }
        textosMarca.setVisible(!c);
        alternar.setVisible(!c);
        logo.setCursor(c ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
        if (c) {
            Tooltip.install(logo, AYUDA_LOGO);
        } else {
            Tooltip.uninstall(logo, AYUDA_LOGO);
        }
        for (Opcion o : porClave.values()) {
            o.nombre().getParent().setVisible(!c);
            boolean hay = !o.contador().getText().isBlank() && !"0".equals(o.contador().getText());
            o.contador().setVisible(hay && !c);
            o.punto().setVisible(hay && c);
            o.contador().getStyleClass().remove("neutro");
            o.boton().setTooltip(c ? o.ayuda() : null);
            o.detalle().setVisible(!c && !o.detalle().getText().isBlank());
            altoSegunDetalle(o);
        }
        titulosGrupo.forEach((l, t) -> l.setText(c ? "" : t));
        alternar.setGraphic(new FontIcon("mdi2d-dock-left"));
        alternar.setAccessibleText("Contraer menú");
        alternar.setTooltip(new Tooltip("Dejar solo los íconos del menú"));
    }
}
