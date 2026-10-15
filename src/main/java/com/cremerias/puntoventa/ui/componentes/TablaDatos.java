package com.cremerias.puntoventa.ui.componentes;

import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Tabla administrativa: {@link TableView} virtualizada con filtro ({@link FilteredList}) y orden
 * por columna ({@link SortedList}) sobre valores reales (importes en centavos, cantidades, fechas),
 * no sobre el texto ya formateado.
 *
 * <ul>
 *   <li>Las columnas conservan su ancho; si no caben, aparece desplazamiento horizontal en lugar de
 *       encogerlas hasta volverlas ilegibles. Una columna «flexible» toma el espacio que sobra.</li>
 *   <li>Al recargar los datos se conservan la selección (por clave) y la posición de desplazamiento.</li>
 *   <li>Sin datos se muestra un estado claro: cargando, sin registros, sin resultados para los
 *       filtros o error con «Reintentar».</li>
 *   <li>Las celdas se reciclan: cada una limpia texto, gráfico, ayuda y estilos al reutilizarse.</li>
 * </ul>
 */
public class TablaDatos<T> {

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Acción de una fila (botón de ícono o entrada del menú «Más acciones»). */
    public record Accion<T>(String icono, Function<T, String> texto, Consumer<T> ejecutar, Predicate<T> visible,
                            boolean peligro, boolean enMenu) {

        public static <T> Accion<T> boton(String icono, String texto, Consumer<T> ejecutar) {
            return new Accion<>(icono, x -> texto, ejecutar, x -> true, false, false);
        }

        public static <T> Accion<T> menu(String icono, Function<T, String> texto, Consumer<T> ejecutar) {
            return new Accion<>(icono, texto, ejecutar, x -> true, false, true);
        }

        public Accion<T> si(Predicate<T> condicion) {
            return new Accion<>(icono, texto, ejecutar, condicion, peligro, enMenu);
        }

        public Accion<T> peligrosa() {
            return new Accion<>(icono, texto, ejecutar, visible, true, enMenu);
        }
    }

    private record Marca<T>(PseudoClass clase, Predicate<T> cuando) {
    }

    private enum Estado { CARGANDO, DATOS, ERROR }

    private final ObservableList<T> fuente = FXCollections.observableArrayList();
    private final FilteredList<T> filtrados = new FilteredList<>(fuente, x -> true);
    private final SortedList<T> ordenados = new SortedList<>(filtrados);
    private final TableView<T> tabla = new TableView<>(ordenados);
    private final ReadOnlyObjectWrapper<T> seleccionado = new ReadOnlyObjectWrapper<>();
    private final List<Marca<T>> marcas = new ArrayList<>();
    private Function<T, ?> clave = Function.identity();
    private Predicate<T> filtro = x -> true;
    private Consumer<T> alActivar;
    private TableColumn<T, ?> flexible;
    private double minimoFlexible = 180;
    private ScrollBar barraVertical;
    private Estado estado = Estado.CARGANDO;
    private Node nodoCargando = EstadoVacio.cargando("Cargando…");
    private Node nodoVacio = new EstadoVacio("mdi2t-tray-remove", "Sin registros", null);
    private Node nodoSinResultados = EstadoVacio.sinResultados("No hay resultados con estos filtros.", null);
    private Node nodoError;

    public TablaDatos(Diseno.Densidad densidad) {
        tabla.getStyleClass().add("tabla-datos");
        tabla.setFixedCellSize(densidad.alto());
        tabla.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tabla.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        ordenados.comparatorProperty().bind(tabla.comparatorProperty());
        tabla.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> seleccionado.set(b));
        tabla.setRowFactory(t -> new Fila());
        tabla.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && alActivar != null && seleccionado.get() != null) {
                alActivar.accept(seleccionado.get());
                e.consume();
            }
        });
        fuente.addListener((javafx.collections.ListChangeListener<T>) c -> actualizarPlaceholder());
        filtrados.addListener((javafx.collections.ListChangeListener<T>) c -> actualizarPlaceholder());
        tabla.widthProperty().addListener((o, a, b) -> ajustarFlexible());
        tabla.skinProperty().addListener((o, a, b) -> Platform.runLater(this::alCrearSkin));
        actualizarPlaceholder();
    }

    // ------------------------------------------------------------------ datos

    public TableView<T> tabla() {
        return tabla;
    }

    /** Clave estable de cada registro (id) para conservar la selección al recargar. */
    public void setClave(Function<T, ?> clave) {
        this.clave = clave;
    }

    /** Reemplaza los datos conservando la fila seleccionada y la posición. */
    public void setDatos(List<T> datos) {
        T antes = seleccionado.get();
        Object claveAntes = antes == null ? null : clave.apply(antes);
        int primera = primeraFilaVisible();
        estado = Estado.DATOS;
        fuente.setAll(datos);
        if (claveAntes != null) {
            for (int i = 0; i < ordenados.size(); i++) {
                if (Objects.equals(clave.apply(ordenados.get(i)), claveAntes)) {
                    tabla.getSelectionModel().clearAndSelect(i);
                    break;
                }
            }
        }
        if (primera > 0 && !ordenados.isEmpty()) {
            tabla.scrollTo(Math.min(primera, ordenados.size() - 1));
        }
        actualizarPlaceholder();
    }

    public List<T> datos() {
        return List.copyOf(fuente);
    }

    /** Filas que pasan el filtro, en el orden en que se ven. */
    public List<T> visibles() {
        return List.copyOf(ordenados);
    }

    public int total() {
        return fuente.size();
    }

    public int mostrados() {
        return filtrados.size();
    }

    public void setFiltro(Predicate<T> filtro) {
        this.filtro = filtro;
        refiltrar();
    }

    /** Vuelve a aplicar el filtro (cuando cambió algo de lo que depende). */
    public void refiltrar() {
        Predicate<T> p = filtro;
        filtrados.setPredicate(x -> p.test(x));
        actualizarPlaceholder();
    }

    /** Muestra «cargando» solo si aún no hay datos (al recargar se quedan los anteriores). */
    public void cargando(String mensaje) {
        if (estado == Estado.CARGANDO || fuente.isEmpty()) {
            nodoCargando = EstadoVacio.cargando(mensaje);
            estado = Estado.CARGANDO;
            actualizarPlaceholder();
        }
    }

    /** Error de carga. Si ya había datos se conservan (la página avisa aparte). */
    public void error(String titulo, String detalle, Runnable reintentar) {
        nodoError = EstadoVacio.error(titulo, detalle, reintentar);
        estado = Estado.ERROR;
        actualizarPlaceholder();
    }

    public boolean sinDatos() {
        return fuente.isEmpty();
    }

    public void setVacio(Node vacio) {
        this.nodoVacio = vacio;
        actualizarPlaceholder();
    }

    public void setSinResultados(Node sinResultados) {
        this.nodoSinResultados = sinResultados;
        actualizarPlaceholder();
    }

    private void actualizarPlaceholder() {
        Node p;
        if (fuente.isEmpty()) {
            p = estado == Estado.CARGANDO ? nodoCargando : estado == Estado.ERROR && nodoError != null ? nodoError : nodoVacio;
        } else {
            p = nodoSinResultados;
        }
        if (tabla.getPlaceholder() != p) {
            tabla.setPlaceholder(p);
        }
    }

    // ------------------------------------------------------------------ selección

    public ReadOnlyObjectProperty<T> seleccionadoProperty() {
        return seleccionado.getReadOnlyProperty();
    }

    public T seleccionado() {
        return seleccionado.get();
    }

    public void limpiarSeleccion() {
        tabla.getSelectionModel().clearSelection();
    }

    /** Clic en una fila o Enter: abre su detalle (los botones de la fila no lo disparan). */
    public void setAlActivar(Consumer<T> accion) {
        this.alActivar = accion;
    }

    /** Marca filas con una pseudoclase CSS (p. ej. {@code :modificada}). */
    public void marcar(String pseudoclase, Predicate<T> cuando) {
        marcas.add(new Marca<>(PseudoClass.getPseudoClass(pseudoclase), cuando));
    }

    /** Vuelve a dibujar las filas visibles (marcas o datos calculados que cambiaron). */
    public void redibujar() {
        tabla.refresh();
    }

    private final class Fila extends TableRow<T> {

        Fila() {
            setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY || isEmpty() || getItem() == null || alActivar == null) {
                    return;
                }
                for (Node n = (Node) e.getTarget(); n != null && n != this; n = n.getParent()) {
                    if (n instanceof ButtonBase || n instanceof javafx.scene.control.TextInputControl) {
                        return;
                    }
                }
                alActivar.accept(getItem());
            });
        }

        @Override
        protected void updateItem(T item, boolean vacio) {
            super.updateItem(item, vacio);
            for (Marca<T> m : marcas) {
                pseudoClassStateChanged(m.clase(), !vacio && item != null && m.cuando().test(item));
            }
        }
    }

    // ------------------------------------------------------------------ anchos

    private void alCrearSkin() {
        for (Node n : tabla.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL && barraVertical == null) {
                barraVertical = sb;
                sb.visibleProperty().addListener((o, a, b) -> ajustarFlexible());
            }
        }
        instalarAyudasEncabezado();
        ajustarFlexible();
    }

    /** El espacio sobrante es para la columna flexible; si no cabe todo, se desplaza horizontalmente. */
    private void ajustarFlexible() {
        if (flexible == null || !flexible.isVisible() || tabla.getWidth() <= 0) {
            return;
        }
        double disponible = tabla.getWidth() - tabla.snappedLeftInset() - tabla.snappedRightInset()
                - (barraVertical != null && barraVertical.isVisible() ? barraVertical.getWidth() : 0) - 1;
        double otras = 0;
        for (TableColumn<T, ?> c : tabla.getVisibleLeafColumns()) {
            if (c != flexible) {
                otras += c.getWidth();
            }
        }
        double ancho = Math.max(minimoFlexible, Math.floor(disponible - otras));
        if (Math.abs(flexible.getPrefWidth() - ancho) > 0.5) {
            flexible.setPrefWidth(ancho);
        }
    }

    private void instalarAyudasEncabezado() {
        for (Node n : tabla.lookupAll(".column-header")) {
            if (n instanceof TableColumnHeader h && h.getTableColumn() != null
                    && h.getTableColumn().getProperties().get("ayuda") instanceof String ayuda) {
                Tooltip.install(h, new Tooltip(ayuda));
            }
        }
    }

    private int primeraFilaVisible() {
        if (tabla.lookup(".virtual-flow") instanceof VirtualFlow<?> flujo && flujo.getFirstVisibleCell() != null) {
            return flujo.getFirstVisibleCell().getIndex();
        }
        return -1;
    }

    // ------------------------------------------------------------------ columnas

    /** Ajustes de una columna: ancho, alineación, orden, ayuda. */
    public final class Columna<V> {

        private final TableColumn<T, V> columna;

        private Columna(TableColumn<T, V> columna) {
            this.columna = columna;
            columna.setPrefWidth(120);
            columna.setMinWidth(56);
            columna.widthProperty().addListener((o, a, b) -> {
                if (columna != flexible) {
                    ajustarFlexible();
                }
            });
            columna.visibleProperty().addListener((o, a, b) -> ajustarFlexible());
            tabla.getColumns().add(columna);
        }

        public Columna<V> ancho(double ancho) {
            columna.setPrefWidth(ancho);
            return this;
        }

        public Columna<V> minimo(double minimo) {
            columna.setMinWidth(minimo);
            return this;
        }

        /** Toma el espacio libre de la tabla (normalmente el nombre). */
        public Columna<V> flexible(double minimo) {
            flexible = columna;
            minimoFlexible = minimo;
            columna.setMinWidth(minimo);
            return this;
        }

        public Columna<V> numero() {
            columna.getStyleClass().add("columna-numero");
            return this;
        }

        public Columna<V> clase(String clase) {
            columna.getStyleClass().add(clase);
            return this;
        }

        public Columna<V> sinOrden() {
            columna.setSortable(false);
            return this;
        }

        public Columna<V> orden(Comparator<V> comparador) {
            columna.setComparator(comparador);
            return this;
        }

        /** Ayuda al pasar el mouse sobre el encabezado (no sustituye un título claro). */
        public Columna<V> ayuda(String texto) {
            columna.getProperties().put("ayuda", texto);
            return this;
        }

        /** Oculta al inicio; se puede mostrar desde el menú de columnas de la tabla. */
        public Columna<V> oculta() {
            columna.setVisible(false);
            tabla.setTableMenuButtonVisible(true);
            return this;
        }

        public TableColumn<T, V> columna() {
            return columna;
        }
    }

    /** Texto simple. Ordena alfabéticamente en español. */
    public Columna<String> texto(String titulo, Function<T, String> valor) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setComparator(Texto.ORDEN);
        c.setCellFactory(col -> new CeldaTexto<>(Function.identity(), null, false));
        return new Columna<>(c);
    }

    /** Texto principal con una línea secundaria debajo; muestra el texto completo al pasar el mouse. */
    public Columna<String> dosLineas(String titulo, Function<T, String> principal, Function<T, String> secundaria) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(principal.apply(f.getValue())));
        c.setComparator(Texto.ORDEN);
        c.setCellFactory(col -> new CeldaTexto<>(Function.identity(), secundaria, true));
        return new Columna<>(c);
    }

    /** Texto largo que puede recortarse: el completo aparece en la ayuda. */
    public Columna<String> textoLargo(String titulo, Function<T, String> valor) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setComparator(Texto.ORDEN);
        c.setCellFactory(col -> new CeldaTexto<>(Function.identity(), null, true));
        return new Columna<>(c);
    }

    /** Importe en centavos: alineado a la derecha y ordenado como número. */
    public Columna<Long> dinero(String titulo, Function<T, Long> centavos) {
        TableColumn<T, Long> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(centavos.apply(f.getValue())));
        c.setCellFactory(col -> new CeldaFormato<>(v -> v == null ? "—" : Dinero.formatear(v)));
        return new Columna<>(c).numero().ancho(120);
    }

    /** Cantidad con su unidad: ordena primero por unidad (piezas, kilos) y luego por cantidad. */
    public record Cantidad(Unidad unidad, BigDecimal valor) implements Comparable<Cantidad> {

        @Override
        public int compareTo(Cantidad otra) {
            int u = unidad.compareTo(otra.unidad);
            return u != 0 ? u : valor.compareTo(otra.valor);
        }

        @Override
        public String toString() {
            return Cantidades.formatear(unidad, valor);
        }
    }

    /** Cantidad en unidad base con su unidad visible ("1,250 g", "12 pzas"). */
    public Columna<Cantidad> cantidad(String titulo, Function<T, BigDecimal> cantidad, Function<T, Unidad> unidad) {
        TableColumn<T, Cantidad> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> {
            BigDecimal v = cantidad.apply(f.getValue());
            return new ReadOnlyObjectWrapper<>(v == null ? null : new Cantidad(unidad.apply(f.getValue()), v));
        });
        c.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        c.setCellFactory(col -> new CeldaFormato<>(v -> v == null ? "—" : v.toString()));
        return new Columna<>(c).numero().ancho(120);
    }

    /**
     * Valor que se ordena por sí mismo y se muestra con ayuda de la fila (ej. costo "$98.90 / kg",
     * que ordena por centavos y toma la unidad del producto).
     */
    public <V extends Comparable<V>> Columna<V> formato(String titulo, Function<T, V> valor,
                                                       java.util.function.BiFunction<T, V, String> formato) {
        TableColumn<T, V> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(V v, boolean vacio) {
                super.updateItem(v, vacio);
                setGraphic(null);
                T fila = vacio ? null : fila(this);
                setText(fila == null ? null : formato.apply(fila, v));
            }
        });
        return new Columna<>(c);
    }

    /** Número cualquiera (conteos, porcentajes) alineado a la derecha y ordenado como número. */
    public <N extends Number & Comparable<N>> Columna<N> numero(String titulo, Function<T, N> valor,
                                                                Function<N, String> formato) {
        TableColumn<T, N> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setCellFactory(col -> new CeldaFormato<>(v -> v == null ? "—" : formato.apply(v)));
        return new Columna<>(c).numero().ancho(96);
    }

    /** Fecha y hora; ordena cronológicamente. */
    public Columna<Instant> fecha(String titulo, Function<T, Instant> valor, DateTimeFormatter formato) {
        TableColumn<T, Instant> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        DateTimeFormatter fmt = formato == null ? FECHA_HORA : formato;
        c.setCellFactory(col -> new CeldaFormato<>(v -> v == null ? "—" : fmt.format(v.atZone(ZoneId.systemDefault()))));
        c.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        return new Columna<>(c).ancho(140);
    }

    /** Etiqueta de estado; ordena por el valor (enumeración, texto) que la produce. */
    public <V extends Comparable<V>> Columna<V> etiqueta(String titulo, Function<T, V> valor, Function<V, String> texto,
                                                        Function<V, Etiqueta.Tono> tono) {
        TableColumn<T, V> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(valor.apply(f.getValue())));
        c.setComparator(Comparator.nullsFirst(Comparator.naturalOrder()));
        c.setCellFactory(col -> new TableCell<>() {
            private final Label etiqueta = Etiqueta.de("", Etiqueta.Tono.NEUTRO);

            @Override
            protected void updateItem(V v, boolean vacio) {
                super.updateItem(v, vacio);
                setText(null);
                if (vacio || v == null || texto.apply(v) == null) {
                    setGraphic(null);
                    return;
                }
                Etiqueta.aplicar(etiqueta, texto.apply(v), tono.apply(v));
                setGraphic(etiqueta);
            }
        });
        return new Columna<>(c).ancho(140);
    }

    /**
     * Celda con un nodo propio (varias etiquetas, etc.). Para contenidos que cambian poco: el nodo
     * se crea al mostrar cada fila y el anterior se descarta (no lleva escuchas que lo retengan).
     */
    public Columna<T> nodo(String titulo, Function<T, Node> fabrica) {
        TableColumn<T, T> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setSortable(false);
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean vacio) {
                super.updateItem(item, vacio);
                setText(null);
                setGraphic(vacio || item == null ? null : fabrica.apply(item));
            }
        });
        return new Columna<>(c);
    }

    /** Acciones de la fila: botones de ícono con nombre accesible y, si hay, un menú «Más acciones». */
    @SafeVarargs
    public final Columna<T> acciones(Accion<T>... acciones) {
        TableColumn<T, T> c = new TableColumn<>("");
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setSortable(false);
        c.setReorderable(false);
        c.setResizable(false);
        c.setCellFactory(col -> new CeldaAcciones(List.of(acciones)));
        long botones = java.util.Arrays.stream(acciones).filter(a -> !a.enMenu()).count();
        boolean hayMenu = java.util.Arrays.stream(acciones).anyMatch(Accion::enMenu);
        double ancho = 14 + 34 * (botones + (hayMenu ? 1 : 0));
        Columna<T> columna = new Columna<>(c).clase("columna-acciones");
        columna.ancho(ancho).minimo(ancho);
        return columna;
    }

    private static <T> T fila(TableCell<T, ?> celda) {
        int i = celda.getIndex();
        List<T> items = celda.getTableView() == null ? List.of() : celda.getTableView().getItems();
        return i >= 0 && i < items.size() ? items.get(i) : null;
    }

    /** Texto con línea secundaria opcional; las etiquetas se crean una vez por celda. */
    private final class CeldaTexto<V> extends TableCell<T, V> {

        private final Function<V, String> texto;
        private final Function<T, String> secundaria;
        private final boolean ayuda;
        private final Label principal = new Label();
        private final Label segunda = new Label();
        private final VBox caja = new VBox(1, principal, segunda);
        private final Tooltip tooltip = new Tooltip();

        CeldaTexto(Function<V, String> texto, Function<T, String> secundaria, boolean ayuda) {
            this.texto = texto;
            this.secundaria = secundaria;
            this.ayuda = ayuda;
            principal.getStyleClass().add("celda-principal");
            segunda.getStyleClass().add("celda-secundaria");
            segunda.managedProperty().bind(segunda.visibleProperty());
            caja.setAlignment(Pos.CENTER_LEFT);
            caja.setMinWidth(0);
            principal.setMinWidth(0);
            segunda.setMinWidth(0);
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(420);
        }

        @Override
        protected void updateItem(V valor, boolean vacio) {
            super.updateItem(valor, vacio);
            if (vacio || valor == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            String t = texto.apply(valor);
            if (secundaria == null) {
                setText(t);
                setGraphic(null);
            } else {
                T fila = fila(this);
                String s = fila == null ? null : secundaria.apply(fila);
                principal.setText(t);
                segunda.setText(s == null ? "" : s);
                segunda.setVisible(s != null && !s.isBlank());
                setText(null);
                setGraphic(caja);
                t = s == null || s.isBlank() ? t : t + "\n" + s;
            }
            if (ayuda && t != null && !t.isBlank()) {
                tooltip.setText(t);
                setTooltip(tooltip);
            } else {
                setTooltip(null);
            }
        }
    }

    /** Valor formateado como texto (importes, números, fechas). */
    private final class CeldaFormato<V> extends TableCell<T, V> {

        private final Function<V, String> formato;

        CeldaFormato(Function<V, String> formato) {
            this.formato = formato;
        }

        @Override
        protected void updateItem(V valor, boolean vacio) {
            super.updateItem(valor, vacio);
            setGraphic(null);
            setText(vacio ? null : formato.apply(valor));
        }
    }

    /** Botones creados una vez; en cada fila solo cambia cuáles se ven y su descripción. */
    private final class CeldaAcciones extends TableCell<T, T> {

        private final List<Accion<T>> acciones;
        private final List<Button> botones = new ArrayList<>();
        private final List<MenuItem> entradas = new ArrayList<>();
        private final HBox caja = new HBox(2);
        private MenuButton mas;

        CeldaAcciones(List<Accion<T>> acciones) {
            this.acciones = acciones;
            caja.getStyleClass().add("acciones-fila");
            caja.setAlignment(Pos.CENTER_RIGHT);
            for (Accion<T> a : acciones) {
                if (a.enMenu()) {
                    if (mas == null) {
                        mas = new MenuButton(null, new FontIcon("mdi2d-dots-horizontal"));
                        mas.getStyleClass().addAll("flat", "boton-icono", "boton-mas");
                        mas.setAccessibleText("Más acciones");
                        mas.setTooltip(new Tooltip("Más acciones"));
                        mas.setOnShowing(e -> prepararMenu());
                    }
                    MenuItem m = new MenuItem();
                    m.setGraphic(new FontIcon(a.icono()));
                    if (a.peligro()) {
                        m.getStyleClass().add("peligro");
                    }
                    m.setOnAction(e -> {
                        T item = getItem();
                        if (item != null) {
                            a.ejecutar().accept(item);
                        }
                    });
                    entradas.add(m);
                    mas.getItems().add(m);
                } else {
                    Button b = new Button(null, new FontIcon(a.icono()));
                    b.getStyleClass().addAll("flat", "boton-icono");
                    if (a.peligro()) {
                        b.getStyleClass().add("danger");
                    }
                    b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    b.setTooltip(new Tooltip());
                    b.managedProperty().bind(b.visibleProperty());
                    b.setOnAction(e -> {
                        T item = getItem();
                        if (item != null) {
                            a.ejecutar().accept(item);
                        }
                    });
                    botones.add(b);
                    caja.getChildren().add(b);
                }
            }
            if (mas != null) {
                caja.getChildren().add(mas);
                mas.setMinSize(32, 32);
                mas.setPrefSize(32, 32);
                mas.setMaxSize(32, 32);
            }
            caja.setMinWidth(Region.USE_PREF_SIZE);
        }

        private void prepararMenu() {
            T item = getItem();
            int i = 0;
            for (Accion<T> a : acciones) {
                if (a.enMenu()) {
                    MenuItem m = entradas.get(i++);
                    m.setText(item == null ? "" : a.texto().apply(item));
                    m.setVisible(item != null && a.visible().test(item));
                }
            }
        }

        @Override
        protected void updateItem(T item, boolean vacio) {
            super.updateItem(item, vacio);
            setText(null);
            if (vacio || item == null) {
                setGraphic(null);
                return;
            }
            int i = 0;
            for (Accion<T> a : acciones) {
                if (!a.enMenu()) {
                    Button b = botones.get(i++);
                    String texto = a.texto().apply(item);
                    b.setVisible(a.visible().test(item));
                    b.setAccessibleText(texto);
                    b.getTooltip().setText(texto);
                }
            }
            if (mas != null) {
                boolean alguna = acciones.stream().anyMatch(a -> a.enMenu() && a.visible().test(item));
                mas.setVisible(alguna);
            }
            setGraphic(caja);
        }
    }
}
