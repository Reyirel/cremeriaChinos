package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.CategoriaGasto;
import com.cremerias.puntoventa.model.Gasto;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.TipoGasto;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.stream.IntStream;

/** Gastos fijos y variables de cada sucursal (renta, servicios, nómina patronal, etc.). */
public class GastosPagina extends Pagina {

    private enum Tipo { TODOS, FIJOS, VARIABLES }

    private final Cargador carga = new Cargador();
    private BarraHerramientas barra;
    private ComboBox<Sucursal> sucursal;
    private ComboBox<YearMonth> mes;
    private Segmentado<Tipo> tipo;
    private Button nuevo;
    private TablaDatos<Gasto> tabla;
    private ResumenMetricas.Metrica metricaTotal;
    private ResumenMetricas.Metrica metricaFijos;
    private ResumenMetricas.Metrica metricaVariables;

    public GastosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Gastos";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        nuevo = Ui.principal("Nuevo gasto", "mdi2p-plus");
        nuevo.setOnAction(e -> formulario(null));
        nuevo.setDisable(true);
        VBox pagina = Ui.pagina("Gastos", "Gastos fijos y variables de cada sucursal. Se restan para calcular la utilidad neta.",
                nuevo);

        ResumenMetricas resumen = new ResumenMetricas();
        metricaTotal = resumen.agregar("Total del mes").principal();
        metricaFijos = resumen.agregar("Fijos");
        metricaVariables = resumen.agregar("Variables");

        sucursal = new ComboBox<>();
        sucursal.setConverter(Combos.sucursal());
        sucursal.setPromptText("Elige la sucursal");
        sucursal.setAccessibleText("Sucursal");
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        mes = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(0, 11).mapToObj(i -> YearMonth.now().minusMonths(i)).toList()));
        mes.setValue(YearMonth.now());
        mes.setConverter(Combos.mes());
        mes.setAccessibleText("Mes");
        mes.valueProperty().addListener((o, x, y) -> cargar());
        tipo = new Segmentado<Tipo>().opcion(Tipo.TODOS, "Todos").opcion(Tipo.FIJOS, "Fijos").opcion(Tipo.VARIABLES, "Variables");
        tipo.valorProperty().addListener((o, x, y) -> filtrar());

        barra = new BarraHerramientas();
        barra.agregar(sucursal);
        barra.agregar(mes);
        barra.buscador("Buscar concepto o categoría");
        barra.agregar(tipo);
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(Gasto::id);
        tabla.fecha("Fecha", Gasto::fecha, Formatos.FECHA).ancho(116);
        tabla.dosLineas("Concepto", Gasto::concepto, Gasto::nota).flexible(220);
        tabla.texto("Categoría", g -> g.categoria().nombre()).ancho(190);
        tabla.etiqueta("Tipo", Gasto::tipo, TipoGasto::nombre,
                t -> t == TipoGasto.FIJO ? Etiqueta.Tono.NEUTRO : Etiqueta.Tono.INFO).ancho(116);
        tabla.dinero("Monto", Gasto::montoCentavos).ancho(130).clase("columna-fuerte");
        tabla.texto("Registró", Gasto::usuario).ancho(170);
        tabla.acciones(
                Accion.<Gasto>boton("mdi2p-pencil-outline", "Editar", this::formulario),
                Accion.<Gasto>boton("mdi2t-trash-can-outline", "Eliminar", this::eliminar).peligrosa());
        tabla.setVacio(new EstadoVacio("mdi2c-cash-minus", "No hay gastos en este mes.",
                "Registra la renta, los servicios y demás gastos para calcular la utilidad neta.")
                .accion("Nuevo gasto", "mdi2p-plus", () -> formulario(null)));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos gastos con estos filtros.", () -> {
            tipo.setValor(Tipo.TODOS);
            barra.limpiar();
        }));
        tabla.setAlActivar(this::formulario);

        VBox panel = Ui.panelTabla(barra, tabla);
        pagina.getChildren().addAll(resumen, panel);
        return Ui.ajustable(pagina, panel, 300);
    }

    @Override
    public void alMostrar() {
        Cargador.ejecutar(() -> a.admin().sucursales().listar(), lista -> {
            Sucursal actual = sucursal.getValue();
            sucursal.setItems(FXCollections.observableArrayList(lista));
            Sucursal elegida = actual == null ? (lista.isEmpty() ? null : lista.getFirst())
                    : lista.stream().filter(s -> s.id().equals(actual.id())).findFirst().orElse(null);
            if (sucursal.getValue() == elegida) {
                cargar();
            } else {
                sucursal.setValue(elegida);
            }
        }, e -> tabla.error("No se pudieron cargar las sucursales.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void cargar() {
        nuevo.setDisable(sucursal.getValue() == null);
        Sucursal s = sucursal.getValue();
        YearMonth m = mes.getValue();
        if (s == null || m == null) {
            return;
        }
        tabla.cargando("Cargando gastos…");
        carga.cargar(() -> a.admin().gastos().listar(a.usuario(), s.id(), m), lista -> {
            tabla.setDatos(lista);
            long fijos = lista.stream().filter(g -> g.tipo() == TipoGasto.FIJO).mapToLong(Gasto::montoCentavos).sum();
            long variables = lista.stream().filter(g -> g.tipo() == TipoGasto.VARIABLE).mapToLong(Gasto::montoCentavos).sum();
            String periodo = Combos.mes().toString(m) + " · " + s.nombre();
            metricaTotal.setValor(Dinero.formatear(fijos + variables)).setContexto(periodo);
            metricaFijos.setValor(Dinero.formatear(fijos))
                    .setContexto(Texto.plural(lista.stream().filter(g -> g.tipo() == TipoGasto.FIJO).count(), "gasto", "gastos"));
            metricaVariables.setValor(Dinero.formatear(variables))
                    .setContexto(Texto.plural(lista.stream().filter(g -> g.tipo() == TipoGasto.VARIABLE).count(), "gasto", "gastos"));
            filtrar();
        }, e -> tabla.error("No se pudieron cargar los gastos.", AdminContexto.mensaje(e), this::cargar));
    }

    private void filtrar() {
        String t = barra.busqueda();
        Tipo ti = tipo.getValor();
        tabla.setFiltro(g -> (t.isEmpty() || Texto.contiene(g.concepto(), t) || Texto.contiene(g.categoria().nombre(), t)
                || Texto.contiene(g.nota(), t))
                && (ti == Tipo.TODOS || (ti == Tipo.FIJOS) == (g.tipo() == TipoGasto.FIJO)));
        long suma = tabla.visibles().stream().mapToLong(Gasto::montoCentavos).sum();
        barra.setResultados((tabla.mostrados() == tabla.total() ? "" : tabla.mostrados() + " de ")
                + Texto.plural(tabla.total(), "gasto", "gastos") + " · " + Dinero.formatear(suma));
    }

    private void eliminar(Gasto g) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar este gasto?",
                g.categoria().nombre() + ": " + g.concepto() + ", " + Dinero.formatear(g.montoCentavos())
                        + ". Dejará de contar en la utilidad neta; queda registrado en el historial.",
                "Eliminar gasto", true,
                () -> a.enFondo("Gasto eliminado: " + g.concepto(), () -> a.admin().gastos().eliminar(g, a.usuario()),
                        this::cargar));
    }

    private void formulario(Gasto g) {
        boolean nuevoGasto = g == null;
        Sucursal s = sucursal.getValue();
        if (s == null) {
            return;
        }
        Dialogo d = new Dialogo(nuevoGasto ? "Nuevo gasto" : "Editar gasto", Formatos.sucursal(s), "mdi2c-cash-minus",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        DatePicker fecha = new DatePicker(nuevoGasto ? LocalDate.now() : g.fecha().atZone(ZoneId.systemDefault()).toLocalDate());
        fecha.setEditable(false);
        ComboBox<CategoriaGasto> categoria = new ComboBox<>(FXCollections.observableArrayList(CategoriaGasto.values()));
        categoria.setValue(nuevoGasto ? CategoriaGasto.RENTA : g.categoria());
        Segmentado<TipoGasto> tipoGasto = new Segmentado<TipoGasto>()
                .opcion(TipoGasto.FIJO, "Fijo").opcion(TipoGasto.VARIABLE, "Variable");
        tipoGasto.setValor(nuevoGasto ? TipoGasto.FIJO : g.tipo());
        TextField concepto = new TextField(nuevoGasto ? "" : g.concepto());
        concepto.setPromptText("Ej. Renta de octubre");
        CampoDinero monto = new CampoDinero().normal();
        monto.setPromptText("0.00");
        if (!nuevoGasto) {
            monto.setCentavos(g.montoCentavos());
        }
        TextField nota = new TextField(nuevoGasto || g.nota() == null ? "" : g.nota());
        Formulario.Campo campoConcepto = Formulario.campo("Concepto", concepto);
        Formulario.Campo campoMonto = Formulario.campo("Monto", monto);
        Formulario.Campo campoTipo = Formulario.campo("Tipo", tipoGasto)
                .ayuda("Fijo: se repite igual cada periodo. Variable: cambia.");
        concepto.textProperty().addListener((o, x, y) -> campoConcepto.setError(null));
        monto.textProperty().addListener((o, x, y) -> campoMonto.setError(null));
        d.setContenido(Formulario.fila(Formulario.campo("Fecha", fecha), campoTipo),
                Formulario.campo("Categoría", categoria), campoConcepto, campoMonto, Formulario.opcional("Nota", nota));
        Runnable guardar = () -> {
            boolean valido = true;
            if (concepto.getText() == null || concepto.getText().isBlank()) {
                campoConcepto.setError("Escribe el concepto del gasto.");
                valido = false;
            }
            if (monto.centavosOCero() <= 0) {
                campoMonto.setError("Escribe un monto mayor a cero.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            Instant instante = fecha.getValue() == null ? null : fecha.getValue().atStartOfDay(ZoneId.systemDefault()).toInstant();
            Gasto aGuardar = new Gasto(nuevoGasto ? null : g.id(), nuevoGasto ? null : g.folio(), s.id(), instante,
                    categoria.getValue(), tipoGasto.getValor(), concepto.getText(), monto.centavosOCero(),
                    nota.getText().isBlank() ? null : nota.getText(), nuevoGasto ? null : g.usuarioId(),
                    nuevoGasto ? null : g.usuario());
            a.guardar(d, (nuevoGasto ? "Gasto registrado: " : "Gasto actualizado: ") + concepto.getText().strip(),
                    () -> a.admin().gastos().guardar(aGuardar, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nuevoGasto ? "Registrar gasto" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(concepto);
        d.vigilarCambios(fecha, categoria, tipoGasto, concepto, monto, nota);
        a.dialogos().mostrar(d);
    }
}
