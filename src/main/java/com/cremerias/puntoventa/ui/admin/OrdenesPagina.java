package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoTexto;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Pestanas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Órdenes de reabastecimiento que generan las sucursales al llegar a su mínimo. */
public class OrdenesPagina extends Pagina {

    /** Pendientes y atendidas, para los conteos de las pestañas. */
    private record Ordenes(List<OrdenRepository.Orden> pendientes, List<OrdenRepository.Orden> atendidas,
                           List<Sucursal> sucursales) {
    }

    private final Cargador carga = new Cargador();
    private final Map<String, Sucursal> sucursales = new HashMap<>();
    private Pestanas pestanas;
    private ToggleButton porAprobar;
    private ToggleButton atendidasPestana;
    private BarraHerramientas barra;
    private TablaDatos<OrdenRepository.Orden> pendientes;
    private TablaDatos<OrdenRepository.Orden> atendidas;
    private StackPane zonaTabla;

    public OrdenesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Órdenes de reabasto";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Órdenes de reabastecimiento",
                "Las genera cada sucursal al llegar a su mínimo y piden lo que falta para su máximo.");
        pestanas = new Pestanas();
        porAprobar = pestanas.agregar("Por aprobar");
        atendidasPestana = pestanas.agregar("Atendidas");

        barra = new BarraHerramientas();
        barra.buscador("Buscar folio, sucursal o producto");
        barra.setAlCambiar(this::filtrar);

        pendientes = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        pendientes.setClave(OrdenRepository.Orden::id);
        pendientes.fecha("Generada", OrdenRepository.Orden::creadoEn, Formatos.FECHA_HORA).ancho(142);
        pendientes.dosLineas("Sucursal", OrdenRepository.Orden::sucursal, o -> codigo(o.sucursalId())).ancho(160);
        pendientes.dosLineas("Producto", OrdenRepository.Orden::producto, OrdenRepository.Orden::folio).flexible(170);
        pendientes.cantidad("Existencia", OrdenRepository.Orden::existencia, o -> Unidad.valueOf(o.unidad()),
                        o -> "Mínimo " + cantidad(o, o.minimo())).ancho(140)
                .ayuda("Existencia de la sucursal al generarse la orden, y el mínimo que la disparó.");
        pendientes.cantidad("Se pide", OrdenRepository.Orden::cantidadSugerida, o -> Unidad.valueOf(o.unidad()),
                        o -> "Máximo " + cantidad(o, o.maximo())).ancho(140).clase("columna-fuerte")
                .ayuda("Lo que falta para llegar al máximo.");
        pendientes.nodo("", this::botonesDecision).ancho(222).minimo(222);
        pendientes.setVacio(new EstadoVacio("mdi2c-check-circle-outline", "No hay órdenes pendientes.",
                "Cuando una sucursal llegue a su mínimo, su orden aparecerá aquí.").tono("exito"));
        pendientes.setSinResultados(EstadoVacio.sinResultados("No encontramos órdenes con esa búsqueda.", barra::limpiar));

        atendidas = new TablaDatos<>(Diseno.Densidad.NORMAL);
        atendidas.setClave(OrdenRepository.Orden::id);
        atendidas.fecha("Generada", OrdenRepository.Orden::creadoEn, Formatos.FECHA_HORA).ancho(132);
        atendidas.dosLineas("Sucursal", OrdenRepository.Orden::sucursal, o -> codigo(o.sucursalId())).ancho(180);
        atendidas.dosLineas("Producto", OrdenRepository.Orden::producto, OrdenRepository.Orden::folio).flexible(190);
        atendidas.cantidad("Pedía", OrdenRepository.Orden::cantidadSugerida, o -> Unidad.valueOf(o.unidad())).ancho(110);
        atendidas.etiqueta("Resultado", OrdenRepository.Orden::estado,
                e -> "APROBADA".equals(e) ? "Aprobada" : "Rechazada",
                e -> "APROBADA".equals(e) ? Etiqueta.Tono.EXITO : Etiqueta.Tono.PELIGRO).ancho(126);
        atendidas.dosLineas("Atendió", OrdenRepository.Orden::resueltoPor, o -> Formatos.fechaHora(o.resueltoEn())).ancho(190);
        atendidas.textoLargo("Motivo del rechazo", o -> o.motivoRechazo() == null ? "—" : o.motivoRechazo()).ancho(220);
        atendidas.setVacio(new EstadoVacio("mdi2c-clipboard-list-outline", "Aún no se ha atendido ninguna orden.", null));
        atendidas.setSinResultados(EstadoVacio.sinResultados("No encontramos órdenes con esa búsqueda.", barra::limpiar));

        zonaTabla = new StackPane(pendientes.tabla());
        VBox panel = new VBox(barra, zonaTabla);
        panel.getStyleClass().add("panel-tabla");
        VBox.setVgrow(zonaTabla, javafx.scene.layout.Priority.ALWAYS);
        Diseno.recortarRedondeado(panel, Diseno.RADIO_PANEL);
        pestanas.seleccionProperty().addListener((o, x, y) -> {
            zonaTabla.getChildren().setAll(porAprobar.isSelected() ? pendientes.tabla() : atendidas.tabla());
            filtrar();
        });
        pagina.getChildren().addAll(pestanas, panel);
        return Ui.ajustable(pagina, panel, 300);
    }

    private String codigo(String sucursalId) {
        Sucursal s = sucursales.get(sucursalId);
        return s == null ? null : s.codigo();
    }

    private static String cantidad(OrdenRepository.Orden o, BigDecimal valor) {
        return Cantidades.formatear(Unidad.valueOf(o.unidad()), valor);
    }

    private Node botonesDecision(OrdenRepository.Orden o) {
        Button aprobar = Ui.boton("Aprobar…", "mdi2c-check", "accent", "small");
        aprobar.setOnAction(e -> aprobar(o));
        aprobar.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        aprobar.setTooltip(new javafx.scene.control.Tooltip("Revisa la cantidad, fija precios y surte desde el almacén"));
        Button rechazar = Ui.boton("Rechazar…", null, "flat", "danger", "small");
        rechazar.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        rechazar.setOnAction(e -> rechazar(o));
        HBox h = new HBox(6, aprobar, rechazar);
        h.setAlignment(Pos.CENTER_RIGHT);
        return h;
    }

    @Override
    public void alMostrar() {
        pendientes.cargando("Cargando órdenes…");
        atendidas.cargando("Cargando órdenes…");
        carga.cargar(() -> new Ordenes(a.admin().ordenes().pendientes(), a.admin().ordenes().atendidas(),
                a.admin().sucursales().listar()), o -> {
            sucursales.clear();
            o.sucursales().forEach(s -> sucursales.put(s.id(), s));
            pendientes.setDatos(o.pendientes());
            atendidas.setDatos(o.atendidas());
            Pestanas.setContador(porAprobar, o.pendientes().size(), true);
            Pestanas.setContador(atendidasPestana, o.atendidas().size(), false);
            filtrar();
        }, e -> {
            String m = AdminContexto.mensaje(e);
            pendientes.error("No se pudieron cargar las órdenes.", m, this::alMostrar);
            atendidas.error("No se pudieron cargar las órdenes.", m, this::alMostrar);
        });
    }

    private void filtrar() {
        String t = barra.busqueda();
        for (TablaDatos<OrdenRepository.Orden> tabla : List.of(pendientes, atendidas)) {
            tabla.setFiltro(o -> t.isEmpty() || Texto.contiene(o.folio(), t) || Texto.contiene(o.sucursal(), t)
                    || Texto.contiene(o.producto(), t));
        }
        TablaDatos<OrdenRepository.Orden> visible = porAprobar.isSelected() ? pendientes : atendidas;
        barra.setResultados(visible.mostrados() == visible.total() ? Texto.plural(visible.total(), "orden", "órdenes")
                : visible.mostrados() + " de " + Texto.plural(visible.total(), "orden", "órdenes"));
    }

    private void aprobar(OrdenRepository.Orden o) {
        Cargador.ejecutar(() -> new Object[]{
                a.admin().productos().listar().stream().filter(p -> p.id().equals(o.productoId())).findFirst().orElse(null),
                a.admin().sucursales().listar().stream().filter(s -> s.id().equals(o.sucursalId())).findFirst().orElse(null)
        }, datos -> {
            ProductoCatalogo producto = (ProductoCatalogo) datos[0];
            Sucursal sucursal = (Sucursal) datos[1];
            if (producto == null || sucursal == null || !producto.activo() || !sucursal.activo()) {
                a.avisos().error("El producto o la sucursal ya no están activos: rechaza la orden.");
                return;
            }
            DialogoSurtido.mostrar(a, sucursal, producto, o.cantidadSugerida(), o.id(), () -> {
                alMostrar();
                a.refrescarContadores().run();
            });
        }, e -> a.avisos().error(AdminContexto.mensaje(e)));
    }

    private void rechazar(OrdenRepository.Orden o) {
        DialogoTexto.mostrar(a.dialogos(), "Rechazar la orden " + o.folio(),
                o.sucursal() + " pide " + cantidad(o, o.cantidadSugerida()) + " de " + o.producto()
                        + ". Se cierra sin surtir; si la sucursal sigue en su mínimo, más adelante se generará otra.",
                "mdi2c-close-circle-outline", "Motivo del rechazo", "Ej. No hay existencia en el almacén", true,
                "Rechazar orden", motivo -> a.enFondo("Orden " + o.folio() + " rechazada",
                        () -> a.admin().ordenes().rechazar(o, motivo, a.usuario()), this::alMostrar));
    }
}
