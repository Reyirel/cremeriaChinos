package com.cremerias.puntoventa.ui;

import com.cremerias.puntoventa.sync.EstadoNube;
import com.cremerias.puntoventa.sync.Sincronizador;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** Muestra cómo está la caja con la nube y cuántos cambios locales faltan por subir. */
public class IndicadorConexion extends HBox {

    private final FontIcon icono = new FontIcon();
    private final Label texto = new Label();
    private final Label pendientes = new Label();

    public IndicadorConexion(Sincronizador sincronizador) {
        getStyleClass().add("indicador-conexion");
        setSpacing(8);
        setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox estado = new HBox(6, icono, texto);
        estado.getStyleClass().add("chip-estado");
        estado.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Tooltip detalle = new Tooltip();
        detalle.textProperty().bind(sincronizador.detalleProperty());
        Tooltip.install(estado, detalle);

        FontIcon iconoPendientes = new FontIcon("mdi2c-cloud-sync-outline");
        pendientes.setGraphic(iconoPendientes);
        pendientes.getStyleClass().add("chip-pendientes");
        pendientes.textProperty().bind(Bindings.createStringBinding(() -> {
            int n = sincronizador.pendientesProperty().get();
            return n == 1 ? "1 cambio por sincronizar" : n + " cambios por sincronizar";
        }, sincronizador.pendientesProperty()));
        pendientes.visibleProperty().bind(sincronizador.pendientesProperty().greaterThan(0));
        pendientes.managedProperty().bind(pendientes.visibleProperty());
        Tooltip.install(pendientes, new Tooltip(
                "Guardados de forma segura en este equipo.\nSe suben a la nube en cuanto hay conexión."));

        getChildren().addAll(estado, pendientes);

        sincronizador.estadoProperty().addListener((obs, antes, ahora) -> actualizar(estado, ahora));
        actualizar(estado, sincronizador.estadoProperty().get());
    }

    private void actualizar(HBox estado, EstadoNube nube) {
        boolean bien = nube == EstadoNube.AL_DIA || nube == EstadoNube.SINCRONIZANDO;
        estado.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("en-linea"), bien);
        switch (nube) {
            case SOLO_LOCAL -> mostrar("mdi2c-cloud-off-outline", "Solo en esta caja");
            case SIN_CONEXION -> mostrar("mdi2w-wifi-off", "Sin conexión · modo local");
            case SINCRONIZANDO -> mostrar("mdi2c-cloud-sync-outline", "Sincronizando…");
            case AL_DIA -> mostrar("mdi2c-cloud-check-outline", "En línea");
            case ERROR -> mostrar("mdi2c-cloud-alert", "Error al sincronizar");
        }
    }

    private void mostrar(String icono, String texto) {
        this.icono.setIconLiteral(icono);
        this.texto.setText(texto);
    }
}
