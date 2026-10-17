package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.admin.CompraService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compras a proveedores (entran al almacén por lotes con su costo), proveedores, devoluciones a
 * proveedor y la política con la que se valúa la mercancía bonificada.
 */
public class ComprasPagina extends Pagina {

    private static final CompraService.Proveedor TODOS = new CompraService.Proveedor(null, "Todos los proveedores",
            null, null, null, null, true);

    private final Cargador carga = new Cargador();
    private final Cargador cargaDetalle = new Cargador();
    private ToggleButton pestanaCompras;
    private ToggleButton pestanaProveedores;
    private ToggleButton pestanaDevoluciones;
    private Button principal;
    private DatePicker desde;
    private DatePicker hasta;
    private BarraHerramientas barraCompras;
    private ComboBox<CompraService.Proveedor> filtroProveedor;
    private TablaDatos<CompraService.Resumen> compras;
    private ResumenMetricas.Metrica metricaTotal;
    private ResumenMetricas.Metrica metricaCompras;
    private ResumenMetricas.Metrica metricaBonificadas;
    private ResumenMetricas.Metrica metricaDevuelto;
    private Segmentado<CompraService.PoliticaBonificacion> politica;
    private boolean cambiandoPolitica;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;
    private BarraHerramientas barraProveedores;
    private TablaDatos<CompraService.Proveedor> proveedores;
    private BarraHerramientas barraDevoluciones;
    private TablaDatos<CompraService.DevolucionResumen> devoluciones;

    public ComprasPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Compras";
    }

    @Override
    public TextField busqueda() {
        BarraHerramientas barra = pestanaCompras.isSelected() ? barraCompras
                : pestanaProveedores.isSelected() ? barraProveedores : barraDevoluciones;
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        principal = Ui.principal("Nueva compra", "mdi2c-cart-arrow-down");
        principal.setOnAction(e -> {
            if (pestanaProveedores.isSelected()) {
                DialogoCompra.proveedor(a, null, this::cargar);
            } else if (pestanaDevoluciones.isSelected()) {
                devolver(null);
            } else {
                nuevaCompra();
            }
        });
        VBox pagina = Ui.pagina("Compras a proveedores",
                "Lo que entra al almacén con su proveedor, nota y costo; y lo que se le regresa.", principal);

        Pestanas pestanas = new Pestanas();
        pestanaCompras = pestanas.agregar("Compras");
        pestanaProveedores = pestanas.agregar("Proveedores");
        pestanaDevoluciones = pestanas.agregar("Devoluciones a proveedor");

        desde = new DatePicker(LocalDate.now().minusDays(30));
        hasta = new DatePicker(LocalDate.now());
        for (DatePicker p : List.of(desde, hasta)) {
            p.setEditable(false);
            p.setPrefWidth(150);
            p.valueProperty().addListener((o, x, y) -> cargar());
        }

        VBox vistaCompras = construirCompras();
        VBox vistaProveedores = construirProveedores();
        VBox vistaDevoluciones = construirDevoluciones();
        StackPane zona = new StackPane(vistaCompras);
        pestanas.seleccionProperty().addListener((o, x, y) -> {
            zona.getChildren().setAll(pestanaCompras.isSelected() ? vistaCompras
                    : pestanaProveedores.isSelected() ? vistaProveedores : vistaDevoluciones);
            // Las fechas se comparten entre compras y devoluciones.
            HBox periodo = periodo();
            if (pestanaCompras.isSelected()) {
                barraCompras.agregar(periodo);
            } else if (pestanaDevoluciones.isSelected()) {
                barraDevoluciones.agregar(periodo);
            }
            principal.setText(pestanaProveedores.isSelected() ? "Nuevo proveedor"
                    : pestanaDevoluciones.isSelected() ? "Devolver a proveedor" : "Nueva compra");
        });
        barraCompras.agregar(periodo());
        pagina.getChildren().addAll(pestanas, zona);
        return Ui.ajustable(pagina, zona, 320);
    }

    private HBox periodoActual;

    /** Del … al … (se mueve a la barra de la pestaña visible). */
    private HBox periodo() {
        if (periodoActual == null) {
            Label del = new Label("Del");
            del.getStyleClass().add("etiqueta-filtro");
            Label al = new Label("al");
            al.getStyleClass().add("etiqueta-filtro");
            periodoActual = new HBox(8, del, desde, al, hasta);
            periodoActual.setAlignment(Pos.CENTER_LEFT);
        }
        if (periodoActual.getParent() instanceof javafx.scene.layout.Pane padre) {
            padre.getChildren().remove(periodoActual);
        }
        return periodoActual;
    }

    // ------------------------------------------------------------------ compras

    private VBox construirCompras() {
        ResumenMetricas resumen = new ResumenMetricas();
        metricaTotal = resumen.agregar("Comprado").principal();
        metricaCompras = resumen.agregar("Compras");
        metricaBonificadas = resumen.agregar("Con bonificación");
        metricaDevuelto = resumen.agregar("Devuelto a proveedores");

        barraCompras = new BarraHerramientas();
        barraCompras.buscador("Buscar folio, nota o proveedor");
        filtroProveedor = barraCompras.filtro("Proveedor", new ComboBox<>(), TODOS, CompraService.Proveedor::nombre);
        filtroProveedor.setValue(TODOS);
        filtroProveedor.setPrefWidth(220);
        barraCompras.setAlCambiar(this::filtrarCompras);

        compras = new TablaDatos<>(Diseno.Densidad.NORMAL);
        compras.setClave(CompraService.Resumen::id);
        compras.fecha("Fecha", CompraService.Resumen::fecha, Formatos.FECHA_HORA).ancho(142);
        compras.dosLineas("Folio", CompraService.Resumen::folio,
                c -> c.documento() == null ? null : "Nota " + c.documento()).ancho(170);
        compras.texto("Proveedor", CompraService.Resumen::proveedor).flexible(180);
        var columnaLineas = compras.numero("Productos", CompraService.Resumen::lineas, String::valueOf).ancho(96);
        compras.dinero("Total", CompraService.Resumen::totalCentavos).ancho(124);
        var columnaBonificacion = compras.etiqueta("Bonificación", c -> c.conBonificacion() ? c.politica().nombre() : null,
                t -> t == null ? null : "Con bonificación", t -> Etiqueta.Tono.INFO).ancho(150);
        compras.texto("Registró", CompraService.Resumen::usuario).ancho(170).oculta();
        var columnaAcciones = compras.acciones(
                Accion.<CompraService.Resumen>boton("mdi2e-eye-outline", "Ver detalle", c -> {
                    compras.tabla().getSelectionModel().select(c);
                    mostrarDetalle(c);
                    maestro.abrir();
                }),
                Accion.<CompraService.Resumen>menu("mdi2k-keyboard-return", c -> "Devolver al proveedor", this::devolver));
        compras.setVacio(new EstadoVacio("mdi2c-cart-outline", "No hay compras en estas fechas.",
                "Registra la mercancía que llega de tus proveedores con «Nueva compra».")
                .accion("Nueva compra", "mdi2p-plus", this::nuevaCompra));
        compras.setSinResultados(EstadoVacio.sinResultados("No encontramos compras con estos filtros.", barraCompras::limpiar));
        compras.setAlActivar(c -> {
            mostrarDetalle(c);
            maestro.abrir();
        });
        compras.seleccionadoProperty().addListener((o, x, c) -> {
            if (c != null && maestro.estaAbierto()) {
                mostrarDetalle(c);
            }
        });
        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barraCompras, compras), detalle, 640);
        maestro.setAlCerrar(() -> {
            cargaDetalle.cancelar();
            compras.tabla().requestFocus();
        });
        maestro.setAnchoCompleto(compras.anchoNecesario());
        maestro.tablaReducidaProperty().addListener((o, x, reducida) -> {
            columnaLineas.columna().setVisible(!reducida);
            columnaBonificacion.columna().setVisible(!reducida);
            columnaAcciones.columna().setVisible(!reducida);
        });

        politica = new Segmentado<>();
        for (CompraService.PoliticaBonificacion p : CompraService.PoliticaBonificacion.values()) {
            politica.opcion(p, p == CompraService.PoliticaBonificacion.PRORRATEAR ? "Repartir el costo" : "Costo cero");
        }
        politica.setValor(null);
        politica.valorProperty().addListener((o, antes, ahora) -> cambiarPolitica(antes, ahora));
        Tooltip.install(politica, new Tooltip("Repartir: el costo pagado se divide entre todo lo recibido.\n"
                + "Costo cero: lo regalado entra en un lote aparte sin costo."));
        Label etiquetaPolitica = Ui.texto("Mercancía bonificada (regalada por el proveedor):", "etiqueta-filtro");
        HBox filaPolitica = new HBox(10, etiquetaPolitica, politica);
        filaPolitica.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(maestro, Priority.ALWAYS);
        return new VBox(Diseno.ESPACIO_L, resumen, filaPolitica, maestro);
    }

    private void cambiarPolitica(CompraService.PoliticaBonificacion antes, CompraService.PoliticaBonificacion ahora) {
        if (cambiandoPolitica || antes == null || ahora == null || antes == ahora) {
            return;
        }
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Cambiar cómo se valúa lo bonificado?",
                "Desde ahora: " + ahora.nombre().toLowerCase() + ". Las compras ya registradas conservan sus costos; "
                        + "el cambio aplica a las siguientes.", "Cambiar",
                false, () -> a.enFondo("Política de bonificación actualizada",
                        () -> a.admin().compras().cambiarPolitica(ahora, a.usuario()), this::cargar));
        // Si no se confirma, el control vuelve a mostrar la política vigente al recargar.
        cambiandoPolitica = true;
        politica.setValor(antes);
        cambiandoPolitica = false;
    }

    private void filtrarCompras() {
        String t = barraCompras.busqueda();
        CompraService.Proveedor p = filtroProveedor.getValue() == null ? TODOS : filtroProveedor.getValue();
        compras.setFiltro(c -> (p.id() == null || p.id().equals(c.proveedorId()))
                && (t.isEmpty() || Texto.contiene(c.folio(), t) || Texto.contiene(c.documento(), t)
                || Texto.contiene(c.proveedor(), t)));
        List<CompraService.Resumen> visibles = compras.visibles();
        metricaTotal.setValor(Dinero.formatear(visibles.stream().mapToLong(CompraService.Resumen::totalCentavos).sum()))
                .setContexto(periodoTexto());
        metricaCompras.setValor(String.valueOf(visibles.size()))
                .setContexto(Texto.plural(visibles.stream().mapToInt(CompraService.Resumen::lineas).sum(), "línea", "líneas"));
        long conBonificacion = visibles.stream().filter(CompraService.Resumen::conBonificacion).count();
        metricaBonificadas.setValor(String.valueOf(conBonificacion)).setContexto("Compras con mercancía regalada");
        barraCompras.setResultados(compras.mostrados() == compras.total() ? Texto.plural(compras.total(), "compra", "compras")
                : compras.mostrados() + " de " + Texto.plural(compras.total(), "compra", "compras"));
    }

    private String periodoTexto() {
        return desde.getValue() == null || hasta.getValue() == null ? ""
                : Formatos.FECHA_CORTA.format(desde.getValue()) + " – " + Formatos.FECHA_CORTA.format(hasta.getValue());
    }

    private void mostrarDetalle(CompraService.Resumen c) {
        detalle.setTitulo("Compra " + c.folio(), c.proveedor() + " · " + Formatos.fechaHora(c.fecha()));
        List<Node> etiquetas = new ArrayList<>();
        if (c.conBonificacion()) {
            etiquetas.add(Etiqueta.de("Con bonificación", Etiqueta.Tono.INFO));
            etiquetas.add(Etiqueta.simple(c.politica() == CompraService.PoliticaBonificacion.PRORRATEAR
                    ? "Costo repartido" : "Regalo a costo cero", Etiqueta.Tono.NEUTRO));
        }
        detalle.setEtiquetas(etiquetas.toArray(Node[]::new));
        VBox cifras = new VBox(6, PanelDetalle.cifra("Total de la compra", Dinero.formatear(c.totalCentavos()), null),
                PanelDetalle.dato("Nota o factura", c.documento() == null ? "—" : c.documento()),
                PanelDetalle.dato("Fecha de la nota", c.fechaDocumento() == null ? "—"
                        : Formatos.FECHA_CORTA.format(c.fechaDocumento())),
                PanelDetalle.dato("Registró", c.usuario()));
        VBox lineas = PanelDetalle.seccion("Productos", Ui.texto("Cargando…", "texto-secundario"));
        detalle.setContenido(cifras, lineas);
        Button devolver = Ui.secundario("Devolver al proveedor", "mdi2k-keyboard-return");
        devolver.setOnAction(e -> devolver(c));
        detalle.setAcciones(devolver);
        cargaDetalle.cargar(() -> a.admin().compras().detalle(c.id()), d -> {
            List<Node> filas = new ArrayList<>();
            for (CompraService.DetalleLinea l : d.lineas()) {
                String unidad = l.presentacion() == null ? (l.unidad() == Unidad.KG ? "kg" : "pzas") : l.presentacion();
                String cantidades = l.cantidadPagada().stripTrailingZeros().toPlainString() + " " + unidad
                        + (l.cantidadBonificada().signum() > 0 ? " + " + l.cantidadBonificada().stripTrailingZeros()
                        .toPlainString() + " de regalo" : "") + " · " + Dinero.formatear(l.costoUnitarioCentavos()) + " c/u";
                String entro = "Entró " + Cantidades.formatear(l.unidad(), l.recibidaBase()) + " a "
                        + Dinero.formatear(l.costoBaseCentavos()) + Formatos.porUnidad(l.unidad())
                        + (l.loteBonificadoId() == null ? "" : " (lo regalado, en lote aparte sin costo)");
                filas.add(PanelDetalle.filaLista(l.producto(), cantidades + "\n" + entro,
                        Dinero.formatear(l.importeCentavos()), null));
            }
            List<Node> contenido = new ArrayList<>();
            contenido.add(lineas.getChildren().getFirst());
            contenido.add(filas.isEmpty() ? Ui.texto("Sin productos.", "texto-secundario") : PanelDetalle.lista(filas));
            if (d.notas() != null && !d.notas().isBlank()) {
                contenido.add(Ui.texto("Notas: " + d.notas(), "campo-ayuda"));
            }
            lineas.getChildren().setAll(contenido);
        }, e -> lineas.getChildren().set(1, Ui.texto("No se pudo cargar el detalle: " + AdminContexto.mensaje(e),
                "texto-error")));
    }

    private void nuevaCompra() {
        Cargador.ejecutar(() -> new Object[]{a.admin().compras().proveedores(), a.admin().compras().politica()},
                datos -> {
                    @SuppressWarnings("unchecked")
                    List<CompraService.Proveedor> lista = (List<CompraService.Proveedor>) datos[0];
                    if (lista.stream().noneMatch(CompraService.Proveedor::activo)) {
                        DialogoConfirmacion.mostrar(a.dialogos(), "Primero agrega un proveedor",
                                "Cada compra queda ligada a su proveedor. Agrega el primero y luego registra la compra.",
                                "Agregar proveedor", false, () -> DialogoCompra.proveedor(a, null, this::cargar));
                        return;
                    }
                    DialogoCompra.mostrar(a, lista, (CompraService.PoliticaBonificacion) datos[1], this::cargar);
                }, e -> a.avisos().error("No se pudo abrir la compra: " + AdminContexto.mensaje(e)));
    }

    // ------------------------------------------------------------------ proveedores

    private VBox construirProveedores() {
        barraProveedores = new BarraHerramientas();
        barraProveedores.buscador("Buscar proveedor, RFC o contacto");
        barraProveedores.setAlCambiar(this::filtrarProveedores);
        proveedores = new TablaDatos<>(Diseno.Densidad.NORMAL);
        proveedores.setClave(CompraService.Proveedor::id);
        proveedores.dosLineas("Proveedor", CompraService.Proveedor::nombre,
                p -> p.rfc() == null ? null : "RFC " + p.rfc()).flexible(220);
        proveedores.dosLineas("Contacto", p -> p.contacto() == null ? "—" : p.contacto(),
                CompraService.Proveedor::telefono).ancho(220);
        proveedores.textoLargo("Notas", p -> p.notas() == null ? "" : p.notas()).ancho(240);
        proveedores.etiqueta("Estado", p -> p.activo() ? 0 : 1, v -> v == 0 ? "Activo" : "Inactivo",
                v -> v == 0 ? Etiqueta.Tono.EXITO : Etiqueta.Tono.NEUTRO).ancho(110);
        proveedores.acciones(
                Accion.<CompraService.Proveedor>boton("mdi2p-pencil-outline", "Editar",
                        p -> DialogoCompra.proveedor(a, p, this::cargar)),
                Accion.<CompraService.Proveedor>menu("mdi2c-cancel", p -> p.activo() ? "Deshabilitar" : "Habilitar",
                        this::cambiarActivo));
        proveedores.marcar("atenuada", p -> !p.activo());
        proveedores.setVacio(new EstadoVacio("mdi2t-truck-outline", "Aún no hay proveedores.",
                "Agrega a quienes te venden mercancía para registrar sus compras.")
                .accion("Nuevo proveedor", "mdi2p-plus", () -> DialogoCompra.proveedor(a, null, this::cargar)));
        proveedores.setSinResultados(EstadoVacio.sinResultados("No encontramos proveedores con esa búsqueda.",
                barraProveedores::limpiar));
        proveedores.setAlActivar(p -> DialogoCompra.proveedor(a, p, this::cargar));
        VBox panel = Ui.panelTabla(barraProveedores, proveedores);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return new VBox(panel);
    }

    private void filtrarProveedores() {
        String t = barraProveedores.busqueda();
        proveedores.setFiltro(p -> t.isEmpty() || Texto.contiene(p.nombre(), t) || Texto.contiene(p.rfc(), t)
                || Texto.contiene(p.contacto(), t));
        barraProveedores.setResultados(Texto.plural(proveedores.mostrados(), "proveedor", "proveedores"));
    }

    private void cambiarActivo(CompraService.Proveedor p) {
        boolean activar = !p.activo();
        DialogoConfirmacion.mostrar(a.dialogos(), (activar ? "¿Habilitar a " : "¿Deshabilitar a ") + p.nombre() + "?",
                activar ? "Volverá a aparecer al registrar compras."
                        : "Ya no aparecerá al registrar compras. Sus compras y devoluciones se conservan.",
                activar ? "Habilitar" : "Deshabilitar", !activar,
                () -> a.enFondo(activar ? "Proveedor habilitado" : "Proveedor deshabilitado",
                        () -> a.admin().compras().cambiarActivoProveedor(p, activar, a.usuario()), this::cargar));
    }

    // ------------------------------------------------------------------ devoluciones a proveedor

    private VBox construirDevoluciones() {
        barraDevoluciones = new BarraHerramientas();
        barraDevoluciones.buscador("Buscar folio, proveedor o motivo");
        barraDevoluciones.setAlCambiar(this::filtrarDevoluciones);
        devoluciones = new TablaDatos<>(Diseno.Densidad.NORMAL);
        devoluciones.setClave(CompraService.DevolucionResumen::id);
        devoluciones.fecha("Fecha", CompraService.DevolucionResumen::fecha, Formatos.FECHA_HORA).ancho(142);
        devoluciones.dosLineas("Folio", CompraService.DevolucionResumen::folio,
                d -> d.compraFolio() == null ? null : "De la compra " + d.compraFolio()).ancho(190);
        devoluciones.texto("Proveedor", CompraService.DevolucionResumen::proveedor).ancho(200);
        devoluciones.textoLargo("Motivo", CompraService.DevolucionResumen::motivo).flexible(200);
        devoluciones.etiqueta("Compensación", CompraService.DevolucionResumen::tratamiento, CompraService.Tratamiento::nombre,
                t -> t == CompraService.Tratamiento.POR_DEFINIR ? Etiqueta.Tono.AVISO : Etiqueta.Tono.NEUTRO).ancho(210);
        devoluciones.dinero("Costo", CompraService.DevolucionResumen::costoCentavos).ancho(120)
                .ayuda("Lo que valía al costo la mercancía que salió del almacén.");
        devoluciones.texto("Registró", CompraService.DevolucionResumen::usuario).ancho(170).oculta();
        devoluciones.setVacio(new EstadoVacio("mdi2k-keyboard-return", "No hay devoluciones a proveedor en estas fechas.",
                "Si algo llegó mal o caducado, regrésalo con «Devolver a proveedor»."));
        devoluciones.setSinResultados(EstadoVacio.sinResultados("No encontramos devoluciones con esa búsqueda.",
                barraDevoluciones::limpiar));
        VBox panel = Ui.panelTabla(barraDevoluciones, devoluciones);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return new VBox(panel);
    }

    private void filtrarDevoluciones() {
        String t = barraDevoluciones.busqueda();
        devoluciones.setFiltro(d -> t.isEmpty() || Texto.contiene(d.folio(), t) || Texto.contiene(d.proveedor(), t)
                || Texto.contiene(d.motivo(), t) || Texto.contiene(d.compraFolio(), t));
        barraDevoluciones.setResultados(Texto.plural(devoluciones.mostrados(), "devolución", "devoluciones"));
    }

    /** Regresa mercancía al proveedor; {@code compra} la liga a una compra (sale de sus lotes). */
    private void devolver(CompraService.Resumen compra) {
        List<CompraService.Proveedor> lista = proveedores.datos().stream().filter(CompraService.Proveedor::activo).toList();
        Dialogo d = new Dialogo("Devolver a proveedor", compra == null ? "La mercancía sale del almacén al costo de sus lotes."
                : "De la compra " + compra.folio() + " · " + compra.proveedor(), "mdi2k-keyboard-return", Dialogo.Tono.ADVERTENCIA);
        d.setPrefWidth(640);
        String devolucionId = Ids.nuevo();
        ComboBox<CompraService.Proveedor> proveedor = new ComboBox<>(FXCollections.observableArrayList(lista));
        proveedor.setConverter(Combos.de(CompraService.Proveedor::nombre));
        proveedor.setMaxWidth(Double.MAX_VALUE);
        proveedor.setPromptText("Elige el proveedor");
        if (compra != null) {
            lista.stream().filter(p -> p.id().equals(compra.proveedorId())).findFirst().ifPresent(proveedor::setValue);
            proveedor.setDisable(true);
        }
        Formulario.Campo campoProveedor = Formulario.campo("Proveedor", proveedor);

        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(productos);
        producto.setMaxWidth(Double.MAX_VALUE);
        TextField cantidad = new TextField();
        Campos.soloNumeros(cantidad, 0);
        cantidad.setPrefColumnCount(7);
        cantidad.setAlignment(Pos.CENTER_RIGHT);
        cantidad.setPromptText("Cantidad");
        Label unidad = new Label("pzas");
        unidad.getStyleClass().add("unidad-campo");
        producto.valueProperty().addListener((o, x, p) -> unidad.setText(p == null ? "pzas" : Cantidades.unidadCaptura(p.unidad())));
        Button agregar = Ui.boton("Agregar", "mdi2p-plus");
        agregar.setMinWidth(Region.USE_PREF_SIZE);
        unidad.setMinWidth(Region.USE_PREF_SIZE);
        HBox captura = new HBox(8, producto, cantidad, unidad, agregar);
        captura.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(producto, Priority.ALWAYS);
        Label errorCaptura = Ui.texto("", "campo-error");
        errorCaptura.managedProperty().bind(errorCaptura.visibleProperty());
        errorCaptura.setVisible(false);
        List<CompraService.LineaDevolucion> lineas = new ArrayList<>();
        VBox listaLineas = new VBox(4);
        Runnable[] dibujar = new Runnable[1];
        dibujar[0] = () -> {
            listaLineas.getChildren().clear();
            for (CompraService.LineaDevolucion l : lineas) {
                Button quitar = Ui.boton(null, "mdi2t-trash-can-outline", "flat", "boton-icono", "danger");
                quitar.setOnAction(e -> {
                    lineas.remove(l);
                    dibujar[0].run();
                });
                listaLineas.getChildren().add(PanelDetalle.filaLista(l.producto().nombre(), null,
                        Cantidades.formatear(l.producto().unidad(), l.cantidad()), quitar));
            }
            if (lineas.isEmpty()) {
                listaLineas.getChildren().add(Ui.texto("Agrega lo que se regresa.", "campo-ayuda"));
            }
        };
        dibujar[0].run();
        Runnable accionAgregar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            BigDecimal c = p == null ? null : DialogoCompra.base(p, cantidad.getText()).orElse(null);
            if (p == null || c == null) {
                errorCaptura.setText(p == null ? "Elige el producto." : "Escribe la cantidad.");
                errorCaptura.setVisible(true);
                return;
            }
            errorCaptura.setVisible(false);
            lineas.removeIf(l -> l.producto().id().equals(p.id()));
            lineas.add(new CompraService.LineaDevolucion(p, null, c));
            producto.getEditor().clear();
            producto.setValue(null);
            cantidad.clear();
            d.marcarCambios();
            dibujar[0].run();
        };
        agregar.setOnAction(e -> accionAgregar.run());
        cantidad.setOnAction(e -> accionAgregar.run());

        TextField motivo = new TextField();
        motivo.setPromptText("Ej. Llegó caducado, empaque roto");
        Formulario.Campo campoMotivo = Formulario.campo("Motivo", motivo);
        ComboBox<CompraService.Tratamiento> tratamiento = new ComboBox<>(FXCollections.observableArrayList(
                CompraService.Tratamiento.values()));
        tratamiento.setValue(CompraService.Tratamiento.POR_DEFINIR);
        tratamiento.setMaxWidth(Double.MAX_VALUE);
        TextField evidencia = new TextField();
        evidencia.setPromptText("Folio de la nota de crédito, foto…");
        motivo.textProperty().addListener((o, x, y) -> campoMotivo.setError(null));

        d.setContenido(campoProveedor,
                Formulario.seccionSeparada("Qué se regresa", compra == null
                                ? "Sale de los lotes más antiguos del almacén." : "Sale de los lotes de esa compra.",
                        captura, errorCaptura, listaLineas),
                Formulario.seccionSeparada("Motivo y compensación",
                        "La compensación se acuerda con el proveedor; si aún no se sabe, queda «Por definir».",
                        campoMotivo, Formulario.fila(Formulario.campo("Compensación", tratamiento),
                                Formulario.opcional("Comprobante", evidencia))),
                Formulario.nota("mdi2a-alert-outline", "La mercancía sale del inventario del almacén al registrar.", "aviso"));
        Runnable guardar = () -> {
            boolean valido = true;
            if (proveedor.getValue() == null) {
                campoProveedor.setError("Elige el proveedor.");
                valido = false;
            }
            if (motivo.getText() == null || motivo.getText().isBlank()) {
                campoMotivo.setError("Escribe por qué se regresa.");
                valido = false;
            }
            if (lineas.isEmpty()) {
                d.mostrarError("Agrega al menos un producto.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            CompraService.Proveedor p = proveedor.getValue();
            String m = motivo.getText();
            CompraService.Tratamiento t = tratamiento.getValue();
            String ev = evidencia.getText();
            List<CompraService.LineaDevolucion> datos = List.copyOf(lineas);
            a.guardar(d, "Devolución a proveedor registrada", () -> a.admin().compras().devolverAProveedor(devolucionId, p,
                    compra == null ? null : compra.id(), datos, m, t, ev, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar devolución", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(compra == null ? proveedor : producto);
        d.vigilarCambios(proveedor, motivo, tratamiento, evidencia);
        a.dialogos().mostrar(d);
    }

    // ------------------------------------------------------------------ carga

    private record Datos(List<CompraService.Resumen> compras, List<CompraService.Proveedor> proveedores,
                         List<CompraService.DevolucionResumen> devoluciones, CompraService.PoliticaBonificacion politica) {
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
        compras.cargando("Cargando compras…");
        carga.cargar(() -> new Datos(a.admin().compras().listar(d, h, null), a.admin().compras().proveedores(),
                a.admin().compras().devolucionesProveedor(d, h), a.admin().compras().politica()), datos -> {
            CompraService.Proveedor elegido = filtroProveedor.getValue();
            List<CompraService.Proveedor> opciones = new ArrayList<>();
            opciones.add(TODOS);
            opciones.addAll(datos.proveedores());
            filtroProveedor.setItems(FXCollections.observableArrayList(opciones));
            filtroProveedor.setValue(elegido == null || elegido.id() == null ? TODOS : opciones.stream()
                    .filter(p -> Objects.equals(p.id(), elegido.id())).findFirst().orElse(TODOS));
            cambiandoPolitica = true;
            politica.setValor(datos.politica());
            cambiandoPolitica = false;
            compras.setDatos(datos.compras());
            proveedores.setDatos(datos.proveedores());
            devoluciones.setDatos(datos.devoluciones());
            long devuelto = datos.devoluciones().stream().mapToLong(CompraService.DevolucionResumen::costoCentavos).sum();
            long porDefinir = datos.devoluciones().stream()
                    .filter(x -> x.tratamiento() == CompraService.Tratamiento.POR_DEFINIR).count();
            metricaDevuelto.setValor(Dinero.formatear(devuelto)).setContexto(porDefinir == 0
                    ? Texto.plural(datos.devoluciones().size(), "devolución", "devoluciones")
                    : Texto.plural(porDefinir, "compensación por definir", "compensaciones por definir"))
                    .setTono(porDefinir > 0 ? Etiqueta.Tono.AVISO : null);
            filtrarCompras();
            filtrarProveedores();
            filtrarDevoluciones();
            if (maestro.estaAbierto() && compras.seleccionado() != null) {
                mostrarDetalle(compras.seleccionado());
            }
        }, e -> {
            if (compras.sinDatos()) {
                compras.error("No se pudieron cargar las compras.", AdminContexto.mensaje(e), this::cargar);
            } else {
                a.avisos().errorConAccion("No se pudieron actualizar las compras: " + AdminContexto.mensaje(e),
                        "Reintentar", this::cargar);
            }
        });
    }
}
