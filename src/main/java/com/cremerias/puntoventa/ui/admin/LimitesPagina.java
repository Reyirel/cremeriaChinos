package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.theme.Styles;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.service.admin.LimitesService;
import com.cremerias.puntoventa.ui.componentes.BarraCambios;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Mínimos y máximos de cada producto por sucursal. Cuando la existencia llega al mínimo se
 * genera una orden de reabastecimiento por (máximo − existencia). Un máximo en 0 significa que el
 * producto no se controla (no se piden órdenes solas): así funcionaba y así sigue.
 */
public class LimitesPagina extends Pagina {

    private static final PseudoClass MODIFICADA = PseudoClass.getPseudoClass("modificada");
    private static final PseudoClass MODIFICADO = PseudoClass.getPseudoClass("modificado");

    /** Qué pasa con la reposición del producto con los valores capturados. */
    private enum Reposicion {
        REVISAR("Máximo menor que el mínimo", Etiqueta.Tono.PELIGRO),
        EN_MINIMO("En su mínimo", Etiqueta.Tono.AVISO),
        CONTROLADO("Con reposición", Etiqueta.Tono.EXITO),
        SIN_CONTROL("Sin control", Etiqueta.Tono.NEUTRO);

        final String texto;
        final Etiqueta.Tono tono;

        Reposicion(String texto, Etiqueta.Tono tono) {
            this.texto = texto;
            this.tono = tono;
        }
    }

    private enum Filtro {
        TODOS("Todos los productos"), CON_CONTROL("Con reposición"), SIN_CONTROL("Sin control"),
        EN_MINIMO("En su mínimo"), MODIFICADOS("Con cambios sin guardar");

        final String texto;

        Filtro(String texto) {
            this.texto = texto;
        }
    }

    /** Renglón editable. */
    private static final class Editable {
        final LimitesService.Fila fila;
        final StringProperty minimo = new SimpleStringProperty();
        final StringProperty maximo = new SimpleStringProperty();
        final String minimoOriginal;
        final String maximoOriginal;

        Editable(LimitesService.Fila fila) {
            this.fila = fila;
            minimoOriginal = Cantidades.aCaptura(fila.producto().unidad(), fila.minimo());
            maximoOriginal = Cantidades.aCaptura(fila.producto().unidad(), fila.maximo());
            minimo.set(minimoOriginal);
            maximo.set(maximoOriginal);
        }

        boolean modificado() {
            return !minimoOriginal.equals(texto(minimo)) || !maximoOriginal.equals(texto(maximo));
        }

        void descartar() {
            minimo.set(minimoOriginal);
            maximo.set(maximoOriginal);
        }

        Unidad unidad() {
            return fila.producto().unidad();
        }

        BigDecimal valor(StringProperty p) {
            return Cantidades.desdeCaptura(unidad(), p.get()).orElse(BigDecimal.ZERO);
        }

        /** Vacío cuenta como 0, igual que antes. */
        private static String texto(StringProperty p) {
            return p.get() == null || p.get().isBlank() ? "0" : p.get().strip();
        }

        Reposicion reposicion() {
            BigDecimal min = valor(minimo);
            BigDecimal max = valor(maximo);
            if (max.signum() == 0) {
                return Reposicion.SIN_CONTROL;
            }
            if (max.compareTo(min) < 0) {
                return Reposicion.REVISAR;
            }
            return fila.existencia().compareTo(min) <= 0 ? Reposicion.EN_MINIMO : Reposicion.CONTROLADO;
        }
    }

    private final Cargador carga = new Cargador();
    private BarraHerramientas barra;
    private ComboBox<Sucursal> sucursal;
    private ComboBox<Filtro> filtro;
    private TablaDatos<Editable> tabla;
    private BarraCambios barraCambios;
    private List<Editable> filas = List.of();
    private Sucursal sucursalCargada;
    private boolean revirtiendo;

    public LimitesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Mínimos y máximos";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    public boolean cambiosPendientes() {
        return filas.stream().anyMatch(Editable::modificado);
    }

    @Override
    public void descartarCambios() {
        filas.forEach(Editable::descartar);
        tabla.redibujar();
        actualizarCambios();
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Mínimos y máximos",
                "Por sucursal. Al llegar al mínimo, la sucursal pide sola lo que falta para el máximo. "
                        + "Con el máximo en 0 el producto queda sin control (no se piden órdenes).");

        barra = new BarraHerramientas();
        sucursal = new ComboBox<>();
        sucursal.setConverter(Combos.sucursal());
        sucursal.setPromptText("Elige la sucursal");
        sucursal.setAccessibleText("Sucursal");
        barra.agregar(sucursal);
        sucursal.setPrefWidth(260);
        sucursal.valueProperty().addListener((o, antes, ahora) -> cambiarSucursal(antes, ahora));
        barra.buscador("Buscar producto");
        filtro = barra.filtro("Mostrar", new ComboBox<>(FXCollections.observableArrayList(Filtro.values())), Filtro.TODOS,
                f -> f.texto);
        filtro.setValue(Filtro.TODOS);
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(e -> e.fila.producto().id());
        tabla.dosLineas("Producto", e -> e.fila.producto().nombre(), e -> Formatos.codigos(e.fila.producto(), true))
                .flexible(240);
        tabla.cantidad("Existencia", e -> e.fila.existencia(), Editable::unidad).ancho(130);
        editable("Mínimo", e -> e.minimo, "Al llegar aquí se pide reabastecimiento.");
        editable("Máximo", e -> e.maximo, "Se pide lo que falta para llegar aquí. En 0: sin control.");
        TableColumn<Editable, Editable> estado = new TableColumn<>("Reposición");
        estado.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        estado.setSortable(false);
        estado.setPrefWidth(230);
        estado.setCellFactory(c -> new CeldaReposicion());
        tabla.tabla().getColumns().add(estado);
        tabla.marcar("modificada", Editable::modificado);
        tabla.setVacio(new EstadoVacio("mdi2t-tune-vertical", "Esta sucursal aún no tiene productos.",
                "Aparecen aquí en cuanto se le surten (no todas las sucursales venden lo mismo)."));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos productos con estos filtros.", barra::limpiar));

        barraCambios = new BarraCambios(this::confirmarDescartar, this::guardar);
        VBox panel = Ui.panelTabla(barra, tabla);
        VBox.setVgrow(panel, Priority.ALWAYS);
        VBox contenedor = new VBox(12, panel, barraCambios);
        contenedor.setAlignment(Pos.TOP_CENTER);
        pagina.getChildren().add(contenedor);
        return Ui.ajustable(pagina, contenedor, 320);
    }

    /** Columna con un campo numérico y su unidad; el campo se crea una vez por celda. */
    private void editable(String titulo, Function<Editable, StringProperty> propiedad, String ayuda) {
        TableColumn<Editable, Editable> c = new TableColumn<>(titulo);
        c.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        c.setPrefWidth(160);
        c.setMinWidth(150);
        c.setSortable(false);
        c.getProperties().put("ayuda", ayuda);
        c.setCellFactory(col -> new TableCell<>() {
            private final TextField campo = new TextField();
            private final Label unidad = new Label();
            private final HBox caja = new HBox(6, campo, unidad);
            private final ChangeListener<String> alEscribir = (o, x, y) -> marcar();
            private StringProperty ligada;
            private Editable actual;

            {
                Campos.soloNumeros(campo, 0);
                campo.getStyleClass().add("campo-celda");
                campo.setPrefColumnCount(6);
                campo.setPromptText("0");
                unidad.getStyleClass().add("unidad-campo");
                unidad.setMinWidth(34);
                caja.setAlignment(Pos.CENTER_LEFT);
                campo.textProperty().addListener(alEscribir);
            }

            private void marcar() {
                if (actual == null) {
                    return;
                }
                campo.pseudoClassStateChanged(MODIFICADO, actual.modificado());
                campo.pseudoClassStateChanged(Styles.STATE_DANGER, actual.reposicion() == Reposicion.REVISAR);
                if (getTableRow() != null) {
                    getTableRow().pseudoClassStateChanged(MODIFICADA, actual.modificado());
                }
                actualizarCambios();
            }

            @Override
            protected void updateItem(Editable item, boolean vacio) {
                super.updateItem(item, vacio);
                if (ligada != null) {
                    campo.textProperty().unbindBidirectional(ligada);
                    ligada = null;
                }
                actual = null;
                campo.pseudoClassStateChanged(MODIFICADO, false);
                campo.pseudoClassStateChanged(Styles.STATE_DANGER, false);
                if (vacio || item == null) {
                    setGraphic(null);
                    return;
                }
                ligada = propiedad.apply(item);
                campo.textProperty().bindBidirectional(ligada);
                campo.setAccessibleText(titulo + " de " + item.fila.producto().nombre());
                unidad.setText(Cantidades.unidadCaptura(item.unidad()));
                actual = item;
                marcar();
                setGraphic(caja);
            }
        });
        tabla.tabla().getColumns().add(c);
    }

    /** Estado de reposición que cambia mientras se escribe (escucha los dos campos de su fila). */
    private final class CeldaReposicion extends TableCell<Editable, Editable> {
        private final Label etiqueta = Etiqueta.de("", Etiqueta.Tono.NEUTRO);
        private final Label nota = new Label();
        private final VBox caja = new VBox(2, etiqueta, nota);
        private final ChangeListener<String> alCambiar = (o, x, y) -> pintar();
        private Editable actual;

        CeldaReposicion() {
            nota.getStyleClass().add("celda-secundaria");
            nota.managedProperty().bind(nota.visibleProperty());
            caja.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Editable item, boolean vacio) {
            super.updateItem(item, vacio);
            if (actual != null) {
                actual.minimo.removeListener(alCambiar);
                actual.maximo.removeListener(alCambiar);
                actual = null;
            }
            if (vacio || item == null) {
                setGraphic(null);
                return;
            }
            actual = item;
            item.minimo.addListener(alCambiar);
            item.maximo.addListener(alCambiar);
            pintar();
            setGraphic(caja);
        }

        private void pintar() {
            Reposicion r = actual.reposicion();
            Etiqueta.aplicar(etiqueta, r.texto, r.tono);
            String texto = switch (r) {
                case SIN_CONTROL -> "Máximo en 0: no se piden órdenes";
                case EN_MINIMO -> actual.modificado() ? "Al guardar se genera su orden" : "Ya tiene o tendrá su orden";
                case REVISAR -> "Corrige antes de guardar";
                case CONTROLADO -> null;
            };
            nota.setText(texto == null ? "" : texto);
            nota.setVisible(texto != null);
        }
    }

    @Override
    public void alMostrar() {
        Cargador.ejecutar(() -> a.admin().sucursales().activas(), activas -> {
            Sucursal actual = sucursal.getValue();
            revirtiendo = true;
            sucursal.setItems(FXCollections.observableArrayList(activas));
            Sucursal elegida = actual == null ? (activas.isEmpty() ? null : activas.getFirst())
                    : activas.stream().filter(s -> s.id().equals(actual.id())).findFirst().orElse(activas.isEmpty() ? null : activas.getFirst());
            sucursal.setValue(elegida);
            revirtiendo = false;
            // Al volver a la página no se pisan los cambios sin guardar.
            if (!cambiosPendientes()) {
                cargar();
            }
        }, e -> tabla.error("No se pudieron cargar las sucursales.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void cambiarSucursal(Sucursal antes, Sucursal ahora) {
        if (revirtiendo || ahora == null || (antes != null && antes.id().equals(ahora.id()))) {
            return;
        }
        if (cambiosPendientes() && sucursalCargada != null) {
            revirtiendo = true;
            sucursal.setValue(antes);
            revirtiendo = false;
            DialogoConfirmacion.mostrar(a.dialogos(), "¿Cambiar de sucursal sin guardar?",
                    "Tienes cambios sin guardar en " + sucursalCargada.nombre() + ". Si cambias de sucursal, se descartan.",
                    "Descartar y cambiar", true, () -> {
                        descartarCambios();
                        revirtiendo = true;
                        sucursal.setValue(ahora);
                        revirtiendo = false;
                        cargar();
                    });
            return;
        }
        cargar();
    }

    private void cargar() {
        Sucursal s = sucursal.getValue();
        if (s == null) {
            filas = List.of();
            tabla.setDatos(filas);
            actualizarCambios();
            return;
        }
        tabla.cargando("Cargando productos de " + s.nombre() + "…");
        carga.cargar(() -> a.admin().limites().listar(s.id()), lista -> {
            sucursalCargada = s;
            filas = lista.stream().map(Editable::new).toList();
            tabla.setDatos(filas);
            filtrar();
            actualizarCambios();
        }, e -> tabla.error("No se pudieron cargar los mínimos y máximos.", AdminContexto.mensaje(e), this::cargar));
    }

    private void filtrar() {
        String t = barra.busqueda();
        Filtro f = filtro.getValue() == null ? Filtro.TODOS : filtro.getValue();
        tabla.setFiltro(e -> (t.isEmpty() || Texto.contiene(e.fila.producto().nombre(), t)
                || Texto.empieza(e.fila.producto().clave(), t))
                && switch (f) {
                    case TODOS -> true;
                    case CON_CONTROL -> e.reposicion() != Reposicion.SIN_CONTROL;
                    case SIN_CONTROL -> e.reposicion() == Reposicion.SIN_CONTROL;
                    case EN_MINIMO -> e.reposicion() == Reposicion.EN_MINIMO;
                    case MODIFICADOS -> e.modificado();
                });
        long sinControl = filas.stream().filter(e -> e.reposicion() == Reposicion.SIN_CONTROL).count();
        barra.setResultados(Texto.plural(tabla.total(), "producto", "productos") + " · " + sinControl + " sin control");
    }

    private void actualizarCambios() {
        int cambios = (int) filas.stream().filter(Editable::modificado).count();
        long invalidos = filas.stream().filter(e -> e.modificado() && e.reposicion() == Reposicion.REVISAR).count();
        barraCambios.setCambios(cambios, invalidos == 0 ? null
                : "Revisa " + Texto.plural(invalidos, "producto", "productos") + ": el máximo es menor que el mínimo");
    }

    private void confirmarDescartar() {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Descartar los cambios?",
                "Los mínimos y máximos vuelven a como estaban guardados.", "Descartar", true, this::descartarCambios);
    }

    private void guardar() {
        Sucursal s = sucursalCargada;
        if (s == null) {
            return;
        }
        List<LimitesService.Cambio> cambios = new ArrayList<>();
        for (Editable e : filas) {
            if (e.modificado()) {
                cambios.add(new LimitesService.Cambio(e.fila.producto(), e.valor(e.minimo), e.valor(e.maximo)));
            }
        }
        if (cambios.isEmpty()) {
            return;
        }
        barraCambios.ocupado(true);
        Cargador.ejecutar(() -> a.admin().limites().guardar(s, cambios, a.usuario()), g -> {
            barraCambios.ocupado(false);
            a.avisos().exito(Texto.plural(cambios.size(), "producto actualizado", "productos actualizados") + " en "
                    + s.nombre() + " · Folio " + g.folio());
            if (g.ordenesGeneradas() > 0) {
                a.avisos().conAccion(Texto.plural(g.ordenesGeneradas(), "producto ya está", "productos ya están")
                        + " en su mínimo: se generaron sus órdenes de reabastecimiento.", "Ver órdenes",
                        () -> a.irA().accept("ordenes"));
            }
            a.refrescarContadores().run();
            cargar();
        }, e -> {
            barraCambios.ocupado(false);
            a.avisos().error("No se guardaron los cambios: " + AdminContexto.mensaje(e));
        });
    }
}
