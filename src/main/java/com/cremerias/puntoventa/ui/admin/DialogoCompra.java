package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.admin.CompraService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Compra a un proveedor con varias líneas. Cada línea va en la presentación comprada (caja,
 * pieza, kilo) con lo pagado, lo bonificado y el costo que cobra el proveedor. Al guardar entra
 * todo al almacén en una sola operación: todas las líneas o ninguna.
 */
final class DialogoCompra {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    /** Renglón de la compra en pantalla. */
    private static final class Linea {
        final ProductoCatalogo producto;
        /** Presentación comprada; {@code null} = la unidad del producto (pieza o kilo). */
        final Presentacion presentacion;
        final TextField pagada = new TextField();
        final TextField bonificada = new TextField();
        final CampoDinero costo = new CampoDinero().normal();
        final Label importe = new Label();
        final Label entra = new Label();

        Linea(ProductoCatalogo producto, Presentacion presentacion) {
            this.producto = producto;
            this.presentacion = presentacion;
        }

        /** Kilos con decimales; piezas, cajas y paquetes en unidades enteras. */
        boolean porPeso() {
            return producto.unidad() == Unidad.KG && (presentacion == null || presentacion.granel());
        }

        BigDecimal factor() {
            return presentacion == null ? BigDecimal.ONE : presentacion.factor();
        }

        String unidad() {
            if (presentacion == null) {
                return producto.unidad() == Unidad.KG ? "kg" : "pzas";
            }
            if (porPeso()) {
                return "kg";
            }
            return presentacion.factor().compareTo(BigDecimal.ONE) == 0 ? "pzas" : presentacion.nombre().toLowerCase(Locale.ROOT);
        }

        BigDecimal pagada() {
            return numero(pagada.getText());
        }

        BigDecimal bonificada() {
            return numero(bonificada.getText());
        }

        long importeCentavos() {
            return Dinero.importe(costo.centavosOCero(), pagada());
        }

        BigDecimal base(BigDecimal cantidad) {
            return producto.unidad().normalizar(cantidad.multiply(factor()));
        }
    }

    private DialogoCompra() {
    }

    /**
     * Abre una compra nueva con los proveedores al día. Es la única forma de meter mercancía al
     * almacén (Compras y el inventario del almacén la usan); sin proveedores, pide agregar uno.
     */
    static void abrir(AdminContexto a, Runnable alTerminar) {
        Cargador.ejecutar(() -> new Object[]{a.admin().compras().proveedores(), a.admin().compras().politica()},
                datos -> {
                    @SuppressWarnings("unchecked")
                    List<CompraService.Proveedor> lista = (List<CompraService.Proveedor>) datos[0];
                    if (lista.stream().noneMatch(CompraService.Proveedor::activo)) {
                        DialogoConfirmacion.mostrar(a.dialogos(), "Primero agrega un proveedor",
                                "Cada compra queda ligada a su proveedor. Agrega el primero y luego registra la compra.",
                                "Agregar proveedor", false, () -> proveedor(a, null, alTerminar));
                        return;
                    }
                    mostrar(a, lista, (CompraService.PoliticaBonificacion) datos[1], alTerminar);
                }, e -> a.avisos().error("No se pudo abrir la compra: " + AdminContexto.mensaje(e)));
    }

    static void mostrar(AdminContexto a, List<CompraService.Proveedor> proveedores, CompraService.PoliticaBonificacion politica,
                        Runnable alTerminar) {
        // El id se genera al abrir: si «Registrar» se manda dos veces, la compra no se duplica.
        String compraId = Ids.nuevo();
        Dialogo d = new Dialogo("Nueva compra", "La mercancía entra al almacén como lotes nuevos con el costo de la compra.",
                "mdi2c-cart-arrow-down", Dialogo.Tono.ACENTO);
        d.setPrefWidth(1160);
        d.sceneProperty().addListener((o, x, escena) -> {
            if (escena != null) {
                d.prefWidthProperty().bind(Bindings.min(1160, escena.widthProperty().subtract(72)));
            }
        });

        ComboBox<CompraService.Proveedor> proveedor = new ComboBox<>(FXCollections.observableArrayList(
                proveedores.stream().filter(CompraService.Proveedor::activo).toList()));
        proveedor.setConverter(Combos.de(CompraService.Proveedor::nombre));
        proveedor.setPromptText("Elige el proveedor");
        proveedor.setId("compra-proveedor");
        proveedor.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoProveedor = Formulario.campo("Proveedor", proveedor);
        if (proveedor.getItems().isEmpty()) {
            campoProveedor.ayuda("Primero da de alta al proveedor en la pestaña «Proveedores».");
        }
        TextField documento = new TextField();
        documento.setPromptText("Ej. Factura A-1234");
        DatePicker fechaDocumento = new DatePicker(LocalDate.now());
        fechaDocumento.setEditable(false);
        fechaDocumento.setMaxWidth(Double.MAX_VALUE);
        HBox cabecera = Formulario.fila(campoProveedor, Formulario.opcional("Nota o factura", documento),
                Formulario.campo("Fecha de la nota", fechaDocumento));

        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> selector = SelectorProducto.crear(productos);
        selector.setPrefWidth(380);
        selector.setId("compra-producto");
        ComboBox<Presentacion> presentacionNueva = new ComboBox<>();
        presentacionNueva.setConverter(Combos.de(p -> p.granel() ? p.nombre() + " (por kilo)"
                : p.nombre() + " (" + Cantidades.formatear(Unidad.PZA, p.factor()) + ")"));
        presentacionNueva.setVisible(false);
        presentacionNueva.setManaged(false);
        Button agregar = Ui.boton("Agregar", "mdi2p-plus", "accent");
        Label errorCaptura = new Label();
        errorCaptura.getStyleClass().add("campo-error");
        errorCaptura.setGraphic(new FontIcon("mdi2a-alert-circle-outline"));
        errorCaptura.managedProperty().bind(errorCaptura.visibleProperty());
        errorCaptura.setVisible(false);

        List<Linea> lineas = new ArrayList<>();
        GridPane grid = new GridPane(12, 8);
        grid.getStyleClass().add("grid-surtido");
        for (double ancho : new double[]{250, 130, 130, 130, 110, 36}) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPrefWidth(ancho);
            col.setMinWidth(Region.USE_PREF_SIZE);
            grid.getColumnConstraints().add(col);
        }
        grid.getColumnConstraints().getFirst().setHgrow(Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(250);
        scroll.getStyleClass().add("scroll-surtido");
        Label vacio = Ui.texto("Agrega los productos de la nota: elige el producto y la presentación en que llegó, "
                + "y presiona «Agregar». Luego escribe lo pagado, lo bonificado y el costo.", "campo-ayuda");

        Label total = Ui.texto("$0.00", "surtido-total");
        Label numProductos = Ui.texto("Sin productos", "surtido-detalle");
        Label textoPolitica = Ui.texto(politica.nombre() + ".", "surtido-detalle");
        Label ayudaPolitica = Ui.texto(politica == CompraService.PoliticaBonificacion.PRORRATEAR
                ? "El costo pagado se reparte entre todo lo recibido: el costo por pieza baja."
                : "Lo bonificado entra en un lote aparte a costo cero; lo pagado conserva su costo.", "campo-ayuda");
        VBox resumen = new VBox(8, Ui.texto("Total a pagar al proveedor", "surtido-etiqueta"), total, numProductos,
                new Separator(), Ui.texto("Mercancía bonificada", "surtido-etiqueta"), textoPolitica, ayudaPolitica);
        resumen.getStyleClass().add("surtido-resumen");
        resumen.setPrefWidth(250);
        resumen.setMinWidth(230);

        Runnable[] recalcular = new Runnable[1];
        Runnable[] redibujar = new Runnable[1];
        recalcular[0] = () -> {
            long suma = 0;
            for (Linea l : lineas) {
                long importe = l.importeCentavos();
                suma += importe;
                l.importe.setText(Dinero.formatear(importe));
                l.pagada.pseudoClassStateChanged(ERROR, l.pagada().signum() <= 0 && l.bonificada().signum() <= 0);
                l.entra.setText(entra(l, politica));
            }
            total.setText(Dinero.formatear(suma));
            numProductos.setText(lineas.isEmpty() ? "Sin productos" : lineas.size() == 1 ? "1 producto"
                    : lineas.size() + " productos");
        };
        redibujar[0] = () -> {
            grid.getChildren().clear();
            String[] titulos = {"Producto", "Pagado", "Bonificado", "Costo unitario", "Importe", ""};
            for (int i = 0; i < titulos.length; i++) {
                Label t = new Label(titulos[i]);
                t.getStyleClass().add("grid-surtido-encabezado");
                grid.add(t, i, 0);
            }
            int r = 1;
            for (Linea l : lineas) {
                Button quitar = Ui.boton(null, "mdi2t-trash-can-outline", "flat", "boton-icono", "danger");
                quitar.setAccessibleText("Quitar " + l.producto.nombre());
                quitar.setTooltip(new Tooltip("Quitar de la compra"));
                quitar.setOnAction(e -> {
                    lineas.remove(l);
                    d.marcarCambios();
                    redibujar[0].run();
                });
                Label nombre = Ui.texto(l.producto.nombre(), "celda-principal");
                Label presentacion = Ui.texto(l.presentacion == null ? Formatos.seVende(l.producto.unidad())
                        : l.presentacion.granel() ? l.presentacion.nombre() + " · por kilo"
                        : l.presentacion.nombre() + " de " + Cantidades.formatear(Unidad.PZA, l.presentacion.factor()),
                        "celda-secundaria");
                l.importe.getStyleClass().setAll("celda-principal");
                l.entra.getStyleClass().setAll("celda-secundaria");
                l.entra.setWrapText(true);
                grid.addRow(r++, new VBox(1, nombre, presentacion, l.entra), conUnidad(l.pagada, l.unidad()),
                        conUnidad(l.bonificada, l.unidad()), l.costo, l.importe, quitar);
            }
            vacio.setVisible(lineas.isEmpty());
            vacio.setManaged(lineas.isEmpty());
            scroll.setVisible(!lineas.isEmpty());
            scroll.setManaged(!lineas.isEmpty());
            recalcular[0].run();
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
            Presentacion pr = presentacionNueva.isVisible() ? presentacionNueva.getValue() : null;
            boolean repetida = lineas.stream().anyMatch(l -> l.producto.id().equals(p.id())
                    && (l.presentacion == null ? pr == null : pr != null && l.presentacion.id().equals(pr.id())));
            if (repetida) {
                errorCaptura.setText(p.nombre() + " ya está en la compra en esa presentación: cambia su cantidad abajo.");
                errorCaptura.setVisible(true);
                return;
            }
            Linea l = new Linea(p, pr);
            for (TextField campo : List.of(l.pagada, l.bonificada)) {
                Campos.soloNumeros(campo, l.porPeso() ? 3 : 0);
                campo.setPrefColumnCount(5);
                campo.setAlignment(Pos.CENTER_RIGHT);
                campo.setPromptText("0");
                campo.textProperty().addListener((o, x, y) -> recalcular[0].run());
            }
            l.pagada.setAccessibleText("Cantidad pagada de " + p.nombre());
            l.pagada.getStyleClass().add("compra-pagada");
            l.bonificada.getStyleClass().add("compra-bonificada");
            l.costo.getStyleClass().add("compra-costo");
            l.bonificada.setAccessibleText("Cantidad bonificada de " + p.nombre());
            l.costo.setPromptText("0.00");
            l.costo.setPrefColumnCount(7);
            l.costo.setAccessibleText("Costo unitario de " + p.nombre());
            l.costo.textProperty().addListener((o, x, y) -> recalcular[0].run());
            lineas.add(l);
            d.marcarCambios();
            redibujar[0].run();
            selector.getEditor().clear();
            selector.setValue(null);
            Platform.runLater(l.pagada::requestFocus);
        };
        agregar.setOnAction(e -> accionAgregar.run());
        selector.valueProperty().addListener((o, x, p) -> {
            errorCaptura.setVisible(false);
            List<Presentacion> activas = p == null ? List.of()
                    : p.presentaciones().stream().filter(Presentacion::activo).toList();
            boolean varias = activas.size() > 1;
            presentacionNueva.setVisible(varias);
            presentacionNueva.setManaged(varias);
            if (varias) {
                presentacionNueva.setItems(FXCollections.observableArrayList(activas));
                presentacionNueva.setValue(activas.contains(p.principal()) ? p.principal() : activas.getFirst());
            }
        });
        selector.getEditor().setOnAction(e -> {
            if (SelectorProducto.valor(selector) != null && !presentacionNueva.isVisible()) {
                accionAgregar.run();
            }
        });

        TextField notas = new TextField();
        notas.setPromptText("Notas de la compra (opcional)");
        HBox captura = new HBox(8, selector, presentacionNueva, agregar);
        captura.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(selector, Priority.ALWAYS);
        VBox bloqueCaptura = new VBox(6, captura, errorCaptura);
        bloqueCaptura.getStyleClass().add("surtido-captura");
        VBox izquierda = new VBox(12, cabecera, bloqueCaptura, vacio, scroll, notas);
        HBox.setHgrow(izquierda, Priority.ALWAYS);
        izquierda.setMinWidth(0);
        d.setContenido(new HBox(20, izquierda, resumen));
        redibujar[0].run();

        Runnable registrar = () -> {
            if (proveedor.getValue() == null) {
                campoProveedor.setError("Elige el proveedor.");
                return;
            }
            if (lineas.isEmpty()) {
                d.mostrarError("Agrega al menos un producto a la compra.");
                return;
            }
            for (Linea l : lineas) {
                if (l.pagada().signum() <= 0 && l.bonificada().signum() <= 0) {
                    d.mostrarError("Escribe cuánto llegó de " + l.producto.nombre() + ".");
                    l.pagada.requestFocus();
                    return;
                }
                if (l.pagada().signum() > 0 && (l.costo.getText() == null || l.costo.getText().isBlank())) {
                    d.mostrarError("Escribe el costo de " + l.producto.nombre() + " (puede ser 0).");
                    l.costo.requestFocus();
                    return;
                }
            }
            CompraService.Proveedor elegido = proveedor.getValue();
            String doc = documento.getText();
            LocalDate fecha = fechaDocumento.getValue();
            String textoNotas = notas.getText();
            List<CompraService.Linea> datos = lineas.stream().map(l -> new CompraService.Linea(l.producto, l.presentacion,
                    l.pagada(), l.bonificada(), l.costo.centavosOCero())).toList();
            d.enviar(() -> a.admin().compras().registrar(compraId, elegido, doc, fecha, datos, textoNotas, a.usuario()),
                    detalle -> {
                        d.cerrar();
                        a.avisos().exito("Compra registrada · Folio " + detalle.resumen().folio() + " · "
                                + Dinero.formatear(detalle.resumen().totalCentavos()));
                        a.refrescarContadores().run();
                        alTerminar.run();
                    });
        };
        proveedor.valueProperty().addListener((o, x, y) -> campoProveedor.setError(null));
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.separarBotones();
        d.agregarBoton("Registrar compra", "mdi2c-check", registrar, "accent", "large");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(proveedor.getItems().isEmpty() ? selector : proveedor);
        d.vigilarCambios(proveedor, documento, fechaDocumento, notas);
        a.dialogos().mostrar(d);
    }

    /** Lo que entra al almacén y a qué costo, según la política de bonificación. */
    private static String entra(Linea l, CompraService.PoliticaBonificacion politica) {
        BigDecimal pagada = l.base(l.pagada());
        BigDecimal bonificada = l.base(l.bonificada());
        BigDecimal recibida = pagada.add(bonificada);
        if (recibida.signum() <= 0) {
            return "—";
        }
        Unidad u = l.producto.unidad();
        String sufijo = Formatos.porUnidad(u);
        long importe = l.importeCentavos();
        if (bonificada.signum() == 0 || politica == CompraService.PoliticaBonificacion.PRORRATEAR) {
            return "Entran " + Cantidades.formatear(u, recibida) + " a " + Dinero.formatear(costoPor(importe, recibida)) + sufijo;
        }
        String pagadoTexto = pagada.signum() == 0 ? "Entran "
                : "Entran " + Cantidades.formatear(u, pagada) + " a " + Dinero.formatear(costoPor(importe, pagada)) + sufijo + " + ";
        return pagadoTexto + Cantidades.formatear(u, bonificada) + " sin costo";
    }

    private static long costoPor(long importe, BigDecimal cantidad) {
        return cantidad.signum() <= 0 ? 0 : BigDecimal.valueOf(importe).divide(cantidad, 0, RoundingMode.HALF_UP).longValue();
    }

    private static Node conUnidad(TextField campo, String unidad) {
        Label u = new Label(unidad);
        u.getStyleClass().add("unidad-campo");
        u.setMinWidth(Region.USE_PREF_SIZE);
        HBox h = new HBox(6, campo, u);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static BigDecimal numero(String texto) {
        if (texto == null || texto.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(texto.strip());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Alta o edición de un proveedor. */
    static void proveedor(AdminContexto a, CompraService.Proveedor p, Runnable alGuardar) {
        boolean nuevo = p == null;
        Dialogo d = new Dialogo(nuevo ? "Nuevo proveedor" : "Editar proveedor", nuevo ? null : p.nombre(),
                "mdi2t-truck-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(540);
        TextField nombre = new TextField();
        nombre.setPromptText("Ej. Lácteos de la Sierra");
        nombre.setId("proveedor-nombre");
        Formulario.Campo campoNombre = Formulario.campo("Nombre", nombre);
        TextField rfc = new TextField();
        TextField telefono = new TextField();
        TextField contacto = new TextField();
        contacto.setPromptText("Persona con quien se trata");
        TextField notas = new TextField();
        if (p != null) {
            nombre.setText(p.nombre());
            rfc.setText(p.rfc());
            telefono.setText(p.telefono());
            contacto.setText(p.contacto());
            notas.setText(p.notas());
        }
        nombre.textProperty().addListener((o, x, y) -> campoNombre.setError(null));
        d.setContenido(campoNombre, Formulario.fila(Formulario.opcional("RFC", rfc), Formulario.opcional("Teléfono", telefono)),
                Formulario.opcional("Contacto", contacto), Formulario.opcional("Notas", notas));
        Runnable guardar = () -> {
            if (nombre.getText() == null || nombre.getText().isBlank()) {
                campoNombre.setError("Escribe el nombre del proveedor.");
                return;
            }
            String id = p == null ? null : p.id();
            String n = nombre.getText();
            String r = rfc.getText();
            String t = telefono.getText();
            String c = contacto.getText();
            String no = notas.getText();
            a.guardar(d, nuevo ? "Proveedor agregado: " + n.strip() : "Proveedor actualizado",
                    () -> a.admin().compras().guardarProveedor(id, n, r, t, c, no, a.usuario()), alGuardar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nuevo ? "Agregar proveedor" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(nombre);
        d.vigilarCambios(nombre, rfc, telefono, contacto, notas);
        a.dialogos().mostrar(d);
    }

    /** Cantidad capturada (gramos o piezas) en unidad base; vacío si no es válida. */
    static Optional<BigDecimal> base(ProductoCatalogo p, String texto) {
        return Cantidades.desdeCaptura(p.unidad(), texto).filter(c -> c.signum() > 0);
    }
}
