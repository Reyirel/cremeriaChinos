package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Surtido del almacén a una sucursal. Muestra claramente cuánto dinero se envía: costo de la
 * mercancía (lo que sale del almacén, por lotes) y su valor a precio de venta en la sucursal.
 */
final class DialogoSurtido {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    /** Renglón del surtido en pantalla. */
    private static final class Linea {
        final ProductoCatalogo producto;
        final TextField cantidad = new TextField();
        Map<String, Long> precios = new HashMap<>();
        /** Precio promedio de los lotes que ya tiene la sucursal, en lugar de precio fijo. */
        boolean promedio;
        SurtidoService.Costeo costeo;
        final Label existencia = new Label();
        final Label costo = new Label();
        final Label detalleCosto = new Label();
        final Button botonPrecios = new Button();
        final Label venta = new Label();

        Linea(ProductoCatalogo producto) {
            this.producto = producto;
        }

        BigDecimal base() {
            return Cantidades.desdeCaptura(producto.unidad(), cantidad.getText()).orElse(BigDecimal.ZERO);
        }

        boolean preciosCompletos() {
            return producto.presentaciones().stream().filter(Presentacion::activo)
                    .allMatch(p -> precios.getOrDefault(p.id(), 0L) > 0);
        }
    }

    private DialogoSurtido() {
    }

    /**
     * @param sucursalFija  sucursal ya elegida (al aprobar una orden), o null
     * @param ordenId       orden de reabastecimiento que se aprueba, o null
     */
    static void mostrar(AdminContexto a, Sucursal sucursalFija, ProductoCatalogo productoInicial, BigDecimal cantidadInicial,
                        String ordenId, Runnable alTerminar) {
        Dialogo d = new Dialogo(ordenId == null ? "Nuevo surtido" : "Aprobar orden de reabastecimiento",
                "La mercancía sale de los lotes más antiguos del almacén y entra a la sucursal con los precios que fijes.",
                "mdi2t-truck-delivery-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(1340);

        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(a.admin().sucursales().activas()));
        sucursal.setPromptText("Elige la sucursal");
        if (sucursalFija != null) {
            sucursal.getItems().stream().filter(s -> s.id().equals(sucursalFija.id())).findFirst().ifPresent(sucursal::setValue);
            sucursal.setDisable(true);
        }
        ToggleGroup pago = new ToggleGroup();
        ToggleButton efectivo = new ToggleButton("Efectivo", new FontIcon("mdi2c-cash"));
        ToggleButton credito = new ToggleButton("Crédito", new FontIcon("mdi2c-credit-card-clock-outline"));
        efectivo.getStyleClass().add("left-pill");
        credito.getStyleClass().add("right-pill");
        efectivo.setToggleGroup(pago);
        credito.setToggleGroup(pago);
        efectivo.setSelected(true);
        pago.selectedToggleProperty().addListener((o, x, y) -> {
            if (y == null) {
                x.setSelected(true);
            }
        });

        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> selector = SelectorProducto.crear(productos);
        selector.setPrefWidth(420);
        TextField cantidadNueva = new TextField();
        Campos.soloNumeros(cantidadNueva, 0);
        cantidadNueva.setPrefColumnCount(7);
        Label unidadNueva = new Label("pzas");
        Button agregar = Ui.principal("Agregar", "mdi2p-plus");

        List<Linea> lineas = new ArrayList<>();
        GridPane grid = new GridPane(12, 6);
        grid.getStyleClass().add("grid-surtido");
        for (double ancho : new double[]{220, 95, 30, 210, 210, 105, 40}) {
            javafx.scene.layout.ColumnConstraints col = new javafx.scene.layout.ColumnConstraints();
            col.setPrefWidth(ancho);
            col.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            grid.getColumnConstraints().add(col);
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(300);
        scroll.getStyleClass().add("scroll-surtido");
        Label vacio = Ui.texto("Agrega los productos que vas a enviar.", "texto-ayuda");

        // Resumen del dinero
        Label totalCosto = Ui.texto("$0.00", "surtido-total");
        Label totalVenta = Ui.texto("$0.00", "surtido-valor");
        Label numProductos = Ui.texto("0 productos", "surtido-detalle");
        Label saldoActual = Ui.texto("", "surtido-detalle");
        Label saldoNuevo = Ui.texto("", "surtido-saldo");
        VBox bloqueCredito = new VBox(2, Ui.texto("SALDO DE CRÉDITO DE LA SUCURSAL", "surtido-etiqueta"), saldoActual, saldoNuevo);
        bloqueCredito.visibleProperty().bind(credito.selectedProperty());
        bloqueCredito.managedProperty().bind(bloqueCredito.visibleProperty());
        VBox resumen = new VBox(10,
                Ui.texto("TOTAL QUE SE ENVÍA (COSTO)", "surtido-etiqueta"), totalCosto,
                new javafx.scene.control.Separator(),
                Ui.texto("VALOR A PRECIO DE VENTA", "surtido-etiqueta"), totalVenta, numProductos, bloqueCredito);
        resumen.getStyleClass().add("surtido-resumen");
        resumen.setPrefWidth(280);
        resumen.setMinWidth(260);

        Runnable[] recalcular = new Runnable[1];
        Runnable[] redibujar = new Runnable[1];

        recalcular[0] = () -> {
            long costo = 0;
            long venta = 0;
            for (Linea l : lineas) {
                BigDecimal base = l.base();
                l.costeo = a.admin().surtidos().costear(l.producto, base);
                l.costo.setText(Dinero.formatear(l.costeo.costoCentavos()));
                boolean insuficiente = !l.costeo.suficiente();
                l.cantidad.pseudoClassStateChanged(ERROR, insuficiente || base.signum() <= 0);
                l.existencia.setText("Almacén: " + Cantidades.formatear(l.producto.unidad(), l.costeo.disponible()));
                l.existencia.pseudoClassStateChanged(ERROR, insuficiente);
                l.detalleCosto.setText(detalleLotes(l));
                l.botonPrecios.setText(resumenPrecios(l));
                l.botonPrecios.pseudoClassStateChanged(ERROR, !l.preciosCompletos());
                long v = SurtidoService.valorVenta(l.producto, base, l.precios);
                l.venta.setText(Dinero.formatear(v));
                costo += l.costeo.costoCentavos();
                venta += v;
            }
            totalCosto.setText(Dinero.formatear(costo));
            totalVenta.setText(Dinero.formatear(venta));
            numProductos.setText(lineas.size() == 1 ? "1 producto" : lineas.size() + " productos");
            if (sucursal.getValue() != null) {
                long saldo = a.admin().credito().saldos(List.of(sucursal.getValue())).getFirst().saldo();
                saldoActual.setText("Actual: " + Dinero.formatear(saldo));
                saldoNuevo.setText("Después del surtido: " + Dinero.formatear(saldo + costo));
            } else {
                saldoActual.setText("Elige la sucursal");
                saldoNuevo.setText("");
            }
        };

        redibujar[0] = () -> {
            grid.getChildren().clear();
            String[] titulos = {"Producto", "Cantidad", "", "Costo (por lotes)", "Precios de venta en la sucursal",
                    "Valor de venta", ""};
            for (int i = 0; i < titulos.length; i++) {
                Label t = new Label(titulos[i]);
                t.getStyleClass().add("campo-etiqueta");
                grid.add(t, i, 0);
            }
            int r = 1;
            for (Linea l : lineas) {
                Button quitar = Ui.accion("mdi2t-trash-can-outline", "Quitar", () -> {
                    lineas.remove(l);
                    redibujar[0].run();
                }, "danger");
                VBox producto = new VBox(1, Ui.texto(l.producto.nombre(), "celda-principal"), l.existencia);
                l.existencia.getStyleClass().setAll("celda-secundaria", "existencia-almacen");
                VBox costo = new VBox(1, l.costo, l.detalleCosto);
                l.costo.getStyleClass().setAll("celda-principal");
                l.detalleCosto.getStyleClass().setAll("celda-secundaria");
                l.venta.getStyleClass().setAll("celda-principal");
                grid.addRow(r++, producto, l.cantidad, new Label(Cantidades.unidadCaptura(l.producto.unidad())), costo,
                        l.botonPrecios, l.venta, quitar);
            }
            vacio.setVisible(lineas.isEmpty());
            vacio.setManaged(lineas.isEmpty());
            recalcular[0].run();
        };

        java.util.function.BiConsumer<ProductoCatalogo, BigDecimal> agregarLinea = (p, cantidad) -> {
            if (lineas.stream().anyMatch(l -> l.producto.id().equals(p.id()))) {
                a.avisos().advertencia(p.nombre() + " ya está en el surtido; cambia su cantidad.");
                return;
            }
            Linea l = new Linea(p);
            l.cantidad.setText(Cantidades.aCaptura(p.unidad(), cantidad));
            Campos.soloNumeros(l.cantidad, 0);
            l.cantidad.setPrefColumnCount(7);
            l.cantidad.setAlignment(Pos.CENTER_RIGHT);
            l.cantidad.getStyleClass().add("campo-cantidad-surtido");
            l.cantidad.textProperty().addListener((o, x, y) -> recalcular[0].run());
            l.botonPrecios.getStyleClass().addAll("boton-precios");
            l.botonPrecios.setGraphic(new FontIcon("mdi2t-tag-outline"));
            l.botonPrecios.setMaxWidth(320);
            l.botonPrecios.setOnAction(e -> editarPrecios(a, l, sucursal.getValue(), recalcular[0]));
            if (sucursal.getValue() != null) {
                l.precios = new HashMap<>(a.admin().surtidos().preciosSugeridos(sucursal.getValue().id(), p.id()));
            }
            lineas.add(l);
            redibujar[0].run();
        };

        Runnable accionAgregar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(selector);
            if (p == null) {
                a.avisos().error("Elige un producto.");
                return;
            }
            BigDecimal c = Cantidades.desdeCaptura(p.unidad(), cantidadNueva.getText()).orElse(BigDecimal.ZERO);
            if (c.signum() <= 0) {
                a.avisos().error("Escribe la cantidad (" + Cantidades.unidadCaptura(p.unidad()) + ").");
                cantidadNueva.requestFocus();
                return;
            }
            agregarLinea.accept(p, c);
            selector.getEditor().clear();
            selector.setValue(null);
            cantidadNueva.clear();
            selector.requestFocus();
        };
        agregar.setOnAction(e -> accionAgregar.run());
        cantidadNueva.setOnAction(e -> accionAgregar.run());
        selector.valueProperty().addListener((o, x, p) -> {
            unidadNueva.setText(p == null ? ""
                    : Cantidades.unidadCaptura(p.unidad()) + (p.unidad() == Unidad.KG ? " (1 kg = 1000 g)" : ""));
            if (p != null) {
                javafx.application.Platform.runLater(cantidadNueva::requestFocus);
            }
        });
        sucursal.valueProperty().addListener((o, x, s) -> {
            javafx.application.Platform.runLater(selector::requestFocus);
            for (Linea l : lineas) {
                l.precios = new HashMap<>(a.admin().surtidos().preciosSugeridos(s.id(), l.producto.id()));
                if (l.promedio) {
                    SurtidoService.PreciosPromedio promedio = a.admin().surtidos().preciosPromedio(s.id(), l.producto.id());
                    l.promedio = promedio.lotes() > 0;
                    l.precios.putAll(promedio.precios());
                }
            }
            recalcular[0].run();
        });
        pago.selectedToggleProperty().addListener((o, x, y) -> recalcular[0].run());
        TextField notas = new TextField();
        notas.setPromptText("Notas (opcional)");

        HBox cabecera = new HBox(14, Ui.campo("Sucursal que recibe", sucursal),
                Ui.campo("Forma de pago", new HBox(efectivo, credito)));
        HBox captura = new HBox(10, selector, cantidadNueva, unidadNueva, agregar);
        captura.setAlignment(Pos.CENTER_LEFT);
        captura.getStyleClass().add("surtido-captura");
        VBox izquierda = new VBox(12, cabecera, captura, vacio, scroll, notas);
        HBox.setHgrow(izquierda, Priority.ALWAYS);
        d.setContenido(new HBox(20, izquierda, resumen));

        if (productoInicial != null) {
            agregarLinea.accept(productoInicial, cantidadInicial);
        } else {
            redibujar[0].run();
        }

        Runnable surtir = () -> {
            if (sucursal.getValue() == null) {
                a.avisos().error("Elige la sucursal que recibe.");
                return;
            }
            List<SurtidoService.Linea> datos = lineas.stream()
                    .map(l -> new SurtidoService.Linea(l.producto, l.base(), l.precios)).toList();
            SurtidoService.FormaPago forma = credito.isSelected() ? SurtidoService.FormaPago.CREDITO
                    : SurtidoService.FormaPago.EFECTIVO;
            try {
                SurtidoService.Detalle detalle = a.admin().surtidos().registrar(sucursal.getValue(), forma, datos,
                        notas.getText(), a.usuario(), ordenId);
                d.cerrar();
                a.avisos().exito("Surtido registrado · Folio " + detalle.resumen().folio());
                a.refrescarContadores().run();
                a.ctx().sincronizador().revisarAhora();
                alTerminar.run();
                DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Surtido " + detalle.resumen().folio(),
                        detalle.resumen().sucursal() + " · " + Dinero.formatear(detalle.resumen().costoCentavos()),
                        TicketTexto.surtido(detalle), null);
            } catch (RuntimeException e) {
                a.avisos().error(AdminContexto.mensaje(e));
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.separarBotones();
        d.agregarBoton(ordenId == null ? "Surtir" : "Aprobar y surtir", "mdi2t-truck-delivery-outline", surtir,
                "accent", "large");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(sucursalFija == null ? sucursal : selector);
        a.dialogos().mostrar(d);
    }

    private static String detalleLotes(Linea l) {
        if (l.costeo == null || l.costeo.salidas().isEmpty()) {
            return l.costeo != null && !l.costeo.suficiente() ? "Sin existencia suficiente" : "";
        }
        String sufijo = l.producto.unidad() == Unidad.KG ? "/kg" : "/pza";
        String texto = l.costeo.salidas().stream()
                .map(s -> Cantidades.formatear(l.producto.unidad(), s.cantidad()) + " a "
                        + Dinero.formatear(s.lote().costoUnitarioCentavos()) + sufijo)
                .collect(Collectors.joining(" + "));
        return (l.costeo.suficiente() ? "" : "Falta existencia · ") + texto;
    }

    private static String resumenPrecios(Linea l) {
        List<String> partes = new ArrayList<>();
        for (Presentacion p : l.producto.presentaciones()) {
            if (!p.activo()) {
                continue;
            }
            Long precio = l.precios.get(p.id());
            partes.add(p.nombre() + " " + (precio == null || precio <= 0 ? "—" : Dinero.formatear(precio)));
        }
        return (l.promedio ? "Promedio: " : "") + String.join(" · ", partes);
    }

    /** Precio de venta de cada presentación para este lote en la sucursal. */
    private static void editarPrecios(AdminContexto a, Linea l, Sucursal sucursal, Runnable alGuardar) {
        Dialogo d = new Dialogo("Precios de " + l.producto.nombre(),
                (sucursal == null ? "Precios de venta" : "Precio de venta en " + sucursal.nombre())
                        + ". Se aplican cuando se termine el lote que tiene ahora.",
                "mdi2t-tag-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(760);
        Map<String, Long> sugeridos = sucursal == null ? Map.of()
                : a.admin().surtidos().preciosSugeridos(sucursal.id(), l.producto.id());
        SurtidoService.PreciosPromedio promedio = sucursal == null ? null
                : a.admin().surtidos().preciosPromedio(sucursal.id(), l.producto.id());
        boolean hayPromedio = promedio != null && promedio.lotes() > 0;

        // Precio fijo (se captura) o precio promedio de los lotes que ya tiene la sucursal
        ToggleGroup modo = new ToggleGroup();
        ToggleButton fijo = new ToggleButton("Precio fijo", new FontIcon("mdi2p-pencil-outline"));
        ToggleButton promediado = new ToggleButton("Precio promedio", new FontIcon("mdi2s-scale-balance"));
        fijo.getStyleClass().add("left-pill");
        promediado.getStyleClass().add("right-pill");
        fijo.setToggleGroup(modo);
        promediado.setToggleGroup(modo);
        promediado.setDisable(!hayPromedio);
        (l.promedio && hayPromedio ? promediado : fijo).setSelected(true);
        Label ayudaModo = Ui.texto("", "texto-ayuda");
        long costoBase = l.costeo == null || l.base().signum() == 0 ? 0
                : BigDecimal.valueOf(l.costeo.costoCentavos()).divide(l.base(), 0, RoundingMode.HALF_UP).longValue();
        GridPane grid = new GridPane(12, 10);
        String[] titulos = {"Presentación", "Contiene", "Costo aprox.", "Precio anterior", "Promedio", "Precio de venta"};
        for (int i = 0; i < titulos.length; i++) {
            Label t = new Label(titulos[i]);
            t.getStyleClass().add("campo-etiqueta");
            t.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            grid.add(t, i, 0);
        }
        Map<String, CampoDinero> campos = new HashMap<>();
        int r = 1;
        CampoDinero primero = null;
        for (Presentacion p : l.producto.presentaciones()) {
            if (!p.activo()) {
                continue;
            }
            CampoDinero campo = new CampoDinero();
            campo.getStyleClass().remove("campo-dinero");
            campo.setPrefColumnCount(8);
            Long actual = l.precios.get(p.id());
            if (actual != null && actual > 0) {
                campo.setCentavos(actual);
            }
            campos.put(p.id(), campo);
            if (primero == null) {
                primero = campo;
            }
            long costoPresentacion = Dinero.importe(costoBase, p.factor());
            Long precioPromedio = hayPromedio ? promedio.precios().get(p.id()) : null;
            Label[] celdas = {new Label(p.nombre() + (p.principal() ? " (principal)" : "")),
                    new Label(p.granel() ? "A granel (por kg)" : Cantidades.formatear(l.producto.unidad(), p.factor())),
                    new Label(costoBase == 0 ? "—" : Dinero.formatear(costoPresentacion)),
                    new Label(sugeridos.containsKey(p.id()) ? Dinero.formatear(sugeridos.get(p.id())) : "Nuevo"),
                    new Label(precioPromedio == null ? "—" : Dinero.formatear(precioPromedio))};
            for (Label c : celdas) {
                c.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            }
            grid.addRow(r++, celdas[0], celdas[1], celdas[2], celdas[3], celdas[4], campo);
        }

        // Lo que se había escrito a mano, para regresarlo al volver a precio fijo.
        Map<String, Long> manuales = new HashMap<>(l.promedio ? sugeridos : l.precios);
        Runnable aplicarModo = () -> {
            boolean conPromedio = promediado.isSelected();
            boolean faltan = false;
            for (Map.Entry<String, CampoDinero> e : campos.entrySet()) {
                Long precioPromedio = hayPromedio ? promedio.precios().get(e.getKey()) : null;
                CampoDinero campo = e.getValue();
                if (conPromedio && precioPromedio != null) {
                    campo.setCentavos(precioPromedio);
                    campo.setDisable(true);
                } else {
                    if (!conPromedio && precioPromedio != null) {
                        Long manual = manuales.get(e.getKey());
                        if (manual != null && manual > 0) {
                            campo.setCentavos(manual);
                        } else {
                            campo.clear();
                        }
                    }
                    campo.setDisable(false);
                    faltan |= conPromedio;
                }
            }
            if (sucursal == null) {
                ayudaModo.setText("Elige la sucursal para poder usar el precio promedio.");
            } else if (!hayPromedio) {
                ayudaModo.setText(sucursal.nombre() + " aún no tiene lotes de este producto: captura el precio.");
            } else if (conPromedio) {
                ayudaModo.setText((promedio.conExistencia()
                        ? "Promedio de " + lotes(promedio.lotes()) + " con existencia en " + sucursal.nombre() + "."
                        : "Ningún lote tiene existencia: promedio de " + lotes(promedio.lotes()) + " anteriores en "
                        + sucursal.nombre() + ".")
                        + (faltan ? " Las presentaciones sin lotes anteriores llevan el precio que captures." : ""));
            } else {
                ayudaModo.setText("Escribe el precio de cada presentación. El costo aproximado sale del lote del "
                        + "almacén que se va a enviar.");
            }
        };
        modo.selectedToggleProperty().addListener((o, antes, ahora) -> {
            if (ahora == null) {
                antes.setSelected(true);
                return;
            }
            if (ahora == promediado) {
                // Guarda lo escrito a mano antes de reemplazarlo por el promedio.
                campos.forEach((id, campo) -> manuales.put(id, campo.centavosOCero()));
            }
            aplicarModo.run();
        });
        aplicarModo.run();
        HBox selectorModo = new HBox(fijo, promediado);
        d.setContenido(Ui.campo("Cómo se fija el precio de este lote", selectorModo), grid, ayudaModo);
        Runnable guardar = () -> {
            Map<String, Long> nuevos = new HashMap<>();
            for (Map.Entry<String, CampoDinero> e : campos.entrySet()) {
                long v = e.getValue().centavosOCero();
                if (v <= 0) {
                    a.avisos().error("Escribe el precio de todas las presentaciones.");
                    return;
                }
                nuevos.put(e.getKey(), v);
            }
            l.precios = nuevos;
            l.promedio = promediado.isSelected();
            d.cerrar();
            alGuardar.run();
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Aplicar precios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(primero);
        a.dialogos().mostrar(d);
    }

    private static String lotes(int n) {
        return n == 1 ? "1 lote" : n + " lotes";
    }
}
