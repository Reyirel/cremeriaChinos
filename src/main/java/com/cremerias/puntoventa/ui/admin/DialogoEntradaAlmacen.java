package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.CampoMasa;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Entrada de mercancía al almacén: crea un lote nuevo con su costo. Si el producto tiene varias
 * presentaciones (pieza suelta, caja de 12...) se captura en la que llegó y se convierte sola a
 * piezas y costo unitario, igual que antes.
 */
final class DialogoEntradaAlmacen {

    private DialogoEntradaAlmacen() {
    }

    /**
     * @param fijo        producto ya elegido (desde su fila o su detalle), o null para elegirlo
     * @param existencia  existencia actual en el almacén de un producto (para mostrar cómo queda)
     */
    static void mostrar(AdminContexto a, ProductoCatalogo fijo, Function<String, BigDecimal> existencia,
                        Runnable alGuardar) {
        Dialogo d = new Dialogo("Registrar entrada al almacén",
                "La mercancía entra como un lote nuevo con su propio costo.", "mdi2t-tray-arrow-down",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(600);
        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(productos);
        TextField productoFijo = new TextField();
        productoFijo.setEditable(false);
        productoFijo.setFocusTraversable(false);
        productoFijo.getStyleClass().add("campo-solo-lectura");
        Formulario.Campo campoProducto = Formulario.campo("Producto", fijo == null ? producto : productoFijo);

        // Varias presentaciones: se elige en cuál se está capturando.
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
        Formulario.Campo campoPresentacion = Formulario.campo("Llegó en", presentacion)
                .ayuda("La cantidad y el costo se capturan en esta presentación.");
        campoPresentacion.managedProperty().bind(campoPresentacion.visibleProperty());

        TextField cantidadPiezas = new TextField();
        Campos.soloNumeros(cantidadPiezas, 0);
        cantidadPiezas.setPromptText("0");
        cantidadPiezas.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        Label unidadPiezas = new Label("pzas");
        unidadPiezas.getStyleClass().add("unidad-campo");
        CampoMasa cantidadGranel = new CampoMasa();
        cantidadGranel.selectorUnidad().setValue(Masa.KG);
        Formulario.Campo campoPiezas = Formulario.campo("Cantidad",
                Formulario.conUnidad(cantidadPiezas, ""));
        ((javafx.scene.layout.HBox) campoPiezas.getChildren().get(1)).getChildren().set(1, unidadPiezas);
        Formulario.Campo campoGranel = Formulario.campo("Cantidad", cantidadGranel);
        campoPiezas.managedProperty().bind(campoPiezas.visibleProperty());
        campoGranel.managedProperty().bind(campoGranel.visibleProperty());

        CampoDinero costo = new CampoDinero().normal();
        costo.setPromptText("0.00");
        Formulario.Campo campoCosto = Formulario.campo("Costo por pieza", costo);
        Label equivalencia = new Label();
        equivalencia.getStyleClass().add("campo-ayuda");
        equivalencia.managedProperty().bind(equivalencia.visibleProperty());

        TextField notas = new TextField();
        notas.setPromptText("Proveedor, factura…");
        Formulario.Campo campoNotas = Formulario.opcional("Notas", notas);

        Label total = new Label("$0.00");
        Label despues = new Label();
        despues.getStyleClass().add("campo-ayuda");
        VBox resumen = Formulario.resumen("Total de la entrada", total, despues);

        // Cantidad y costo tal como se escriben (en piezas, en la presentación elegida o en kg si es
        // a granel). «cantidadBase» y «costoBase» los llevan a la unidad base del producto.
        Supplier<ProductoCatalogo> elegido = () -> fijo != null ? fijo : SelectorProducto.valor(producto);
        Supplier<BigDecimal> cantidadCapturada = () -> {
            ProductoCatalogo p = elegido.get();
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
        // Lo que se registra en el lote (misma conversión que antes del rediseño).
        Supplier<BigDecimal> cantidadFinal = cantidadBase;

        Runnable actualizar = () -> {
            ProductoCatalogo p = elegido.get();
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
            Presentacion pr = presentacionElegida.get();
            unidadPiezas.setText(pr == null ? "pzas" : pr.nombre().toLowerCase(Locale.ROOT));
            campoCosto.etiqueta().setText(u == Unidad.KG ? "Costo por kilo"
                    : pr == null ? "Costo por pieza" : "Costo por " + pr.nombre().toLowerCase(Locale.ROOT));
            BigDecimal factor = factorElegido.get();
            if (u != Unidad.KG && factor.compareTo(BigDecimal.ONE) != 0 && cantidadCapturada.get().signum() > 0) {
                long costoPorPieza = costo.centavosOCero() > 0 ? costoBase.get() : 0;
                equivalencia.setText("Equivale a " + Cantidades.formatear(Unidad.PZA, cantidadBase.get())
                        + (costoPorPieza > 0 ? " a " + Dinero.formatear(costoPorPieza) + " cada una" : "") + ".");
                equivalencia.setVisible(true);
            } else {
                equivalencia.setVisible(false);
            }
            total.setText(Dinero.formatear(Dinero.importe(costo.centavosOCero(), cantidadCapturada.get())));
            if (p != null) {
                BigDecimal actual = existencia.apply(p.id());
                BigDecimal nueva = (actual == null ? BigDecimal.ZERO : actual).add(cantidadFinal.get());
                despues.setText("Existencia en el almacén: " + Cantidades.formatear(u, actual == null ? BigDecimal.ZERO : actual)
                        + " → " + Cantidades.formatear(u, nueva));
            } else {
                despues.setText("Elige el producto para ver cómo queda su existencia.");
            }
            campoProducto.setError(null);
        };
        producto.valueProperty().addListener((o, x, y) -> actualizar.run());
        presentacion.valueProperty().addListener((o, x, y) -> actualizar.run());
        cantidadPiezas.textProperty().addListener((o, x, y) -> {
            campoPiezas.setError(null);
            actualizar.run();
        });
        cantidadGranel.campo().textProperty().addListener((o, x, y) -> {
            campoGranel.setError(null);
            actualizar.run();
        });
        cantidadGranel.selectorUnidad().valueProperty().addListener((o, x, y) -> actualizar.run());
        costo.textProperty().addListener((o, x, y) -> {
            campoCosto.setError(null);
            actualizar.run();
        });
        if (fijo != null) {
            productoFijo.setText(fijo.nombre());
        }
        actualizar.run();

        d.setContenido(
                Formulario.seccion(null, null, campoProducto, campoPresentacion),
                Formulario.seccionSeparada("Cantidad y costo", null,
                        Formulario.fila(new VBox(campoPiezas, campoGranel), campoCosto), equivalencia),
                campoNotas,
                resumen,
                Formulario.nota("mdi2i-information-outline",
                        "Se crea un lote con este costo. Al surtir o vender sale primero el lote más antiguo.", null));

        Runnable guardar = () -> {
            ProductoCatalogo p = elegido.get();
            boolean valido = true;
            if (p == null) {
                campoProducto.setError("Elige el producto que llegó.");
                valido = false;
            }
            if (cantidadCapturada.get().signum() <= 0) {
                (p != null && p.unidad() == Unidad.KG ? campoGranel : campoPiezas).setError("Escribe una cantidad mayor a cero.");
                valido = false;
            }
            if (costo.getText() == null || costo.getText().isBlank()) {
                campoCosto.setError("Escribe el costo (puede ser 0).");
                valido = false;
            }
            if (!valido) {
                return;
            }
            BigDecimal c = cantidadFinal.get();
            long costoUnitario = costoBase.get();
            String notasTexto = notas.getText();
            a.guardar(d, "Entrada registrada: " + p.nombre(),
                    () -> a.admin().almacen().reabastecer(p, c, costoUnitario, notasTexto, a.usuario()), alGuardar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar entrada", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(fijo == null ? producto : (fijo.unidad() == Unidad.KG ? cantidadGranel.campo() : cantidadPiezas));
        d.vigilarCambios(producto, presentacion, cantidadPiezas, cantidadGranel.campo(), costo, notas);
        a.dialogos().mostrar(d);
    }
}
