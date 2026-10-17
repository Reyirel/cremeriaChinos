package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.AjusteService;
import com.cremerias.puntoventa.service.ReconciliacionService;
import com.cremerias.puntoventa.service.TransformacionService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.Pestanas;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ajustes de inventario con motivo y documento, transformaciones de un producto en otro y la
 * revisión de existencias que quedaron negativas por ventas sin conexión en dos cajas.
 *
 * <p>El supervisor la usa con su sucursal fija: solo ve y ajusta la suya (el servicio también lo
 * valida) y no ve la revisión de existencias negativas, que es del administrador.</p>
 */
public class AjustesPagina extends Pagina {

    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las ubicaciones", null, null, true, false);

    private enum Direccion { ENTRA, SALE }

    private final Cargador carga = new Cargador();
    private ToggleButton pestanaAjustes;
    private ToggleButton pestanaTransformaciones;
    private ToggleButton pestanaNegativos;
    private Button principal;
    private DatePicker desde;
    private DatePicker hasta;
    private HBox periodo;
    private BarraHerramientas barraAjustes;
    private ComboBox<Sucursal> ubicacion;
    private TablaDatos<AjusteService.Ajuste> ajustes;
    private ResumenMetricas.Metrica metricaPerdida;
    private ResumenMetricas.Metrica metricaSobrante;
    private ResumenMetricas.Metrica metricaAjustes;
    private BarraHerramientas barraTransformaciones;
    private TablaDatos<TransformacionService.Transformacion> transformaciones;
    private TablaDatos<ReconciliacionService.Negativo> negativos;
    private List<Sucursal> ubicaciones = List.of();
    private List<ProductoCatalogo> productos = List.of();
    /** Sucursal del supervisor; {@code null} = administrador (todas las ubicaciones). */
    private final Sucursal fija;

    public AjustesPagina(AdminContexto a) {
        this(a, null);
    }

    public AjustesPagina(AdminContexto a, Sucursal fija) {
        super(a);
        this.fija = fija;
    }

    @Override
    public String titulo() {
        return "Ajustes de inventario";
    }

    @Override
    public TextField busqueda() {
        return pestanaAjustes.isSelected() ? barraAjustes.campoBusqueda()
                : pestanaTransformaciones.isSelected() ? barraTransformaciones.campoBusqueda() : null;
    }

    @Override
    protected Node construir() {
        principal = Ui.principal("Nuevo ajuste", "mdi2p-plus");
        principal.setOnAction(e -> {
            if (pestanaTransformaciones.isSelected()) {
                transformar();
            } else {
                ajustar(null, null, null);
            }
        });
        principal.managedProperty().bind(principal.visibleProperty());
        VBox pagina = Ui.pagina(fija == null ? "Ajustes de inventario" : "Ajustes de " + fija.nombre(), fija == null
                ? "Correcciones con motivo y documento, transformaciones entre productos y existencias negativas."
                : "Correcciones de existencia con su motivo y transformaciones de productos en tu sucursal.", principal);
        Pestanas pestanas = new Pestanas();
        pestanaAjustes = pestanas.agregar("Ajustes");
        pestanaTransformaciones = pestanas.agregar("Transformaciones");
        pestanaNegativos = pestanas.agregar("Existencias negativas");

        desde = new DatePicker(LocalDate.now().minusDays(30));
        hasta = new DatePicker(LocalDate.now());
        for (DatePicker p : List.of(desde, hasta)) {
            p.setEditable(false);
            p.setPrefWidth(150);
            p.valueProperty().addListener((o, x, y) -> cargar());
        }
        Label del = new Label("Del");
        del.getStyleClass().add("etiqueta-filtro");
        Label al = new Label("al");
        al.getStyleClass().add("etiqueta-filtro");
        periodo = new HBox(8, del, desde, al, hasta);
        periodo.setAlignment(Pos.CENTER_LEFT);

        VBox vistaAjustes = construirAjustes();
        VBox vistaTransformaciones = construirTransformaciones();
        VBox vistaNegativos = construirNegativos();
        StackPane zona = new StackPane(vistaAjustes);
        pestanas.seleccionProperty().addListener((o, x, y) -> {
            zona.getChildren().setAll(pestanaAjustes.isSelected() ? vistaAjustes
                    : pestanaTransformaciones.isSelected() ? vistaTransformaciones : vistaNegativos);
            if (periodo.getParent() instanceof javafx.scene.layout.Pane padre) {
                padre.getChildren().remove(periodo);
            }
            if (pestanaAjustes.isSelected()) {
                barraAjustes.agregar(periodo);
            } else if (pestanaTransformaciones.isSelected()) {
                barraTransformaciones.agregar(periodo);
            }
            principal.setVisible(!pestanaNegativos.isSelected());
            principal.setText(pestanaTransformaciones.isSelected() ? "Nueva transformación" : "Nuevo ajuste");
        });
        barraAjustes.agregar(periodo);
        if (fija != null) {
            pestanaNegativos.setVisible(false);
            pestanaNegativos.setManaged(false);
            ubicacion.setVisible(false);
            ubicacion.setManaged(false);
        }
        pagina.getChildren().addAll(pestanas, zona);
        return Ui.ajustable(pagina, zona, 320);
    }

    // ------------------------------------------------------------------ ajustes

    private VBox construirAjustes() {
        ResumenMetricas resumen = new ResumenMetricas();
        metricaPerdida = resumen.agregar("Pérdidas al costo").principal();
        metricaSobrante = resumen.agregar("Sobrantes al costo");
        metricaAjustes = resumen.agregar("Ajustes");
        barraAjustes = new BarraHerramientas();
        barraAjustes.buscador("Buscar folio, producto, documento o nota");
        ubicacion = barraAjustes.filtro("Ubicación", new ComboBox<>(), TODAS, s -> s.id() == null ? s.nombre()
                : Formatos.sucursal(s));
        ubicacion.setValue(TODAS);
        ubicacion.valueProperty().addListener((o, x, y) -> cargar());
        barraAjustes.setAlCambiar(this::filtrarAjustes);

        ajustes = new TablaDatos<>(Diseno.Densidad.NORMAL);
        ajustes.setClave(AjusteService.Ajuste::id);
        ajustes.fecha("Fecha", AjusteService.Ajuste::fecha, Formatos.FECHA_HORA).ancho(142);
        ajustes.dosLineas("Folio", AjusteService.Ajuste::folio, x -> x.documento() == null ? null : "Doc. " + x.documento())
                .ancho(170);
        ajustes.dosLineas("Producto", AjusteService.Ajuste::producto, AjusteService.Ajuste::sucursal).flexible(200);
        ajustes.etiqueta("Motivo", AjusteService.Ajuste::motivo, AjusteService.Motivo::nombre, AjustesPagina::tono).ancho(190);
        ajustes.formato("Cantidad", AjusteService.Ajuste::cantidad, (x, v) -> (v.signum() < 0 ? "−" : "+")
                + Cantidades.formatear(x.unidad(), v.abs())).numero().ancho(120);
        ajustes.formato("Costo", AjusteService.Ajuste::costoCentavos, (x, v) -> (v < 0 ? "−" : "+")
                + Dinero.formatear(Math.abs(v))).numero().ancho(120)
                .ayuda("Valor al costo de los lotes que salieron o entraron.");
        ajustes.textoLargo("Nota", x -> x.nota() == null ? "" : x.nota()).ancho(200);
        ajustes.texto("Registró", AjusteService.Ajuste::usuario).ancho(170).oculta();
        ajustes.setVacio(new EstadoVacio("mdi2c-clipboard-edit-outline", "No hay ajustes en estas fechas.",
                "Registra sobrantes, faltantes, caducidad o daños con «Nuevo ajuste».")
                .accion("Nuevo ajuste", "mdi2p-plus", () -> ajustar(null, null, null)));
        ajustes.setSinResultados(EstadoVacio.sinResultados("No encontramos ajustes con estos filtros.", barraAjustes::limpiar));
        VBox panel = Ui.panelTabla(barraAjustes, ajustes);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return new VBox(Diseno.ESPACIO_L, resumen, panel);
    }

    private static Etiqueta.Tono tono(AjusteService.Motivo m) {
        return switch (m) {
            case SOBRANTE -> Etiqueta.Tono.EXITO;
            case FALTANTE, CADUCIDAD, DANO -> Etiqueta.Tono.PELIGRO;
            default -> Etiqueta.Tono.NEUTRO;
        };
    }

    private void filtrarAjustes() {
        String t = barraAjustes.busqueda();
        ajustes.setFiltro(x -> t.isEmpty() || Texto.contiene(x.folio(), t) || Texto.contiene(x.producto(), t)
                || Texto.contiene(x.documento(), t) || Texto.contiene(x.nota(), t) || Texto.contiene(x.sucursal(), t));
        List<AjusteService.Ajuste> visibles = ajustes.visibles();
        long perdida = -visibles.stream().filter(x -> x.costoCentavos() < 0).mapToLong(AjusteService.Ajuste::costoCentavos).sum();
        long sobrante = visibles.stream().filter(x -> x.costoCentavos() > 0).mapToLong(AjusteService.Ajuste::costoCentavos).sum();
        metricaPerdida.setValor(Dinero.formatear(perdida)).setContexto("Faltantes, caducidad, daños y otras salidas")
                .setTono(perdida > 0 ? Etiqueta.Tono.PELIGRO : null);
        metricaSobrante.setValor(Dinero.formatear(sobrante)).setContexto("Sobrantes y otras entradas");
        metricaAjustes.setValor(String.valueOf(visibles.size())).setContexto(periodoTexto());
        barraAjustes.setResultados(ajustes.mostrados() == ajustes.total() ? Texto.plural(ajustes.total(), "ajuste", "ajustes")
                : ajustes.mostrados() + " de " + Texto.plural(ajustes.total(), "ajuste", "ajustes"));
    }

    private String periodoTexto() {
        return Formatos.FECHA_CORTA.format(desde.getValue()) + " – " + Formatos.FECHA_CORTA.format(hasta.getValue());
    }

    /** Ajuste nuevo; los parámetros (opcionales) llegan desde «Existencias negativas». */
    private void ajustar(Sucursal ubicacionFija, ProductoCatalogo productoFijo, AjusteService.Motivo motivoInicial) {
        Dialogo d = new Dialogo("Nuevo ajuste de inventario",
                "Corrige la existencia de un producto con su motivo. Queda en el historial con su valor al costo.",
                "mdi2c-clipboard-edit-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(600);
        String ajusteId = Ids.nuevo();
        ComboBox<Sucursal> lugar = new ComboBox<>(FXCollections.observableArrayList(ubicaciones));
        lugar.setConverter(Combos.sucursal());
        lugar.setMaxWidth(Double.MAX_VALUE);
        lugar.setValue(ubicacionFija != null ? ubicacionFija : ubicaciones.isEmpty() ? null : ubicaciones.getFirst());
        Formulario.Campo campoLugar = Formulario.campo("Ubicación", lugar);
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(productos);
        producto.setMaxWidth(Double.MAX_VALUE);
        producto.setId("ajuste-producto");
        if (productoFijo != null) {
            producto.setValue(productoFijo);
        }
        Formulario.Campo campoProducto = Formulario.campo("Producto", producto);
        ComboBox<AjusteService.Motivo> motivo = new ComboBox<>(FXCollections.observableArrayList(AjusteService.Motivo.values()));
        motivo.setMaxWidth(Double.MAX_VALUE);
        motivo.setValue(motivoInicial != null ? motivoInicial : AjusteService.Motivo.FALTANTE);
        Segmentado<Direccion> direccion = new Segmentado<Direccion>().opcion(Direccion.ENTRA, "Entra", "mdi2p-plus")
                .opcion(Direccion.SALE, "Sale", "mdi2m-minus");
        Formulario.Campo campoDireccion = Formulario.campo("La existencia", direccion);
        campoDireccion.managedProperty().bind(campoDireccion.visibleProperty());
        TextField cantidad = new TextField();
        cantidad.setId("ajuste-cantidad");
        Campos.soloNumeros(cantidad, 0);
        cantidad.setAlignment(Pos.CENTER_RIGHT);
        cantidad.setPromptText("0");
        Label unidad = new Label("pzas");
        unidad.getStyleClass().add("unidad-campo");
        HBox filaCantidad = new HBox(8, cantidad, unidad);
        filaCantidad.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cantidad, Priority.ALWAYS);
        Formulario.Campo campoCantidad = Formulario.campo("Cantidad", filaCantidad);
        TextField documento = new TextField();
        documento.setPromptText("Ej. Acta de conteo 12, nota de cortesía 45");
        Formulario.Campo campoDocumento = Formulario.campo("Documento de referencia", documento);
        TextField nota = new TextField();
        nota.setId("ajuste-nota");
        nota.setPromptText("Qué pasó");
        Formulario.Campo campoNota = Formulario.campo("Nota", nota);
        Label existencia = new Label("—");
        Label despues = Ui.texto("", "campo-ayuda");
        VBox resumen = Formulario.resumen("Existencia en la ubicación", existencia, despues);
        BigDecimal[] actual = {null};

        Runnable vista = () -> {
            AjusteService.Motivo m = motivo.getValue();
            campoDireccion.setVisible(m != null && m.signo() == 0);
            if (m != null && m.signo() != 0) {
                direccion.setValor(m.signo() > 0 ? Direccion.ENTRA : Direccion.SALE);
            }
            campoDocumento.ayuda(m != null && m.pideDocumento() ? "Obligatorio para " + m.nombre().toLowerCase() + "."
                    : "Opcional: acta de conteo, nota o folio que respalde el ajuste.");
            campoNota.ayuda(m == AjusteService.Motivo.CORRECCION || m == AjusteService.Motivo.OTRO
                    ? "Obligatoria para este motivo: explica qué pasó." : "Opcional.");
            ProductoCatalogo p = SelectorProducto.valor(producto);
            unidad.setText(p == null ? "pzas" : Cantidades.unidadCaptura(p.unidad()));
            if (p == null || actual[0] == null) {
                existencia.setText("—");
                despues.setText(p == null ? "Elige el producto para ver su existencia." : "");
                return;
            }
            existencia.setText(Cantidades.formatear(p.unidad(), actual[0]));
            Optional<BigDecimal> c = Cantidades.desdeCaptura(p.unidad(), cantidad.getText());
            if (c.isPresent() && c.get().signum() > 0) {
                BigDecimal nueva = direccion.getValor() == Direccion.ENTRA ? actual[0].add(c.get()) : actual[0].subtract(c.get());
                despues.setText("Queda en " + Cantidades.formatear(p.unidad(), nueva)
                        + (nueva.signum() < 0 ? " (no alcanza: no se puede sacar más de lo que hay)" : ""));
            } else {
                despues.setText("");
            }
        };
        Runnable cargarExistencia = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            Sucursal s = lugar.getValue();
            actual[0] = null;
            vista.run();
            if (p == null || s == null) {
                return;
            }
            Cargador.ejecutar(() -> a.admin().almacen().existencia(p.id(), s.id()), ex -> {
                actual[0] = ex;
                vista.run();
            }, e -> vista.run());
        };
        producto.valueProperty().addListener((o, x, y) -> {
            campoProducto.setError(null);
            cargarExistencia.run();
        });
        lugar.valueProperty().addListener((o, x, y) -> cargarExistencia.run());
        motivo.valueProperty().addListener((o, x, y) -> vista.run());
        direccion.valorProperty().addListener((o, x, y) -> vista.run());
        cantidad.textProperty().addListener((o, x, y) -> {
            campoCantidad.setError(null);
            vista.run();
        });
        documento.textProperty().addListener((o, x, y) -> campoDocumento.setError(null));
        nota.textProperty().addListener((o, x, y) -> campoNota.setError(null));
        cargarExistencia.run();

        d.setContenido(Formulario.fila(campoLugar, Formulario.campo("Motivo", motivo)), campoProducto,
                Formulario.fila(campoDireccion, campoCantidad), resumen, campoDocumento, campoNota,
                Formulario.nota("mdi2i-information-outline", "Lo que sale se toma de los lotes más antiguos; lo que entra, "
                        + "del lote más reciente. Un ajuste no reemplaza una compra, un surtido ni una devolución.", null));
        Runnable guardar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            AjusteService.Motivo m = motivo.getValue();
            boolean valido = true;
            if (p == null) {
                campoProducto.setError("Elige el producto.");
                valido = false;
            }
            Optional<BigDecimal> c = p == null ? Optional.empty() : DialogoCompra.base(p, cantidad.getText());
            if (p != null && c.isEmpty()) {
                campoCantidad.setError("Escribe una cantidad mayor a cero.");
                valido = false;
            }
            if (m != null && m.pideDocumento() && documento.getText().isBlank()) {
                campoDocumento.setError("Escribe el documento de la " + m.nombre().toLowerCase() + ".");
                valido = false;
            }
            if ((m == AjusteService.Motivo.CORRECCION || m == AjusteService.Motivo.OTRO) && nota.getText().isBlank()) {
                campoNota.setError("Explica el ajuste.");
                valido = false;
            }
            Sucursal s = lugar.getValue();
            if (!valido || s == null || m == null) {
                return;
            }
            BigDecimal conSigno = direccion.getValor() == Direccion.SALE ? c.get().negate() : c.get();
            String doc = documento.getText();
            String texto = nota.getText();
            a.guardar(d, "Ajuste registrado: " + p.nombre(), () -> a.ctx().ajustes().registrar(ajusteId, s, p, m, conSigno,
                    null, doc, texto, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar ajuste", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(productoFijo == null ? producto : cantidad);
        d.vigilarCambios(lugar, producto, motivo, direccion, cantidad, documento, nota);
        a.dialogos().mostrar(d);
    }

    // ------------------------------------------------------------------ transformaciones

    private VBox construirTransformaciones() {
        barraTransformaciones = new BarraHerramientas();
        barraTransformaciones.buscador("Buscar folio o producto");
        barraTransformaciones.setAlCambiar(this::filtrarTransformaciones);
        transformaciones = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        transformaciones.setClave(TransformacionService.Transformacion::id);
        transformaciones.fecha("Fecha", TransformacionService.Transformacion::fecha, Formatos.FECHA_HORA).ancho(142);
        transformaciones.dosLineas("Folio", TransformacionService.Transformacion::folio,
                TransformacionService.Transformacion::sucursal).ancho(180);
        transformaciones.dosLineas("Se usó", TransformacionService.Transformacion::origen,
                t -> Cantidades.formatear(t.unidadOrigen(), t.cantidadOrigen())).flexible(180);
        transformaciones.dosLineas("Se obtuvo", TransformacionService.Transformacion::destino,
                t -> Cantidades.formatear(t.unidadDestino(), t.cantidadDestino())).flexible(180);
        transformaciones.formato("Merma", TransformacionService.Transformacion::merma,
                (t, v) -> Cantidades.formatear(t.unidadOrigen(), v) + " (" + porcentaje(v, t.cantidadOrigen()) + ")")
                .numero().ancho(150).ayuda("Lo consumido que no quedó en el producto nuevo.");
        transformaciones.formato("Costo", TransformacionService.Transformacion::costoCentavos,
                        (t, v) -> Dinero.formatear(v)).numero().ancho(120)
                .ayuda("Costo de lo consumido; pasa completo al producto nuevo (incluida la merma).");
        transformaciones.formato("Costo nuevo", TransformacionService.Transformacion::costoUnitarioDestino,
                (t, v) -> Dinero.formatear(v) + Formatos.porUnidad(t.unidadDestino())).numero().ancho(140);
        transformaciones.texto("Registró", TransformacionService.Transformacion::usuario).ancho(170).oculta();
        transformaciones.setVacio(new EstadoVacio("mdi2s-swap-horizontal", "No hay transformaciones en estas fechas.",
                "Convierte un producto en otro (p. ej. queso entero en paquetes) con «Nueva transformación».")
                .accion("Nueva transformación", "mdi2p-plus", this::transformar));
        transformaciones.setSinResultados(EstadoVacio.sinResultados("No encontramos transformaciones con esa búsqueda.",
                barraTransformaciones::limpiar));
        VBox panel = Ui.panelTabla(barraTransformaciones, transformaciones);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return new VBox(panel);
    }

    private static String porcentaje(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return "0 %";
        }
        return parte.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString() + " %";
    }

    private void filtrarTransformaciones() {
        String t = barraTransformaciones.busqueda();
        transformaciones.setFiltro(x -> t.isEmpty() || Texto.contiene(x.folio(), t) || Texto.contiene(x.origen(), t)
                || Texto.contiene(x.destino(), t));
        barraTransformaciones.setResultados(Texto.plural(transformaciones.mostrados(), "transformación", "transformaciones"));
    }

    private void transformar() {
        Dialogo d = new Dialogo("Nueva transformación",
                "El producto de origen sale de sus lotes y el nuevo entra con todo su costo; la merma queda registrada.",
                "mdi2s-swap-horizontal", Dialogo.Tono.ACENTO);
        d.setPrefWidth(660);
        String id = Ids.nuevo();
        ComboBox<Sucursal> lugar = new ComboBox<>(FXCollections.observableArrayList(ubicaciones));
        lugar.setConverter(Combos.sucursal());
        lugar.setMaxWidth(Double.MAX_VALUE);
        lugar.setValue(ubicaciones.isEmpty() ? null : ubicaciones.getFirst());
        ComboBox<ProductoCatalogo> origen = SelectorProducto.crear(productos);
        origen.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoOrigen = Formulario.campo("Producto que se usa", origen);
        TextField cantidadOrigen = new TextField();
        Label unidadOrigen = new Label("g");
        Formulario.Campo campoCantidadOrigen = Formulario.campo("Cantidad que se usa", conUnidad(cantidadOrigen, unidadOrigen));
        ComboBox<ProductoCatalogo> destino = SelectorProducto.crear(productos);
        destino.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoDestino = Formulario.campo("Producto que se obtiene", destino);
        TextField cantidadDestino = new TextField();
        Label unidadDestino = new Label("pzas");
        Formulario.Campo campoCantidadDestino = Formulario.campo("Cantidad que se obtiene",
                conUnidad(cantidadDestino, unidadDestino));
        TextField equivalencia = new TextField();
        Label unidadEquivalencia = new Label("g");
        Formulario.Campo campoEquivalencia = Formulario.campo("Cada unidad obtenida lleva",
                conUnidad(equivalencia, unidadEquivalencia)).ayuda("Del producto que se usa. Ej. un paquete de 500 g lleva 500 g.");
        for (TextField campo : List.of(cantidadOrigen, cantidadDestino, equivalencia)) {
            Campos.soloNumeros(campo, 0);
            campo.setAlignment(Pos.CENTER_RIGHT);
            campo.setPromptText("0");
        }
        Label merma = new Label("—");
        Label detalleMerma = Ui.texto("", "campo-ayuda");
        VBox resumen = Formulario.resumen("Merma del proceso", merma, detalleMerma);
        GridPane precios = new GridPane(12, 8);
        Map<String, CampoDinero> camposPrecio = new HashMap<>();
        VBox seccionPrecios = Formulario.seccionSeparada("Precio de venta en la sucursal",
                "El lote nuevo se vende con estos precios.", precios);
        seccionPrecios.managedProperty().bind(seccionPrecios.visibleProperty());
        TextField nota = new TextField();
        nota.setPromptText("Opcional");

        Runnable[] vista = new Runnable[1];
        vista[0] = () -> {
            ProductoCatalogo o = SelectorProducto.valor(origen);
            ProductoCatalogo dd = SelectorProducto.valor(destino);
            unidadOrigen.setText(o == null ? "g" : Cantidades.unidadCaptura(o.unidad()));
            unidadEquivalencia.setText(o == null ? "g" : Cantidades.unidadCaptura(o.unidad()));
            unidadDestino.setText(dd == null ? "pzas" : Cantidades.unidadCaptura(dd.unidad()));
            campoEquivalencia.etiqueta().setText(dd != null && dd.unidad() == Unidad.KG ? "Cada kilo obtenido lleva"
                    : "Cada pieza obtenida lleva");
            Optional<BigDecimal> co = o == null ? Optional.empty() : Cantidades.desdeCaptura(o.unidad(), cantidadOrigen.getText());
            Optional<BigDecimal> cd = dd == null ? Optional.empty() : Cantidades.desdeCaptura(dd.unidad(), cantidadDestino.getText());
            Optional<BigDecimal> eq = equivalencia(o, dd, equivalencia.getText());
            if (co.isEmpty() || cd.isEmpty() || eq.isEmpty()) {
                merma.setText("—");
                detalleMerma.setText("Escribe las cantidades para calcularla.");
                return;
            }
            BigDecimal m = TransformacionService.merma(co.get(), cd.get(), eq.get());
            merma.setText(Cantidades.formatear(o.unidad(), m.max(BigDecimal.ZERO)) + " · " + porcentaje(m.max(BigDecimal.ZERO), co.get()));
            detalleMerma.setText(m.signum() < 0 ? "No alcanza: para " + Cantidades.formatear(dd.unidad(), cd.get())
                    + " se necesitan " + Cantidades.formatear(o.unidad(), cd.get().multiply(eq.get())) + "."
                    : "Se usan " + Cantidades.formatear(o.unidad(), co.get()) + " y quedan "
                    + Cantidades.formatear(o.unidad(), cd.get().multiply(eq.get()).setScale(3, RoundingMode.HALF_UP))
                    + " en el producto nuevo. Su costo absorbe la merma.");
        };
        Runnable alCambiarDestino = () -> {
            ProductoCatalogo dd = SelectorProducto.valor(destino);
            ProductoCatalogo o = SelectorProducto.valor(origen);
            Sucursal s = lugar.getValue();
            precios.getChildren().clear();
            camposPrecio.clear();
            seccionPrecios.setVisible(dd != null && s != null && !s.esAlmacen());
            if (dd != null && o != null && o.unidad() == Unidad.KG && dd.unidad() == Unidad.PZA
                    && dd.gramajeGramos() != null && dd.gramajeGramos().signum() > 0 && equivalencia.getText().isBlank()) {
                equivalencia.setText(dd.gramajeGramos().setScale(0, RoundingMode.HALF_UP).toPlainString());
            }
            if (dd == null || s == null || s.esAlmacen()) {
                vista[0].run();
                return;
            }
            Map<String, Long> sugeridos = a.admin().surtidos().preciosSugeridos(s.id(), dd.id());
            int fila = 0;
            for (Presentacion p : dd.presentaciones()) {
                if (!p.activo()) {
                    continue;
                }
                CampoDinero campo = new CampoDinero().normal();
                campo.setPrefColumnCount(8);
                Long sugerido = sugeridos.get(p.id());
                if (sugerido != null && sugerido > 0) {
                    campo.setCentavos(sugerido);
                }
                camposPrecio.put(p.id(), campo);
                precios.addRow(fila++, Ui.texto(p.nombre(), "celda-principal"), campo);
            }
            vista[0].run();
        };
        origen.valueProperty().addListener((o, x, y) -> {
            campoOrigen.setError(null);
            alCambiarDestino.run();
        });
        destino.valueProperty().addListener((o, x, y) -> {
            campoDestino.setError(null);
            alCambiarDestino.run();
        });
        lugar.valueProperty().addListener((o, x, y) -> alCambiarDestino.run());
        for (TextField campo : List.of(cantidadOrigen, cantidadDestino, equivalencia)) {
            campo.textProperty().addListener((o, x, y) -> vista[0].run());
        }
        alCambiarDestino.run();

        d.setContenido(Formulario.campo("Ubicación", lugar),
                Formulario.seccion(null, null, Formulario.fila(campoOrigen, campoCantidadOrigen),
                        Formulario.fila(campoDestino, campoCantidadDestino), campoEquivalencia),
                resumen, seccionPrecios, Formulario.opcional("Nota", nota));
        Runnable guardar = () -> {
            ProductoCatalogo o = SelectorProducto.valor(origen);
            ProductoCatalogo dd = SelectorProducto.valor(destino);
            boolean valido = true;
            if (o == null) {
                campoOrigen.setError("Elige el producto que se usa.");
                valido = false;
            }
            if (dd == null) {
                campoDestino.setError("Elige el producto que se obtiene.");
                valido = false;
            }
            Optional<BigDecimal> co = o == null ? Optional.empty() : DialogoCompra.base(o, cantidadOrigen.getText());
            Optional<BigDecimal> cd = dd == null ? Optional.empty() : DialogoCompra.base(dd, cantidadDestino.getText());
            Optional<BigDecimal> eq = equivalencia(o, dd, equivalencia.getText());
            if (o != null && co.isEmpty()) {
                campoCantidadOrigen.setError("Escribe cuánto se usa.");
                valido = false;
            }
            if (dd != null && cd.isEmpty()) {
                campoCantidadDestino.setError("Escribe cuánto se obtiene.");
                valido = false;
            }
            if (eq.isEmpty()) {
                campoEquivalencia.setError("Escribe cuánto lleva cada unidad.");
                valido = false;
            }
            Sucursal s = lugar.getValue();
            if (!valido || s == null) {
                return;
            }
            Map<String, Long> preciosDestino = new HashMap<>();
            camposPrecio.forEach((k, v) -> preciosDestino.put(k, v.centavosOCero()));
            String texto = nota.getText();
            a.guardar(d, "Transformación registrada: " + dd.nombre(), () -> a.ctx().transformaciones().registrar(id, s, o,
                    co.get(), dd, cd.get(), eq.get(), preciosDestino, texto, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar transformación", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(origen);
        d.vigilarCambios(lugar, origen, cantidadOrigen, destino, cantidadDestino, equivalencia, nota);
        a.dialogos().mostrar(d);
    }

    /** Unidades base del origen por unidad base del destino (gramos o piezas capturados). */
    private static Optional<BigDecimal> equivalencia(ProductoCatalogo origen, ProductoCatalogo destino, String texto) {
        if (origen == null || destino == null) {
            return Optional.empty();
        }
        return Cantidades.desdeCaptura(origen.unidad(), texto).filter(v -> v.signum() > 0).map(BigDecimal::stripTrailingZeros);
    }

    private static HBox conUnidad(TextField campo, Label unidad) {
        unidad.getStyleClass().add("unidad-campo");
        HBox h = new HBox(8, campo, unidad);
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(campo, Priority.ALWAYS);
        return h;
    }

    // ------------------------------------------------------------------ existencias negativas

    private VBox construirNegativos() {
        negativos = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        negativos.setClave(n -> n.sucursalId() + n.productoId());
        negativos.dosLineas("Producto", ReconciliacionService.Negativo::producto, ReconciliacionService.Negativo::sucursal)
                .flexible(220);
        negativos.cantidad("Existencia", ReconciliacionService.Negativo::existencia, ReconciliacionService.Negativo::unidad)
                .ancho(140);
        negativos.numero("Lotes en negativo", ReconciliacionService.Negativo::lotesNegativos, String::valueOf).ancho(150);
        negativos.etiqueta("Qué hacer", n -> n.soloLotes() ? 0 : 1,
                v -> v == 0 ? "Reacomodar lotes" : "Contar y ajustar",
                v -> v == 0 ? Etiqueta.Tono.AVISO : Etiqueta.Tono.PELIGRO).ancho(170);
        negativos.acciones(
                Accion.<ReconciliacionService.Negativo>boton("mdi2s-swap-vertical", "Reacomodar lotes", this::reacomodar)
                        .si(ReconciliacionService.Negativo::soloLotes),
                Accion.<ReconciliacionService.Negativo>boton("mdi2c-clipboard-edit-outline", "Registrar ajuste",
                        n -> ajustar(ubicaciones.stream().filter(s -> s.id().equals(n.sucursalId())).findFirst().orElse(null),
                                productos.stream().filter(p -> p.id().equals(n.productoId())).findFirst().orElse(null),
                                AjusteService.Motivo.CORRECCION)).si(n -> !n.soloLotes()));
        negativos.setVacio(new EstadoVacio("mdi2c-check-circle-outline", "Sin existencias negativas.",
                "Todo cuadra: ningún producto ni lote quedó por debajo de cero.").compacto());
        VBox panel = Ui.panelTabla(null, negativos);
        VBox.setVgrow(panel, Priority.ALWAYS);
        HBox nota = Formulario.nota("mdi2i-information-outline",
                "Sin conexión, dos cajas pueden vender las mismas últimas piezas. Al sincronizar, la existencia puede "
                        + "quedar negativa. No se corrige sola: si solo un lote quedó en negativo se reacomoda; si el total "
                        + "es negativo, cuenta el producto y registra el ajuste.", "aviso");
        return new VBox(Diseno.ESPACIO_M, nota, panel);
    }

    private void reacomodar(ReconciliacionService.Negativo n) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Reacomodar los lotes de " + n.producto() + "?",
                "El faltante de los lotes en negativo pasa a los lotes más antiguos con existencia en " + n.sucursal()
                        + ". La existencia total no cambia.", "Reacomodar", false,
                () -> a.enFondo("Lotes reacomodados", () -> a.ctx().reconciliacion().reacomodarLotes(n.sucursalId(),
                        n.productoId(), a.usuario()), this::cargar));
    }

    // ------------------------------------------------------------------ carga

    private record Datos(List<Sucursal> ubicaciones, List<ProductoCatalogo> productos, List<AjusteService.Ajuste> ajustes,
                         List<TransformacionService.Transformacion> transformaciones,
                         List<ReconciliacionService.Negativo> negativos) {
    }

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        LocalDate d = desde.getValue();
        LocalDate h = hasta.getValue();
        if (d == null || h == null) {
            return;
        }
        if (d.isAfter(h)) {
            a.avisos().advertencia("El periodo no es válido: «del» es después de «al».");
            return;
        }
        Sucursal filtro = fija != null ? fija : ubicacion.getValue();
        String sucursalId = filtro == null ? null : filtro.id();
        ajustes.cargando("Cargando ajustes…");
        carga.cargar(() -> new Datos(fija != null ? List.of(fija) : a.admin().sucursales().ubicaciones(),
                a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList(),
                a.ctx().ajustes().listar(sucursalId, d, h, a.usuario()),
                a.ctx().transformaciones().listar(sucursalId, d, h, a.usuario()),
                fija != null ? List.of() : a.ctx().reconciliacion().negativos(a.usuario())), datos -> {
            ubicaciones = datos.ubicaciones();
            productos = datos.productos();
            List<Sucursal> opciones = new ArrayList<>();
            opciones.add(TODAS);
            opciones.addAll(ubicaciones);
            if (!opciones.equals(ubicacion.getItems())) {
                Sucursal elegida = ubicacion.getValue();
                ubicacion.setItems(FXCollections.observableArrayList(opciones));
                ubicacion.setValue(elegida == null || elegida.id() == null ? TODAS : opciones.stream()
                        .filter(s -> Objects.equals(s.id(), elegida.id())).findFirst().orElse(TODAS));
            }
            ajustes.setDatos(datos.ajustes());
            transformaciones.setDatos(datos.transformaciones());
            negativos.setDatos(datos.negativos());
            Pestanas.setContador(pestanaNegativos, datos.negativos().size(), true);
            filtrarAjustes();
            filtrarTransformaciones();
        }, e -> {
            if (ajustes.sinDatos()) {
                ajustes.error("No se pudieron cargar los ajustes.", AdminContexto.mensaje(e), this::cargar);
            } else {
                a.avisos().errorConAccion("No se pudieron actualizar los ajustes: " + AdminContexto.mensaje(e),
                        "Reintentar", this::cargar);
            }
        });
    }
}
