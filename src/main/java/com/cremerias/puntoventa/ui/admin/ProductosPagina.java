package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Catálogo de productos del almacén. */
public class ProductosPagina extends Pagina {

    private TableView<ProductoCatalogo> tabla;
    private TextField buscador;
    private ComboBox<String> filtro;
    private List<ProductoCatalogo> todos = List.of();

    public ProductosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        var nuevo = Ui.principal("Nuevo producto", "mdi2p-plus");
        nuevo.setOnAction(e -> DialogoProducto.mostrar(a, null, this::alMostrar));
        VBox pagina = Ui.pagina("Productos",
                "Cada producto puede venderse en varias presentaciones. Los precios se fijan por sucursal al surtir.", nuevo);
        buscador = Ui.buscador("Buscar por nombre, clave, código de barras o código de inventario");
        buscador.textProperty().addListener((o, x, y) -> filtrar());
        filtro = new ComboBox<>(FXCollections.observableArrayList("Todos", "Activos", "Deshabilitados",
                "Edición especial", "De temporada", "Sujetos a merma", "Sin gramaje"));
        filtro.setValue("Todos");
        filtro.valueProperty().addListener((o, x, y) -> filtrar());

        tabla = Ui.tabla("No hay productos.");
        tabla.setFixedCellSize(58);
        tabla.getColumns().setAll(List.of(
                Ui.nodo("Producto", 280, p -> {
                    VBox v = Ui.dosLineas(p.nombre(), p.categoria() == null ? "Sin categoría" : p.categoria());
                    return v;
                }),
                Ui.texto("Clave", 80, p -> p.clave() == null ? "" : p.clave()),
                Ui.texto("Cód. inventario", 110, p -> p.codigoInventario() == null ? "" : p.codigoInventario()),
                Ui.texto("Se vende", 100, p -> p.unidad() == Unidad.KG ? "Por kilo" : "Por pieza"),
                Ui.nodo("Gramaje", 170, p -> {
                    if (p.gramajeGramos() == null) {
                        return Ui.chip("Sin gramaje", "peligro");
                    }
                    String porUnidad = p.unidad() == Unidad.KG ? " por kg" : "";
                    return p.mermaGramos().signum() > 0
                            ? Ui.dosLineas(Masa.formatear(p.gramajeNeto()) + " netos" + porUnidad,
                            Masa.formatear(p.gramajeGramos()) + " − " + Masa.formatear(p.mermaGramos()) + " merma")
                            : Ui.dosLineas(Masa.formatear(p.gramajeGramos()) + porUnidad, null);
                }),
                Ui.nodo("Presentaciones", 240, p -> {
                    FlowPane f = new FlowPane(4, 4);
                    f.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    for (Presentacion pr : p.presentaciones()) {
                        f.getChildren().add(Ui.chip(pr.nombre(), pr.activo() ? "neutro" : "tachado"));
                    }
                    return f;
                }),
                Ui.nodo("Tipo", 170, p -> {
                    HBox h = new HBox(4);
                    if (p.disponibilidad() != Disponibilidad.REGULAR) {
                        h.getChildren().add(Ui.chip(p.disponibilidad().nombre(), "acento"));
                    }
                    if (p.sujetoMerma()) {
                        h.getChildren().add(Ui.chip("Merma", "advertencia"));
                    }
                    return h;
                }),
                Ui.nodo("Estado", 120, p -> p.activo() ? Ui.chip("Activo", "exito") : Ui.chip("Deshabilitado", "neutro")),
                Ui.id(ProductoCatalogo::id),
                Ui.nodo("", 130, p -> Ui.acciones(
                        Ui.accion("mdi2p-pencil-outline", "Editar", () -> DialogoProducto.mostrar(a, p, this::alMostrar)),
                        Ui.accion(p.activo() ? "mdi2e-eye-off-outline" : "mdi2e-eye-outline",
                                p.activo() ? "Deshabilitar (deja de venderse)" : "Habilitar", () -> cambiarActivo(p)),
                        Ui.accion("mdi2t-trash-can-outline", "Eliminar", () -> eliminar(p), "danger")))));
        pagina.getChildren().addAll(new HBox(10, buscador, filtro), tabla);
        return pagina;
    }

    @Override
    public void alMostrar() {
        todos = a.admin().productos().listar();
        filtrar();
    }

    private void filtrar() {
        String t = buscador.getText() == null ? "" : buscador.getText().strip().toLowerCase(Locale.ROOT);
        tabla.setItems(FXCollections.observableArrayList(todos.stream()
                .filter(p -> switch (filtro.getValue()) {
                    case "Activos" -> p.activo();
                    case "Deshabilitados" -> !p.activo();
                    case "Edición especial" -> p.disponibilidad() == Disponibilidad.EDICION_ESPECIAL;
                    case "De temporada" -> p.disponibilidad() == Disponibilidad.TEMPORADA;
                    case "Sujetos a merma" -> p.sujetoMerma();
                    case "Sin gramaje" -> p.gramajeGramos() == null;
                    default -> true;
                })
                .filter(p -> t.isEmpty() || p.nombre().toLowerCase(Locale.ROOT).contains(t)
                        || (p.clave() != null && p.clave().toLowerCase(Locale.ROOT).startsWith(t))
                        || (p.codigoInventario() != null && p.codigoInventario().toLowerCase(Locale.ROOT).startsWith(t))
                        || p.presentaciones().stream().anyMatch(pr -> pr.codigoBarras() != null && pr.codigoBarras().startsWith(t)))
                .collect(Collectors.toList())));
    }

    private void cambiarActivo(ProductoCatalogo p) {
        DialogoConfirmacion.mostrar(a.dialogos(), (p.activo() ? "¿Deshabilitar " : "¿Habilitar ") + p.nombre() + "?",
                p.activo() ? "Dejará de venderse en las cajas y no se podrá surtir. No se borra: puedes habilitarlo después."
                        : "Volverá a venderse en las sucursales que tengan existencia.",
                p.activo() ? "Deshabilitar" : "Habilitar", p.activo(), () -> {
                    a.ejecutar(p.activo() ? "Producto deshabilitado" : "Producto habilitado",
                            () -> a.admin().productos().cambiarActivo(p, !p.activo(), a.usuario()));
                    alMostrar();
                });
    }

    private void eliminar(ProductoCatalogo p) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar " + p.nombre() + "?",
                "Ya no aparecerá en el catálogo ni en las cajas. Su historial de ventas y lotes se conserva con su id "
                        + p.id() + ".", "Eliminar", true, () -> {
                    a.ejecutar("Producto eliminado", () -> a.admin().productos().eliminar(p, a.usuario()));
                    alMostrar();
                });
    }
}
