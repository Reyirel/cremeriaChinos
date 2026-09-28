package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.service.admin.LimitesService;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Mínimos y máximos de cada producto por sucursal. Cuando la existencia llega al mínimo se
 * genera una orden de reabastecimiento por (máximo − existencia).
 */
public class LimitesPagina extends Pagina {

    /** Renglón editable. */
    private static final class Editable {
        final LimitesService.Fila fila;
        final StringProperty minimo = new SimpleStringProperty();
        final StringProperty maximo = new SimpleStringProperty();
        final String minimoOriginal;
        final String maximoOriginal;

        Editable(LimitesService.Fila fila) {
            this.fila = fila;
            minimoOriginal = Cantidades.aCaptura(fila.producto().unidad(), fila.minimo());
            maximoOriginal = Cantidades.aCaptura(fila.producto().unidad(), fila.maximo());
            minimo.set(minimoOriginal);
            maximo.set(maximoOriginal);
        }

        boolean modificado() {
            return !minimoOriginal.equals(texto(minimo)) || !maximoOriginal.equals(texto(maximo));
        }

        private static String texto(StringProperty p) {
            return p.get() == null || p.get().isBlank() ? "0" : p.get().strip();
        }
    }

    private ComboBox<Sucursal> sucursal;
    private TextField buscador;
    private TableView<Editable> tabla;
    private Button guardar;
    private List<Editable> filas = List.of();

    public LimitesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        guardar = Ui.principal("Guardar cambios", "mdi2c-check");
        guardar.setOnAction(e -> guardar());
        VBox pagina = Ui.pagina("Mínimos y máximos",
                "Por sucursal, solo de los productos que ya se le han surtido (no todas venden lo mismo). Al llegar "
                        + "al mínimo, la sucursal genera sola una orden por lo que falta para el máximo. Deja 0 para "
                        + "no controlar el producto.", guardar);
        sucursal = new ComboBox<>();
        sucursal.setPromptText("Elige la sucursal");
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        buscador = Ui.buscador("Buscar producto");
        buscador.textProperty().addListener((o, x, y) -> filtrar());

        tabla = Ui.tabla("Elige una sucursal.");
        tabla.getColumns().setAll(List.of(
                Ui.nodo("Producto", 300, e -> Ui.dosLineas(e.fila.producto().nombre(),
                        e.fila.producto().categoria() == null ? "" : e.fila.producto().categoria())),
                Ui.texto("Existencia", 150, e -> Cantidades.formatear(e.fila.producto().unidad(), e.fila.existencia())),
                editable("Mínimo", e -> e.minimo),
                editable("Máximo", e -> e.maximo),
                Ui.texto("Unidad", 80, e -> Cantidades.unidadCaptura(e.fila.producto().unidad())),
                Ui.nodo("Estado", 150, e -> e.fila.maximo().signum() == 0 ? Ui.chip("Sin control", "neutro")
                        : e.fila.enMinimo() ? Ui.chip("En mínimo", "peligro") : Ui.chip("Bien", "exito"))));
        HBox filtros = new HBox(10, Ui.campo("Sucursal", sucursal), Ui.campo("Buscar", buscador));
        filtros.setAlignment(Pos.BOTTOM_LEFT);
        pagina.getChildren().addAll(filtros, tabla);
        return pagina;
    }

    private TableColumn<Editable, Editable> editable(String titulo, Function<Editable, StringProperty> propiedad) {
        TableColumn<Editable, Editable> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new javafx.beans.property.ReadOnlyObjectWrapper<>(f.getValue()));
        c.setPrefWidth(140);
        c.setSortable(false);
        c.setCellFactory(col -> new TableCell<>() {
            private final TextField campo = new TextField();
            private StringProperty ligada;

            {
                Campos.soloNumeros(campo, 0);
                campo.setAlignment(Pos.CENTER_RIGHT);
                campo.setPrefColumnCount(7);
                campo.getStyleClass().add("campo-tabla");
            }

            @Override
            protected void updateItem(Editable item, boolean vacio) {
                super.updateItem(item, vacio);
                if (ligada != null) {
                    campo.textProperty().unbindBidirectional(ligada);
                    ligada = null;
                }
                if (vacio || item == null) {
                    setGraphic(null);
                    return;
                }
                ligada = propiedad.apply(item);
                campo.textProperty().bindBidirectional(ligada);
                setGraphic(campo);
            }
        });
        return c;
    }

    @Override
    public void alMostrar() {
        Sucursal actual = sucursal.getValue();
        List<Sucursal> activas = a.admin().sucursales().activas();
        sucursal.setItems(FXCollections.observableArrayList(activas));
        if (actual != null) {
            activas.stream().filter(s -> s.id().equals(actual.id())).findFirst().ifPresent(sucursal::setValue);
        } else if (!activas.isEmpty()) {
            sucursal.setValue(activas.getFirst());
        }
        cargar();
    }

    private void cargar() {
        if (sucursal.getValue() == null) {
            filas = List.of();
        } else {
            filas = a.admin().limites().listar(sucursal.getValue().id()).stream().map(Editable::new).toList();
        }
        filtrar();
    }

    private void filtrar() {
        String t = buscador.getText() == null ? "" : buscador.getText().strip().toLowerCase(Locale.ROOT);
        tabla.setItems(FXCollections.observableArrayList(filas.stream()
                .filter(e -> t.isEmpty() || e.fila.producto().nombre().toLowerCase(Locale.ROOT).contains(t)).toList()));
    }

    private void guardar() {
        if (sucursal.getValue() == null) {
            a.avisos().error("Elige la sucursal.");
            return;
        }
        List<LimitesService.Cambio> cambios = new ArrayList<>();
        for (Editable e : filas) {
            if (e.modificado()) {
                var u = e.fila.producto().unidad();
                BigDecimal min = Cantidades.desdeCaptura(u, e.minimo.get()).orElse(BigDecimal.ZERO);
                BigDecimal max = Cantidades.desdeCaptura(u, e.maximo.get()).orElse(BigDecimal.ZERO);
                cambios.add(new LimitesService.Cambio(e.fila.producto(), min, max));
            }
        }
        if (cambios.isEmpty()) {
            a.avisos().info("No hay cambios que guardar.");
            return;
        }
        try {
            LimitesService.Guardado g = a.admin().limites().guardar(sucursal.getValue(), cambios, a.usuario());
            a.avisos().exito(cambios.size() + " producto(s) actualizados · Folio " + g.folio());
            if (g.ordenesGeneradas() > 0) {
                a.avisos().conAccion(g.ordenesGeneradas() + " producto(s) ya están en su mínimo: se generaron órdenes.",
                        "Ver", () -> a.irA().accept("ordenes"));
            }
            a.refrescarContadores().run();
            cargar();
        } catch (RuntimeException ex) {
            a.avisos().error(AdminContexto.mensaje(ex));
        }
    }
}
