package com.cremerias.puntoventa.ui;

import com.cremerias.puntoventa.sync.Sincronizador;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** Muestra si hay internet y cuántos cambios locales faltan por subir a la nube. */
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
                "Guardados de forma segura en este equipo.\nSe subirán a la nube cuando se active la sincronización."));

        getChildren().addAll(estado, pendientes);

        sincronizador.enLineaProperty().addListener((obs, antes, ahora) -> actualizar(estado, ahora));
        actualizar(estado, sincronizador.enLinea());
    }

    private void actualizar(HBox estado, boolean enLinea) {
        estado.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("en-linea"), enLinea);
        icono.setIconLiteral(enLinea ? "mdi2w-wifi" : "mdi2w-wifi-off");
        texto.setText(enLinea ? "En línea" : "Sin conexión · modo local");
    }
}
