package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.ToggleSwitch;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.service.DevolucionService;
import com.cremerias.puntoventa.service.admin.DevolucionSucursalService;
import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Surtidos enviados del almacén a las sucursales: consulta, detalle, ticket, estado de la
 * recepción, cancelación de lo que no se ha recibido, aclaración de diferencias y devoluciones de
 * las sucursales a la bodega.
 */
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
    private Map<String, RecepcionSurtidoService.Estado> estados = Map.of();
    private Button diferencias;
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
        diferencias = Ui.secundario("Diferencias", "mdi2a-alert-circle-outline");
        diferencias.setOnAction(e -> diferencias());
        Button devolucion = Ui.secundario("Devolución de sucursal", "mdi2k-keyboard-return");
        devolucion.setOnAction(e -> devolucionSucursal());
        VBox pagina = Ui.pagina("Surtidos a sucursales",
                "Mercancía que sale del almacén hacia las sucursales, en efectivo o a crédito.", devolucion, diferencias, nuevo);

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
        var columnaEstado = tabla.etiqueta("Recepción", this::estado, RecepcionSurtidoService.Estado::nombre,
                SurtidosPagina::tono).ancho(190);
        var columnaProductos = tabla.numero("Productos", SurtidoService.Resumen::productos, String::valueOf).ancho(98);
        tabla.dinero("Costo", SurtidoService.Resumen::costoCentavos).ancho(120)
                .ayuda("Lo que sale del almacén, al costo de sus lotes.");
        var columnaInterno = tabla.dinero("Cargo a la sucursal", SurtidoService.Resumen::internoCentavos).ancho(150)
                .ayuda("Precio interno: lo que la sucursal le debe a la bodega (costo más el margen del canal).");
        var columnaVenta = tabla.dinero("Valor de venta", SurtidoService.Resumen::ventaCentavos).ancho(132)
                .ayuda("Lo que vale a los precios que se fijaron en la sucursal.");
        var columnaUsuario = tabla.texto("Surtió", SurtidoService.Resumen::usuario).ancho(170).oculta();
        var columnaAcciones = tabla.acciones(
                Accion.<SurtidoService.Resumen>boton("mdi2e-eye-outline", "Ver detalle", s -> {
                    tabla.tabla().getSelectionModel().select(s);
                    mostrarDetalle(s);
                    maestro.abrir();
                }),
                Accion.<SurtidoService.Resumen>boton("mdi2p-printer-outline", "Ticket para imprimir", this::ticket),
                Accion.<SurtidoService.Resumen>menu("mdi2c-cancel", s -> "Cancelar surtido", this::cancelar)
                        .si(s -> estado(s) == RecepcionSurtidoService.Estado.ENVIADO).peligrosa());
        tabla.marcar("atenuada", s -> estado(s) == RecepcionSurtidoService.Estado.CANCELADO);
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
            columnaEstado.columna().setVisible(!reducida);
            columnaInterno.columna().setVisible(!reducida);
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

    private RecepcionSurtidoService.Estado estado(SurtidoService.Resumen s) {
        return estados.getOrDefault(s.id(), RecepcionSurtidoService.Estado.ENVIADO);
    }

    private static Etiqueta.Tono tono(RecepcionSurtidoService.Estado e) {
        return switch (e) {
            case ENVIADO -> Etiqueta.Tono.INFO;
            case RECIBIDO -> Etiqueta.Tono.EXITO;
            case CON_DIFERENCIA -> Etiqueta.Tono.AVISO;
            case ACLARADO -> Etiqueta.Tono.NEUTRO;
            case CANCELADO -> Etiqueta.Tono.PELIGRO;
        };
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
        carga.cargar(() -> {
            List<SurtidoService.Resumen> lista = a.admin().surtidos().listar(d, h, s.id());
            return new Carga(lista, a.admin().recepciones().estados(lista.stream().map(SurtidoService.Resumen::id).toList()),
                    a.admin().recepciones().diferencias(true, a.usuario()).size());
        }, carga -> {
            List<SurtidoService.Resumen> lista = carga.lista();
            estados = carga.estados();
            diferencias.setText(carga.diferenciasPendientes() == 0 ? "Diferencias"
                    : "Diferencias (" + carga.diferenciasPendientes() + ")");
            diferencias.getStyleClass().remove("warning");
            if (carga.diferenciasPendientes() > 0) {
                diferencias.getStyleClass().add("warning");
            }
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
        long porRecibir = visibles.stream().filter(x -> estado(x) == RecepcionSurtidoService.Estado.ENVIADO).count();
        metricaSurtidos.setValor(String.valueOf(visibles.size()))
                .setContexto(porRecibir == 0 ? "Todos recibidos o aclarados"
                        : Texto.plural(porRecibir, "por recibir en sucursal", "por recibir en sucursal"));
        metricaCredito.setValor(Dinero.formatear(credito.stream().mapToLong(SurtidoService.Resumen::internoCentavos).sum()))
                .setContexto(Texto.plural(credito.size(), "surtido", "surtidos") + " a crédito, a precio interno");
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
        etiquetas.add(Etiqueta.de(estado(s).nombre(), tono(estado(s))));
        if (s.ordenFolio() != null) {
            etiquetas.add(Etiqueta.simple("Por orden " + s.ordenFolio(), Etiqueta.Tono.NEUTRO));
        }
        detalle.setEtiquetas(etiquetas.toArray(Node[]::new));
        VBox cifras = new VBox(6,
                PanelDetalle.cifra("Enviado al costo", Dinero.formatear(s.costoCentavos()), null),
                PanelDetalle.dato("Cargo a la sucursal", Dinero.formatear(s.internoCentavos())),
                PanelDetalle.dato("Valor a precio de venta", Dinero.formatear(s.ventaCentavos())),
                PanelDetalle.dato("Productos", String.valueOf(s.productos())),
                PanelDetalle.dato("Surtió", s.usuario()));
        VBox lineas = PanelDetalle.seccion("Productos enviados", Ui.texto("Cargando…", "texto-secundario"));
        detalle.setContenido(cifras, lineas);
        Button ticket = Ui.principal("Ver ticket", "mdi2p-printer-outline");
        ticket.setOnAction(e -> ticket(s));
        if (estado(s) == RecepcionSurtidoService.Estado.ENVIADO) {
            Button cancelar = Ui.boton("Cancelar", "mdi2c-cancel", "flat", "danger");
            cancelar.setOnAction(e -> cancelar(s));
            detalle.setAcciones(ticket, cancelar);
        } else {
            detalle.setAcciones(ticket);
        }
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

    private record Carga(List<SurtidoService.Resumen> lista, Map<String, RecepcionSurtidoService.Estado> estados,
                         int diferenciasPendientes) {
    }

    // ------------------------------------------------------------------ cancelar, diferencias y devoluciones

    /** Cancela un surtido que la sucursal no ha recibido ni tocado: todo regresa a los lotes del almacén. */
    private void cancelar(SurtidoService.Resumen s) {
        Dialogo d = new Dialogo("Cancelar el surtido " + s.folio(), s.sucursal() + " · " + Dinero.formatear(s.costoCentavos()),
                "mdi2c-cancel", Dialogo.Tono.PELIGRO);
        d.setPrefWidth(540);
        TextField motivo = new TextField();
        motivo.setPromptText("Ej. Se capturó a la sucursal equivocada");
        Formulario.Campo campoMotivo = Formulario.campo("Motivo", motivo);
        motivo.textProperty().addListener((o, x, y) -> campoMotivo.setError(null));
        d.setContenido(Formulario.nota("mdi2a-alert-outline", "La mercancía sale de la sucursal y regresa a los mismos "
                        + "lotes del almacén. " + (s.formaPago() == SurtidoService.FormaPago.CREDITO
                        ? "El cargo a crédito se cancela con una nota de crédito." : "")
                        + " Solo se puede si la sucursal no lo ha recibido ni vendido.", "peligro"), campoMotivo);
        String cancelacionId = Ids.nuevo();
        Runnable confirmar = () -> {
            if (motivo.getText() == null || motivo.getText().isBlank()) {
                campoMotivo.setError("Escribe por qué se cancela.");
                return;
            }
            String texto = motivo.getText();
            a.guardar(d, "Surtido " + s.folio() + " cancelado",
                    () -> a.admin().recepciones().cancelar(cancelacionId, s.id(), texto, a.usuario()), this::cargar);
        };
        d.agregarBoton("Regresar", null, d::cancelar, "flat");
        d.agregarBoton("Cancelar surtido", "mdi2c-cancel", confirmar, "danger");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(confirmar);
        d.setFocoInicial(motivo);
        d.vigilarCambios(motivo);
        a.dialogos().mostrar(d);
    }

    /** Diferencias que reportaron las sucursales al recibir; el administrador decide qué pasó. */
    private void diferencias() {
        Dialogo d = new Dialogo("Diferencias de entrega", "Lo que la sucursal dijo que no llegó, al recibir el surtido.",
                "mdi2a-alert-circle-outline", Dialogo.Tono.ADVERTENCIA);
        d.setPrefWidth(980);
        TablaDatos<RecepcionSurtidoService.Diferencia> lista = new TablaDatos<>(Diseno.Densidad.NORMAL);
        lista.setClave(RecepcionSurtidoService.Diferencia::recepcionDetalleId);
        lista.dosLineas("Surtido", RecepcionSurtidoService.Diferencia::surtidoFolio, RecepcionSurtidoService.Diferencia::sucursal)
                .ancho(200);
        lista.texto("Producto", RecepcionSurtidoService.Diferencia::producto).flexible(180);
        lista.cantidad("No llegó", RecepcionSurtidoService.Diferencia::cantidad, RecepcionSurtidoService.Diferencia::unidad)
                .ancho(110);
        lista.dinero("Al costo", RecepcionSurtidoService.Diferencia::costoCentavos).ancho(110);
        lista.dinero("Cargo", RecepcionSurtidoService.Diferencia::internoCentavos).ancho(110)
                .ayuda("Lo que se le cargó a la sucursal por esa cantidad (precio interno).");
        lista.etiqueta("Estado", x -> x.aclarada() ? 1 : 0, v -> v == 0 ? "Por aclarar" : "Aclarada",
                v -> v == 0 ? Etiqueta.Tono.AVISO : Etiqueta.Tono.NEUTRO).ancho(120);
        lista.nodo("", x -> {
            if (x.aclarada()) {
                return Ui.texto(x.decision() == null ? "" : x.decision().nombre(), "texto-secundario");
            }
            Button aclarar = Ui.boton("Aclarar", "mdi2c-check-decagram-outline", "accent", "small");
            aclarar.setOnAction(e -> aclarar(x, () -> {
                d.cerrar();
                diferencias();
            }));
            HBox h = new HBox(aclarar);
            h.setAlignment(Pos.CENTER_RIGHT);
            return h;
        }).ancho(170).minimo(170);
        lista.setVacio(new EstadoVacio("mdi2c-check-circle-outline", "No hay diferencias de entrega.",
                "Cuando una sucursal reciba menos de lo enviado, aparecerá aquí.").compacto());
        lista.tabla().setPrefHeight(320);
        lista.cargando("Cargando diferencias…");
        Cargador.ejecutar(() -> a.admin().recepciones().diferencias(false, a.usuario()), lista::setDatos,
                e -> lista.error("No se pudieron cargar las diferencias.", AdminContexto.mensaje(e), null));
        VBox panel = new VBox(lista.tabla());
        panel.getStyleClass().add("panel-tabla");
        Diseno.recortarRedondeado(panel, Diseno.RADIO_PANEL);
        d.setContenido(panel);
        d.agregarBoton("Cerrar", null, d::cerrar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(d::cerrar);
        a.dialogos().mostrar(d);
    }

    private void aclarar(RecepcionSurtidoService.Diferencia dif, Runnable despues) {
        Dialogo d = new Dialogo("Aclarar diferencia", dif.surtidoFolio() + " · " + dif.producto() + " · no llegaron "
                + Cantidades.formatear(dif.unidad(), dif.cantidad()), "mdi2c-check-decagram-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        Segmentado<RecepcionSurtidoService.Decision> decision = new Segmentado<>();
        for (RecepcionSurtidoService.Decision x : RecepcionSurtidoService.Decision.values()) {
            decision.opcion(x, x.nombre());
        }
        Label ayuda = Ui.texto("", "campo-ayuda");
        decision.valorProperty().addListener((o, x, y) -> ayuda.setText(y == RecepcionSurtidoService.Decision.REINGRESO_ALMACEN
                ? "La mercancía vuelve a la existencia del almacén (al costo con que salió)."
                : "La mercancía se perdió: no regresa a ningún inventario y queda como pérdida al costo."));
        ayuda.setText("La mercancía vuelve a la existencia del almacén (al costo con que salió).");
        ToggleSwitch acreditar = new ToggleSwitch("Acreditar a la sucursal " + Dinero.formatear(dif.internoCentavos()));
        acreditar.setSelected(dif.credito());
        acreditar.setDisable(!dif.credito());
        Label ayudaCredito = Ui.texto(dif.credito() ? "Se registra una nota de crédito: la sucursal deja de deber lo que no recibió."
                : "El surtido fue de contado: no hay saldo que acreditar.", "campo-ayuda");
        TextField nota = new TextField();
        nota.setPromptText("Ej. Se quedó en la camioneta");
        d.setContenido(Formulario.campo("¿Qué pasó?", new VBox(6, decision, ayuda)), new VBox(6, acreditar, ayudaCredito),
                Formulario.opcional("Nota", nota));
        String aclaracionId = Ids.nuevo();
        Runnable guardar = () -> {
            RecepcionSurtidoService.Decision decisionElegida = decision.getValor();
            boolean conNota = acreditar.isSelected();
            String texto = nota.getText();
            a.guardar(d, "Diferencia aclarada", () -> a.admin().recepciones().aclarar(aclaracionId, dif, decisionElegida,
                    conNota, texto, a.usuario()), () -> {
                cargar();
                despues.run();
            });
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Aclarar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.vigilarCambios(decision, acreditar, nota);
        a.dialogos().mostrar(d);
    }

    /** La sucursal regresa mercancía a la bodega: lo vendible vuelve al almacén; se le acredita el precio interno. */
    private void devolucionSucursal() {
        Dialogo d = new Dialogo("Devolución de sucursal a bodega",
                "Lo vendible regresa al almacén; lo dañado o caducado solo sale de la sucursal.", "mdi2k-keyboard-return",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(680);
        String devolucionId = Ids.nuevo();
        ComboBox<Sucursal> origen = new ComboBox<>(FXCollections.observableArrayList(sucursales.values().stream()
                .filter(x -> !x.esAlmacen() && x.activo()).sorted(java.util.Comparator.comparing(Sucursal::nombre)).toList()));
        origen.setConverter(Combos.sucursal());
        origen.setMaxWidth(Double.MAX_VALUE);
        origen.setPromptText("Elige la sucursal");
        Formulario.Campo campoOrigen = Formulario.campo("Sucursal que devuelve", origen);
        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(productos);
        TextField cantidad = new TextField();
        Campos.soloNumeros(cantidad, 0);
        cantidad.setPrefColumnCount(6);
        cantidad.setAlignment(Pos.CENTER_RIGHT);
        cantidad.setPromptText("Cantidad");
        Label unidad = new Label("pzas");
        unidad.getStyleClass().add("unidad-campo");
        producto.valueProperty().addListener((o, x, p) -> unidad.setText(p == null ? "pzas" : Cantidades.unidadCaptura(p.unidad())));
        ComboBox<DevolucionService.EstadoProducto> estado = new ComboBox<>(FXCollections.observableArrayList(
                DevolucionService.EstadoProducto.values()));
        estado.setConverter(Combos.de(DevolucionService.EstadoProducto::corto));
        estado.setValue(DevolucionService.EstadoProducto.VENDIBLE);
        estado.setPrefWidth(150);
        Button agregar = Ui.boton("Agregar", "mdi2p-plus");
        agregar.setMinWidth(Region.USE_PREF_SIZE);
        unidad.setMinWidth(Region.USE_PREF_SIZE);
        producto.setMaxWidth(Double.MAX_VALUE);
        HBox detalleCaptura = new HBox(8, cantidad, unidad, estado, agregar);
        detalleCaptura.setAlignment(Pos.CENTER_LEFT);
        VBox captura = new VBox(8, producto, detalleCaptura);
        Label error = Ui.texto("", "campo-error");
        error.managedProperty().bind(error.visibleProperty());
        error.setVisible(false);
        List<DevolucionSucursalService.Linea> lineas = new ArrayList<>();
        VBox lista = new VBox(4);
        Runnable[] dibujar = new Runnable[1];
        dibujar[0] = () -> {
            lista.getChildren().clear();
            for (DevolucionSucursalService.Linea l : lineas) {
                Button quitar = Ui.boton(null, "mdi2t-trash-can-outline", "flat", "boton-icono", "danger");
                quitar.setOnAction(e -> {
                    lineas.remove(l);
                    dibujar[0].run();
                });
                lista.getChildren().add(PanelDetalle.filaLista(l.producto().nombre(), l.estado().nombre(),
                        Cantidades.formatear(l.producto().unidad(), l.cantidad()), quitar));
            }
            if (lineas.isEmpty()) {
                lista.getChildren().add(Ui.texto("Agrega lo que regresa la sucursal.", "campo-ayuda"));
            }
        };
        dibujar[0].run();
        Runnable accionAgregar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            BigDecimal c = p == null ? null : DialogoCompra.base(p, cantidad.getText()).orElse(null);
            if (p == null || c == null) {
                error.setText(p == null ? "Elige el producto." : "Escribe la cantidad.");
                error.setVisible(true);
                return;
            }
            error.setVisible(false);
            lineas.add(new DevolucionSucursalService.Linea(p, c, estado.getValue()));
            producto.getEditor().clear();
            producto.setValue(null);
            cantidad.clear();
            d.marcarCambios();
            dibujar[0].run();
        };
        agregar.setOnAction(e -> accionAgregar.run());
        cantidad.setOnAction(e -> accionAgregar.run());
        TextField motivo = new TextField();
        motivo.setPromptText("Ej. No se vendió, cerca de caducar");
        Formulario.Campo campoMotivo = Formulario.campo("Motivo", motivo);
        motivo.textProperty().addListener((o, x, y) -> campoMotivo.setError(null));
        ToggleSwitch acreditar = new ToggleSwitch("Acreditar a la sucursal lo devuelto (precio interno)");
        acreditar.setSelected(true);
        d.setContenido(campoOrigen, Formulario.seccionSeparada("Qué regresa", "Sale de los lotes más antiguos de la sucursal.",
                        captura, error, lista), campoMotivo,
                new VBox(6, acreditar, Ui.texto("Con nota de crédito, el saldo de la sucursal baja por lo que se le cobró "
                        + "de esa mercancía. Nunca deja el saldo en negativo.", "campo-ayuda")));
        Runnable guardar = () -> {
            boolean valido = true;
            if (origen.getValue() == null) {
                campoOrigen.setError("Elige la sucursal.");
                valido = false;
            }
            if (motivo.getText() == null || motivo.getText().isBlank()) {
                campoMotivo.setError("Escribe por qué se devuelve.");
                valido = false;
            }
            if (lineas.isEmpty()) {
                d.mostrarError("Agrega al menos un producto.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            Sucursal s = origen.getValue();
            String m = motivo.getText();
            boolean conNota = acreditar.isSelected();
            List<DevolucionSucursalService.Linea> datos = List.copyOf(lineas);
            a.guardar(d, "Devolución de " + s.nombre() + " registrada", () -> a.admin().devolucionesSucursal().registrar(
                    devolucionId, s, datos, m, conNota, a.usuario()), this::cargar);
        };
        origen.valueProperty().addListener((o, x, y) -> campoOrigen.setError(null));
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar devolución", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(origen);
        d.vigilarCambios(origen, motivo, acreditar);
        a.dialogos().mostrar(d);
    }

    private void ticket(SurtidoService.Resumen s) {
        Cargador.ejecutar(() -> a.admin().surtidos().detalle(s.id()),
                d -> DialogoTicket.mostrar(a.dialogos(), a.avisos(), "Surtido " + s.folio(),
                        s.sucursal() + " · " + Dinero.formatear(s.costoCentavos()), TicketTexto.lineasSurtido(d), null),
                e -> a.avisos().error("No se pudo preparar el ticket: " + AdminContexto.mensaje(e)));
    }
}
