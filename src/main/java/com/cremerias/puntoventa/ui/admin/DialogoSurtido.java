package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Surtido del almacén a una sucursal. Muestra claramente cuánto dinero se envía: costo de la
 * mercancía (lo que sale del almacén, por lotes), lo que se le carga a la sucursal (precio interno,
 * con el margen del canal) y su valor a precio de venta en la sucursal.
 */
final class DialogoSurtido {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private enum Pago { EFECTIVO, CREDITO }

    /** Renglón del surtido en pantalla. */
    private static final class Linea {
        final ProductoCatalogo producto;
        final TextField cantidad = new TextField();
        Map<String, Long> precios = new HashMap<>();
        /** Precio promedio de los lotes que ya tiene la sucursal, en lugar de precio fijo. */
        boolean promedio;
        SurtidoService.Costeo costeo;
        /** Lo que se le carga a la sucursal por esta línea (precio interno); nulo hasta calcularlo. */
        Long interno;
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

    /** Costeo por lotes, precio interno de cada línea y saldo de crédito (fuera del hilo de la interfaz). */
    private record Calculo(Map<Linea, SurtidoService.Costeo> costeos, Map<Linea, Long> internos, Long saldo) {
    }

    private DialogoSurtido() {
    }

    /**
     * @param sucursalFija  sucursal ya elegida (al aprobar una orden), o null
     * @param ordenId       orden de reabastecimiento que se aprueba, o null
     */
    static void mostrar(AdminContexto a, Sucursal sucursalFija, ProductoCatalogo productoInicial, BigDecimal cantidadInicial,
                        String ordenId, Runnable alTerminar) {
        boolean aprobando = ordenId != null;
        Dialogo d = new Dialogo(aprobando ? "Aprobar orden de reabastecimiento" : "Nuevo surtido",
                "La mercancía sale de los lotes más antiguos del almacén y entra a la sucursal con los precios que fijes.",
                "mdi2t-truck-delivery-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(1180);
        d.sceneProperty().addListener((o, x, escena) -> {
            if (escena != null) {
                d.prefWidthProperty().bind(Bindings.min(1180, escena.widthProperty().subtract(72)));
            }
        });

        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(a.admin().sucursales().activas()));
        sucursal.setConverter(Combos.sucursal());
        sucursal.setPromptText("Elige la sucursal");
        if (sucursalFija != null) {
            sucursal.getItems().stream().filter(s -> s.id().equals(sucursalFija.id())).findFirst().ifPresent(sucursal::setValue);
            sucursal.setDisable(true);
        }
        Formulario.Campo campoSucursal = Formulario.campo("Sucursal que recibe", sucursal);
        Segmentado<Pago> pago = new Segmentado<Pago>()
                .opcion(Pago.EFECTIVO, "Efectivo", "mdi2c-cash")
                .opcion(Pago.CREDITO, "Crédito", "mdi2c-credit-card-clock-outline");
        Formulario.Campo campoPago = Formulario.campo("Forma de pago", pago);
        campoPago.setMaxWidth(Region.USE_PREF_SIZE);

        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> selector = SelectorProducto.crear(productos);
        selector.setPrefWidth(380);
        selector.setId("surtido-producto");

        // Si el producto tiene varias presentaciones (pieza suelta, caja de 12...) se elige en
        // cuál se está capturando la cantidad, para no tener que convertir a piezas a mano.
        ComboBox<Presentacion> presentacionNueva = new ComboBox<>();
        presentacionNueva.setConverter(new StringConverter<>() {
            @Override
            public String toString(Presentacion p) {
                return p == null ? "" : p.nombre() + " (" + Cantidades.formatear(Unidad.PZA, p.factor()) + ")";
            }

            @Override
            public Presentacion fromString(String texto) {
                return presentacionNueva.getValue();
            }
        });
        presentacionNueva.setVisible(false);
        presentacionNueva.setManaged(false);

        TextField cantidadNueva = new TextField();
        cantidadNueva.setId("surtido-cantidad");
        Campos.soloNumeros(cantidadNueva, 0);
        cantidadNueva.setPrefColumnCount(7);
        cantidadNueva.setAlignment(Pos.CENTER_RIGHT);
        cantidadNueva.setPromptText("Cantidad");
        Label unidadNueva = new Label("pzas");
        unidadNueva.getStyleClass().add("unidad-campo");
        Button agregar = Ui.boton("Agregar", "mdi2p-plus", "accent");
        Label errorCaptura = new Label();
        errorCaptura.getStyleClass().add("campo-error");
        errorCaptura.setGraphic(new FontIcon("mdi2a-alert-circle-outline"));
        errorCaptura.managedProperty().bind(errorCaptura.visibleProperty());
        errorCaptura.setVisible(false);

        List<Linea> lineas = new ArrayList<>();
        GridPane grid = new GridPane(12, 8);
        grid.getStyleClass().add("grid-surtido");
        for (double ancho : new double[]{230, 120, 190, 210, 110, 36}) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPrefWidth(ancho);
            col.setMinWidth(Region.USE_PREF_SIZE);
            grid.getColumnConstraints().add(col);
        }
        grid.getColumnConstraints().getFirst().setHgrow(Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(260);
        scroll.getStyleClass().add("scroll-surtido");
        Label vacio = Ui.texto("Agrega los productos que vas a enviar: elige el producto, escribe la cantidad y presiona "
                + "«Agregar» o Enter.", "campo-ayuda");

        // Resumen del dinero
        Label totalCosto = Ui.texto("$0.00", "surtido-total");
        Label totalInterno = Ui.texto("", "surtido-detalle");
        Label totalVenta = Ui.texto("$0.00", "surtido-valor");
        Label numProductos = Ui.texto("Sin productos", "surtido-detalle");
        Label saldoActual = Ui.texto("", "surtido-detalle");
        Label saldoNuevo = Ui.texto("", "surtido-saldo");
        VBox bloqueCredito = new VBox(2, Ui.texto("Saldo de crédito de la sucursal", "surtido-etiqueta"), saldoActual, saldoNuevo);
        bloqueCredito.visibleProperty().bind(pago.valorProperty().isEqualTo(Pago.CREDITO));
        bloqueCredito.managedProperty().bind(bloqueCredito.visibleProperty());
        VBox resumen = new VBox(8,
                Ui.texto("Se envía (al costo)", "surtido-etiqueta"), totalCosto, totalInterno,
                new Separator(),
                Ui.texto("Valor a precio de venta", "surtido-etiqueta"), totalVenta, numProductos, bloqueCredito);
        resumen.getStyleClass().add("surtido-resumen");
        resumen.setPrefWidth(250);
        resumen.setMinWidth(230);

        Cargador calculos = new Cargador();
        Runnable[] recalcular = new Runnable[1];
        Runnable[] redibujar = new Runnable[1];

        // Lo que no necesita la base se actualiza al instante; el costeo por lotes y el saldo llegan después.
        recalcular[0] = () -> {
            long venta = 0;
            for (Linea l : lineas) {
                BigDecimal base = l.base();
                l.cantidad.pseudoClassStateChanged(ERROR, base.signum() <= 0
                        || (l.costeo != null && !l.costeo.suficiente()));
                l.botonPrecios.setText(resumenPrecios(l));
                l.botonPrecios.pseudoClassStateChanged(ERROR, !l.preciosCompletos());
                long v = SurtidoService.valorVenta(l.producto, base, l.precios);
                l.venta.setText(Dinero.formatear(v));
                venta += v;
            }
            totalVenta.setText(Dinero.formatear(venta));
            numProductos.setText(lineas.isEmpty() ? "Sin productos" : lineas.size() == 1 ? "1 producto" : lineas.size() + " productos");
            List<Linea> copia = List.copyOf(lineas);
            Map<Linea, BigDecimal> cantidades = new HashMap<>();
            copia.forEach(l -> cantidades.put(l, l.base()));
            Sucursal s = sucursal.getValue();
            calculos.cargar(() -> {
                Map<Linea, SurtidoService.Costeo> costeos = new HashMap<>();
                Map<Linea, Long> internos = new HashMap<>();
                for (Linea l : copia) {
                    SurtidoService.Costeo costeo = a.admin().surtidos().costear(l.producto, cantidades.get(l));
                    costeos.put(l, costeo);
                    if (s != null) {
                        internos.put(l, a.admin().surtidos().precioInterno(s, l.producto, costeo.costoCentavos())
                                .importeCentavos());
                    }
                }
                Long saldo = s == null ? null : a.admin().credito().saldos(List.of(s)).getFirst().saldo();
                return new Calculo(costeos, internos, saldo);
            }, c -> {
                long costo = 0;
                long interno = 0;
                for (Linea l : copia) {
                    l.costeo = c.costeos().get(l);
                    if (l.costeo == null) {
                        continue;
                    }
                    l.costo.setText(Dinero.formatear(l.costeo.costoCentavos()));
                    boolean insuficiente = !l.costeo.suficiente();
                    l.cantidad.pseudoClassStateChanged(ERROR, insuficiente || l.base().signum() <= 0);
                    l.existencia.setText("En almacén: " + Cantidades.formatear(l.producto.unidad(), l.costeo.disponible()));
                    l.existencia.pseudoClassStateChanged(ERROR, insuficiente);
                    l.interno = c.internos().get(l);
                    l.detalleCosto.setText(detalleLotes(l) + (l.interno == null || l.interno == l.costeo.costoCentavos()
                            ? "" : " · Cargo " + Dinero.formatear(l.interno)));
                    costo += l.costeo.costoCentavos();
                    interno += l.interno == null ? l.costeo.costoCentavos() : l.interno;
                }
                totalCosto.setText(Dinero.formatear(costo));
                totalInterno.setText(s == null ? "" : interno == costo ? "Se le carga a la sucursal al costo"
                        : "Se le carga a la sucursal " + Dinero.formatear(interno) + " (precio interno)");
                if (c.saldo() != null) {
                    saldoActual.setText("Hoy: " + Dinero.formatear(c.saldo()));
                    // El crédito se carga a precio interno (costo más el margen del canal), no al costo.
                    saldoNuevo.setText("Después del surtido: " + Dinero.formatear(c.saldo() + interno));
                } else {
                    saldoActual.setText("Elige la sucursal");
                    saldoNuevo.setText("");
                }
            }, e -> totalCosto.setText("—"));
        };

        redibujar[0] = () -> {
            grid.getChildren().clear();
            String[] titulos = {"Producto", "Cantidad", "Costo (por lotes)", "Precios de venta en la sucursal",
                    "Valor de venta", ""};
            for (int i = 0; i < titulos.length; i++) {
                Label t = new Label(titulos[i]);
                t.getStyleClass().add("grid-surtido-encabezado");
                grid.add(t, i, 0);
            }
            int r = 1;
            for (Linea l : lineas) {
                Button quitar = Ui.boton(null, "mdi2t-trash-can-outline", "flat", "boton-icono", "danger");
                quitar.setAccessibleText("Quitar " + l.producto.nombre());
                quitar.setTooltip(new Tooltip("Quitar del surtido"));
                quitar.setOnAction(e -> {
                    lineas.remove(l);
                    d.marcarCambios();
                    redibujar[0].run();
                });
                Label nombre = Ui.texto(l.producto.nombre(), "celda-principal");
                VBox producto = new VBox(1, nombre, l.existencia);
                l.existencia.getStyleClass().setAll("celda-secundaria", "existencia-almacen");
                Label unidad = new Label(Cantidades.unidadCaptura(l.producto.unidad()));
                unidad.getStyleClass().add("unidad-campo");
                HBox cantidad = new HBox(6, l.cantidad, unidad);
                cantidad.setAlignment(Pos.CENTER_LEFT);
                VBox costo = new VBox(1, l.costo, l.detalleCosto);
                l.costo.getStyleClass().setAll("celda-principal");
                l.detalleCosto.getStyleClass().setAll("celda-secundaria");
                l.detalleCosto.setWrapText(true);
                l.venta.getStyleClass().setAll("celda-principal");
                grid.addRow(r++, producto, cantidad, costo, l.botonPrecios, l.venta, quitar);
            }
            vacio.setVisible(lineas.isEmpty());
            vacio.setManaged(lineas.isEmpty());
            scroll.setVisible(!lineas.isEmpty());
            scroll.setManaged(!lineas.isEmpty());
            recalcular[0].run();
        };

        java.util.function.BiConsumer<ProductoCatalogo, BigDecimal> agregarLinea = (p, cantidad) -> {
            Linea existente = lineas.stream().filter(l -> l.producto.id().equals(p.id())).findFirst().orElse(null);
            if (existente != null) {
                // Mismo producto en otra presentación (ej. ya había cajas y ahora se agregan
                // piezas sueltas): se suma a la línea en vez de bloquear o crear una repetida.
                BigDecimal total = existente.base().add(cantidad);
                existente.cantidad.setText(Cantidades.aCaptura(p.unidad(), total));
                redibujar[0].run();
                a.avisos().info(p.nombre() + ": ahora son " + Cantidades.formatear(p.unidad(), total) + " en el surtido.");
                return;
            }
            Linea l = new Linea(p);
            l.cantidad.setText(Cantidades.aCaptura(p.unidad(), cantidad));
            Campos.soloNumeros(l.cantidad, 0);
            l.cantidad.setPrefColumnCount(6);
            l.cantidad.setAlignment(Pos.CENTER_RIGHT);
            l.cantidad.getStyleClass().add("campo-cantidad-surtido");
            l.cantidad.setAccessibleText("Cantidad de " + p.nombre());
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
            errorCaptura.setVisible(false);
            ProductoCatalogo p = SelectorProducto.valor(selector);
            if (p == null) {
                errorCaptura.setText("Elige un producto de la lista.");
                errorCaptura.setVisible(true);
                selector.requestFocus();
                return;
            }
            BigDecimal capturado = Cantidades.desdeCaptura(p.unidad(), cantidadNueva.getText()).orElse(BigDecimal.ZERO);
            if (capturado.signum() <= 0) {
                errorCaptura.setText("Escribe la cantidad (" + (presentacionNueva.isVisible()
                        ? "en " + presentacionNueva.getValue().nombre() : Cantidades.unidadCaptura(p.unidad())) + ").");
                errorCaptura.setVisible(true);
                cantidadNueva.requestFocus();
                return;
            }
            BigDecimal factor = presentacionNueva.isVisible() && presentacionNueva.getValue() != null
                    ? presentacionNueva.getValue().factor() : BigDecimal.ONE;
            // Las piezas se redondean a piezas enteras; los kilos se quedan como se capturaron.
            BigDecimal c = p.unidad() == Unidad.KG ? capturado
                    : capturado.multiply(factor).setScale(0, RoundingMode.HALF_UP);
            agregarLinea.accept(p, c);
            d.marcarCambios();
            selector.getEditor().clear();
            selector.setValue(null);
            cantidadNueva.clear();
            selector.requestFocus();
        };
        agregar.setOnAction(e -> accionAgregar.run());
        cantidadNueva.setOnAction(e -> accionAgregar.run());
        selector.valueProperty().addListener((o, x, p) -> {
            errorCaptura.setVisible(false);
            List<Presentacion> activas = p == null ? List.of()
                    : p.presentaciones().stream().filter(Presentacion::activo).toList();
            boolean varias = p != null && p.unidad() != Unidad.KG && activas.size() > 1;
            presentacionNueva.setVisible(varias);
            presentacionNueva.setManaged(varias);
            if (varias) {
                presentacionNueva.setItems(FXCollections.observableArrayList(activas));
                Presentacion principal = p.principal();
                presentacionNueva.setValue(activas.contains(principal) ? principal : activas.getFirst());
            }
            unidadNueva.setText(p == null || varias ? ""
                    : Cantidades.unidadCaptura(p.unidad()) + (p.unidad() == Unidad.KG ? " (1 kg = 1000 g)" : ""));
            if (p != null) {
                javafx.application.Platform.runLater(cantidadNueva::requestFocus);
            }
        });
        sucursal.valueProperty().addListener((o, x, s) -> {
            campoSucursal.setError(null);
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
        pago.valorProperty().addListener((o, x, y) -> recalcular[0].run());
        TextField notas = new TextField();
        notas.setPromptText("Notas para el ticket (opcional)");

        HBox cabecera = new HBox(16, campoSucursal, campoPago);
        HBox.setHgrow(campoSucursal, Priority.ALWAYS);
        campoSucursal.setMaxWidth(420);
        HBox captura = new HBox(8, selector, presentacionNueva, cantidadNueva, unidadNueva, agregar);
        captura.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(selector, Priority.ALWAYS);
        VBox bloqueCaptura = new VBox(6, captura, errorCaptura);
        bloqueCaptura.getStyleClass().add("surtido-captura");
        VBox izquierda = new VBox(12, cabecera, bloqueCaptura, vacio, scroll, notas);
        HBox.setHgrow(izquierda, Priority.ALWAYS);
        izquierda.setMinWidth(0);
        List<javafx.scene.Node> contenido = new ArrayList<>();
        if (aprobando) {
            contenido.add(Formulario.nota("mdi2i-information-outline", "Al aprobar, la mercancía se descuenta del almacén "
                    + "(primero los lotes más antiguos), entra a la sucursal con los precios que fijes, se genera el ticket "
                    + "del surtido y la orden queda como aprobada. Puedes ajustar la cantidad que se envía.", null));
        }
        contenido.add(new HBox(20, izquierda, resumen));
        d.setContenido(contenido.toArray(javafx.scene.Node[]::new));

        if (productoInicial != null) {
            agregarLinea.accept(productoInicial, cantidadInicial);
        } else {
            redibujar[0].run();
        }

        Runnable surtir = () -> {
            if (sucursal.getValue() == null) {
                campoSucursal.setError("Elige la sucursal que recibe.");
                return;
            }
            if (lineas.isEmpty()) {
                d.mostrarError("Agrega al menos un producto al surtido.");
                return;
            }
            Sucursal destino = sucursal.getValue();
            List<SurtidoService.Linea> datos = lineas.stream()
                    .map(l -> new SurtidoService.Linea(l.producto, l.base(), l.precios)).toList();
            SurtidoService.FormaPago forma = pago.getValor() == Pago.CREDITO ? SurtidoService.FormaPago.CREDITO
                    : SurtidoService.FormaPago.EFECTIVO;
            String textoNotas = notas.getText();
            d.enviar(() -> a.admin().surtidos().registrar(destino, forma, datos, textoNotas, a.usuario(), ordenId), detalle -> {
                d.cerrar();
                a.avisos().exito((aprobando ? "Orden aprobada y surtida" : "Surtido registrado") + " · Folio "
                        + detalle.resumen().folio() + " · " + Dinero.formatear(detalle.resumen().costoCentavos()));
                a.refrescarContadores().run();
                a.ctx().sincronizador().revisarAhora();
                alTerminar.run();
                DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Surtido " + detalle.resumen().folio(),
                        detalle.resumen().sucursal() + " · " + Dinero.formatear(detalle.resumen().costoCentavos()),
                        TicketTexto.lineasSurtido(detalle), null);
            });
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.separarBotones();
        d.agregarBoton(aprobando ? "Aprobar y surtir" : "Surtir", "mdi2t-truck-delivery-outline", surtir, "accent", "large");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(sucursalFija == null ? sucursal : selector);
        d.vigilarCambios(sucursal, pago, notas);
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
        d.setPrefWidth(900);
        Map<String, Long> sugeridos = sucursal == null ? Map.of()
                : a.admin().surtidos().preciosSugeridos(sucursal.id(), l.producto.id());
        SurtidoService.PreciosPromedio promedio = sucursal == null ? null
                : a.admin().surtidos().preciosPromedio(sucursal.id(), l.producto.id());
        boolean hayPromedio = promedio != null && promedio.lotes() > 0;

        // Precio fijo (se captura) o precio promedio de los lotes que ya tiene la sucursal
        ToggleGroup modo = new ToggleGroup();
        ToggleButton fijo = new ToggleButton("Precio fijo", new FontIcon("mdi2p-pencil-outline"));
        ToggleButton promediado = new ToggleButton("Precio promedio", new FontIcon("mdi2s-scale-balance"));
        fijo.setToggleGroup(modo);
        promediado.setToggleGroup(modo);
        promediado.setDisable(!hayPromedio);
        (l.promedio && hayPromedio ? promediado : fijo).setSelected(true);
        HBox selectorModo = new HBox(2, fijo, promediado);
        selectorModo.getStyleClass().add("segmentado");
        selectorModo.setMaxWidth(Region.USE_PREF_SIZE);
        Label ayudaModo = Ui.texto("", "campo-ayuda");
        long costoBase = l.costeo == null || l.base().signum() == 0 ? 0
                : BigDecimal.valueOf(l.costeo.costoCentavos()).divide(l.base(), 0, RoundingMode.HALF_UP).longValue();
        // Precio al público que da la regla de margen del canal público (solo si hay una que aplique).
        Map<String, Long> conMargen = sucursal == null || costoBase == 0 ? Map.of()
                : a.admin().precios().preciosPublicosSugeridos(l.producto, costoBase, sucursal.id(), LocalDate.now());
        GridPane grid = new GridPane(14, 10);
        String[] titulos = {"Presentación", "Contiene", "Costo aprox.", "Precio anterior", "Promedio", "Con margen",
                "Precio de venta"};
        for (int i = 0; i < titulos.length; i++) {
            Label t = new Label(titulos[i]);
            t.getStyleClass().add("grid-surtido-encabezado");
            t.setMinWidth(Region.USE_PREF_SIZE);
            grid.add(t, i, 0);
        }
        Map<String, CampoDinero> campos = new HashMap<>();
        int r = 1;
        CampoDinero primero = null;
        for (Presentacion p : l.producto.presentaciones()) {
            if (!p.activo()) {
                continue;
            }
            CampoDinero campo = new CampoDinero().normal();
            campo.setPrefColumnCount(8);
            campo.setAccessibleText("Precio de venta de " + p.nombre());
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
                    new Label(precioPromedio == null ? "—" : Dinero.formatear(precioPromedio)),
                    new Label(conMargen.containsKey(p.id()) ? Dinero.formatear(conMargen.get(p.id())) : "—")};
            for (Label c : celdas) {
                c.setMinWidth(Region.USE_PREF_SIZE);
            }
            grid.addRow(r++, celdas[0], celdas[1], celdas[2], celdas[3], celdas[4], celdas[5], campo);
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
        d.setContenido(Formulario.campo("Cómo se fija el precio de este lote", selectorModo), grid, ayudaModo);
        Runnable guardar = () -> {
            Map<String, Long> nuevos = new HashMap<>();
            for (Map.Entry<String, CampoDinero> e : campos.entrySet()) {
                long v = e.getValue().centavosOCero();
                if (v <= 0) {
                    d.mostrarError("Escribe el precio de venta de todas las presentaciones.");
                    e.getValue().requestFocus();
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
        if (!conMargen.isEmpty()) {
            d.separarBotones();
            d.agregarBoton("Usar precio con margen", "mdi2p-percent-outline", () -> {
                fijo.setSelected(true);
                conMargen.forEach((id, precio) -> {
                    CampoDinero campo = campos.get(id);
                    if (campo != null) {
                        campo.setCentavos(precio);
                    }
                });
            }, "");
        }
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
