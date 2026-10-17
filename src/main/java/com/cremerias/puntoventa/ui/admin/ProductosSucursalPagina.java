package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.InventarioSucursalService;
import com.cremerias.puntoventa.service.InventarioSucursalService.EstadoInventario;
import com.cremerias.puntoventa.service.InventarioSucursalService.FilaDetallada;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Productos por sucursal, solo lectura. La misma lista en tres vistas para que cada columna se lea
 * completa: inventario (existencia y reposición), precios y costos, y ventas y rotación del periodo.
 */
public class ProductosSucursalPagina extends Pagina {

    private enum Vista { INVENTARIO, PRECIOS, VENTAS }

    private enum Estado {
        TODOS("Todos los estados", null), AGOTADO("Agotado", EstadoInventario.AGOTADO),
        EN_MINIMO("En mínimo", EstadoInventario.EN_MINIMO), POR_REABASTECER("Orden pendiente", EstadoInventario.POR_REABASTECER),
        CON_EXISTENCIA("Con existencia", EstadoInventario.OK);

        final String texto;
        final EstadoInventario estado;

        Estado(String texto, EstadoInventario estado) {
            this.texto = texto;
            this.estado = estado;
        }
    }

    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);
    private static final String TODAS_CAT = "Todas";
    private static final String TODAS_MARCAS = "Todas";
    private static final String TEMP_TODOS = "Todos";
    private static final String TEMP_SI = "De temporada";
    private static final String TEMP_NO = "Regulares";

    /** Lotes y movimientos del producto en la sucursal, para el detalle. */
    private record DatosDetalle(List<LoteRepository.Lote> lotes, List<InventarioSucursalService.Movimiento> movimientos) {
    }

    private final Cargador carga = new Cargador();
    private final Cargador cargaDetalle = new Cargador();
    private BarraHerramientas barra;
    private ComboBox<Sucursal> sucursal;
    private ComboBox<Estado> estado;
    private ComboBox<String> categoria;
    private ComboBox<String> marca;
    private ComboBox<String> temporada;
    private CheckBox soloConExistencia;
    private DatePicker desdeVendido;
    private DatePicker hastaVendido;
    private Segmentado<Vista> vista;
    private TablaDatos<FilaDetallada> tabla;
    private TableColumn<FilaDetallada, ?> columnaSucursal;
    private final List<TableColumn<FilaDetallada, ?>> columnasInventario = new ArrayList<>();
    private final List<TableColumn<FilaDetallada, ?>> columnasPrecios = new ArrayList<>();
    private final List<TableColumn<FilaDetallada, ?>> columnasVentas = new ArrayList<>();
    /** Se ocultan con el detalle acoplado: quedan la sucursal, el producto y la cifra principal de la vista. */
    private final Set<TableColumn<FilaDetallada, ?>> secundarias = new HashSet<>();
    private boolean aplicandoVista;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;

    public ProductosSucursalPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Productos por sucursal";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Productos por sucursal",
                "Existencias, precios y ventas de lo que tiene cada sucursal. Solo consulta: se actualiza con los surtidos y las ventas.");

        vista = new Segmentado<Vista>()
                .opcion(Vista.INVENTARIO, "Inventario", "mdi2p-package-variant-closed")
                .opcion(Vista.PRECIOS, "Precios y costos", "mdi2t-tag-outline")
                .opcion(Vista.VENTAS, "Ventas y rotación", "mdi2c-chart-timeline-variant");
        LocalDate hoy = LocalDate.now();
        desdeVendido = new DatePicker(hoy.minusDays(29));
        hastaVendido = new DatePicker(hoy);
        for (DatePicker p : List.of(desdeVendido, hastaVendido)) {
            p.setEditable(false);
            p.setPrefWidth(150);
            p.valueProperty().addListener((o, x, y) -> cargar());
        }
        Label etiquetaPeriodo = new Label("Ventas del");
        etiquetaPeriodo.getStyleClass().add("etiqueta-filtro");
        Label al = new Label("al");
        al.getStyleClass().add("etiqueta-filtro");
        HBox periodo = new HBox(8, etiquetaPeriodo, desdeVendido, al, hastaVendido);
        periodo.setAlignment(Pos.CENTER_RIGHT);
        periodo.visibleProperty().bind(vista.valorProperty().isEqualTo(Vista.VENTAS));
        periodo.managedProperty().bind(periodo.visibleProperty());
        HBox barraVistas = new HBox(12, vista, Ui.espacio(), periodo);
        barraVistas.setAlignment(Pos.CENTER_LEFT);

        barra = new BarraHerramientas();
        barra.buscador("Buscar producto o código");
        sucursal = barra.filtro("Sucursal", new ComboBox<>(), TODAS, s -> s.id() == null ? s.nombre() : Formatos.sucursal(s));
        sucursal.setPrefWidth(250);
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        estado = barra.filtro("Estado", new ComboBox<>(FXCollections.observableArrayList(Estado.values())), Estado.TODOS,
                e -> e.texto);
        estado.setValue(Estado.TODOS);
        categoria = barra.filtroAdicional("Categoría", new ComboBox<>(FXCollections.observableArrayList(TODAS_CAT)),
                TODAS_CAT, c -> c);
        categoria.setValue(TODAS_CAT);
        marca = barra.filtroAdicional("Marca", new ComboBox<>(FXCollections.observableArrayList(TODAS_MARCAS)),
                TODAS_MARCAS, m -> m);
        marca.setValue(TODAS_MARCAS);
        temporada = barra.filtroAdicional("Temporada",
                new ComboBox<>(FXCollections.observableArrayList(TEMP_TODOS, TEMP_SI, TEMP_NO)), TEMP_TODOS, t -> t);
        temporada.setValue(TEMP_TODOS);
        soloConExistencia = new CheckBox("Solo con existencia");
        barra.filtroAdicional("", soloConExistencia, soloConExistencia::isSelected, soloConExistencia::getText,
                () -> soloConExistencia.setSelected(false), soloConExistencia.selectedProperty());
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(f -> f.sucursal().id() + "/" + f.fila().producto().id());
        columnaSucursal = tabla.dosLineas("Sucursal", f -> f.sucursal().nombre(), f -> f.sucursal().codigo())
                .ancho(150).columna();
        tabla.dosLineas("Producto", f -> f.fila().producto().nombre(), f -> detalleProducto(f.fila().producto()))
                .flexible(220);

        // Inventario
        columnasInventario.add(tabla.cantidad("Existencia", f -> f.fila().existencia(), f -> f.fila().producto().unidad())
                .ancho(120).columna());
        columnasInventario.add(secundaria(tabla.formato("Mínimo", f -> f.fila().minimo(),
                (f, v) -> f.fila().maximo().signum() == 0 ? "—" : Cantidades.formatear(f.fila().producto().unidad(), v))
                .numero().ancho(110).ayuda("Al llegar aquí la sucursal pide reabastecimiento.").columna()));
        columnasInventario.add(secundaria(tabla.formato("Máximo", f -> f.fila().maximo(),
                (f, v) -> v.signum() == 0 ? "Sin control" : Cantidades.formatear(f.fila().producto().unidad(), v))
                .numero().ancho(116).ayuda("Lo que se pide es hasta llegar a este máximo. «Sin control» = no se pide solo.")
                .columna()));
        // Solo las excepciones llevan etiqueta: una fila sin etiqueta tiene existencia y no espera orden.
        columnasInventario.add(tabla.etiqueta("Estado", FilaDetallada::estado,
                e -> e == EstadoInventario.OK ? null : textoEstado(e), ProductosSucursalPagina::tonoEstado)
                .ancho(150).ayuda("Solo se marcan los agotados, los que están en su mínimo y los que esperan su orden.")
                .columna());

        // Precios y costos
        columnasPrecios.add(tabla.formato("Precio principal", f -> precioPrincipal(f), (f, v) -> v < 0 ? "Sin precio"
                        : nombrePrincipal(f) + " · " + Dinero.formatear(v)).ancho(190)
                .ayuda("Precio actual de la presentación principal (del lote más antiguo con existencia).").columna());
        columnasPrecios.add(secundaria(tabla.textoLargo("Otros precios", ProductosSucursalPagina::otrosPrecios).ancho(200)
                .columna()));
        columnasPrecios.add(secundaria(tabla.dinero("Costo promedio", FilaDetallada::costoPromedioCentavos).ancho(132)
                .ayuda("Valor del inventario entre la existencia.").columna()));
        columnasPrecios.add(secundaria(tabla.dinero("Valor del inventario", FilaDetallada::valorInventarioCentavos).ancho(150)
                .ayuda("Existencia de cada lote por su costo.").columna()));

        // Ventas y rotación
        columnasVentas.add(tabla.cantidad("Vendido", FilaDetallada::cantidadVendida, f -> f.fila().producto().unidad())
                .ancho(120).ayuda("Cantidad vendida en el periodo elegido.").columna());
        columnasVentas.add(secundaria(tabla.dinero("Importe vendido", FilaDetallada::importeVendidoCentavos).ancho(140)
                .columna()));
        columnasVentas.add(secundaria(tabla.fecha("Última venta", FilaDetallada::ultimaVenta, Formatos.FECHA).ancho(124)
                .columna()));
        columnasVentas.add(tabla.formato("Días sin vender", f -> f.diasSinVenta() == null ? Long.MAX_VALUE : f.diasSinVenta(),
                        (f, v) -> f.diasSinVenta() == null ? "Nunca se ha vendido" : Texto.plural(v, "día", "días"))
                .numero().ancho(150).columna());

        tabla.setVacio(new EstadoVacio("mdi2s-storefront-outline", "Esta sucursal aún no tiene productos.",
                "Aparecen al surtirle productos o al capturarles mínimo y máximo."));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos productos con estos filtros.", barra::limpiar));
        tabla.setAlActivar(f -> {
            mostrarDetalle(f);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, f) -> {
            if (f != null && maestro.estaAbierto()) {
                mostrarDetalle(f);
            }
        });
        vista.valorProperty().addListener((o, x, v) -> aplicarVista());
        aplicarVista();

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 640);
        maestro.setAlCerrar(() -> {
            cargaDetalle.cancelar();
            tabla.tabla().requestFocus();
        });
        maestro.tablaReducidaProperty().addListener((o, x, y) -> aplicarVista());
        aplicarVista();
        pagina.getChildren().addAll(barraVistas, maestro);
        return Ui.ajustable(pagina, maestro, 320);
    }

    /**
     * Muestra las columnas de la vista elegida. Primero todas, para medir lo que piden; si con el
     * detalle acoplado no caben, se quitan las secundarias (el panel las muestra).
     */
    private void aplicarVista() {
        if (aplicandoVista) {
            return;
        }
        aplicandoVista = true;
        try {
            Vista v = vista.getValor();
            columnasInventario.forEach(c -> c.setVisible(v == Vista.INVENTARIO));
            columnasPrecios.forEach(c -> c.setVisible(v == Vista.PRECIOS));
            columnasVentas.forEach(c -> c.setVisible(v == Vista.VENTAS));
            if (maestro != null) {
                maestro.setAnchoCompleto(tabla.anchoNecesario());
                if (maestro.tablaReducida()) {
                    secundarias.forEach(c -> c.setVisible(false));
                }
            }
        } finally {
            aplicandoVista = false;
        }
    }

    private TableColumn<FilaDetallada, ?> secundaria(TableColumn<FilaDetallada, ?> columna) {
        secundarias.add(columna);
        return columna;
    }

    @Override
    public void alMostrar() {
        Cargador.ejecutar(() -> new Object[]{a.admin().sucursales().listar(), a.admin().productos().listar()}, datos -> {
            @SuppressWarnings("unchecked")
            List<Sucursal> reales = (List<Sucursal>) datos[0];
            @SuppressWarnings("unchecked")
            List<ProductoCatalogo> catalogo = (List<ProductoCatalogo>) datos[1];
            Sucursal actual = sucursal.getValue();
            List<Sucursal> opciones = new ArrayList<>();
            opciones.add(TODAS);
            opciones.addAll(reales);
            sucursal.setItems(FXCollections.observableArrayList(opciones));
            Sucursal elegida = actual == null || actual.id() == null ? TODAS
                    : opciones.stream().filter(s -> actual.id().equals(s.id())).findFirst().orElse(TODAS);
            ProductosPagina.actualizarOpciones(categoria, TODAS_CAT, catalogo.stream().map(ProductoCatalogo::categoria).toList());
            ProductosPagina.actualizarOpciones(marca, TODAS_MARCAS, catalogo.stream().map(ProductoCatalogo::marca).toList());
            if (sucursal.getValue() == elegida) {
                cargar();
            } else {
                sucursal.setValue(elegida);
            }
        }, e -> tabla.error("No se pudieron cargar las sucursales.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void cargar() {
        Sucursal s = sucursal.getValue();
        LocalDate desde = desdeVendido.getValue();
        LocalDate hasta = hastaVendido.getValue();
        if (s == null || desde == null || hasta == null) {
            return;
        }
        if (desde.isAfter(hasta)) {
            a.avisos().advertencia("El periodo de ventas no es válido: «desde» es después de «hasta».");
            return;
        }
        boolean todas = s == TODAS;
        columnaSucursal.setVisible(todas);
        tabla.cargando("Cargando productos de " + (todas ? "todas las sucursales" : s.nombre()) + "…");
        List<Sucursal> elegidas = todas ? sucursal.getItems().stream().filter(x -> x.id() != null).toList() : List.of(s);
        carga.cargar(() -> {
            List<FilaDetallada> lista = new ArrayList<>();
            for (Sucursal real : elegidas) {
                lista.addAll(a.ctx().inventarioSucursal().listarDetallado(a.usuario(), real, desde, hasta));
            }
            return lista;
        }, lista -> {
            tabla.setDatos(lista);
            filtrar();
            if (maestro.estaAbierto() && tabla.seleccionado() != null) {
                mostrarDetalle(tabla.seleccionado());
            }
        }, e -> {
            if (tabla.sinDatos()) {
                tabla.error("No se pudieron cargar los productos.", AdminContexto.mensaje(e), this::cargar);
            } else {
                a.avisos().errorConAccion("No se pudieron actualizar los productos: " + AdminContexto.mensaje(e),
                        "Reintentar", this::cargar);
            }
        });
    }

    private void filtrar() {
        String t = barra.busqueda();
        Estado e = estado.getValue() == null ? Estado.TODOS : estado.getValue();
        String cat = categoria.getValue() == null ? TODAS_CAT : categoria.getValue();
        String mar = marca.getValue() == null ? TODAS_MARCAS : marca.getValue();
        String temp = temporada.getValue() == null ? TEMP_TODOS : temporada.getValue();
        tabla.setFiltro(f -> {
            ProductoCatalogo p = f.fila().producto();
            boolean deTemporada = p.temporada() != null;
            return (t.isEmpty() || Texto.contiene(p.nombre(), t) || Texto.contiene(p.clave(), t)
                    || Texto.contiene(p.codigoInventario(), t))
                    && (e.estado == null || f.estado() == e.estado)
                    && (TODAS_CAT.equals(cat) || cat.equals(p.categoria()))
                    && (TODAS_MARCAS.equals(mar) || mar.equals(p.marca()))
                    && (TEMP_TODOS.equals(temp) || (TEMP_SI.equals(temp) == deTemporada))
                    && (!soloConExistencia.isSelected() || f.fila().existencia().signum() > 0);
        });
        long valor = tabla.visibles().stream().mapToLong(FilaDetallada::valorInventarioCentavos).sum();
        barra.setResultados((tabla.mostrados() == tabla.total() ? "" : tabla.mostrados() + " de ")
                + Texto.plural(tabla.total(), "producto", "productos") + " · Inventario " + Dinero.formatear(valor));
    }

    private static String detalleProducto(ProductoCatalogo p) {
        String codigos = Formatos.codigos(p, true);
        return (codigos.isBlank() ? "" : codigos + " · ") + Formatos.seVende(p.unidad());
    }

    private static String textoEstado(EstadoInventario e) {
        return switch (e) {
            case AGOTADO -> "Agotado";
            case EN_MINIMO -> "En mínimo";
            case POR_REABASTECER -> "Orden pendiente";
            case OK -> "Con existencia";
        };
    }

    private static Etiqueta.Tono tonoEstado(EstadoInventario e) {
        return switch (e) {
            case AGOTADO -> Etiqueta.Tono.PELIGRO;
            case EN_MINIMO -> Etiqueta.Tono.AVISO;
            case POR_REABASTECER -> Etiqueta.Tono.INFO;
            case OK -> Etiqueta.Tono.EXITO;
        };
    }

    /** Precio de la presentación principal (o la primera con precio); -1 si no tiene. */
    private static long precioPrincipal(FilaDetallada f) {
        List<InventarioSucursalService.Precio> precios = f.fila().precios();
        return precios.isEmpty() ? -1 : precios.getFirst().precioCentavos();
    }

    private static String nombrePrincipal(FilaDetallada f) {
        var pr = f.fila().precios().getFirst().presentacion();
        return pr.nombre() + (pr.granel() ? " (kg)" : "");
    }

    private static String otrosPrecios(FilaDetallada f) {
        List<InventarioSucursalService.Precio> precios = f.fila().precios();
        if (precios.size() <= 1) {
            return "—";
        }
        return precios.stream().skip(1).map(ProductosSucursalPagina::precio).collect(Collectors.joining(" · "));
    }

    private static String precio(InventarioSucursalService.Precio p) {
        return p.presentacion().nombre() + (p.presentacion().granel() ? " (kg)" : "") + ": "
                + Dinero.formatear(p.precioCentavos());
    }

    // ------------------------------------------------------------------ detalle

    private void mostrarDetalle(FilaDetallada f) {
        ProductoCatalogo p = f.fila().producto();
        detalle.setTitulo(p.nombre(), Formatos.sucursal(f.sucursal()));
        detalle.setEtiquetas(Etiqueta.de(textoEstado(f.estado()), tonoEstado(f.estado())),
                Etiqueta.simple(Formatos.seVende(p.unidad()), Etiqueta.Tono.NEUTRO));
        String[] partes = Formatos.cantidadPartida(p.unidad(), f.fila().existencia());
        VBox existencia = PanelDetalle.cifra("Existencia en la sucursal", partes[0], partes[1]);
        VBox reposicion = PanelDetalle.seccion("Reposición",
                f.fila().maximo().signum() == 0
                        ? Ui.texto("Sin control de reposición: el máximo está en 0, así que no se piden órdenes solas.",
                        "campo-ayuda")
                        : new VBox(6, PanelDetalle.dato("Mínimo", Cantidades.formatear(p.unidad(), f.fila().minimo())),
                        PanelDetalle.dato("Máximo", Cantidades.formatear(p.unidad(), f.fila().maximo()))));
        List<Node> filasPrecio = new ArrayList<>();
        for (InventarioSucursalService.Precio pr : f.fila().precios()) {
            filasPrecio.add(PanelDetalle.filaLista(pr.presentacion().nombre() + (pr.presentacion().granel() ? " (kg)" : ""),
                    null, Dinero.formatear(pr.precioCentavos()), null));
        }
        VBox precios = PanelDetalle.seccion("Precios de venta", filasPrecio.isEmpty()
                ? Ui.texto("Sin precio: aún no tiene lotes con existencia.", "campo-ayuda") : PanelDetalle.lista(filasPrecio));
        VBox costos = PanelDetalle.seccion("Costo",
                PanelDetalle.dato("Costo promedio", Dinero.formatear(f.costoPromedioCentavos())),
                PanelDetalle.dato("Valor del inventario", Dinero.formatear(f.valorInventarioCentavos())));
        VBox ventas = PanelDetalle.seccion("Ventas del periodo",
                PanelDetalle.dato("Vendido", Cantidades.formatear(p.unidad(), f.cantidadVendida())),
                PanelDetalle.dato("Importe", Dinero.formatear(f.importeVendidoCentavos())),
                PanelDetalle.dato("Última venta", f.ultimaVenta() == null ? "Nunca" : Formatos.fechaHora(f.ultimaVenta())));
        Temporada t = p.temporada();
        VBox lotes = PanelDetalle.seccion("Lotes en la sucursal", Ui.texto("Cargando lotes…", "texto-secundario"));
        VBox movimientos = PanelDetalle.seccion("Últimos movimientos", Ui.texto("Cargando movimientos…", "texto-secundario"));
        List<Node> secciones = new ArrayList<>(List.of(existencia, reposicion, precios, costos, ventas, lotes, movimientos));
        if (t != null) {
            secciones.add(2, PanelDetalle.seccion("Temporada", PanelDetalle.dato("Vigencia",
                    Formatos.FECHA.format(t.desde()) + " – " + Formatos.FECHA.format(t.fin())
                            + (t.repite() ? " (se repite " + t.describirRepeticion() + ")" : ""))));
        }
        detalle.setContenido(secciones.toArray(Node[]::new));
        detalle.setAcciones();
        cargaDetalle.cargar(() -> new DatosDetalle(a.ctx().inventarioSucursal().lotesDe(a.usuario(), p.id(), f.sucursal().id()),
                        a.ctx().inventarioSucursal().movimientos(a.usuario(), p.id(), f.sucursal().id(), 8)),
                d -> {
                    List<Node> filas = new ArrayList<>();
                    for (LoteRepository.Lote l : d.lotes()) {
                        if (l.existencia().signum() <= 0) {
                            continue;
                        }
                        filas.add(PanelDetalle.filaLista(Formatos.origenLote(l.origen(), l.folio()) + " · " + Formatos.fechaCorta(l.fechaIngreso()),
                                "Costo " + Dinero.formatear(l.costoUnitarioCentavos()) + Formatos.porUnidad(p.unidad()),
                                Cantidades.formatear(p.unidad(), l.existencia()), null));
                    }
                    lotes.getChildren().set(1, filas.isEmpty()
                            ? Ui.texto("Sin lotes con existencia.", "texto-secundario") : PanelDetalle.lista(filas));
                    List<Node> movs = new ArrayList<>();
                    for (InventarioSucursalService.Movimiento m : d.movimientos()) {
                        String signo = m.cantidad().signum() < 0 ? "−" : "+";
                        movs.add(PanelDetalle.filaLista(Formatos.movimiento(m.tipo()), Formatos.cuando(m.fecha()),
                                signo + Cantidades.formatear(p.unidad(), m.cantidad().abs()), null));
                    }
                    movimientos.getChildren().set(1, movs.isEmpty()
                            ? Ui.texto("Sin movimientos registrados.", "texto-secundario") : PanelDetalle.lista(movs));
                },
                e -> {
                    lotes.getChildren().set(1, Ui.texto("No se pudieron cargar los lotes.", "texto-error"));
                    movimientos.getChildren().set(1, Ui.texto("No se pudieron cargar los movimientos.", "texto-error"));
                });
    }
}
