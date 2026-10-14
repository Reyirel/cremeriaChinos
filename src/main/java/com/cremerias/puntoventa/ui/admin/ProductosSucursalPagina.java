package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.InventarioSucursalService;
import com.cremerias.puntoventa.service.InventarioSucursalService.EstadoInventario;
import com.cremerias.puntoventa.service.InventarioSucursalService.Fila;
import com.cremerias.puntoventa.service.InventarioSucursalService.FilaDetallada;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Productos por sucursal con su costo, valor de inventario y lo vendido en el periodo. Solo lectura. */
public class ProductosSucursalPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);

    private ComboBox<Sucursal> sucursal;
    private TextField buscador;
    private ComboBox<String> categoriaFiltro;
    private ComboBox<String> marcaFiltro;
    private ComboBox<String> estadoFiltro;
    private ComboBox<String> temporadaFiltro;
    private CheckBox soloConExistencia;
    private DatePicker desdeVendido;
    private DatePicker hastaVendido;
    private TableView<FilaDetallada> tabla;
    private TableColumn<FilaDetallada, String> columnaSucursal;
    private Label totalProductos;
    private Label totalValor;
    private List<FilaDetallada> todos = List.of();

    public ProductosSucursalPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Productos por sucursal",
                "Costo, valor de inventario y lo vendido en el periodo, por sucursal. Solo lectura.");

        sucursal = new ComboBox<>();
        sucursal.valueProperty().addListener((o, x, y) -> cargar());

        buscador = Ui.buscador("Buscar por nombre o código");
        buscador.textProperty().addListener((o, x, y) -> filtrar());

        categoriaFiltro = comboFiltro();
        categoriaFiltro.valueProperty().addListener((o, x, y) -> filtrar());
        marcaFiltro = comboFiltro();
        marcaFiltro.valueProperty().addListener((o, x, y) -> filtrar());
        estadoFiltro = new ComboBox<>(FXCollections.observableArrayList(
                "Todos", "Agotado", "En mínimo", "Por reabastecer", "Con existencia"));
        estadoFiltro.setValue("Todos");
        estadoFiltro.valueProperty().addListener((o, x, y) -> filtrar());
        temporadaFiltro = new ComboBox<>(FXCollections.observableArrayList("Todos", "De temporada", "Regulares"));
        temporadaFiltro.setValue("Todos");
        temporadaFiltro.valueProperty().addListener((o, x, y) -> filtrar());
        soloConExistencia = new CheckBox("Solo con existencia");
        soloConExistencia.selectedProperty().addListener((o, x, y) -> filtrar());

        LocalDate hoy = LocalDate.now();
        desdeVendido = new DatePicker(hoy.minusDays(29));
        hastaVendido = new DatePicker(hoy);
        desdeVendido.setEditable(false);
        hastaVendido.setEditable(false);
        desdeVendido.valueProperty().addListener((o, x, y) -> cargar());
        hastaVendido.valueProperty().addListener((o, x, y) -> cargar());

        HBox filaUno = new HBox(14, Ui.campo("Sucursal", sucursal), Ui.campo("Buscar", buscador),
                Ui.campo("Categoría", categoriaFiltro), Ui.campo("Marca", marcaFiltro));
        HBox filaDos = new HBox(14, Ui.campo("Estado", estadoFiltro), Ui.campo("Temporada", temporadaFiltro),
                Ui.campo("Vendido desde", desdeVendido), Ui.campo("Vendido hasta", hastaVendido), soloConExistencia);
        filaDos.setAlignment(Pos.BOTTOM_LEFT);
        VBox filtros = new VBox(12, filaUno, filaDos);

        tabla = Ui.tabla("No hay productos para esta sucursal.");
        columnaSucursal = Ui.texto("Sucursal", 130, f -> f.sucursal().nombre());
        tabla.getColumns().setAll(List.of(
                columnaSucursal,
                Ui.nodo("Código", 110, f -> Ui.dosLineas(
                        f.fila().producto().clave() == null ? "—" : f.fila().producto().clave(),
                        f.fila().producto().codigoInventario() == null ? "" : "Inv. " + f.fila().producto().codigoInventario())),
                Ui.texto("Producto", 220, f -> f.fila().producto().nombre()),
                Ui.texto("Categoría", 130, f -> f.fila().producto().categoria() == null ? "—" : f.fila().producto().categoria()),
                Ui.texto("Marca", 120, f -> f.fila().producto().marca() == null ? "—" : f.fila().producto().marca()),
                Ui.texto("Unidad", 90, f -> f.fila().producto().unidad() == Unidad.KG ? "Por kilo" : "Por pieza"),
                Ui.texto("Existencia", 110, f -> Cantidades.formatear(f.fila().producto().unidad(), f.fila().existencia())),
                Ui.nodo("Mínimo / máximo", 130, f -> f.fila().maximo().signum() == 0
                        ? Ui.texto("Sin control", "texto-apagado")
                        : Ui.dosLineas("Mín. " + Cantidades.formatear(f.fila().producto().unidad(), f.fila().minimo()),
                        "Máx. " + Cantidades.formatear(f.fila().producto().unidad(), f.fila().maximo()))),
                Ui.nodo("Estado", 140, this::chipEstado),
                Ui.dinero("Costo promedio", 110, FilaDetallada::costoPromedioCentavos),
                Ui.dinero("Valor del inventario", 130, FilaDetallada::valorInventarioCentavos),
                Ui.nodo("Precios", 220, this::precios),
                Ui.nodo("Vendido en el periodo", 150, f -> Ui.dosLineas(
                        Cantidades.formatear(f.fila().producto().unidad(), f.cantidadVendida()) + " vendidos",
                        Dinero.formatear(f.importeVendidoCentavos()))),
                Ui.nodo("Última venta", 150, f -> f.ultimaVenta() == null
                        ? Ui.texto("Sin ventas", "texto-apagado")
                        : Ui.dosLineas(FECHA.format(f.ultimaVenta().atZone(ZoneId.systemDefault())),
                        f.diasSinVenta() + (f.diasSinVenta() == 1 ? " día sin vender" : " días sin vender"))),
                Ui.nodo("Temporada", 150, this::chipTemporada),
                Ui.nodo("", 70, f -> Ui.accion("mdi2e-eye-outline", "Ver detalle", () -> verDetalle(f)))));
        tabla.setPrefHeight(420);

        totalProductos = new Label();
        totalProductos.getStyleClass().add("indicador-valor");
        totalValor = new Label();
        totalValor.getStyleClass().add("indicador-valor");
        HBox totales = new HBox(24,
                new HBox(8, Ui.texto("Productos:", "indicador-titulo"), totalProductos),
                new HBox(8, Ui.texto("Valor del inventario:", "indicador-titulo"), totalValor));

        pagina.getChildren().addAll(filtros, tabla, totales);
        return pagina;
    }

    private static ComboBox<String> comboFiltro() {
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("Todas"));
        combo.setValue("Todas");
        return combo;
    }

    @Override
    public void alMostrar() {
        Sucursal actual = sucursal.getValue();
        List<Sucursal> reales = a.admin().sucursales().listar();
        List<Sucursal> opciones = new ArrayList<>();
        opciones.add(TODAS);
        opciones.addAll(reales);
        sucursal.setItems(FXCollections.observableArrayList(opciones));
        if (actual != null) {
            opciones.stream().filter(s -> (s.id() == null ? TODAS.id() : s.id())
                    .equals(actual.id() == null ? TODAS.id() : actual.id())).findFirst().ifPresent(sucursal::setValue);
        } else {
            sucursal.setValue(TODAS);
        }

        List<ProductoCatalogo> catalogo = a.admin().productos().listar();
        List<String> categorias = new ArrayList<>();
        categorias.add("Todas");
        catalogo.stream().map(ProductoCatalogo::categoria).filter(v -> v != null && !v.isBlank()).distinct().sorted()
                .forEach(categorias::add);
        categoriaFiltro.setItems(FXCollections.observableArrayList(categorias));
        if (!categorias.contains(categoriaFiltro.getValue())) {
            categoriaFiltro.setValue("Todas");
        }
        List<String> marcas = new ArrayList<>();
        marcas.add("Todas");
        catalogo.stream().map(ProductoCatalogo::marca).filter(v -> v != null && !v.isBlank()).distinct().sorted()
                .forEach(marcas::add);
        marcaFiltro.setItems(FXCollections.observableArrayList(marcas));
        if (!marcas.contains(marcaFiltro.getValue())) {
            marcaFiltro.setValue("Todas");
        }

        cargar();
    }

    private void cargar() {
        Sucursal s = sucursal.getValue();
        LocalDate desde = desdeVendido.getValue();
        LocalDate hasta = hastaVendido.getValue();
        if (s == null || desde == null || hasta == null || desde.isAfter(hasta)) {
            return;
        }
        boolean todasLasSucursales = s == TODAS;
        columnaSucursal.setVisible(todasLasSucursales);
        try {
            List<FilaDetallada> lista = new ArrayList<>();
            for (Sucursal real : todasLasSucursales ? a.admin().sucursales().listar() : List.of(s)) {
                lista.addAll(a.ctx().inventarioSucursal().listarDetallado(a.usuario(), real, desde, hasta));
            }
            todos = lista;
            filtrar();
        } catch (RuntimeException e) {
            a.avisos().error(AdminContexto.mensaje(e));
        }
    }

    private void filtrar() {
        String t = buscador.getText() == null ? "" : buscador.getText().strip().toLowerCase(Locale.ROOT);
        List<FilaDetallada> filtrados = todos.stream()
                .filter(f -> t.isEmpty() || coincideBusqueda(f.fila(), t))
                .filter(f -> "Todas".equals(categoriaFiltro.getValue())
                        || categoriaFiltro.getValue().equals(f.fila().producto().categoria()))
                .filter(f -> "Todas".equals(marcaFiltro.getValue()) || marcaFiltro.getValue().equals(f.fila().producto().marca()))
                .filter(f -> coincideEstado(f))
                .filter(f -> coincideTemporada(f))
                .filter(f -> !soloConExistencia.isSelected() || f.fila().existencia().signum() > 0)
                .toList();
        tabla.setItems(FXCollections.observableArrayList(filtrados));
        totalProductos.setText(String.valueOf(filtrados.size()));
        totalValor.setText(Dinero.formatear(filtrados.stream().mapToLong(FilaDetallada::valorInventarioCentavos).sum()));
    }

    private static boolean coincideBusqueda(Fila f, String texto) {
        var p = f.producto();
        return Stream.of(p.nombre(), p.clave(), p.codigoInventario())
                .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(texto));
    }

    private boolean coincideEstado(FilaDetallada f) {
        return switch (estadoFiltro.getValue()) {
            case "Agotado" -> f.estado() == EstadoInventario.AGOTADO;
            case "En mínimo" -> f.estado() == EstadoInventario.EN_MINIMO;
            case "Por reabastecer" -> f.estado() == EstadoInventario.POR_REABASTECER;
            case "Con existencia" -> f.estado() == EstadoInventario.OK;
            default -> true;
        };
    }

    private boolean coincideTemporada(FilaDetallada f) {
        boolean esDeTemporada = f.fila().producto().temporada() != null;
        return switch (temporadaFiltro.getValue()) {
            case "De temporada" -> esDeTemporada;
            case "Regulares" -> !esDeTemporada;
            default -> true;
        };
    }

    private Node chipEstado(FilaDetallada f) {
        return switch (f.estado()) {
            case AGOTADO -> Ui.chip("Agotado", "peligro");
            case EN_MINIMO -> Ui.chip("En mínimo", "advertencia");
            case POR_REABASTECER -> Ui.chip("Por reabastecer", "acento");
            case OK -> Ui.chip("Con existencia", "exito");
        };
    }

    private Node chipTemporada(FilaDetallada f) {
        Temporada t = f.fila().producto().temporada();
        if (t == null) {
            return Ui.texto("—", "texto-apagado");
        }
        return Ui.texto(FECHA.format(t.desde()) + " – " + FECHA.format(t.fin())
                + (t.repite() ? " (se repite " + t.describirRepeticion() + ")" : ""), "texto-ayuda");
    }

    /** Precio de la presentación principal y, debajo, el de las demás. */
    private Node precios(FilaDetallada f) {
        List<InventarioSucursalService.Precio> precios = f.fila().precios();
        if (precios.isEmpty()) {
            return Ui.texto("Sin precio", "texto-apagado");
        }
        String otros = precios.stream().skip(1).map(ProductosSucursalPagina::precio).collect(Collectors.joining(" · "));
        return Ui.dosLineas(precio(precios.getFirst()), otros);
    }

    private static String precio(InventarioSucursalService.Precio p) {
        return p.presentacion().nombre() + (p.presentacion().granel() ? " (kg)" : "") + ": "
                + Dinero.formatear(p.precioCentavos());
    }

    private void verDetalle(FilaDetallada f) {
        ProductoCatalogo p = f.fila().producto();
        Dialogo d = new Dialogo(p.nombre(), f.sucursal().nombre(), "mdi2p-package-variant-closed", Dialogo.Tono.INFO);
        d.setPrefWidth(780);

        TableView<LoteRepository.Lote> lotes = Ui.tabla("Sin lotes en esta sucursal.");
        lotes.getColumns().setAll(List.of(
                Ui.texto("Origen", 110, LoteRepository.Lote::origen),
                Ui.texto("Cantidad inicial", 120, l -> Cantidades.formatear(p.unidad(), l.cantidadInicial())),
                Ui.texto("Existencia", 110, l -> Cantidades.formatear(p.unidad(), l.existencia())),
                Ui.dinero("Costo unitario", 120, LoteRepository.Lote::costoUnitarioCentavos),
                Ui.texto("Entrada", 110, l -> FECHA.format(l.fechaIngreso().atZone(ZoneId.systemDefault())))));
        lotes.setItems(FXCollections.observableArrayList(
                a.ctx().inventarioSucursal().lotesDe(a.usuario(), p.id(), f.sucursal().id())));
        lotes.setPrefHeight(200);

        TableView<InventarioSucursalService.Movimiento> movimientos = Ui.tabla("Sin movimientos registrados.");
        movimientos.getColumns().setAll(List.of(
                Ui.texto("Tipo", 150, InventarioSucursalService.Movimiento::tipo),
                Ui.texto("Cantidad", 110, m -> Cantidades.formatear(p.unidad(), m.cantidad())),
                Ui.texto("Fecha", 140, m -> FECHA_HORA.format(m.fecha().atZone(ZoneId.systemDefault()))),
                Ui.texto("Notas", 220, m -> m.notas() == null ? "" : m.notas())));
        movimientos.setItems(FXCollections.observableArrayList(
                a.ctx().inventarioSucursal().movimientos(a.usuario(), p.id(), f.sucursal().id(), 30)));
        movimientos.setPrefHeight(240);

        VBox contenido = new VBox(16, Ui.tarjeta("Lotes en esta sucursal", lotes),
                Ui.tarjeta("Últimos movimientos de inventario", movimientos));
        ScrollPane desplazable = new ScrollPane(contenido);
        desplazable.setFitToWidth(true);
        d.setContenido(desplazable);
        d.agregarBoton("Cerrar", null, d::cerrar, "flat");
        d.setAlCancelar(d::cerrar);
        a.dialogos().mostrar(d);
    }
}
