package com.cremerias.puntoventa.ui.componentes;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Franja de métricas en un solo panel: la principal más grande, las demás compactas y separadas
 * por líneas finas. Las métricas se crean una vez y solo cambian sus valores al recargar.
 */
public class ResumenMetricas extends HBox {

    public ResumenMetricas() {
        getStyleClass().add("resumen-metricas");
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(true);
    }

    public Metrica agregar(String etiqueta) {
        if (!getChildren().isEmpty()) {
            Region separador = new Region();
            separador.getStyleClass().add("metrica-separador");
            getChildren().add(separador);
        }
        Metrica m = new Metrica(etiqueta);
        HBox.setHgrow(m, Priority.ALWAYS);
        getChildren().add(m);
        return m;
    }

    /** Una métrica: etiqueta, valor y una línea opcional de contexto. */
    public static final class Metrica extends VBox {

        private static final String[] TONOS = {"tono-peligro", "tono-aviso", "tono-exito"};

        private final Label valor = new Label("—");
        private final Label contexto = new Label();
        private final FontIcon icono = new FontIcon();
        private final Label etiqueta;

        private Metrica(String texto) {
            getStyleClass().add("metrica");
            etiqueta = new Label(texto);
            etiqueta.getStyleClass().add("metrica-etiqueta");
            valor.getStyleClass().add("metrica-valor");
            valor.setMinWidth(Region.USE_PREF_SIZE);
            contexto.getStyleClass().add("metrica-contexto");
            contexto.managedProperty().bind(contexto.visibleProperty());
            contexto.setVisible(false);
            icono.managedProperty().bind(icono.visibleProperty());
            icono.setVisible(false);
            HBox fila = new HBox(6, valor, icono);
            fila.setAlignment(Pos.CENTER_LEFT);
            setMaxWidth(Double.MAX_VALUE);
            setMinWidth(Region.USE_PREF_SIZE);
            getChildren().addAll(etiqueta, fila, contexto);
        }

        public Metrica principal() {
            getStyleClass().add("principal");
            return this;
        }

        public Metrica setValor(String texto) {
            valor.setText(texto);
            return this;
        }

        public Metrica setContexto(String texto) {
            contexto.setText(texto == null ? "" : texto);
            contexto.setVisible(texto != null && !texto.isBlank());
            return this;
        }

        public Metrica setEtiqueta(String texto) {
            etiqueta.setText(texto);
            return this;
        }

        /** Tono de alerta con un ícono que lo acompaña (no solo color). {@code null} = normal. */
        public Metrica setTono(Etiqueta.Tono tono) {
            getStyleClass().removeAll(TONOS);
            if (tono == null || tono == Etiqueta.Tono.NEUTRO) {
                icono.setVisible(false);
                return this;
            }
            switch (tono) {
                case PELIGRO -> {
                    getStyleClass().add("tono-peligro");
                    icono.setIconLiteral("mdi2a-alert-circle-outline");
                }
                case AVISO -> {
                    getStyleClass().add("tono-aviso");
                    icono.setIconLiteral("mdi2a-alert-outline");
                }
                default -> {
                    getStyleClass().add("tono-exito");
                    icono.setIconLiteral("mdi2c-check-circle-outline");
                }
            }
            icono.setVisible(true);
            return this;
        }
    }
}
