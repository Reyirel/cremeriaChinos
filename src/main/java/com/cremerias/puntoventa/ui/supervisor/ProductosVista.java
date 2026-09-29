package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.service.InventarioSucursalService;
import com.cremerias.puntoventa.service.InventarioSucursalService.Fila;
import com.cremerias.puntoventa.service.SinVentaService.SinVenta;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Productos de la sucursal del supervisor, solo para consulta: existencia, precios, mínimo/máximo
 * y los que hay que ofertar porque llevan su plazo sin venderse. No hay nada que editar; la
 * existencia baja con cada venta registrada en caja.
 */
public class ProductosVista {

    private final AppContext ctx;
    private final Avisos avisos;
    private final String sucursalId;
    private final VBox pagina;
    private final HBox indicadores = new HBox(14);
    private final TextField buscador = Ui.buscador("Buscar por nombre, clave o código");
    private final TableView<Fila> tabla;
    private List<Fila> todos = List.of();
    /** Productos que llevan su plazo sin venderse (avisos para el supervisor), por id. */
    private Map<String, SinVenta> paraOfertar = Map.of();

    public ProductosVista(Navegador navegador) {
        this.ctx = navegador.contexto();
        this.avisos = navegador.avisos();
        this.sucursalId = ctx.sesionActual().obtener().orElseThrow().usuario().sucursalId();

        Button actualizar = Ui.secundario("Actualizar", "mdi2r-refresh");
        actualizar.setOnAction(e -> {
            actualizar();
            avisos.info("Información actualizada.");
        });
        pagina = Ui.pagina("Productos", sucursalId == null
                ? "Tu usuario no tiene una sucursal asignada."
                : ctx.nombreSucursal(sucursalId) + " · solo consulta. La existencia se descuenta con cada venta "
                        + "registrada en caja.", actualizar);
        buscador.textProperty().addListener((o, x, y) -> filtrar());
        tabla = Ui.tabla(sucursalId == null ? "Tu usuario no tiene una sucursal asignada."
                : "Esta sucursal aún no tiene productos surtidos.");
        tabla.getColumns().setAll(List.of(
                Ui.nodo("Producto", 300, f -> Ui.dosLineas(f.producto().nombre(), detalle(f))),
                Ui.nodo("Existencia", 140, f -> {
                    Label l = new Label(Cantidades.formatear(f.producto().unidad(), f.existencia()));
                    l.getStyleClass().add(f.agotado() ? "texto-apagado" : "texto-negrita");
                    return l;
                }),
                Ui.nodo("Precio de venta", 260, ProductosVista::precios),
                Ui.nodo("Mínimo / máximo", 190, f -> f.maximo().signum() == 0
                        ? Ui.texto("Sin control", "texto-apagado")
                        : Ui.dosLineas("Mín. " + Cantidades.formatear(f.producto().unidad(), f.minimo()),
                        "Máx. " + Cantidades.formatear(f.producto().unidad(), f.maximo()))),
                Ui.nodo("Estado", 220, this::estado)));
        pagina.getChildren().addAll(indicadores, new HBox(10, buscador), tabla);
    }

    public Node nodo() {
        return pagina;
    }

    public void actualizar() {
        todos = sucursalId == null ? List.of() : ctx.inventarioSucursal().listar(sucursalId);
        paraOfertar = sucursalId == null ? Map.of() : ctx.sinVenta().pendientes(Rol.SUPERVISOR, sucursalId).stream()
                .collect(Collectors.toMap(SinVenta::productoId, s -> s, (a, b) -> a));
        indicadores.getChildren().setAll(
                Ui.indicador("Productos", String.valueOf(todos.size()), "mdi2p-package-variant-closed"),
                Ui.indicador("Para ofertar", String.valueOf(paraOfertar.size()), "mdi2t-tag-outline"),
                Ui.indicador("En mínimo", String.valueOf(todos.stream().filter(Fila::enMinimo).count()),
                        "mdi2a-alert-outline"),
                Ui.indicador("Agotados", String.valueOf(todos.stream().filter(Fila::agotado).count()),
                        "mdi2c-close-circle-outline"));
        filtrar();
    }

    private void filtrar() {
        String t = buscador.getText() == null ? "" : buscador.getText().strip().toLowerCase(Locale.ROOT);
        tabla.setItems(FXCollections.observableArrayList(todos.stream()
                .filter(f -> t.isEmpty() || coincide(f, t))
                .toList()));
    }

    private static boolean coincide(Fila f, String texto) {
        var p = f.producto();
        return Stream.concat(Stream.of(p.nombre(), p.clave(), p.codigoInventario()),
                        p.presentaciones().stream().map(pr -> pr.codigoBarras()))
                .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(texto));
    }

    /** Agotado, para ofertar (con el tiempo sin venta), en mínimo o con existencia. */
    private Node estado(Fila f) {
        if (f.agotado()) {
            return Ui.chip("Agotado", "peligro");
        }
        HBox chips = new HBox(4);
        SinVenta s = paraOfertar.get(f.producto().id());
        if (s != null) {
            Label ofertar = Ui.chip("Ofertar · " + AvisoSinVenta.formatear(s.sinVender()), "advertencia");
            ofertar.setTooltip(new Tooltip("Plazo sin venta: " + s.plazo()));
            chips.getChildren().add(ofertar);
        }
        if (f.enMinimo()) {
            chips.getChildren().add(Ui.chip("En mínimo", "advertencia"));
        }
        if (chips.getChildren().isEmpty()) {
            chips.getChildren().add(Ui.chip("Con existencia", "exito"));
        }
        chips.setAlignment(Pos.CENTER_LEFT);
        return chips;
    }

    /** Categoría y clave/PLU debajo del nombre. */
    private static String detalle(Fila f) {
        var p = f.producto();
        return Stream.of(p.categoria(), p.clave() == null ? null : "Clave " + p.clave())
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(" · "));
    }

    /** Precio de la presentación principal y, debajo, el de las demás. */
    private static Node precios(Fila f) {
        List<InventarioSucursalService.Precio> precios = f.precios();
        if (precios.isEmpty()) {
            return Ui.texto("Sin precio", "texto-apagado");
        }
        String otros = precios.stream().skip(1).map(ProductosVista::precio).collect(Collectors.joining(" · "));
        return Ui.dosLineas(precio(precios.getFirst()), otros);
    }

    private static String precio(InventarioSucursalService.Precio p) {
        return p.presentacion().nombre() + (p.presentacion().granel() ? " (kg)" : "") + ": "
                + Dinero.formatear(p.precioCentavos());
    }
}
