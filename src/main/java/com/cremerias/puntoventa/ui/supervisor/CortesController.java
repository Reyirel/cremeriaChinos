package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.CorteRealizado;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.caja.DialogoCorte;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EncabezadoPagina;
import com.cremerias.puntoventa.ui.componentes.Errores;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Cajas abiertas y cortes realizados. El supervisor ve solo su sucursal; el administrador, todas o
 * la que elija (misma vista en su panel).
 */
public class CortesController {

    private static final Logger log = LoggerFactory.getLogger(CortesController.class);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("d MMM, HH:mm",
            Locale.forLanguageTag("es-MX"));
    private static final Sucursal TODAS = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);

    /** Lo que se muestra, cargado fuera del hilo de la interfaz. */
    private record Datos(List<ResumenTurno> abiertas, List<CorteRealizado> cortesHoy, List<CorteRealizado> cortesFecha) {
    }

    private final AppContext ctx;
    private final Sesion sesion;
    private final Dialogos dialogos;
    private final Avisos avisos;
    private final boolean esAdministrador;
    private final Map<String, String> nombresSucursal = new HashMap<>();
    private final Cargador carga = new Cargador();
    /** Sucursal que se muestra; nula = todas (solo el administrador). */
    private String sucursalId;
    private String nombreSucursal;
    private Runnable alCambiar = () -> { };

    @FXML private ScrollPane scroll;
    @FXML private VBox pagina;
    private EncabezadoPagina encabezado;
    private ComboBox<Sucursal> filtroSucursal;
    private ResumenMetricas.Metrica metricaAbiertas;
    private ResumenMetricas.Metrica metricaVendido;
    private ResumenMetricas.Metrica metricaEfectivo;
    private ResumenMetricas.Metrica metricaCortes;
    private Label contadorAbiertas;
    private FlowPane tarjetasAbiertas;
    private Node sinAbiertas;
    private Label contadorCortes;
    private DatePicker fecha;
    private TablaDatos<CorteRealizado> tablaCortes;

    public CortesController(Navegador navegador) {
        this.ctx = navegador.contexto();
        this.sesion = ctx.sesionActual().obtener().orElseThrow();
        this.dialogos = navegador.dialogos();
        this.avisos = navegador.avisos();
        this.esAdministrador = sesion.usuario().rol() == Rol.ADMINISTRADOR;
        this.sucursalId = esAdministrador ? null : sesion.usuario().sucursalId();
        this.nombreSucursal = esAdministrador ? "Todas las sucursales" : ctx.nombreSucursal(sucursalId);
    }

    public void setAlCambiar(Runnable alCambiar) {
        this.alCambiar = alCambiar;
    }

    @FXML
    private void initialize() {
        Button refrescar = new Button("Actualizar", new FontIcon("mdi2r-refresh"));
        refrescar.setOnAction(e -> {
            actualizar();
            avisos.info("Información actualizada.");
        });
        encabezado = new EncabezadoPagina("Cortes de caja", null);
        if (esAdministrador) {
            filtroSucursal = new ComboBox<>();
            filtroSucursal.setAccessibleText("Sucursal");
            filtroSucursal.setPrefWidth(250);
            filtroSucursal.setConverter(new StringConverter<>() {
                @Override
                public String toString(Sucursal s) {
                    return s == null ? "" : s.id() == null ? s.nombre() : s.nombre() + " (" + s.codigo() + ")";
                }

                @Override
                public Sucursal fromString(String t) {
                    return filtroSucursal.getValue();
                }
            });
            encabezado.acciones(filtroSucursal, refrescar);
        } else {
            encabezado.acciones(refrescar);
        }
        encabezado.setDescripcion(descripcion());

        ResumenMetricas resumen = new ResumenMetricas();
        metricaAbiertas = resumen.agregar("Cajas abiertas").principal();
        metricaVendido = resumen.agregar("Vendido en cajas abiertas");
        metricaEfectivo = resumen.agregar("Efectivo por recoger");
        metricaCortes = resumen.agregar("Cortes de hoy");

        contadorAbiertas = new Label();
        contadorAbiertas.getStyleClass().add("seccion-contador");
        tarjetasAbiertas = new FlowPane(Diseno.ESPACIO_L, Diseno.ESPACIO_L);
        sinAbiertas = new EstadoVacio("mdi2c-check-circle-outline", "No hay cajas abiertas.",
                "Todas las cajas tienen su corte hecho.").tono("exito").compacto();
        VBox panelSinAbiertas = new VBox(sinAbiertas);
        panelSinAbiertas.getStyleClass().add("panel");
        panelSinAbiertas.managedProperty().bind(panelSinAbiertas.visibleProperty());
        tarjetasAbiertas.managedProperty().bind(tarjetasAbiertas.visibleProperty());
        sinAbiertas = panelSinAbiertas;

        fecha = new DatePicker(LocalDate.now());
        fecha.setEditable(false);
        fecha.setPrefWidth(160);
        fecha.setAccessibleText("Fecha de los cortes");
        fecha.valueProperty().addListener((o, a, b) -> actualizar());
        contadorCortes = new Label();
        contadorCortes.getStyleClass().add("seccion-contador");
        Label etiquetaFecha = new Label("Fecha");
        etiquetaFecha.getStyleClass().add("etiqueta-filtro");
        HBox tituloCortes = new HBox(10, titulo("Cortes realizados"), contadorCortes, espacio(), etiquetaFecha, fecha);
        tituloCortes.setAlignment(Pos.CENTER_LEFT);

        tablaCortes = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tablaCortes.setClave(c -> c.turno().id());
        tablaCortes.dosLineas("Caja", c -> c.turno().dispositivoNombre(),
                c -> esAdministrador ? nombresSucursal.getOrDefault(c.turno().sucursalId(), "") : null).ancho(180);
        tablaCortes.texto("Cajero", c -> c.turno().usuarioNombre()).flexible(170);
        tablaCortes.formato("Horario", c -> c.turno().abiertoEn(),
                (c, v) -> HORA.format(v.atZone(ZoneId.systemDefault())) + " – " + HORA.format(c.cerradoEn().atZone(ZoneId.systemDefault())))
                .ancho(130);
        tablaCortes.numero("Ventas", CorteRealizado::numeroVentas, String::valueOf).ancho(86);
        tablaCortes.dinero("Vendido", CorteRealizado::totalVendido).ancho(120);
        tablaCortes.dinero("Esperado", CorteRealizado::efectivoEsperado).ancho(120).ayuda("Efectivo que debía haber en el cajón.");
        tablaCortes.dinero("Contado", CorteRealizado::efectivoContado).ancho(120);
        tablaCortes.etiqueta("Diferencia", CorteRealizado::diferencia,
                d -> d == 0 ? "Exacto" : (d < 0 ? "Faltan " : "Sobran ") + Dinero.formatear(Math.abs(d)),
                d -> d == 0 ? Etiqueta.Tono.EXITO : d < 0 ? Etiqueta.Tono.PELIGRO : Etiqueta.Tono.AVISO).ancho(150);
        tablaCortes.texto("Hizo el corte", CorteRealizado::cerradoPor).ancho(170);
        tablaCortes.acciones(TablaDatos.Accion.<CorteRealizado>boton("mdi2r-receipt-text-outline", "Ver ticket del corte",
                this::verTicket));
        tablaCortes.setVacio(new EstadoVacio("mdi2c-calculator-variant-outline", "No hay cortes en esta fecha.",
                "Elige otra fecha para ver sus cortes.").compacto());
        tablaCortes.setAlActivar(this::verTicket);
        tablaCortes.tabla().setPrefHeight(40 + 7 * 48);
        tablaCortes.tabla().setMinHeight(40 + 3 * 48);
        VBox panelCortes = new VBox(tablaCortes.tabla());
        panelCortes.getStyleClass().add("panel-tabla");
        Diseno.recortarRedondeado(panelCortes, Diseno.RADIO_PANEL);

        HBox tituloAbiertas = new HBox(10, titulo("Cajas abiertas"), contadorAbiertas);
        tituloAbiertas.setAlignment(Pos.CENTER_LEFT);
        pagina.getChildren().setAll(encabezado, resumen, tituloAbiertas, tarjetasAbiertas, sinAbiertas, tituloCortes,
                panelCortes);

        if (esAdministrador) {
            configurarFiltro();
        } else if (sucursalId == null) {
            encabezado.setDescripcion("Tu usuario no tiene una sucursal asignada.");
            return;
        }
        actualizar();
    }

    private static Label titulo(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("seccion-titulo");
        return l;
    }

    private static Region espacio() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private String descripcion() {
        return esAdministrador
                ? (sucursalId == null ? "Cajas de todas las sucursales." : "Cajas de " + nombreSucursal + ".")
                : nombreSucursal + " · solo ves y cierras las cajas de esta sucursal.";
    }

    /** El administrador puede ver y cortar las cajas de todas las sucursales o de una. */
    private void configurarFiltro() {
        List<Sucursal> opciones = new ArrayList<>();
        opciones.add(TODAS);
        for (Sucursal s : ctx.admin().sucursales().listar()) {
            opciones.add(s);
            nombresSucursal.put(s.id(), s.nombre());
        }
        filtroSucursal.setItems(FXCollections.observableArrayList(opciones));
        filtroSucursal.setValue(TODAS);
        filtroSucursal.valueProperty().addListener((o, x, s) -> {
            if (s == null) {
                return;
            }
            sucursalId = s.id();
            nombreSucursal = s.nombre();
            encabezado.setDescripcion(descripcion());
            actualizar();
        });
    }

    public void actualizar() {
        if (sucursalId == null && !esAdministrador) {
            return;
        }
        LocalDate dia = fecha.getValue() == null ? LocalDate.now() : fecha.getValue();
        String sucursal = sucursalId;
        tablaCortes.cargando("Cargando cortes…");
        carga.cargar(() -> new Datos(ctx.caja().abiertosDeSucursal(sucursal), ctx.caja().cortesDelDia(sucursal, LocalDate.now()),
                ctx.caja().cortesDelDia(sucursal, dia)), this::pintar,
                e -> tablaCortes.error("No se pudieron cargar los cortes.", Errores.mensaje(e), this::actualizar));
    }

    private void pintar(Datos d) {
        tarjetasAbiertas.getChildren().setAll(d.abiertas().stream().map(this::tarjeta).toList());
        tarjetasAbiertas.setVisible(!d.abiertas().isEmpty());
        sinAbiertas.setVisible(d.abiertas().isEmpty());
        contadorAbiertas.setText(String.valueOf(d.abiertas().size()));

        long vendidoAbiertas = d.abiertas().stream().mapToLong(ResumenTurno::totalVentas).sum();
        long efectivoPorRecoger = d.abiertas().stream().mapToLong(ResumenTurno::efectivoEsperado).sum();
        long vendidoCortes = d.cortesHoy().stream().mapToLong(CorteRealizado::totalVendido).sum();
        long diferencias = d.cortesHoy().stream().mapToLong(CorteRealizado::diferencia).sum();
        metricaAbiertas.setValor(String.valueOf(d.abiertas().size()))
                .setContexto(d.abiertas().isEmpty() ? "Todas con su corte" : "Pendientes de corte");
        metricaVendido.setValor(Dinero.formatear(vendidoAbiertas));
        metricaEfectivo.setValor(Dinero.formatear(efectivoPorRecoger)).setContexto("Fondo + efectivo + entradas − retiros");
        metricaCortes.setValor(d.cortesHoy().size() + " · " + Dinero.formatear(vendidoCortes))
                .setContexto(diferencias == 0 ? (d.cortesHoy().isEmpty() ? "Aún no hay cortes hoy" : "Sin diferencias")
                        : (diferencias < 0 ? "Faltantes " : "Sobrantes ") + Dinero.formatear(Math.abs(diferencias)))
                .setTono(diferencias < 0 ? Etiqueta.Tono.PELIGRO : diferencias > 0 ? Etiqueta.Tono.AVISO : null);

        tablaCortes.setDatos(d.cortesFecha());
        contadorCortes.setText(String.valueOf(d.cortesFecha().size()));
    }

    // ---------------------------------------------------------------------
    // Tarjetas de cajas abiertas
    // ---------------------------------------------------------------------

    private VBox tarjeta(ResumenTurno r) {
        Turno t = r.turno();
        boolean estaCaja = t.dispositivoId().equals(ctx.caja().dispositivoId());

        Label nombre = new Label(t.dispositivoNombre());
        nombre.getStyleClass().add("tarjeta-caja-nombre");
        VBox titulo = new VBox(2, nombre);
        // Con todas las sucursales, la sucursal va debajo para que no se recorte la etiqueta «Abierta».
        if (esAdministrador && sucursalId == null) {
            Label sucursal = new Label(nombresSucursal.getOrDefault(t.sucursalId(), ""));
            sucursal.getStyleClass().add("tarjeta-caja-sucursal");
            titulo.getChildren().add(sucursal);
        }
        HBox estado = new HBox(6, Etiqueta.de("Abierta", Etiqueta.Tono.EXITO));
        if (estaCaja) {
            estado.getChildren().add(Etiqueta.simple("Esta caja", Etiqueta.Tono.NEUTRO));
        }
        estado.setMinWidth(Region.USE_PREF_SIZE);
        HBox encabezadoTarjeta = new HBox(12, titulo, espacio(), estado);
        encabezadoTarjeta.setAlignment(Pos.TOP_LEFT);

        GridPane datos = new GridPane(12, 6);
        fila(datos, 0, "Cajero", t.usuarioNombre());
        fila(datos, 1, "Desde", desde(t.abiertoEn()));
        fila(datos, 2, "Ventas", r.numeroVentas() + " · " + Dinero.formatear(r.totalVentas()));
        fila(datos, 3, "Tarjeta / transf.", Dinero.formatear(r.ventasTarjeta() + r.ventasTransferencia()));

        Label etiquetaEfectivo = new Label("Efectivo esperado en caja");
        etiquetaEfectivo.getStyleClass().add("tarjeta-caja-etiqueta");
        Label efectivo = new Label(Dinero.formatear(r.efectivoEsperado()));
        efectivo.getStyleClass().add("tarjeta-caja-efectivo");
        VBox bloqueEfectivo = new VBox(0, etiquetaEfectivo, efectivo);
        bloqueEfectivo.getStyleClass().add("tarjeta-caja-bloque");

        Button corte = new Button("Hacer corte", new FontIcon("mdi2c-calculator-variant-outline"));
        corte.getStyleClass().add("accent");
        corte.setMaxWidth(Double.MAX_VALUE);
        corte.setOnAction(e -> hacerCorte(t, estaCaja));

        VBox tarjeta = new VBox(12, encabezadoTarjeta, datos, bloqueEfectivo, corte);
        tarjeta.getStyleClass().add("tarjeta-caja");
        tarjeta.setPrefWidth(320);
        return tarjeta;
    }

    private static void fila(GridPane grid, int renglon, String etiqueta, String valor) {
        Label a = new Label(etiqueta);
        a.getStyleClass().add("tarjeta-caja-etiqueta");
        Label b = new Label(valor);
        b.getStyleClass().add("tarjeta-caja-valor");
        grid.addRow(renglon, a, b);
    }

    private static String desde(Instant instante) {
        LocalDate dia = instante.atZone(ZoneId.systemDefault()).toLocalDate();
        return dia.equals(LocalDate.now())
                ? "Hoy, " + HORA.format(instante.atZone(ZoneId.systemDefault()))
                : FECHA_HORA.format(instante.atZone(ZoneId.systemDefault()));
    }

    // ---------------------------------------------------------------------
    // Corte
    // ---------------------------------------------------------------------

    private void hacerCorte(Turno turno, boolean estaCaja) {
        Optional<Turno> vigente = ctx.caja().abiertoPorId(turno.id());
        if (vigente.isEmpty()) {
            avisos.info("A esta caja ya se le hizo el corte.");
            actualizar();
            alCambiar.run();
            return;
        }
        Runnable abrirCorte = () -> DialogoCorte.mostrar(dialogos, ctx.caja().resumen(vigente.get()),
                (contado, notas) -> cerrar(vigente.get(), contado, notas));
        if (estaCaja) {
            abrirCorte.run();
        } else {
            DialogoConfirmacion.mostrar(dialogos, "Corte de " + turno.dispositivoNombre(),
                    "Asegúrate de que " + turno.usuarioNombre() + " haya terminado de vender en esa caja y cuenta "
                            + "el efectivo de su cajón antes de continuar.",
                    "Continuar", false, abrirCorte);
        }
    }

    private void cerrar(Turno turno, long contado, String notas) {
        ResumenTurno fin;
        try {
            fin = ctx.caja().cerrar(turno, contado, notas, sesion.usuario());
        } catch (RuntimeException e) {
            log.error("No se pudo hacer el corte", e);
            avisos.error(e.getMessage() == null ? "No se pudo hacer el corte." : e.getMessage());
            actualizar();
            return;
        }
        long diferencia = contado - fin.efectivoEsperado();
        List<TicketTexto.Linea> texto = TicketTexto.lineasCorte(fin, contado, nombreDe(turno), Instant.now(), notas,
                sesion.usuario().nombreCompleto());
        ctx.sincronizador().revisarAhora();
        fecha.setValue(LocalDate.now());
        actualizar();
        alCambiar.run();
        avisos.exito("Corte de " + turno.dispositivoNombre() + " registrado: "
                + (diferencia == 0 ? "la caja cuadró exacto." : (diferencia < 0 ? "faltan " : "sobran ")
                + Dinero.formatear(Math.abs(diferencia)) + "."));
        DialogoTicket.mostrar(dialogos, avisos, "Corte de " + turno.dispositivoNombre(),
                diferencia == 0 ? "La caja cuadró exacto." : (diferencia < 0 ? "Faltante de " : "Sobrante de ")
                        + Dinero.formatear(Math.abs(diferencia)), texto, null);
    }

    // ---------------------------------------------------------------------
    // Historial
    // ---------------------------------------------------------------------

    private String nombreDe(Turno turno) {
        return esAdministrador ? nombresSucursal.getOrDefault(turno.sucursalId(), "") : nombreSucursal;
    }

    private void verTicket(CorteRealizado corte) {
        if (corte == null) {
            return;
        }
        Cargador.ejecutar(() -> ctx.caja().resumen(corte.turno()), r -> {
            List<TicketTexto.Linea> texto = TicketTexto.lineasCorte(r, corte.efectivoContado(), nombreDe(corte.turno()),
                    corte.cerradoEn(), corte.notas(), corte.cerradoPor());
            DialogoTicket.mostrar(dialogos, avisos, "Corte de " + corte.turno().dispositivoNombre(),
                    corte.turno().usuarioNombre() + " · " + Texto.plural(corte.numeroVentas(), "venta", "ventas"), texto, null);
        }, e -> avisos.error("No se pudo preparar el ticket: " + Errores.mensaje(e)));
    }
}
