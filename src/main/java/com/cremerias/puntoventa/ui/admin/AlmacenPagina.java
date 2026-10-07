package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.admin.AlmacenService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.CampoMasa;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Inventario del almacén central por lotes: reabastecer, ver lotes y registrar merma. */
public class AlmacenPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TableView<AlmacenService.Existencia> tabla;
    private HBox indicadores;
    private TextField buscador;
    private List<AlmacenService.Existencia> todos = List.of();

    public AlmacenPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        var reabastecer = Ui.principal("Reabastecer", "mdi2t-tray-arrow-down");
        reabastecer.setOnAction(e -> reabastecer(null));
        VBox pagina = Ui.pagina("Inventario del almacén",
                "Cada entrada crea un lote con su costo. Al surtir, sale primero el lote más antiguo.", reabastecer);
        indicadores = new HBox(14);
        buscador = Ui.buscador("Buscar producto");
        buscador.textProperty().addListener((o, x, y) -> filtrar());
        tabla = Ui.tabla("No hay productos en el catálogo.");
        tabla.getColumns().setAll(List.of(
                Ui.nodo("Producto", 280, e -> Ui.dosLineas(e.producto().nombre(),
                        e.producto().categoria() == null ? "" : e.producto().categoria())),
                Ui.nodo("Existencia", 150, e -> {
                    Label l = new Label(Cantidades.formatear(e.producto().unidad(), e.existencia()));
                    l.getStyleClass().add(e.existencia().signum() > 0 ? "texto-negrita" : "texto-apagado");
                    return l;
                }),
                Ui.texto("Lotes", 70, e -> String.valueOf(e.lotesConExistencia())),
                Ui.texto("Costo del lote actual", 170, e -> e.lotesConExistencia() == 0 ? "—"
                        : Dinero.formatear(e.costoActualCentavos()) + (e.producto().unidad() == Unidad.KG ? " / kg" : " / pza")),
                Ui.dinero("Valor en almacén", 150, AlmacenService.Existencia::valorCentavos),
                Ui.nodo("", 150, e -> {
                    HBox acciones = Ui.acciones(
                            Ui.accion("mdi2t-tray-arrow-down", "Reabastecer", () -> reabastecer(e.producto())),
                            Ui.accion("mdi2l-layers-outline", "Ver lotes", () -> lotes(e.producto())));
                    if (e.producto().sujetoMerma()) {
                        acciones.getChildren().add(Ui.accion("mdi2d-delete-variant", "Registrar merma",
                                () -> merma(e.producto()), "danger"));
                    }
                    return acciones;
                })));
        pagina.getChildren().addAll(indicadores, new HBox(10, buscador), tabla);
        return pagina;
    }

    @Override
    public void alMostrar() {
        todos = a.admin().almacen().existencias();
        long valor = todos.stream().mapToLong(AlmacenService.Existencia::valorCentavos).sum();
        long conExistencia = todos.stream().filter(e -> e.existencia().signum() > 0).count();
        long agotados = todos.stream().filter(e -> e.producto().activo() && e.existencia().signum() <= 0).count();
        indicadores.getChildren().setAll(
                Ui.indicador("Valor del inventario (al costo)", Dinero.formatear(valor), "mdi2c-cash-multiple"),
                Ui.indicador("Productos con existencia", String.valueOf(conExistencia), "mdi2p-package-variant"),
                Ui.indicador("Productos agotados", String.valueOf(agotados), "mdi2a-alert-outline"));
        filtrar();
    }

    private void filtrar() {
        String t = buscador.getText() == null ? "" : buscador.getText().strip().toLowerCase(Locale.ROOT);
        tabla.setItems(FXCollections.observableArrayList(todos.stream()
                .filter(e -> e.producto().activo() || e.existencia().signum() > 0)
                .filter(e -> t.isEmpty() || e.producto().nombre().toLowerCase(Locale.ROOT).contains(t))
                .toList()));
    }

    private void reabastecer(ProductoCatalogo fijo) {
        Dialogo d = new Dialogo("Reabastecer almacén", "La mercancía entra como un lote nuevo con su propio costo.",
                "mdi2t-tray-arrow-down", Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(productos);

        // Si el producto tiene varias presentaciones (pieza suelta, caja de 12...) se elige en
        // cuál se está capturando, para no tener que calcular a mano las piezas y el costo unitario.
        ComboBox<Presentacion> presentacion = new ComboBox<>();
        presentacion.setConverter(new StringConverter<>() {
            @Override
            public String toString(Presentacion p) {
                return p == null ? "" : p.nombre() + " (" + Cantidades.formatear(Unidad.PZA, p.factor()) + ")";
            }

            @Override
            public Presentacion fromString(String texto) {
                return presentacion.getValue();
            }
        });
        VBox campoPresentacion = Ui.campo("Presentación", presentacion);
        campoPresentacion.managedProperty().bind(campoPresentacion.visibleProperty());

        TextField cantidadPiezas = new TextField();
        Campos.soloNumeros(cantidadPiezas, 0);
        cantidadPiezas.getStyleClass().add("campo-dinero");
        CampoMasa cantidadGranel = new CampoMasa();
        cantidadGranel.selectorUnidad().setValue(Masa.KG);
        Label etiquetaCantidad = new Label("Cantidad en piezas");
        etiquetaCantidad.getStyleClass().add("campo-etiqueta");
        VBox campoPiezas = new VBox(6, etiquetaCantidad, cantidadPiezas);
        VBox campoGranel = Ui.campo("Cantidad (kg, toneladas...)", cantidadGranel);
        campoPiezas.managedProperty().bind(campoPiezas.visibleProperty());
        campoGranel.managedProperty().bind(campoGranel.visibleProperty());
        CampoDinero costo = new CampoDinero();
        Label unidadCosto = Ui.texto("", "texto-ayuda");
        Label equivalencia = Ui.texto("", "texto-ayuda");
        equivalencia.visibleProperty().bind(equivalencia.textProperty().isNotEmpty());
        equivalencia.managedProperty().bind(equivalencia.visibleProperty());
        TextField notas = new TextField();
        notas.setPromptText("Proveedor, factura... (opcional)");
        Label total = Ui.texto("$0.00", "total-grande");

        // Cantidad y costo tal como se escriben en pantalla (en piezas, en la presentación elegida,
        // o en kg si es a granel). «cantidadBase»/«costoBase» más abajo los llevan a la unidad base.
        Supplier<BigDecimal> cantidadCapturada = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            Unidad u = p == null ? Unidad.PZA : p.unidad();
            return u == Unidad.KG
                    ? cantidadGranel.gramos().map(g -> g.movePointLeft(3).setScale(3, RoundingMode.HALF_UP)).orElse(BigDecimal.ZERO)
                    : Cantidades.desdeCaptura(u, cantidadPiezas.getText()).orElse(BigDecimal.ZERO);
        };
        Supplier<Presentacion> presentacionElegida = () -> campoPresentacion.isVisible() ? presentacion.getValue() : null;
        Supplier<BigDecimal> factorElegido = () -> {
            Presentacion pr = presentacionElegida.get();
            return pr == null || pr.factor() == null || pr.factor().signum() <= 0 ? BigDecimal.ONE : pr.factor();
        };
        Supplier<BigDecimal> cantidadBase = () -> cantidadCapturada.get().multiply(factorElegido.get())
                .setScale(0, RoundingMode.HALF_UP);
        Supplier<Long> costoBase = () -> {
            long capturado = costo.centavosOCero();
            BigDecimal factor = factorElegido.get();
            return factor.compareTo(BigDecimal.ONE) == 0 ? capturado
                    : BigDecimal.valueOf(capturado).divide(factor, 0, RoundingMode.HALF_UP).longValueExact();
        };

        Runnable actualizar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            Unidad u = p == null ? Unidad.PZA : p.unidad();
            List<Presentacion> activas = p == null ? List.of()
                    : p.presentaciones().stream().filter(Presentacion::activo).toList();
            boolean variasPresentaciones = u != Unidad.KG && activas.size() > 1;
            campoPresentacion.setVisible(variasPresentaciones);
            if (variasPresentaciones && !activas.equals(presentacion.getItems())) {
                presentacion.setItems(FXCollections.observableArrayList(activas));
                Presentacion principal = p.principal();
                presentacion.setValue(activas.contains(principal) ? principal : activas.getFirst());
            }
            campoPiezas.setVisible(u != Unidad.KG);
            campoGranel.setVisible(u == Unidad.KG);
            Presentacion elegida = presentacionElegida.get();
            etiquetaCantidad.setText(u == Unidad.KG ? "" : elegida == null ? "Cantidad en piezas"
                    : "Cantidad de " + elegida.nombre());
            unidadCosto.setText(u == Unidad.KG ? "Costo por kilo"
                    : elegida == null ? "Costo por pieza" : "Costo por " + elegida.nombre().toLowerCase(Locale.ROOT));
            BigDecimal factor = factorElegido.get();
            if (u != Unidad.KG && factor.compareTo(BigDecimal.ONE) != 0) {
                long costoPorPieza = costo.centavosOCero() > 0 ? costoBase.get() : 0;
                equivalencia.setText("= " + Cantidades.formatear(Unidad.PZA, cantidadBase.get())
                        + (costoPorPieza > 0 ? " · " + Dinero.formatear(costoPorPieza) + " c/u" : ""));
            } else {
                equivalencia.setText("");
            }
            total.setText(Dinero.formatear(Dinero.importe(costo.centavosOCero(), cantidadCapturada.get())));
        };
        producto.valueProperty().addListener((o, x, y) -> actualizar.run());
        presentacion.valueProperty().addListener((o, x, y) -> actualizar.run());
        cantidadPiezas.textProperty().addListener((o, x, y) -> actualizar.run());
        cantidadGranel.campo().textProperty().addListener((o, x, y) -> actualizar.run());
        cantidadGranel.selectorUnidad().valueProperty().addListener((o, x, y) -> actualizar.run());
        costo.textProperty().addListener((o, x, y) -> actualizar.run());
        if (fijo != null) {
            producto.setValue(fijo);
            producto.setDisable(true);
        }
        actualizar.run();
        VBox totalCaja = new VBox(2, Ui.texto("TOTAL DE LA ENTRADA", "total-grande-etiqueta"), total);
        totalCaja.getStyleClass().add("caja-total-plana");
        d.setContenido(Ui.campo("Producto", producto), campoPresentacion,
                Ui.fila(new VBox(6, campoPiezas, campoGranel), new VBox(6, unidadCosto, costo)),
                equivalencia, Ui.campo("Notas", notas), totalCaja);
        Runnable guardar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            if (p == null) {
                a.avisos().error("Elige el producto.");
                return;
            }
            BigDecimal c = cantidadBase.get();
            long costoUnitario = costoBase.get();
            if (a.ejecutar("Entrada registrada", () -> a.admin().almacen().reabastecer(p, c, costoUnitario,
                    notas.getText(), a.usuario()))) {
                d.cerrar();
                alMostrar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Registrar entrada", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(fijo == null ? producto : cantidadPiezas);
        a.dialogos().mostrar(d);
    }

    private void lotes(ProductoCatalogo producto) {
        Dialogo d = new Dialogo("Lotes de " + producto.nombre(),
                "Se venden y surten en orden: primero el más antiguo con existencia.", "mdi2l-layers-outline",
                Dialogo.Tono.INFO);
        d.setPrefWidth(900);
        ComboBox<Sucursal> ubicacion = new ComboBox<>(FXCollections.observableArrayList(a.admin().sucursales().ubicaciones()));
        TableView<LoteRepository.Lote> tabla = Ui.tabla("No hay lotes en esta ubicación.");
        tabla.setPrefHeight(360);
        tabla.getColumns().setAll(List.of(
                Ui.texto("Folio", 160, l -> l.folio() == null ? origen(l.origen()) : l.folio()),
                Ui.texto("Ingreso", 140, l -> FECHA.format(l.fechaIngreso().atZone(ZoneId.systemDefault()))),
                Ui.texto("Origen", 110, l -> origen(l.origen())),
                Ui.texto("Cantidad inicial", 130, l -> Cantidades.formatear(producto.unidad(), l.cantidadInicial())),
                Ui.nodo("Existencia", 120, l -> l.existencia().signum() > 0
                        ? Ui.chip(Cantidades.formatear(producto.unidad(), l.existencia()), "exito")
                        : Ui.chip("Agotado", "neutro")),
                Ui.texto("Costo", 120, l -> Dinero.formatear(l.costoUnitarioCentavos())
                        + (producto.unidad() == Unidad.KG ? " / kg" : " / pza")),
                Ui.id(LoteRepository.Lote::id)));
        ubicacion.valueProperty().addListener((o, x, u) -> tabla.setItems(FXCollections.observableArrayList(
                a.admin().almacen().lotes(producto.id(), u.id()))));
        ubicacion.setValue(ubicacion.getItems().getFirst());
        d.setContenido(Ui.campo("Ubicación", ubicacion), tabla);
        d.agregarBoton("Cerrar", null, d::cerrar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(d::cerrar);
        a.dialogos().mostrar(d);
    }

    private static String origen(String origen) {
        return switch (origen) {
            case "ENTRADA" -> "Reabastecimiento";
            case "SURTIDO" -> "Surtido";
            case "INICIAL" -> "Inventario inicial";
            default -> "Migración";
        };
    }

    private void merma(ProductoCatalogo producto) {
        Dialogo d = new Dialogo("Registrar merma", producto.nombre() + " · se descuenta del lote más antiguo",
                "mdi2d-delete-variant", Dialogo.Tono.PELIGRO);
        d.setPrefWidth(520);
        ComboBox<Sucursal> ubicacion = new ComboBox<>(FXCollections.observableArrayList(a.admin().sucursales().ubicaciones()));
        ubicacion.setValue(ubicacion.getItems().getFirst());
        Label existencia = Ui.texto("", "texto-ayuda");
        TextField cantidad = new TextField();
        Campos.soloNumeros(cantidad, 0);
        TextField motivo = new TextField();
        motivo.setPromptText("Ej. Se echó a perder, se cayó, caducó");
        Runnable actualizar = () -> existencia.setText("Existencia en " + ubicacion.getValue().nombre() + ": "
                + Cantidades.formatear(producto.unidad(), a.admin().almacen().existencia(producto.id(), ubicacion.getValue().id())));
        ubicacion.valueProperty().addListener((o, x, y) -> actualizar.run());
        actualizar.run();
        d.setContenido(Ui.campo("Ubicación", ubicacion), existencia,
                Ui.campo("Cantidad (" + Cantidades.unidadCaptura(producto.unidad()) + ")", cantidad),
                Ui.campo("Motivo", motivo));
        Runnable guardar = () -> {
            BigDecimal c = Cantidades.desdeCaptura(producto.unidad(), cantidad.getText()).orElse(BigDecimal.ZERO);
            if (a.ejecutar("Merma registrada", () -> a.admin().almacen().registrarMerma(producto, ubicacion.getValue(), c,
                    motivo.getText(), a.usuario()))) {
                d.cerrar();
                alMostrar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Registrar merma", "mdi2c-check", guardar, "danger");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(cantidad);
        a.dialogos().mostrar(d);
    }
}
