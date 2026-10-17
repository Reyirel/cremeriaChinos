package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.ToggleSwitch;
import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.service.admin.PreciosService;
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
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Márgenes por canal (público, bodega a cremerías, ruta de la Sierra), precios de mayoreo por
 * volumen y la lista de precios que resulta del costo. Ningún porcentaje está fijo en el sistema:
 * todos salen de estas reglas, cada una con su vigencia.
 */
public class PreciosPagina extends Pagina {

    /** Filtro de canal, con la opción que no filtra. */
    private enum FiltroCanal {
        TODOS("Todos los canales", null), PUBLICO("Público en sucursal", ReglaPrecio.Canal.PUBLICO),
        BODEGA("Bodega a cremerías", ReglaPrecio.Canal.BODEGA_SUCURSAL),
        RUTA("Ruta de la Sierra", ReglaPrecio.Canal.RUTA_SIERRA);

        final String texto;
        final ReglaPrecio.Canal canal;

        FiltroCanal(String texto, ReglaPrecio.Canal canal) {
            this.texto = texto;
            this.canal = canal;
        }
    }

    private enum Mostrar {
        VIGENTES("Vigentes y programadas"), TODAS("Incluir vencidas e inactivas");

        final String texto;

        Mostrar(String texto) {
            this.texto = texto;
        }
    }

    /** Situación de una regla o escala hoy. */
    private enum Situacion {
        VIGENTE("Vigente", Etiqueta.Tono.EXITO), PROGRAMADA("Programada", Etiqueta.Tono.INFO),
        VENCIDA("Vencida", Etiqueta.Tono.NEUTRO), INACTIVA("Inactiva", Etiqueta.Tono.NEUTRO);

        final String texto;
        final Etiqueta.Tono tono;

        Situacion(String texto, Etiqueta.Tono tono) {
            this.texto = texto;
            this.tono = tono;
        }

        static Situacion de(boolean activo, LocalDate desde, LocalDate hasta) {
            LocalDate hoy = LocalDate.now();
            if (!activo) {
                return INACTIVA;
            }
            if (hoy.isBefore(desde)) {
                return PROGRAMADA;
            }
            return hasta != null && hoy.isAfter(hasta) ? VENCIDA : VIGENTE;
        }

        boolean visible(Mostrar mostrar) {
            return mostrar == Mostrar.TODAS || this == VIGENTE || this == PROGRAMADA;
        }
    }

    /** Lo que se carga junto para mostrar nombres en lugar de identificadores. */
    private record Datos(List<ReglaPrecio> reglas, List<Escala> escalas, List<ProductoCatalogo> productos,
                         List<ProductoRepository.Categoria> categorias, List<Sucursal> sucursales) {
    }

    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);

    private final Cargador carga = new Cargador();
    private final Cargador cargaLista = new Cargador();
    private ToggleButton pestanaReglas;
    private ToggleButton pestanaMayoreo;
    private ToggleButton pestanaLista;
    private Button nuevo;
    private final Map<ReglaPrecio.Canal, ResumenMetricas.Metrica> metricas = new EnumMap<>(ReglaPrecio.Canal.class);
    private BarraHerramientas barraReglas;
    private ComboBox<FiltroCanal> filtroCanal;
    private ComboBox<Mostrar> mostrarReglas;
    private TablaDatos<ReglaPrecio> reglas;
    private BarraHerramientas barraEscalas;
    private ComboBox<Mostrar> mostrarEscalas;
    private TablaDatos<Escala> escalas;
    private BarraHerramientas barraLista;
    private Segmentado<ReglaPrecio.Canal> canalLista;
    private ComboBox<Sucursal> sucursalLista;
    private TablaDatos<PreciosService.FilaLista> lista;

    private List<ProductoCatalogo> catalogo = List.of();
    private List<ProductoRepository.Categoria> listaCategorias = List.of();
    private List<Sucursal> listaSucursales = List.of();
    private Map<String, ProductoCatalogo> productos = Map.of();
    private Map<String, String> categorias = Map.of();
    private Map<String, Sucursal> sucursales = Map.of();
    private Map<String, Presentacion> presentaciones = Map.of();

    public PreciosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Precios y márgenes";
    }

    @Override
    public TextField busqueda() {
        BarraHerramientas barra = pestanaReglas.isSelected() ? barraReglas
                : pestanaMayoreo.isSelected() ? barraEscalas : barraLista;
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        nuevo = Ui.principal("Nueva regla", "mdi2p-plus");
        nuevo.setOnAction(e -> {
            if (pestanaMayoreo.isSelected()) {
                formularioEscala();
            } else {
                formularioRegla(null);
            }
        });
        VBox pagina = Ui.pagina("Precios y márgenes",
                "Porcentajes que convierten el costo en precio por canal y precios por volumen, con vigencia.", nuevo);

        Pestanas pestanas = new Pestanas();
        pestanaReglas = pestanas.agregar("Márgenes por canal");
        pestanaMayoreo = pestanas.agregar("Mayoreo");
        pestanaLista = pestanas.agregar("Lista de precios");

        VBox vistaReglas = construirReglas();
        VBox vistaEscalas = construirEscalas();
        VBox vistaLista = construirLista();
        StackPane zona = new StackPane(vistaReglas);
        pestanas.seleccionProperty().addListener((o, x, y) -> {
            zona.getChildren().setAll(pestanaReglas.isSelected() ? vistaReglas
                    : pestanaMayoreo.isSelected() ? vistaEscalas : vistaLista);
            nuevo.setVisible(!pestanaLista.isSelected());
            nuevo.setText(pestanaMayoreo.isSelected() ? "Nueva escala" : "Nueva regla");
            if (pestanaLista.isSelected()) {
                cargarLista();
            }
        });
        nuevo.managedProperty().bind(nuevo.visibleProperty());
        pagina.getChildren().addAll(pestanas, zona);
        return Ui.ajustable(pagina, zona, 320);
    }

    // ------------------------------------------------------------------ márgenes

    private VBox construirReglas() {
        ResumenMetricas resumen = new ResumenMetricas();
        for (ReglaPrecio.Canal canal : ReglaPrecio.Canal.values()) {
            ResumenMetricas.Metrica m = resumen.agregar(canal.nombre());
            metricas.put(canal, canal == ReglaPrecio.Canal.PUBLICO ? m.principal() : m);
        }

        barraReglas = new BarraHerramientas();
        barraReglas.buscador("Buscar producto, categoría, sucursal o folio");
        filtroCanal = barraReglas.filtro("Canal", new ComboBox<>(FXCollections.observableArrayList(FiltroCanal.values())),
                FiltroCanal.TODOS, f -> f.texto);
        filtroCanal.setValue(FiltroCanal.TODOS);
        mostrarReglas = barraReglas.filtro("Mostrar", new ComboBox<>(FXCollections.observableArrayList(Mostrar.values())),
                Mostrar.VIGENTES, m -> m.texto);
        mostrarReglas.setValue(Mostrar.VIGENTES);
        barraReglas.setAlCambiar(this::filtrarReglas);

        reglas = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        reglas.setClave(ReglaPrecio::id);
        reglas.etiqueta("Canal", ReglaPrecio::canal, ReglaPrecio.Canal::nombre, PreciosPagina::tono).ancho(170);
        reglas.dosLineas("Aplica a", this::alcance, r -> donde(r.sucursalId())).flexible(220);
        reglas.dosLineas("Porcentaje", PreciosService::porcentaje, PreciosPagina::equivalente).ancho(210)
                .orden(Comparator.comparing((String s) -> s));
        reglas.dosLineas("Vigencia", r -> "Desde " + Formatos.FECHA_CORTA.format(r.vigenteDesde()),
                r -> r.vigenteHasta() == null ? "Sin fecha final" : "Hasta " + Formatos.FECHA_CORTA.format(r.vigenteHasta()))
                .ancho(150);
        reglas.etiqueta("Estado", this::situacion, s -> s.texto, s -> s.tono).ancho(120);
        reglas.texto("Folio", ReglaPrecio::folio).ancho(130).clase("columna-tenue").oculta();
        reglas.acciones(
                Accion.<ReglaPrecio>boton("mdi2p-pencil-outline", "Editar", this::formularioRegla).si(ReglaPrecio::activo),
                Accion.<ReglaPrecio>menu("mdi2c-cancel", r -> "Desactivar", this::desactivarRegla)
                        .si(ReglaPrecio::activo).peligrosa());
        reglas.marcar("atenuada", r -> situacion(r) == Situacion.INACTIVA || situacion(r) == Situacion.VENCIDA);
        reglas.setVacio(new EstadoVacio("mdi2p-percent-outline", "No hay reglas de precio.",
                "Sin reglas, lo surtido se cobra al costo y no se sugieren precios al público.")
                .accion("Nueva regla", "mdi2p-plus", () -> formularioRegla(null)));
        reglas.setSinResultados(EstadoVacio.sinResultados("No encontramos reglas con estos filtros.", barraReglas::limpiar));
        reglas.setAlActivar(r -> {
            if (r.activo()) {
                formularioRegla(r);
            }
        });
        VBox panel = Ui.panelTabla(barraReglas, reglas);
        VBox.setVgrow(panel, Priority.ALWAYS);
        return new VBox(Diseno.ESPACIO_L, resumen, panel);
    }

    private static Etiqueta.Tono tono(ReglaPrecio.Canal canal) {
        return switch (canal) {
            case PUBLICO -> Etiqueta.Tono.ACENTO;
            case BODEGA_SUCURSAL -> Etiqueta.Tono.INFO;
            case RUTA_SIERRA -> Etiqueta.Tono.AVISO;
        };
    }

    private Situacion situacion(ReglaPrecio r) {
        return Situacion.de(r.activo(), r.vigenteDesde(), r.vigenteHasta());
    }

    private static String equivalente(ReglaPrecio r) {
        return r.tipo() == ReglaPrecio.Tipo.RECARGO_COSTO
                ? "Deja " + texto(r.margenEquivalente()) + " % de margen sobre el precio"
                : "Equivale a " + texto(r.recargoEquivalente()) + " % sobre el costo";
    }

    private static String texto(BigDecimal valor) {
        return valor.stripTrailingZeros().toPlainString();
    }

    private String alcance(ReglaPrecio r) {
        if (r.productoId() != null) {
            ProductoCatalogo p = productos.get(r.productoId());
            String nombre = p == null ? "Producto" : p.nombre();
            if (r.presentacionId() != null) {
                Presentacion pr = presentaciones.get(r.presentacionId());
                nombre += " · " + (pr == null ? "presentación" : pr.nombre());
            }
            return nombre;
        }
        if (r.categoriaId() != null) {
            return "Categoría " + categorias.getOrDefault(r.categoriaId(), "—");
        }
        return "Todos los productos";
    }

    private String donde(String sucursalId) {
        if (sucursalId == null) {
            return "Todas las sucursales";
        }
        Sucursal s = sucursales.get(sucursalId);
        return s == null ? "Sucursal" : Formatos.sucursal(s);
    }

    private void filtrarReglas() {
        String t = barraReglas.busqueda();
        FiltroCanal f = filtroCanal.getValue() == null ? FiltroCanal.TODOS : filtroCanal.getValue();
        Mostrar m = mostrarReglas.getValue() == null ? Mostrar.VIGENTES : mostrarReglas.getValue();
        reglas.setFiltro(r -> (f.canal == null || f.canal == r.canal()) && situacion(r).visible(m)
                && (t.isEmpty() || Texto.contiene(alcance(r), t) || Texto.contiene(donde(r.sucursalId()), t)
                || Texto.contiene(r.folio(), t) || Texto.contiene(r.notas(), t)));
        barraReglas.setResultados(reglas.mostrados() == reglas.total() ? Texto.plural(reglas.total(), "regla", "reglas")
                : reglas.mostrados() + " de " + Texto.plural(reglas.total(), "regla", "reglas"));
    }

    private void actualizarMetricas(List<ReglaPrecio> lista) {
        LocalDate hoy = LocalDate.now();
        for (ReglaPrecio.Canal canal : ReglaPrecio.Canal.values()) {
            List<ReglaPrecio> vigentes = lista.stream().filter(r -> r.canal() == canal && r.vigenteEn(hoy)).toList();
            Optional<ReglaPrecio> general = vigentes.stream().filter(r -> r.especificidad() == 0).findFirst();
            long especificas = vigentes.stream().filter(r -> r.especificidad() > 0).count();
            ResumenMetricas.Metrica m = metricas.get(canal);
            if (general.isPresent()) {
                m.setValor(PreciosService.porcentaje(general.get()))
                        .setContexto("General" + (especificas == 0 ? "" : " · " + Texto.plural(especificas,
                                "regla específica", "reglas específicas")));
            } else {
                m.setValor(especificas == 0 ? "Sin regla" : Texto.plural(especificas, "regla", "reglas"))
                        .setContexto(canal == ReglaPrecio.Canal.PUBLICO ? "Sin regla general: no se sugiere precio"
                                : "Sin regla general: lo demás se cobra al costo");
            }
        }
    }

    private void desactivarRegla(ReglaPrecio r) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Desactivar la regla " + r.folio() + "?",
                PreciosService.porcentaje(r) + " para " + r.canal().nombre().toLowerCase() + " · " + alcance(r) + " · "
                        + donde(r.sucursalId()) + ". Los surtidos y precios ya registrados no cambian; lo que se haga "
                        + "después usará la siguiente regla que aplique.",
                "Desactivar", true, () -> a.enFondo("Regla desactivada",
                        () -> a.admin().precios().desactivarRegla(r, a.usuario()), this::cargar));
    }

    private void formularioRegla(ReglaPrecio r) {
        boolean nueva = r == null;
        Dialogo d = new Dialogo(nueva ? "Nueva regla de precio" : "Editar regla " + r.folio(),
                "El porcentaje convierte el costo en precio para el canal que elijas.", "mdi2p-percent-outline",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(640);

        Segmentado<ReglaPrecio.Canal> canal = new Segmentado<>();
        for (ReglaPrecio.Canal c : ReglaPrecio.Canal.values()) {
            canal.opcion(c, c.nombre());
        }
        Label ayudaCanal = Ui.texto("", "campo-ayuda");
        Segmentado<ReglaPrecio.Tipo> tipo = new Segmentado<ReglaPrecio.Tipo>()
                .opcion(ReglaPrecio.Tipo.RECARGO_COSTO, "Sobre el costo")
                .opcion(ReglaPrecio.Tipo.MARGEN_PRECIO, "Margen sobre el precio");
        TextField porcentaje = new TextField();
        Campos.soloNumeros(porcentaje, 2);
        porcentaje.setAlignment(Pos.CENTER_RIGHT);
        porcentaje.setPromptText("0");
        Formulario.Campo campoPorcentaje = Formulario.campo("Porcentaje", Formulario.conUnidad(porcentaje, "%"));
        Label ejemplo = new Label("—");
        Label equivalencia = Ui.texto("", "campo-ayuda");
        VBox resumen = Formulario.resumen("Con un costo de $100.00 el precio queda en", ejemplo, equivalencia);

        Segmentado<String> alcance = new Segmentado<String>().opcion("TODOS", "Todos los productos")
                .opcion("CATEGORIA", "Una categoría").opcion("PRODUCTO", "Un producto");
        ComboBox<ProductoRepository.Categoria> categoria = new ComboBox<>(FXCollections.observableArrayList(listaCategorias));
        categoria.setConverter(Combos.de(ProductoRepository.Categoria::nombre));
        categoria.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoCategoria = Formulario.campo("Categoría", categoria);
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(catalogo.stream().filter(ProductoCatalogo::activo).toList());
        producto.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoProducto = Formulario.campo("Producto", producto);
        ComboBox<Presentacion> presentacion = new ComboBox<>();
        presentacion.setConverter(Combos.de(p -> p.id() == null ? "Todas las presentaciones" : p.nombre()));
        presentacion.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoPresentacion = Formulario.opcional("Presentación", presentacion);
        for (Formulario.Campo campo : List.of(campoCategoria, campoProducto, campoPresentacion)) {
            campo.managedProperty().bind(campo.visibleProperty());
        }
        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(conTodas()));
        sucursal.setConverter(Combos.sucursal());
        sucursal.setMaxWidth(Double.MAX_VALUE);
        sucursal.setValue(TODAS);

        DatePicker desde = new DatePicker(LocalDate.now());
        desde.setEditable(false);
        DatePicker hasta = new DatePicker();
        hasta.setEditable(false);
        ToggleSwitch conFin = new ToggleSwitch("Termina en una fecha");
        Formulario.Campo campoDesde = Formulario.campo("Desde", desde);
        Formulario.Campo campoHasta = Formulario.campo("Hasta", hasta);
        campoHasta.visibleProperty().bind(conFin.selectedProperty());
        TextField notas = new TextField();
        notas.setPromptText("Ej. Acordado con la sucursal en octubre");

        Presentacion todasPresentaciones = new Presentacion(null, null, "Todas las presentaciones", false, BigDecimal.ONE,
                null, false, true);
        Runnable alCambiarProducto = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            List<Presentacion> opciones = new ArrayList<>();
            opciones.add(todasPresentaciones);
            if (p != null) {
                p.presentaciones().stream().filter(Presentacion::activo).forEach(opciones::add);
            }
            Presentacion actual = presentacion.getValue();
            presentacion.setItems(FXCollections.observableArrayList(opciones));
            presentacion.setValue(actual != null && opciones.contains(actual) ? actual : todasPresentaciones);
            campoProducto.setError(null);
        };
        producto.valueProperty().addListener((o, x, y) -> alCambiarProducto.run());
        Runnable alCambiarAlcance = () -> {
            campoCategoria.setVisible("CATEGORIA".equals(alcance.getValor()));
            campoProducto.setVisible("PRODUCTO".equals(alcance.getValor()));
            campoPresentacion.setVisible("PRODUCTO".equals(alcance.getValor()));
        };
        alcance.valorProperty().addListener((o, x, y) -> alCambiarAlcance.run());
        Runnable vista = () -> {
            ReglaPrecio.Canal c = canal.getValor();
            ayudaCanal.setText(c == null ? "" : c.descripcion() + ".");
            Optional<BigDecimal> pct = decimal(porcentaje.getText());
            campoPorcentaje.setError(null);
            if (pct.isEmpty()) {
                ejemplo.setText("—");
                equivalencia.setText("");
                return;
            }
            if (tipo.getValor() == ReglaPrecio.Tipo.MARGEN_PRECIO && pct.get().compareTo(BigDecimal.valueOf(100)) >= 0) {
                ejemplo.setText("—");
                equivalencia.setText("El margen sobre el precio debe ser menor a 100 %.");
                return;
            }
            ReglaPrecio prueba = new ReglaPrecio(null, null, c, tipo.getValor(), pct.get(), null, null, null, null,
                    LocalDate.now(), null, null, true);
            ejemplo.setText(Dinero.formatear(prueba.precioDesde(10000)));
            equivalencia.setText(equivalente(prueba) + ". Utilidad: "
                    + Dinero.formatear(prueba.precioDesde(10000) - 10000) + " por cada $100.00 de costo.");
        };
        canal.valorProperty().addListener((o, x, y) -> vista.run());
        tipo.valorProperty().addListener((o, x, y) -> vista.run());
        porcentaje.textProperty().addListener((o, x, y) -> vista.run());

        if (r != null) {
            canal.setValor(r.canal());
            tipo.setValor(r.tipo());
            porcentaje.setText(texto(r.porcentaje()));
            if (r.productoId() != null) {
                alcance.setValor("PRODUCTO");
                producto.setValue(productos.get(r.productoId()));
                alCambiarProducto.run();
                if (r.presentacionId() != null) {
                    presentacion.setValue(presentaciones.get(r.presentacionId()));
                }
            } else if (r.categoriaId() != null) {
                alcance.setValor("CATEGORIA");
                listaCategorias.stream().filter(x -> x.id().equals(r.categoriaId())).findFirst().ifPresent(categoria::setValue);
            }
            sucursal.setValue(r.sucursalId() == null ? TODAS : sucursales.getOrDefault(r.sucursalId(), TODAS));
            desde.setValue(r.vigenteDesde());
            conFin.setSelected(r.vigenteHasta() != null);
            hasta.setValue(r.vigenteHasta());
            notas.setText(r.notas());
        } else {
            alCambiarProducto.run();
        }
        alCambiarAlcance.run();
        vista.run();

        d.setContenido(
                Formulario.seccion("Canal y porcentaje", null, new VBox(6, canal, ayudaCanal), tipo,
                        campoPorcentaje, resumen),
                Formulario.seccionSeparada("A qué aplica",
                        "La regla más específica gana: presentación, luego producto, categoría y la general. "
                                + "Con sucursal gana sobre la misma sin sucursal.",
                        alcance, campoCategoria, campoProducto, campoPresentacion,
                        Formulario.campo("Sucursal", sucursal)),
                Formulario.seccionSeparada("Vigencia", "Para cambiar un porcentaje desde una fecha, termina la regla "
                                + "actual y crea otra: así queda el historial.",
                        Formulario.fila(campoDesde, conFin), campoHasta, Formulario.opcional("Notas", notas)));

        Runnable guardar = () -> {
            Optional<BigDecimal> pct = decimal(porcentaje.getText());
            boolean valido = true;
            if (pct.isEmpty()) {
                campoPorcentaje.setError("Escribe el porcentaje (puede ser 0).");
                valido = false;
            }
            ProductoCatalogo p = SelectorProducto.valor(producto);
            if ("PRODUCTO".equals(alcance.getValor()) && p == null) {
                campoProducto.setError("Elige el producto.");
                valido = false;
            }
            if ("CATEGORIA".equals(alcance.getValor()) && categoria.getValue() == null) {
                campoCategoria.setError("Elige la categoría.");
                valido = false;
            }
            if (desde.getValue() == null) {
                campoDesde.setError("Elige desde cuándo aplica.");
                valido = false;
            }
            if (conFin.isSelected() && hasta.getValue() == null) {
                campoHasta.setError("Elige la fecha final o apaga «Termina en una fecha».");
                valido = false;
            }
            if (!valido) {
                return;
            }
            boolean porProducto = "PRODUCTO".equals(alcance.getValor());
            Presentacion pr = presentacion.getValue();
            PreciosService.DatosRegla datos = new PreciosService.DatosRegla(r == null ? null : r.id(), canal.getValor(),
                    tipo.getValor(), pct.get(),
                    "CATEGORIA".equals(alcance.getValor()) ? categoria.getValue().id() : null,
                    porProducto ? p.id() : null, porProducto && pr != null ? pr.id() : null,
                    sucursal.getValue() == null ? null : sucursal.getValue().id(), desde.getValue(),
                    conFin.isSelected() ? hasta.getValue() : null, notas.getText());
            a.guardar(d, nueva ? "Regla de precio creada" : "Regla de precio actualizada",
                    () -> a.admin().precios().guardarRegla(datos, a.usuario()), this::cargar);
        };
        desde.valueProperty().addListener((o, x, y) -> campoDesde.setError(null));
        hasta.valueProperty().addListener((o, x, y) -> campoHasta.setError(null));
        categoria.valueProperty().addListener((o, x, y) -> campoCategoria.setError(null));
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nueva ? "Crear regla" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(porcentaje);
        d.vigilarCambios(canal, tipo, porcentaje, alcance, categoria, producto, presentacion, sucursal, desde, hasta,
                conFin, notas);
        a.dialogos().mostrar(d);
    }

    // ------------------------------------------------------------------ mayoreo

    private VBox construirEscalas() {
        barraEscalas = new BarraHerramientas();
        barraEscalas.buscador("Buscar producto, presentación o sucursal");
        mostrarEscalas = barraEscalas.filtro("Mostrar", new ComboBox<>(FXCollections.observableArrayList(Mostrar.values())),
                Mostrar.VIGENTES, m -> m.texto);
        mostrarEscalas.setValue(Mostrar.VIGENTES);
        barraEscalas.setAlCambiar(this::filtrarEscalas);

        escalas = new TablaDatos<>(Diseno.Densidad.NORMAL);
        escalas.setClave(Escala::id);
        escalas.dosLineas("Producto", e -> nombreProducto(e.productoId()), this::presentacionEscala).flexible(220);
        escalas.etiqueta("Nivel", Escala::nivel, Escala.Nivel::nombre,
                n -> n == Escala.Nivel.SUPER_MAYOREO ? Etiqueta.Tono.ACENTO : Etiqueta.Tono.INFO).ancho(140);
        escalas.cantidad("Desde", Escala::cantidadMinima, e -> unidad(e.productoId())).ancho(120)
                .ayuda("Cantidad mínima de la venta, en unidades del producto (una caja de 12 cuenta 12 piezas).");
        escalas.texto("Precio", PreciosService::valor).ancho(190);
        escalas.texto("Sucursal", e -> donde(e.sucursalId())).ancho(190);
        escalas.dosLineas("Vigencia", e -> "Desde " + Formatos.FECHA_CORTA.format(e.vigenteDesde()),
                e -> e.vigenteHasta() == null ? "Sin fecha final" : "Hasta " + Formatos.FECHA_CORTA.format(e.vigenteHasta()))
                .ancho(150);
        escalas.etiqueta("Estado", e -> Situacion.de(e.activo(), e.vigenteDesde(), e.vigenteHasta()), s -> s.texto,
                s -> s.tono).ancho(120);
        escalas.acciones(Accion.<Escala>boton("mdi2c-cancel", "Desactivar", this::desactivarEscala)
                .si(Escala::activo).peligrosa());
        escalas.marcar("atenuada", e -> !Situacion.de(e.activo(), e.vigenteDesde(), e.vigenteHasta()).visible(Mostrar.VIGENTES));
        escalas.setVacio(new EstadoVacio("mdi2s-sale-outline", "No hay precios de mayoreo.",
                "Sin escalas, todo se vende a precio de menudeo.")
                .accion("Nueva escala", "mdi2p-plus", this::formularioEscala));
        escalas.setSinResultados(EstadoVacio.sinResultados("No encontramos escalas con estos filtros.", barraEscalas::limpiar));
        VBox panel = Ui.panelTabla(barraEscalas, escalas);
        VBox.setVgrow(panel, Priority.ALWAYS);
        HBox nota = Formulario.nota("mdi2i-information-outline",
                "La caja suma lo que lleva el cliente de cada producto (en piezas o kilos) y aplica el nivel que alcanza. "
                        + "Nunca cobra más que el precio de menudeo; los tickets ya cobrados no cambian.", null);
        return new VBox(Diseno.ESPACIO_M, nota, panel);
    }

    private String nombreProducto(String productoId) {
        ProductoCatalogo p = productos.get(productoId);
        return p == null ? "Producto" : p.nombre();
    }

    private Unidad unidad(String productoId) {
        ProductoCatalogo p = productos.get(productoId);
        return p == null ? Unidad.PZA : p.unidad();
    }

    private String presentacionEscala(Escala e) {
        if (e.presentacionId() == null) {
            return "Todas las presentaciones";
        }
        Presentacion pr = presentaciones.get(e.presentacionId());
        return pr == null ? "Presentación" : pr.nombre();
    }

    private void filtrarEscalas() {
        String t = barraEscalas.busqueda();
        Mostrar m = mostrarEscalas.getValue() == null ? Mostrar.VIGENTES : mostrarEscalas.getValue();
        escalas.setFiltro(e -> Situacion.de(e.activo(), e.vigenteDesde(), e.vigenteHasta()).visible(m)
                && (t.isEmpty() || Texto.contiene(nombreProducto(e.productoId()), t)
                || Texto.contiene(presentacionEscala(e), t) || Texto.contiene(donde(e.sucursalId()), t)));
        barraEscalas.setResultados(escalas.mostrados() == escalas.total()
                ? Texto.plural(escalas.total(), "escala", "escalas")
                : escalas.mostrados() + " de " + Texto.plural(escalas.total(), "escala", "escalas"));
    }

    private void desactivarEscala(Escala e) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Desactivar " + e.nivel().nombre().toLowerCase() + "?",
                nombreProducto(e.productoId()) + " · " + presentacionEscala(e) + " · " + PreciosService.valor(e)
                        + ". Las ventas ya cobradas no cambian.",
                "Desactivar", true, () -> a.enFondo("Escala desactivada",
                        () -> a.admin().precios().desactivarEscala(e, a.usuario()), this::cargar));
    }

    private void formularioEscala() {
        Dialogo d = new Dialogo("Nuevo precio de mayoreo",
                "A partir de una cantidad en la misma venta, el producto se cobra con descuento o a un precio fijo.",
                "mdi2s-sale-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(600);
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(catalogo.stream().filter(ProductoCatalogo::activo).toList());
        producto.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoProducto = Formulario.campo("Producto", producto);
        Presentacion todas = new Presentacion(null, null, "Todas las presentaciones", false, BigDecimal.ONE, null, false, true);
        ComboBox<Presentacion> presentacion = new ComboBox<>(FXCollections.observableArrayList(todas));
        presentacion.setConverter(Combos.de(Presentacion::nombre));
        presentacion.setValue(todas);
        presentacion.setMaxWidth(Double.MAX_VALUE);
        Formulario.Campo campoPresentacion = Formulario.campo("Presentación", presentacion);
        Segmentado<Escala.Nivel> nivel = new Segmentado<Escala.Nivel>()
                .opcion(Escala.Nivel.MAYOREO, "Mayoreo").opcion(Escala.Nivel.SUPER_MAYOREO, "Súper mayoreo");
        TextField minima = new TextField();
        Campos.soloNumeros(minima, 0);
        minima.setAlignment(Pos.CENTER_RIGHT);
        minima.setPromptText("Ej. 6");
        Label unidadMinima = new Label("pzas");
        unidadMinima.getStyleClass().add("unidad-campo");
        HBox filaMinima = new HBox(8, minima, unidadMinima);
        filaMinima.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(minima, Priority.ALWAYS);
        Formulario.Campo campoMinima = Formulario.campo("Desde (cantidad en la venta)", filaMinima)
                .ayuda("Se cuenta en unidades del producto: una caja de 12 cuenta como 12 piezas.");
        Segmentado<Escala.Tipo> tipo = new Segmentado<Escala.Tipo>()
                .opcion(Escala.Tipo.DESCUENTO_PCT, "Descuento %").opcion(Escala.Tipo.PRECIO_FIJO, "Precio fijo");
        TextField descuento = new TextField();
        Campos.soloNumeros(descuento, 2);
        descuento.setAlignment(Pos.CENTER_RIGHT);
        descuento.setPromptText("0");
        Formulario.Campo campoDescuento = Formulario.campo("Descuento sobre el precio de menudeo",
                Formulario.conUnidad(descuento, "%"));
        CampoDinero precio = new CampoDinero().normal();
        precio.setPromptText("0.00");
        Formulario.Campo campoPrecio = Formulario.campo("Precio por unidad de la presentación", precio)
                .ayuda("A granel es el precio por kilo.");
        campoDescuento.managedProperty().bind(campoDescuento.visibleProperty());
        campoPrecio.managedProperty().bind(campoPrecio.visibleProperty());
        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(conTodas()));
        sucursal.setConverter(Combos.sucursal());
        sucursal.setValue(TODAS);
        sucursal.setMaxWidth(Double.MAX_VALUE);
        DatePicker desde = new DatePicker(LocalDate.now());
        desde.setEditable(false);
        DatePicker hasta = new DatePicker();
        hasta.setEditable(false);
        ToggleSwitch conFin = new ToggleSwitch("Termina en una fecha");
        Formulario.Campo campoDesde = Formulario.campo("Desde", desde);
        Formulario.Campo campoHasta = Formulario.campo("Hasta", hasta);
        campoHasta.visibleProperty().bind(conFin.selectedProperty());
        campoHasta.managedProperty().bind(campoHasta.visibleProperty());
        Label existentes = Ui.texto("", "campo-ayuda");
        existentes.managedProperty().bind(existentes.visibleProperty());
        existentes.visibleProperty().bind(existentes.textProperty().isNotEmpty());

        Runnable alCambiarProducto = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            List<Presentacion> opciones = new ArrayList<>();
            opciones.add(todas);
            if (p != null) {
                p.presentaciones().stream().filter(Presentacion::activo).forEach(opciones::add);
                unidadMinima.setText(Cantidades.unidadCaptura(p.unidad()));
                minima.setPromptText(p.unidad() == Unidad.KG ? "Ej. 5000" : "Ej. 6");
                List<Escala> suyas = escalas.datos().stream()
                        .filter(e -> e.productoId().equals(p.id()) && e.activo()).toList();
                existentes.setText(suyas.isEmpty() ? "Este producto aún no tiene precios de mayoreo."
                        : "Ya tiene: " + suyas.stream().map(e -> e.nivel().nombre() + " desde "
                                + Cantidades.formatear(p.unidad(), e.cantidadMinima()) + " (" + presentacionEscala(e)
                                + ", " + PreciosService.valor(e) + ")").collect(Collectors.joining("; ")) + ".");
            } else {
                existentes.setText("");
            }
            presentacion.setItems(FXCollections.observableArrayList(opciones));
            presentacion.setValue(todas);
            campoProducto.setError(null);
        };
        producto.valueProperty().addListener((o, x, y) -> alCambiarProducto.run());
        Runnable alCambiarTipo = () -> {
            campoDescuento.setVisible(tipo.getValor() == Escala.Tipo.DESCUENTO_PCT);
            campoPrecio.setVisible(tipo.getValor() == Escala.Tipo.PRECIO_FIJO);
        };
        tipo.valorProperty().addListener((o, x, y) -> alCambiarTipo.run());
        alCambiarTipo.run();
        minima.textProperty().addListener((o, x, y) -> campoMinima.setError(null));
        descuento.textProperty().addListener((o, x, y) -> campoDescuento.setError(null));
        precio.textProperty().addListener((o, x, y) -> campoPrecio.setError(null));
        presentacion.valueProperty().addListener((o, x, y) -> campoPresentacion.setError(null));

        d.setContenido(
                Formulario.seccion(null, null, campoProducto, existentes, campoPresentacion,
                        Formulario.campo("Nivel", nivel), campoMinima),
                Formulario.seccionSeparada("Precio", "Si el precio con mayoreo saliera mayor que el de menudeo, se "
                        + "cobra el de menudeo.", tipo, campoDescuento, campoPrecio),
                Formulario.seccionSeparada("Dónde y cuándo", null, Formulario.campo("Sucursal", sucursal),
                        Formulario.fila(campoDesde, conFin), campoHasta));

        Runnable guardar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            boolean valido = true;
            if (p == null) {
                campoProducto.setError("Elige el producto.");
                valido = false;
            }
            Optional<BigDecimal> cantidad = p == null ? Optional.empty() : Cantidades.desdeCaptura(p.unidad(), minima.getText())
                    .filter(c -> c.signum() > 0);
            if (p != null && cantidad.isEmpty()) {
                campoMinima.setError("Escribe desde qué cantidad aplica.");
                valido = false;
            }
            Optional<BigDecimal> pct = decimal(descuento.getText());
            if (tipo.getValor() == Escala.Tipo.DESCUENTO_PCT && (pct.isEmpty() || pct.get().signum() <= 0
                    || pct.get().compareTo(BigDecimal.valueOf(100)) >= 0)) {
                campoDescuento.setError("Escribe un descuento mayor a 0 % y menor a 100 %.");
                valido = false;
            }
            if (tipo.getValor() == Escala.Tipo.PRECIO_FIJO) {
                if (precio.centavosOCero() <= 0) {
                    campoPrecio.setError("Escribe el precio.");
                    valido = false;
                }
                if (presentacion.getValue() == null || presentacion.getValue().id() == null) {
                    campoPresentacion.setError("Un precio fijo es por presentación: elígela.");
                    valido = false;
                }
            }
            if (conFin.isSelected() && hasta.getValue() == null) {
                campoHasta.setError("Elige la fecha final o apaga «Termina en una fecha».");
                valido = false;
            }
            if (!valido) {
                return;
            }
            Presentacion pr = presentacion.getValue();
            PreciosService.DatosEscala datos = new PreciosService.DatosEscala(p.id(), pr == null ? null : pr.id(),
                    sucursal.getValue() == null ? null : sucursal.getValue().id(), nivel.getValor(), cantidad.get(),
                    tipo.getValor(), tipo.getValor() == Escala.Tipo.DESCUENTO_PCT ? pct.get() : null,
                    tipo.getValor() == Escala.Tipo.PRECIO_FIJO ? precio.centavosOCero() : null, desde.getValue(),
                    conFin.isSelected() ? hasta.getValue() : null);
            a.guardar(d, nivel.getValor().nombre() + " guardado para " + p.nombre(),
                    () -> a.admin().precios().guardarEscala(datos, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(producto);
        d.vigilarCambios(producto, presentacion, nivel, minima, tipo, descuento, precio, sucursal, desde, hasta, conFin);
        a.dialogos().mostrar(d);
    }

    // ------------------------------------------------------------------ lista de precios

    private VBox construirLista() {
        barraLista = new BarraHerramientas();
        barraLista.buscador("Buscar producto, clave o categoría");
        canalLista = new Segmentado<>();
        for (ReglaPrecio.Canal c : ReglaPrecio.Canal.values()) {
            canalLista.opcion(c, c.nombre());
        }
        canalLista.valorProperty().addListener((o, x, y) -> cargarLista());
        barraLista.agregar(canalLista);
        sucursalLista = barraLista.filtro("Sucursal", new ComboBox<>(), TODAS,
                s -> s.id() == null ? s.nombre() : Formatos.sucursal(s));
        sucursalLista.setValue(TODAS);
        sucursalLista.valueProperty().addListener((o, x, y) -> cargarLista());
        barraLista.setAlCambiar(this::filtrarLista);

        lista = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        lista.setClave(f -> f.producto().id());
        lista.dosLineas("Producto", f -> f.producto().nombre(), f -> Formatos.codigos(f.producto(), true)).flexible(220);
        lista.formato("Costo", PreciosService.FilaLista::costoBaseCentavos,
                        (f, v) -> v == 0 ? "Sin costo" : Dinero.formatear(v) + Formatos.porUnidad(f.producto().unidad()))
                .numero().ancho(140)
                .ayuda("Costo del lote del almacén que sale primero (o del último lote si no hay existencia).");
        lista.dosLineas("Regla", f -> f.regla() == null ? "Sin regla" : PreciosService.porcentaje(f.regla()),
                f -> f.regla() == null ? sinRegla() : f.regla().folio()).ancho(200);
        lista.textoLargo("Precio por presentación", f -> f.precios().isEmpty() ? "—" : f.precios().entrySet().stream()
                .map(e -> e.getKey().nombre() + " " + Dinero.formatear(e.getValue()))
                .collect(Collectors.joining(" · "))).flexible(240);
        lista.formato("Utilidad", PreciosPagina::utilidad, (f, v) -> v == null ? "—" : Dinero.formatear(v)
                        + Formatos.porUnidad(f.producto().unidad()))
                .numero().ancho(130).ayuda("Precio menos costo por unidad del producto, con la presentación principal.");
        lista.formato("Margen", PreciosPagina::margen, (f, v) -> v == null ? "—" : texto(v) + " %").numero().ancho(96)
                .ayuda("Utilidad entre precio: la parte del precio que queda de ganancia.");
        lista.marcar("atenuada", f -> f.costoBaseCentavos() == 0);
        lista.setVacio(new EstadoVacio("mdi2f-format-list-bulleted", "No hay productos activos.", null));
        lista.setSinResultados(EstadoVacio.sinResultados("No encontramos productos con esa búsqueda.", barraLista::limpiar));
        VBox panel = Ui.panelTabla(barraLista, lista);
        VBox.setVgrow(panel, Priority.ALWAYS);
        HBox nota = Formulario.nota("mdi2i-information-outline",
                "Cálculo de referencia con el costo actual del almacén. Los precios de cada sucursal se fijan al surtir; "
                        + "esta lista no los cambia.", null);
        return new VBox(Diseno.ESPACIO_M, nota, panel);
    }

    private String sinRegla() {
        return canalLista.getValor() == ReglaPrecio.Canal.PUBLICO ? "Sin precio sugerido" : "Se cobra al costo";
    }

    private static Optional<Presentacion> principal(PreciosService.FilaLista f) {
        return f.precios().keySet().stream().filter(Presentacion::principal).findFirst()
                .or(() -> f.precios().keySet().stream().findFirst());
    }

    /** Utilidad por unidad base con la presentación principal; {@code null} sin costo. */
    private static Long utilidad(PreciosService.FilaLista f) {
        Optional<Presentacion> p = principal(f);
        if (f.costoBaseCentavos() == 0 || p.isEmpty() || p.get().factor().signum() <= 0) {
            return null;
        }
        long porUnidad = BigDecimal.valueOf(f.precios().get(p.get())).divide(p.get().factor(), 0, RoundingMode.HALF_UP)
                .longValue();
        return porUnidad - f.costoBaseCentavos();
    }

    private static BigDecimal margen(PreciosService.FilaLista f) {
        Optional<Presentacion> p = principal(f);
        Long utilidad = utilidad(f);
        if (utilidad == null || p.isEmpty()) {
            return null;
        }
        long porUnidad = utilidad + f.costoBaseCentavos();
        return porUnidad == 0 ? null : BigDecimal.valueOf(utilidad * 100L).divide(BigDecimal.valueOf(porUnidad), 1,
                RoundingMode.HALF_UP);
    }

    private void cargarLista() {
        if (lista == null || !pestanaLista.isSelected()) {
            return;
        }
        ReglaPrecio.Canal canal = canalLista.getValor();
        Sucursal s = sucursalLista.getValue();
        lista.cargando("Calculando la lista de precios…");
        cargaLista.cargar(() -> a.admin().precios().listaDePrecios(canal, s == null ? null : s.id(), LocalDate.now()),
                filas -> {
                    lista.setDatos(filas);
                    filtrarLista();
                }, e -> lista.error("No se pudo calcular la lista de precios.", AdminContexto.mensaje(e), this::cargarLista));
    }

    private void filtrarLista() {
        String t = barraLista.busqueda();
        lista.setFiltro(f -> AlmacenPagina.coincide(f.producto(), t) || Texto.contiene(f.producto().categoria(), t));
        barraLista.setResultados(lista.mostrados() == lista.total() ? Texto.plural(lista.total(), "producto", "productos")
                : lista.mostrados() + " de " + Texto.plural(lista.total(), "producto", "productos"));
    }

    // ------------------------------------------------------------------ carga

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        reglas.cargando("Cargando reglas…");
        escalas.cargando("Cargando escalas…");
        carga.cargar(() -> new Datos(a.admin().precios().reglas(), a.admin().precios().escalas(),
                a.admin().productos().listar(), a.admin().productos().categorias(), a.admin().sucursales().listar()), d -> {
            catalogo = d.productos();
            listaCategorias = d.categorias();
            listaSucursales = d.sucursales().stream().filter(s -> !s.esAlmacen()).toList();
            productos = porId(d.productos(), ProductoCatalogo::id);
            categorias = d.categorias().stream().collect(Collectors.toMap(ProductoRepository.Categoria::id,
                    ProductoRepository.Categoria::nombre, (x, y) -> x));
            sucursales = porId(d.sucursales(), Sucursal::id);
            Map<String, Presentacion> pres = new HashMap<>();
            d.productos().forEach(p -> p.presentaciones().forEach(pr -> pres.put(pr.id(), pr)));
            presentaciones = pres;
            Sucursal elegida = sucursalLista.getValue();
            List<Sucursal> opciones = conTodas();
            sucursalLista.setItems(FXCollections.observableArrayList(opciones));
            sucursalLista.setValue(elegida == null || elegida.id() == null ? TODAS
                    : opciones.stream().filter(s -> Objects.equals(s.id(), elegida.id())).findFirst().orElse(TODAS));
            reglas.setDatos(d.reglas());
            escalas.setDatos(d.escalas());
            actualizarMetricas(d.reglas());
            filtrarReglas();
            filtrarEscalas();
            cargarLista();
        }, e -> {
            reglas.error("No se pudieron cargar las reglas de precio.", AdminContexto.mensaje(e), this::cargar);
            escalas.error("No se pudieron cargar las escalas.", AdminContexto.mensaje(e), this::cargar);
        });
    }

    private List<Sucursal> conTodas() {
        List<Sucursal> opciones = new ArrayList<>();
        opciones.add(TODAS);
        opciones.addAll(listaSucursales);
        return opciones;
    }

    private static <T> Map<String, T> porId(List<T> lista, Function<T, String> id) {
        return lista.stream().collect(Collectors.toMap(id, x -> x, (x, y) -> x));
    }

    private static Optional<BigDecimal> decimal(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(texto.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
