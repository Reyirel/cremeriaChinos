package com.cremerias.puntoventa.ui;

import com.cremerias.puntoventa.sync.EstadoNube;
import com.cremerias.puntoventa.sync.Sincronizador;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * Cómo está la caja con la nube y cuántos cambios locales faltan por subir. El estado se dice con
 * texto; el punto de color solo lo refuerza. El detalle completo aparece al pasar el mouse.
 */
public class IndicadorConexion extends HBox {

    private static final PseudoClass EN_LINEA = PseudoClass.getPseudoClass("en-linea");
    private static final PseudoClass SINCRONIZANDO = PseudoClass.getPseudoClass("sincronizando");
    private static final PseudoClass SIN_CONEXION = PseudoClass.getPseudoClass("sin-conexion");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final HBox estado = new HBox();
    private final Label texto = new Label();
    private final Label pendientes = new Label();

    public IndicadorConexion(Sincronizador sincronizador) {
        getStyleClass().add("indicador-conexion");
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);

        Circle punto = new Circle(4);
        punto.getStyleClass().add("punto-estado");
        estado.getStyleClass().add("estado-conexion");
        estado.getChildren().addAll(punto, texto);
        Tooltip detalle = new Tooltip();
        detalle.setWrapText(true);
        detalle.setMaxWidth(360);
        detalle.textProperty().bind(sincronizador.detalleProperty());
        Tooltip.install(estado, detalle);

        pendientes.setGraphic(new FontIcon("mdi2c-cloud-upload-outline"));
        pendientes.getStyleClass().add("pendientes-subir");
        pendientes.textProperty().bind(Bindings.createStringBinding(() -> {
            int n = sincronizador.pendientesProperty().get();
            return n == 1 ? "1 cambio por subir" : n + " cambios por subir";
        }, sincronizador.pendientesProperty()));
        // Una caja sin nube no sube nada: si se conecta, sus datos se cambian por los de la nube.
        pendientes.visibleProperty().bind(sincronizador.pendientesProperty().greaterThan(0)
                .and(sincronizador.estadoProperty().isNotEqualTo(EstadoNube.SOLO_LOCAL)));
        pendientes.managedProperty().bind(pendientes.visibleProperty());
        Tooltip.install(pendientes, new Tooltip(
                "Guardados de forma segura en este equipo.\nSe suben a la nube en cuanto hay conexión."));

        getChildren().addAll(estado, pendientes);

        sincronizador.estadoProperty().addListener((obs, antes, ahora) -> actualizar(ahora));
        actualizar(sincronizador.estadoProperty().get());
    }

    private void actualizar(EstadoNube nube) {
        for (PseudoClass p : List.of(EN_LINEA, SINCRONIZANDO, SIN_CONEXION, ERROR)) {
            estado.pseudoClassStateChanged(p, false);
        }
        switch (nube) {
            case SOLO_LOCAL -> texto.setText("Solo en este equipo");
            case SIN_CONEXION -> {
                texto.setText("Sin conexión · trabajo local");
                estado.pseudoClassStateChanged(SIN_CONEXION, true);
            }
            case SINCRONIZANDO -> {
                texto.setText("Sincronizando…");
                estado.pseudoClassStateChanged(SINCRONIZANDO, true);
            }
            case AL_DIA -> {
                texto.setText("En línea");
                estado.pseudoClassStateChanged(EN_LINEA, true);
            }
            case ERROR -> {
                texto.setText("Error al sincronizar");
                estado.pseudoClassStateChanged(ERROR, true);
            }
        }
        estado.setAccessibleText("Conexión: " + texto.getText());
    }
}
