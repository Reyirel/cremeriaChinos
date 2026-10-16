package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Catálogo de productos del almacén: alta, edición, habilitación y borrado. */
public class ProductosPagina extends Pagina {

    private enum Estado {
        TODOS("Todos los estados"), ACTIVOS("Activos"), DESHABILITADOS("Deshabilitados");

        final String texto;

        Estado(String texto) {
            this.texto = texto;
        }
    }

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("d MMM yyyy", Formatos.ES);
    private static final String TODOS_TIPOS = "Todos los tipos";
    private static final String TODAS_CATEGORIAS = "Todas";
    private static final String TODAS_MARCAS = "Todas";

    private final Cargador carga = new Cargador();
    private TablaDatos<ProductoCatalogo> tabla;
    private BarraHerramientas barra;
    private ComboBox<Estado> estado;
    private ComboBox<String> tipo;
    private ComboBox<String> categoria;
    private ComboBox<String> marca;
    private CheckBox soloMerma;
    private CheckBox sinGramaje;
    private CheckBox conAviso;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;

    public ProductosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Productos";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        Button nuevo = Ui.principal("Nuevo producto", "mdi2p-plus");
        nuevo.setOnAction(e -> DialogoProducto.mostrar(a, null, this::cargar));
        VBox pagina = Ui.pagina("Productos",
                "Catálogo del almacén. Cada producto se vende en una o varias presentaciones; el precio se fija por sucursal al surtir.",
                nuevo);

        barra = new BarraHerramientas();
        barra.buscador("Buscar por nombre o código");
        estado = barra.filtro("Estado", new ComboBox<>(FXCollections.observableArrayList(Estado.values())),
                Estado.TODOS, e -> e.texto);
        estado.setValue(Estado.TODOS);
        List<String> tipos = new ArrayList<>(List.of(TODOS_TIPOS));
        for (Disponibilidad d : Disponibilidad.values()) {
            tipos.add(d.nombre());
        }
        tipo = barra.filtro("Tipo", new ComboBox<>(FXCollections.observableArrayList(tipos)), TODOS_TIPOS, t -> t);
        tipo.setValue(TODOS_TIPOS);
        categoria = barra.filtroAdicional("Categoría", new ComboBox<>(FXCollections.observableArrayList(TODAS_CATEGORIAS)),
                TODAS_CATEGORIAS, c -> c);
        categoria.setValue(TODAS_CATEGORIAS);
        marca = barra.filtroAdicional("Marca", new ComboBox<>(FXCollections.observableArrayList(TODAS_MARCAS)),
                TODAS_MARCAS, m -> m);
        marca.setValue(TODAS_MARCAS);
        soloMerma = casilla("Sujetos a merma");
        sinGramaje = casilla("Sin gramaje");
        conAviso = casilla("Con aviso propio sin venta");
        for (CheckBox c : List.of(soloMerma, sinGramaje, conAviso)) {
            barra.filtroAdicional("", c, c::isSelected, c::getText, () -> c.setSelected(false), c.selectedProperty());
        }
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        tabla.setClave(ProductoCatalogo::id);
        tabla.dosLineas("Producto", ProductoCatalogo::nombre, ProductosPagina::marcaCategoria).flexible(200);
        tabla.dosLineas("Código", p -> p.clave() == null || p.clave().isBlank() ? "—" : p.clave(),
                p -> p.codigoInventario() == null || p.codigoInventario().isBlank() ? null : "Inv. " + p.codigoInventario())
                .ancho(120).ayuda("Clave o PLU de báscula; debajo, el código de inventario del almacén.");
        tabla.dosLineas("Se vende", p -> Formatos.seVende(p.unidad()), ProductosPagina::gramaje).ancho(140);
        tabla.nodo("Presentaciones", ProductosPagina::presentaciones).ancho(210);
        var columnaEstado = tabla.nodo("Estado", ProductosPagina::estado).ancho(196);
        var columnaAcciones = tabla.acciones(
                Accion.<ProductoCatalogo>boton("mdi2p-pencil-outline", "Editar", p -> DialogoProducto.mostrar(a, p, this::cargar)),
                Accion.<ProductoCatalogo>menu("mdi2e-eye-off-outline",
                                p -> p.activo() ? "Deshabilitar (deja de venderse)" : "Habilitar", this::cambiarActivo)
                        .si(p -> p.disponibilidad() != Disponibilidad.REGULAR),
                Accion.<ProductoCatalogo>menu("mdi2t-trash-can-outline", p -> "Eliminar…", this::eliminar).peligrosa());
        tabla.setVacio(new EstadoVacio("mdi2p-package-variant-closed", "Aún no hay productos en el catálogo.",
                "Crea el primero con «Nuevo producto».")
                .accion("Nuevo producto", "mdi2p-plus", () -> DialogoProducto.mostrar(a, null, this::cargar)));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos productos con estos filtros.", barra::limpiar));
        tabla.marcar("atenuada", p -> !p.activo());
        tabla.setAlActivar(p -> {
            mostrarDetalle(p);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, p) -> {
            if (p != null && maestro.estaAbierto()) {
                mostrarDetalle(p);
            }
        });

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 680);
        maestro.setAlCerrar(() -> tabla.tabla().requestFocus());
        maestro.setAnchoCompleto(tabla.anchoNecesario());
        maestro.tablaReducidaProperty().addListener((o, x, reducida) -> {
            columnaEstado.columna().setVisible(!reducida);
            columnaAcciones.columna().setVisible(!reducida);
        });
        pagina.getChildren().add(maestro);
        return Ui.ajustable(pagina, maestro, 340);
    }

    private static CheckBox casilla(String texto) {
        CheckBox c = new CheckBox(texto);
        c.setMinWidth(CheckBox.USE_PREF_SIZE);
        return c;
    }

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        tabla.cargando("Cargando productos…");
        carga.cargar(() -> a.admin().productos().listar(), lista -> {
            actualizarOpciones(categoria, TODAS_CATEGORIAS, lista.stream().map(ProductoCatalogo::categoria).toList());
            actualizarOpciones(marca, TODAS_MARCAS, lista.stream().map(ProductoCatalogo::marca).toList());
            tabla.setDatos(lista);
            filtrar();
            if (maestro.estaAbierto()) {
                if (tabla.seleccionado() != null) {
                    mostrarDetalle(tabla.seleccionado());
                } else {
                    maestro.cerrar();
                }
            }
        }, e -> {
            if (tabla.sinDatos()) {
                tabla.error("No se pudo cargar el catálogo.", AdminContexto.mensaje(e), this::cargar);
            } else {
                a.avisos().errorConAccion("No se pudo actualizar el catálogo: " + AdminContexto.mensaje(e),
                        "Reintentar", this::cargar);
            }
        });
    }

    static void actualizarOpciones(ComboBox<String> combo, String todas, List<String> valores) {
        List<String> opciones = new ArrayList<>();
        opciones.add(todas);
        valores.stream().filter(v -> v != null && !v.isBlank()).distinct().sorted(Texto.ORDEN).forEach(opciones::add);
        if (!opciones.equals(combo.getItems())) {
            String actual = combo.getValue();
            combo.setItems(FXCollections.observableArrayList(opciones));
            combo.setValue(opciones.contains(actual) ? actual : todas);
        }
    }

    private void filtrar() {
        String t = barra.busqueda();
        Estado e = estado.getValue() == null ? Estado.TODOS : estado.getValue();
        String ti = tipo.getValue() == null ? TODOS_TIPOS : tipo.getValue();
        String cat = categoria.getValue() == null ? TODAS_CATEGORIAS : categoria.getValue();
        String mar = marca.getValue() == null ? TODAS_MARCAS : marca.getValue();
        tabla.setFiltro(p -> AlmacenPagina.coincide(p, t)
                && switch (e) {
                    case TODOS -> true;
                    case ACTIVOS -> p.activo();
                    case DESHABILITADOS -> !p.activo();
                }
                && (TODOS_TIPOS.equals(ti) || p.disponibilidad().nombre().equals(ti))
                && (TODAS_CATEGORIAS.equals(cat) || cat.equals(p.categoria()))
                && (TODAS_MARCAS.equals(mar) || mar.equals(p.marca()))
                && (!soloMerma.isSelected() || p.sujetoMerma())
                && (!sinGramaje.isSelected() || p.gramajeGramos() == null)
                && (!conAviso.isSelected() || p.avisoSinVenta() != null));
        long activos = tabla.datos().stream().filter(ProductoCatalogo::activo).count();
        barra.setResultados(tabla.mostrados() == tabla.total()
                ? Texto.plural(tabla.total(), "producto", "productos") + " · " + activos + " activos"
                : tabla.mostrados() + " de " + Texto.plural(tabla.total(), "producto", "productos"));
    }

    private static String marcaCategoria(ProductoCatalogo p) {
        String marca = p.marca() == null || p.marca().isBlank() ? null : p.marca();
        String categoria = p.categoria() == null || p.categoria().isBlank() ? "Sin categoría" : p.categoria();
        return marca == null ? categoria : marca + " · " + categoria;
    }

    /** Gramaje neto por unidad; en productos por kilo es por cada kilo. */
    private static String gramaje(ProductoCatalogo p) {
        if (p.gramajeGramos() == null) {
            return "Sin gramaje";
        }
        String porUnidad = p.unidad() == Unidad.KG ? " por kg" : "";
        return p.mermaGramos() != null && p.mermaGramos().signum() > 0
                ? Masa.formatear(p.gramajeNeto()) + " netos" + porUnidad
                : Masa.formatear(p.gramajeGramos()) + porUnidad;
    }

    /** Activo o deshabilitado y, si no es de línea, si es de temporada o edición especial. */
    private static Node estado(ProductoCatalogo p) {
        FlowPane f = new FlowPane(4, 4);
        f.setAlignment(Pos.CENTER_LEFT);
        f.getChildren().add(p.activo() ? Etiqueta.de("Activo", Etiqueta.Tono.EXITO) : Etiqueta.de("Deshabilitado", Etiqueta.Tono.NEUTRO));
        if (p.disponibilidad() != Disponibilidad.REGULAR) {
            Label tipo = Etiqueta.simple(p.disponibilidad() == Disponibilidad.TEMPORADA ? "Temporada" : "Edición especial",
                    p.disponibilidad() == Disponibilidad.TEMPORADA ? Etiqueta.Tono.INFO : Etiqueta.Tono.ACENTO);
            if (p.temporada() != null) {
                Temporada t = p.temporada();
                tipo.setTooltip(new Tooltip(FECHA.format(t.desde()) + " – " + FECHA.format(t.fin())
                        + (t.repite() ? " · se repite " + t.describirRepeticion() : "")));
            }
            f.getChildren().add(tipo);
        }
        return f;
    }

    private static Node presentaciones(ProductoCatalogo p) {
        FlowPane f = new FlowPane(4, 4);
        f.setAlignment(Pos.CENTER_LEFT);
        f.setPrefWrapLength(220);
        int mostradas = 0;
        for (Presentacion pr : p.presentaciones()) {
            if (mostradas == 3) {
                Label mas = Etiqueta.simple("+" + (p.presentaciones().size() - 3), Etiqueta.Tono.NEUTRO);
                mas.setTooltip(new Tooltip(String.join("\n", p.presentaciones().stream().map(Presentacion::nombre).toList())));
                f.getChildren().add(mas);
                break;
            }
            f.getChildren().add(pr.activo() ? Etiqueta.simple(pr.nombre(), Etiqueta.Tono.NEUTRO) : Etiqueta.tachada(pr.nombre()));
            mostradas++;
        }
        return f;
    }

    // ------------------------------------------------------------------ detalle

    private void mostrarDetalle(ProductoCatalogo p) {
        detalle.setTitulo(p.nombre(), marcaCategoria(p));
        List<Node> etiquetas = new ArrayList<>();
        etiquetas.add(p.activo() ? Etiqueta.de("Activo", Etiqueta.Tono.EXITO) : Etiqueta.de("Deshabilitado", Etiqueta.Tono.NEUTRO));
        if (p.disponibilidad() != Disponibilidad.REGULAR) {
            etiquetas.add(Etiqueta.simple(p.disponibilidad().nombre(),
                    p.disponibilidad() == Disponibilidad.TEMPORADA ? Etiqueta.Tono.INFO : Etiqueta.Tono.ACENTO));
        }
        detalle.setEtiquetas(etiquetas.toArray(Node[]::new));

        VBox venta = PanelDetalle.seccion("Venta",
                PanelDetalle.dato("Se vende", Formatos.seVende(p.unidad())),
                PanelDetalle.dato("Clave / PLU", p.clave()),
                PanelDetalle.dato("Código de inventario", p.codigoInventario()));

        List<Node> gramaje = new ArrayList<>();
        if (p.gramajeGramos() == null) {
            gramaje.add(Ui.texto("Aún no tiene gramaje: captúralo al editar el producto (lo usan las comisiones por gramaje).",
                    "campo-ayuda"));
        } else {
            String porUnidad = p.unidad() == Unidad.KG ? " por kg" : " por pieza";
            gramaje.add(PanelDetalle.dato("Gramaje", Masa.formatear(p.gramajeGramos()) + porUnidad));
            if (p.sujetoMerma()) {
                gramaje.add(PanelDetalle.dato("Merma", p.mermaGramos() == null || p.mermaGramos().signum() == 0
                        ? "Sin capturar" : Masa.formatear(p.mermaGramos())));
            }
            gramaje.add(PanelDetalle.dato("Gramaje neto", Masa.formatear(p.gramajeNeto()) + porUnidad));
        }
        if (p.sujetoMerma()) {
            gramaje.add(PanelDetalle.dato("Sujeto a merma", "Sí"));
        }
        VBox seccionGramaje = PanelDetalle.seccion("Gramaje", gramaje.toArray(Node[]::new));

        List<Node> filas = new ArrayList<>();
        for (Presentacion pr : p.presentaciones()) {
            String contenido = pr.granel() ? "A granel, por kilo" : "Contiene " + Cantidades.formatear(p.unidad(), pr.factor());
            String codigo = pr.codigoBarras() == null || pr.codigoBarras().isBlank() ? "Sin código de barras" : pr.codigoBarras();
            Node marca = !pr.activo() ? Etiqueta.simple("Inactiva", Etiqueta.Tono.NEUTRO)
                    : pr.principal() ? Etiqueta.simple("Principal", Etiqueta.Tono.ACENTO) : null;
            filas.add(PanelDetalle.filaLista(pr.nombre(), contenido + " · " + codigo, null, marca));
        }
        VBox seccionPresentaciones = PanelDetalle.seccion("Presentaciones", filas.isEmpty()
                ? Ui.texto("Sin presentaciones.", "texto-secundario") : PanelDetalle.lista(filas));

        List<Node> reglas = new ArrayList<>();
        if (p.disponibilidad() == Disponibilidad.TEMPORADA && p.temporada() != null) {
            Temporada t = p.temporada();
            reglas.add(PanelDetalle.dato("Temporada", FECHA.format(t.desde()) + " – " + FECHA.format(t.fin())));
            reglas.add(PanelDetalle.dato("Se repite", t.repite() ? t.describirRepeticion() : "No"));
            if (t.vencida(LocalDate.now())) {
                reglas.add(Ui.texto("La temporada ya terminó: al habilitarlo se piden las fechas nuevas.", "campo-ayuda"));
            }
        }
        reglas.add(PanelDetalle.dato("Aviso si no se vende", p.avisoSinVenta() == null
                ? "El de la sucursal" : p.avisoSinVenta().describir()));
        if (p.avisoSinVenta() != null) {
            reglas.add(PanelDetalle.dato("Se avisa a", p.avisoSinVenta().destinatarios()));
        }
        VBox seccionReglas = PanelDetalle.seccion("Disponibilidad y avisos", reglas.toArray(Node[]::new));

        Label id = new Label(p.id());
        id.getStyleClass().add("texto-id");
        id.setWrapText(true);
        VBox tecnico = PanelDetalle.seccion("Identificador interno", id);

        detalle.setContenido(venta, seccionGramaje, seccionPresentaciones, seccionReglas, tecnico);

        Button editar = Ui.principal("Editar", "mdi2p-pencil-outline");
        editar.setOnAction(e -> DialogoProducto.mostrar(a, p, this::cargar));
        List<Node> acciones = new ArrayList<>(List.of(editar));
        if (p.disponibilidad() != Disponibilidad.REGULAR) {
            Button activar = Ui.secundario(p.activo() ? "Deshabilitar" : "Habilitar",
                    p.activo() ? "mdi2e-eye-off-outline" : "mdi2e-eye-outline");
            activar.setOnAction(e -> cambiarActivo(p));
            acciones.add(activar);
        }
        Button eliminar = Ui.boton("Eliminar", "mdi2t-trash-can-outline", "flat", "danger");
        eliminar.setOnAction(e -> eliminar(p));
        acciones.add(eliminar);
        detalle.setAcciones(acciones.toArray(Node[]::new));
    }

    // ------------------------------------------------------------------ acciones

    private void cambiarActivo(ProductoCatalogo p) {
        if (!p.activo() && p.disponibilidad() == Disponibilidad.TEMPORADA && p.temporada() != null
                && p.temporada().vencida(LocalDate.now())) {
            DialogoTemporada.mostrarRenovar(a, p, this::cargar);
            return;
        }
        DialogoConfirmacion.mostrar(a.dialogos(), (p.activo() ? "¿Deshabilitar " : "¿Habilitar ") + p.nombre() + "?",
                p.activo() ? "Dejará de venderse en las cajas y no se podrá surtir. No se borra: puedes habilitarlo después."
                        : "Volverá a venderse en las sucursales que tengan existencia.",
                p.activo() ? "Deshabilitar" : "Habilitar", p.activo(),
                () -> a.enFondo(p.activo() ? "Producto deshabilitado: " + p.nombre() : "Producto habilitado: " + p.nombre(),
                        () -> a.admin().productos().cambiarActivo(p, !p.activo(), a.usuario()), this::cargar));
    }

    /** Confirmación con contexto: dice si aún hay existencia en el almacén. */
    private void eliminar(ProductoCatalogo p) {
        String almacen = a.admin().almacen().almacenId();
        Cargador.ejecutar(() -> a.admin().almacen().existencia(p.id(), almacen), existencia -> {
            String stock = existencia != null && existencia.signum() > 0
                    ? " Todavía hay " + Cantidades.formatear(p.unidad(), existencia) + " en el almacén: esa existencia "
                    + "deja de verse en el inventario."
                    : "";
            confirmarEliminar(p, stock);
        }, e -> confirmarEliminar(p, ""));
    }

    private void confirmarEliminar(ProductoCatalogo p, String stock) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar «" + p.nombre() + "»?",
                "Ya no aparecerá en el catálogo ni en las cajas." + stock
                        + " Su historial de ventas y lotes se conserva."
                        + (p.disponibilidad() != Disponibilidad.REGULAR && p.activo()
                        ? " Si solo quieres que deje de venderse por un tiempo, mejor deshabilítalo." : ""),
                "Eliminar producto", true,
                () -> a.enFondo("Producto eliminado: " + p.nombre(),
                        () -> a.admin().productos().eliminar(p, a.usuario()), () -> {
                            maestro.cerrar();
                            cargar();
                        }));
    }
}
