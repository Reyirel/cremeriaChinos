package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.service.DevolucionService;
import com.cremerias.puntoventa.service.admin.DevolucionSucursalService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Pestanas;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Consulta de devoluciones: de clientes en las cajas y de las sucursales a la bodega. El supervisor
 * la usa con su sucursal fija y solo ve las de sus clientes.
 */
public class DevolucionesPagina extends Pagina {

    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);

    private record Datos(List<DevolucionService.Resumen> clientes, List<DevolucionSucursalService.Resumen> sucursales,
                         List<Sucursal> lista) {
    }

    private final Cargador carga = new Cargador();
    private ToggleButton pestanaClientes;
    private DatePicker desde;
    private DatePicker hasta;
    private ComboBox<Sucursal> sucursal;
    private BarraHerramientas barraClientes;
    private BarraHerramientas barraSucursales;
    private TablaDatos<DevolucionService.Resumen> clientes;
    private TablaDatos<DevolucionSucursalService.Resumen> sucursales;
    private ResumenMetricas.Metrica metricaReembolsado;
    private ResumenMetricas.Metrica metricaDevoluciones;
    private ResumenMetricas.Metrica metricaPerdida;
    private ResumenMetricas.Metrica metricaSucursales;
    private HBox periodo;
    /** Sucursal del supervisor; {@code null} = administrador. */
    private final Sucursal fija;

    public DevolucionesPagina(AdminContexto a) {
        this(a, null);
    }

    public DevolucionesPagina(AdminContexto a, Sucursal fija) {
        super(a);
        this.fija = fija;
    }

    @Override
    public String titulo() {
        return "Devoluciones";
    }

    @Override
    public TextField busqueda() {
        return (pestanaClientes.isSelected() ? barraClientes : barraSucursales).campoBusqueda();
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Devoluciones", fija == null
                ? "Lo que regresaron los clientes en las cajas y lo que las sucursales devolvieron a la bodega."
                : "Lo que regresaron los clientes de " + fija.nombre() + ".");
        ResumenMetricas resumen = new ResumenMetricas();
        metricaReembolsado = resumen.agregar("Reembolsado a clientes").principal();
        metricaDevoluciones = resumen.agregar("Devoluciones de clientes");
        metricaPerdida = resumen.agregar("No vendible (al costo)");
        metricaSucursales = resumen.agregar("De sucursales a bodega");

        Pestanas pestanas = new Pestanas();
        pestanaClientes = pestanas.agregar("De clientes");
        ToggleButton pestanaSucursales = pestanas.agregar("De sucursales a bodega");
        if (fija != null) {
            pestanas.setVisible(false);
            pestanas.setManaged(false);
            pestanaSucursales.setDisable(true);
            metricaSucursales.setVisible(false);
            metricaSucursales.setManaged(false);
        }

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

        barraClientes = new BarraHerramientas();
        barraClientes.buscador("Buscar folio, ticket, motivo o quién atendió");
        sucursal = barraClientes.filtro("Sucursal", new ComboBox<>(), TODAS, s -> s.id() == null ? s.nombre()
                : Formatos.sucursal(s));
        sucursal.setValue(TODAS);
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        if (fija != null) {
            sucursal.setVisible(false);
            sucursal.setManaged(false);
        }
        barraClientes.agregar(periodo);
        barraClientes.setAlCambiar(this::filtrar);
        clientes = new TablaDatos<>(Diseno.Densidad.NORMAL);
        clientes.setClave(DevolucionService.Resumen::id);
        clientes.fecha("Fecha", DevolucionService.Resumen::fecha, Formatos.FECHA_HORA).ancho(142);
        clientes.dosLineas("Folio", DevolucionService.Resumen::folio, d -> "Ticket " + d.ventaFolio()).ancho(200);
        clientes.texto("Sucursal", DevolucionService.Resumen::sucursal).ancho(170).columna().setVisible(fija == null);
        clientes.etiqueta("Tipo", DevolucionService.Resumen::tipo, t -> t == DevolucionService.Tipo.CAMBIO ? "Cambio"
                : "Devolución", t -> t == DevolucionService.Tipo.CAMBIO ? Etiqueta.Tono.INFO : Etiqueta.Tono.AVISO).ancho(120);
        clientes.dosLineas("Reembolso", d -> Dinero.formatear(d.reembolsoCentavos()), d -> d.metodo().nombre()).ancho(150);
        clientes.dinero("No vendible", DevolucionService.Resumen::costoNoVendibleCentavos).ancho(120)
                .ayuda("Costo de lo que regresó dañado o caducado (no vuelve a venderse).");
        clientes.dosLineas("Motivo", DevolucionService.Resumen::motivo, d -> "Atendió " + d.usuario()
                + (d.autorizo() == null || d.autorizo().equals(d.usuario()) ? "" : " · autorizó " + d.autorizo())).flexible(220);
        clientes.acciones(Accion.<DevolucionService.Resumen>boton("mdi2p-printer-outline", "Comprobante", this::comprobante));
        clientes.setVacio(new EstadoVacio("mdi2k-keyboard-return", "No hay devoluciones de clientes en estas fechas.",
                "Se registran en la caja con F7 o desde «Ventas del turno»."));
        clientes.setSinResultados(EstadoVacio.sinResultados("No encontramos devoluciones con estos filtros.",
                barraClientes::limpiar));
        VBox vistaClientes = Ui.panelTabla(barraClientes, clientes);

        barraSucursales = new BarraHerramientas();
        barraSucursales.buscador("Buscar folio, sucursal o motivo");
        barraSucursales.setAlCambiar(this::filtrar);
        sucursales = new TablaDatos<>(Diseno.Densidad.NORMAL);
        sucursales.setClave(DevolucionSucursalService.Resumen::id);
        sucursales.fecha("Fecha", DevolucionSucursalService.Resumen::fecha, Formatos.FECHA_HORA).ancho(142);
        sucursales.texto("Folio", DevolucionSucursalService.Resumen::folio).ancho(170);
        sucursales.texto("Sucursal", DevolucionSucursalService.Resumen::sucursal).ancho(180);
        sucursales.numero("Productos", DevolucionSucursalService.Resumen::productos, String::valueOf).ancho(96);
        sucursales.dinero("Al costo", DevolucionSucursalService.Resumen::costoCentavos).ancho(120);
        sucursales.dinero("Nota de crédito", DevolucionSucursalService.Resumen::notaCreditoCentavos).ancho(140)
                .ayuda("Lo que se le acreditó a la sucursal (precio interno de lo devuelto).");
        sucursales.dosLineas("Motivo", DevolucionSucursalService.Resumen::motivo, d -> "Registró " + d.usuario())
                .flexible(220);
        sucursales.setVacio(new EstadoVacio("mdi2k-keyboard-return", "No hay devoluciones de sucursales en estas fechas.",
                "Se registran en Surtidos con «Devolución de sucursal»."));
        sucursales.setSinResultados(EstadoVacio.sinResultados("No encontramos devoluciones con esa búsqueda.",
                barraSucursales::limpiar));
        VBox vistaSucursales = Ui.panelTabla(barraSucursales, sucursales);

        StackPane zona = new StackPane(vistaClientes);
        pestanas.seleccionProperty().addListener((o, x, y) -> {
            zona.getChildren().setAll(pestanaClientes.isSelected() ? vistaClientes : vistaSucursales);
            if (periodo.getParent() instanceof javafx.scene.layout.Pane padre) {
                padre.getChildren().remove(periodo);
            }
            (pestanaClientes.isSelected() ? barraClientes : barraSucursales).agregar(periodo);
        });
        VBox.setVgrow(zona, Priority.ALWAYS);
        pagina.getChildren().addAll(resumen, pestanas, zona);
        return Ui.ajustable(pagina, zona, 320);
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
        Sucursal s = fija != null ? fija : sucursal.getValue();
        String sucursalId = s == null ? null : s.id();
        clientes.cargando("Cargando devoluciones…");
        sucursales.cargando("Cargando devoluciones…");
        carga.cargar(() -> new Datos(a.ctx().devoluciones().listar(sucursalId, d, h, a.usuario()),
                fija != null ? List.of() : a.admin().devolucionesSucursal().listar(d, h, sucursalId, a.usuario()),
                fija != null ? List.of(fija) : a.admin().sucursales().activas()), datos -> {
            List<Sucursal> opciones = new ArrayList<>();
            opciones.add(TODAS);
            datos.lista().stream().filter(x -> !x.esAlmacen()).forEach(opciones::add);
            if (fija == null && !opciones.equals(sucursal.getItems())) {
                Sucursal elegida = sucursal.getValue();
                sucursal.setItems(FXCollections.observableArrayList(opciones));
                sucursal.setValue(elegida == null || elegida.id() == null ? TODAS : opciones.stream()
                        .filter(x -> Objects.equals(x.id(), elegida.id())).findFirst().orElse(TODAS));
            }
            clientes.setDatos(datos.clientes());
            sucursales.setDatos(datos.sucursales());
            filtrar();
        }, e -> {
            clientes.error("No se pudieron cargar las devoluciones.", AdminContexto.mensaje(e), this::cargar);
            sucursales.error("No se pudieron cargar las devoluciones.", AdminContexto.mensaje(e), this::cargar);
        });
    }

    private void filtrar() {
        String t = barraClientes.busqueda();
        clientes.setFiltro(d -> t.isEmpty() || Texto.contiene(d.folio(), t) || Texto.contiene(d.ventaFolio(), t)
                || Texto.contiene(d.motivo(), t) || Texto.contiene(d.usuario(), t) || Texto.contiene(d.sucursal(), t));
        String ts = barraSucursales.busqueda();
        sucursales.setFiltro(d -> ts.isEmpty() || Texto.contiene(d.folio(), ts) || Texto.contiene(d.sucursal(), ts)
                || Texto.contiene(d.motivo(), ts));
        List<DevolucionService.Resumen> visibles = clientes.visibles();
        metricaReembolsado.setValor(Dinero.formatear(visibles.stream().mapToLong(DevolucionService.Resumen::reembolsoCentavos)
                .sum())).setContexto(Formatos.FECHA_CORTA.format(desde.getValue()) + " – "
                + Formatos.FECHA_CORTA.format(hasta.getValue()));
        metricaDevoluciones.setValor(String.valueOf(visibles.size()))
                .setContexto(Texto.plural(visibles.stream().filter(x -> x.tipo() == DevolucionService.Tipo.CAMBIO).count(),
                        "cambio", "cambios"));
        long perdida = visibles.stream().mapToLong(DevolucionService.Resumen::costoNoVendibleCentavos).sum();
        metricaPerdida.setValor(Dinero.formatear(perdida)).setContexto("Dañado o caducado")
                .setTono(perdida > 0 ? Etiqueta.Tono.AVISO : null);
        metricaSucursales.setValor(Dinero.formatear(sucursales.visibles().stream()
                        .mapToLong(DevolucionSucursalService.Resumen::costoCentavos).sum()))
                .setContexto(Texto.plural(sucursales.visibles().size(), "devolución", "devoluciones") + " al costo");
        barraClientes.setResultados(Texto.plural(clientes.mostrados(), "devolución", "devoluciones"));
        barraSucursales.setResultados(Texto.plural(sucursales.mostrados(), "devolución", "devoluciones"));
    }

    private void comprobante(DevolucionService.Resumen d) {
        Cargador.ejecutar(() -> a.ctx().devoluciones().comprobante(d.id()),
                r -> DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Comprobante " + r.folio(),
                        d.sucursal() + " · " + Dinero.formatear(r.reembolsoCentavos()),
                        TicketTexto.lineasDevolucion(r, d.sucursal()), null),
                e -> a.avisos().error("No se pudo preparar el comprobante: " + AdminContexto.mensaje(e)));
    }
}
