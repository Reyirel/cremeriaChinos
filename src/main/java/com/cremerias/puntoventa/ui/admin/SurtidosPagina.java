package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Surtidos enviados del almacén a las sucursales: consulta, detalle e impresión del ticket. */
public class SurtidosPagina extends Pagina {

    private enum Pago {
        TODOS("Todas las formas de pago"), EFECTIVO("Efectivo"), CREDITO("Crédito");

        final String texto;

        Pago(String texto) {
            this.texto = texto;
        }
    }

    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);

    private final Cargador carga = new Cargador();
    private final Cargador cargaDetalle = new Cargador();
    private final Map<String, Sucursal> sucursales = new HashMap<>();
    private BarraHerramientas barra;
    private DatePicker desde;
    private DatePicker hasta;
    private ComboBox<Sucursal> sucursal;
    private ComboBox<Pago> pago;
    private TablaDatos<SurtidoService.Resumen> tabla;
    private ResumenMetricas.Metrica metricaCosto;
    private ResumenMetricas.Metrica metricaSurtidos;
    private ResumenMetricas.Metrica metricaCredito;
    private ResumenMetricas.Metrica metricaVenta;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;

    public SurtidosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Surtidos";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        Button nuevo = Ui.principal("Nuevo surtido", "mdi2t-truck-delivery-outline");
        nuevo.setOnAction(e -> DialogoSurtido.mostrar(a, null, null, null, null, this::cargar));
        VBox pagina = Ui.pagina("Surtidos a sucursales",
                "Mercancía que sale del almacén hacia las sucursales, en efectivo o a crédito.", nuevo);

        ResumenMetricas resumen = new ResumenMetricas();
        metricaCosto = resumen.agregar("Enviado al costo").principal();
        metricaSurtidos = resumen.agregar("Surtidos");
        metricaCredito = resumen.agregar("A crédito");
        metricaVenta = resumen.agregar("Valor a precio de venta");

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
        HBox periodo = new HBox(8, del, desde, al, hasta);
        periodo.setAlignment(Pos.CENTER_LEFT);

        barra = new BarraHerramientas();
        barra.buscador("Buscar folio, sucursal o quién surtió");
        barra.agregar(periodo);
        sucursal = barra.filtro("Sucursal", new ComboBox<>(), TODAS, s -> s.id() == null ? s.nombre() : Formatos.sucursal(s));
        sucursal.setPrefWidth(240);
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        pago = barra.filtro("Pago", new ComboBox<>(FXCollections.observableArrayList(Pago.values())), Pago.TODOS, p -> p.texto);
        pago.setValue(Pago.TODOS);
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(SurtidoService.Resumen::id);
        tabla.fecha("Fecha", SurtidoService.Resumen::fecha, Formatos.FECHA_HORA).ancho(142);
        tabla.dosLineas("Folio", SurtidoService.Resumen::folio,
                s -> s.ordenFolio() == null ? null : "Por orden " + s.ordenFolio()).ancho(172);
        tabla.dosLineas("Destino", SurtidoService.Resumen::sucursal, s -> codigo(s.sucursalId())).flexible(170);
        var columnaPago = tabla.etiqueta("Pago", SurtidoService.Resumen::formaPago, f -> f.nombre(),
                f -> f == SurtidoService.FormaPago.CREDITO ? Etiqueta.Tono.AVISO : Etiqueta.Tono.EXITO).ancho(120);
        var columnaProductos = tabla.numero("Productos", SurtidoService.Resumen::productos, String::valueOf).ancho(98);
        tabla.dinero("Costo", SurtidoService.Resumen::costoCentavos).ancho(120)
                .ayuda("Lo que sale del almacén, al costo de sus lotes.");
        var columnaVenta = tabla.dinero("Valor de venta", SurtidoService.Resumen::ventaCentavos).ancho(132)
                .ayuda("Lo que vale a los precios que se fijaron en la sucursal.");
        var columnaUsuario = tabla.texto("Surtió", SurtidoService.Resumen::usuario).ancho(170).oculta();
        var columnaAcciones = tabla.acciones(
                Accion.<SurtidoService.Resumen>boton("mdi2e-eye-outline", "Ver detalle", s -> {
                    tabla.tabla().getSelectionModel().select(s);
                    mostrarDetalle(s);
                    maestro.abrir();
                }),
                Accion.<SurtidoService.Resumen>boton("mdi2p-printer-outline", "Ticket para imprimir", this::ticket));
        tabla.setVacio(new EstadoVacio("mdi2t-truck-delivery-outline", "No hay surtidos en estas fechas.",
                "Cambia el periodo o registra uno con «Nuevo surtido».")
                .accion("Nuevo surtido", "mdi2p-plus", () -> DialogoSurtido.mostrar(a, null, null, null, null, this::cargar)));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos surtidos con estos filtros.", barra::limpiar));
        tabla.setAlActivar(s -> {
            mostrarDetalle(s);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, s) -> {
            if (s != null && maestro.estaAbierto()) {
                mostrarDetalle(s);
            }
        });

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 660);
        maestro.setAlCerrar(() -> {
            cargaDetalle.cancelar();
            tabla.tabla().requestFocus();
        });
        // Si con el detalle acoplado no cabe todo, la tabla deja lo que el panel ya muestra
        // (pago, productos, valor de venta, quién surtió, acciones).
        maestro.setAnchoCompleto(tabla.anchoNecesario());
        maestro.tablaReducidaProperty().addListener((o, x, reducida) -> {
            columnaPago.columna().setVisible(!reducida);
            columnaProductos.columna().setVisible(!reducida);
            columnaVenta.columna().setVisible(!reducida);
            if (reducida) {
                columnaUsuario.columna().setVisible(false);
            }
            columnaAcciones.columna().setVisible(!reducida);
        });
        pagina.getChildren().addAll(resumen, maestro);
        return Ui.ajustable(pagina, maestro, 320);
    }

    private String codigo(String sucursalId) {
        Sucursal s = sucursalId == null ? null : sucursales.get(sucursalId);
        return s == null || s.codigo() == null ? null : s.codigo();
    }

    @Override
    public void alMostrar() {
        Cargador.ejecutar(() -> a.admin().sucursales().listar(), lista -> {
            sucursales.clear();
            lista.forEach(s -> sucursales.put(s.id(), s));
            Sucursal actual = sucursal.getValue();
            List<Sucursal> opciones = new ArrayList<>();
            opciones.add(TODAS);
            opciones.addAll(lista);
            sucursal.setItems(FXCollections.observableArrayList(opciones));
            Sucursal elegida = actual == null || actual.id() == null ? TODAS
                    : opciones.stream().filter(s -> actual.id().equals(s.id())).findFirst().orElse(TODAS);
            if (sucursal.getValue() == elegida) {
                cargar();
            } else {
                sucursal.setValue(elegida);
            }
        }, e -> tabla.error("No se pudieron cargar las sucursales.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void cargar() {
        LocalDate d = desde.getValue();
        LocalDate h = hasta.getValue();
        Sucursal s = sucursal.getValue();
        if (d == null || h == null || s == null) {
            return;
        }
        if (d.isAfter(h)) {
            a.avisos().advertencia("El periodo no es válido: «del» es después de «al».");
            return;
        }
        tabla.cargando("Cargando surtidos…");
        carga.cargar(() -> a.admin().surtidos().listar(d, h, s.id()), lista -> {
            tabla.setDatos(lista);
            filtrar();
            if (maestro.estaAbierto() && tabla.seleccionado() != null) {
                mostrarDetalle(tabla.seleccionado());
            }
        }, e -> {
            if (tabla.sinDatos()) {
                tabla.error("No se pudieron cargar los surtidos.", AdminContexto.mensaje(e), this::cargar);
            } else {
                a.avisos().errorConAccion("No se pudieron actualizar los surtidos: " + AdminContexto.mensaje(e),
                        "Reintentar", this::cargar);
            }
        });
    }

    private void filtrar() {
        String t = barra.busqueda();
        Pago p = pago.getValue() == null ? Pago.TODOS : pago.getValue();
        tabla.setFiltro(s -> (t.isEmpty() || Texto.contiene(s.folio(), t) || Texto.contiene(s.sucursal(), t)
                || Texto.contiene(s.usuario(), t) || Texto.contiene(s.ordenFolio(), t))
                && (p == Pago.TODOS || (p == Pago.CREDITO) == (s.formaPago() == SurtidoService.FormaPago.CREDITO)));
        List<SurtidoService.Resumen> visibles = tabla.visibles();
        long costo = visibles.stream().mapToLong(SurtidoService.Resumen::costoCentavos).sum();
        long venta = visibles.stream().mapToLong(SurtidoService.Resumen::ventaCentavos).sum();
        List<SurtidoService.Resumen> credito = visibles.stream()
                .filter(s -> s.formaPago() == SurtidoService.FormaPago.CREDITO).toList();
        metricaCosto.setValor(Dinero.formatear(costo)).setContexto(periodoTexto());
        metricaSurtidos.setValor(String.valueOf(visibles.size()))
                .setContexto(Texto.plural(visibles.stream().mapToInt(SurtidoService.Resumen::productos).sum(),
                        "producto enviado", "productos enviados"));
        metricaCredito.setValor(Dinero.formatear(credito.stream().mapToLong(SurtidoService.Resumen::costoCentavos).sum()))
                .setContexto(Texto.plural(credito.size(), "surtido", "surtidos") + " a crédito");
        metricaVenta.setValor(Dinero.formatear(venta)).setContexto("A los precios fijados en cada sucursal");
        barra.setResultados(tabla.mostrados() == tabla.total() ? Texto.plural(tabla.total(), "surtido", "surtidos")
                : tabla.mostrados() + " de " + Texto.plural(tabla.total(), "surtido", "surtidos"));
    }

    private String periodoTexto() {
        return desde.getValue() == null || hasta.getValue() == null ? ""
                : Formatos.FECHA_CORTA.format(desde.getValue()) + " – " + Formatos.FECHA_CORTA.format(hasta.getValue());
    }

    private void mostrarDetalle(SurtidoService.Resumen s) {
        String destino = s.sucursal() + (codigo(s.sucursalId()) == null ? "" : " (" + codigo(s.sucursalId()) + ")");
        detalle.setTitulo("Surtido " + s.folio(), destino + " · " + Formatos.fechaHora(s.fecha()));
        List<Node> etiquetas = new ArrayList<>();
        etiquetas.add(Etiqueta.de(s.formaPago().nombre(),
                s.formaPago() == SurtidoService.FormaPago.CREDITO ? Etiqueta.Tono.AVISO : Etiqueta.Tono.EXITO));
        if (s.ordenFolio() != null) {
            etiquetas.add(Etiqueta.simple("Por orden " + s.ordenFolio(), Etiqueta.Tono.NEUTRO));
        }
        detalle.setEtiquetas(etiquetas.toArray(Node[]::new));
        VBox cifras = new VBox(6,
                PanelDetalle.cifra("Enviado al costo", Dinero.formatear(s.costoCentavos()), null),
                PanelDetalle.dato("Valor a precio de venta", Dinero.formatear(s.ventaCentavos())),
                PanelDetalle.dato("Productos", String.valueOf(s.productos())),
                PanelDetalle.dato("Surtió", s.usuario()));
        VBox lineas = PanelDetalle.seccion("Productos enviados", Ui.texto("Cargando…", "texto-secundario"));
        detalle.setContenido(cifras, lineas);
        Button ticket = Ui.principal("Ver ticket", "mdi2p-printer-outline");
        ticket.setOnAction(e -> ticket(s));
        detalle.setAcciones(ticket);
        cargaDetalle.cargar(() -> a.admin().surtidos().detalle(s.id()), d -> {
            List<Node> filas = new ArrayList<>();
            for (SurtidoService.DetalleLinea l : d.lineas()) {
                String precios = l.precios().entrySet().stream()
                        .map(e -> e.getKey() + " " + Dinero.formatear(e.getValue())).collect(Collectors.joining(" · "));
                filas.add(PanelDetalle.filaLista(l.producto(),
                        Cantidades.formatear(l.unidad(), l.cantidad()) + (precios.isBlank() ? "" : " · " + precios),
                        Dinero.formatear(l.costoCentavos()), null));
            }
            List<Node> contenido = new ArrayList<>();
            contenido.add(lineas.getChildren().getFirst());
            contenido.add(filas.isEmpty() ? Ui.texto("Sin productos.", "texto-secundario") : PanelDetalle.lista(filas));
            if (d.notas() != null && !d.notas().isBlank()) {
                contenido.add(Ui.texto("Notas: " + d.notas(), "campo-ayuda"));
            }
            if (s.formaPago() == SurtidoService.FormaPago.CREDITO) {
                contenido.add(PanelDetalle.dato("Saldo de crédito de la sucursal hoy", Dinero.formatear(d.saldoCredito())));
            }
            lineas.getChildren().setAll(contenido);
        }, e -> lineas.getChildren().set(1, Ui.texto("No se pudo cargar el detalle: " + AdminContexto.mensaje(e),
                "texto-error")));
    }

    private void ticket(SurtidoService.Resumen s) {
        Cargador.ejecutar(() -> a.admin().surtidos().detalle(s.id()),
                d -> DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Surtido " + s.folio(),
                        s.sucursal() + " · " + Dinero.formatear(s.costoCentavos()), TicketTexto.lineasSurtido(d), null),
                e -> a.avisos().error("No se pudo preparar el ticket: " + AdminContexto.mensaje(e)));
    }
}
