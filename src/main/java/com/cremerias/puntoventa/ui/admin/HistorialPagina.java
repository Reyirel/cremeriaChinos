package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.Texto;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historial de todas las acciones, cada una con su folio y el id del registro afectado. */
public class HistorialPagina extends Pagina {

    private static final DateTimeFormatter FECHA_SEGUNDOS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final int LIMITE = 2000;
    private static final Map<String, String> TIPOS = new LinkedHashMap<>();

    static {
        TIPOS.put("", "Todas las acciones");
        TIPOS.put("SUC", "Sucursales");
        TIPOS.put("USR", "Usuarios");
        TIPOS.put("HOR", "Horarios");
        TIPOS.put("ACC", "Accesos");
        TIPOS.put("PRD", "Productos");
        TIPOS.put("ENT", "Reabastecimientos");
        TIPOS.put("MER", "Mermas");
        TIPOS.put("SUR", "Surtidos");
        TIPOS.put("ORD", "Órdenes");
        TIPOS.put("LIM", "Mínimos y máximos");
        TIPOS.put("ABN", "Abonos");
        TIPOS.put("COM", "Comisiones");
        TIPOS.put("CRT", "Cortes de caja");
        TIPOS.put("CAN", "Ventas canceladas");
        TIPOS.put("MOV", "Entradas/retiros de caja");
        TIPOS.put("CFG", "Configuración");
    }

    private final Cargador carga = new Cargador();
    private final PauseTransition espera = new PauseTransition(Duration.millis(350));
    private BarraHerramientas barra;
    private DatePicker desde;
    private DatePicker hasta;
    private ComboBox<String> tipo;
    private TablaDatos<BitacoraRepository.Registro> tabla;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;

    public HistorialPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Historial";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Historial", "Cada acción del sistema con su folio, quién la hizo y desde qué sucursal.");
        desde = new DatePicker(LocalDate.now().minusDays(7));
        hasta = new DatePicker(LocalDate.now());
        for (DatePicker p : List.of(desde, hasta)) {
            p.setEditable(false);
            p.setPrefWidth(150);
            p.valueProperty().addListener((o, x, y) -> cargar());
        }
        Label del = new Label("Del");
        Label al = new Label("al");
        del.getStyleClass().add("etiqueta-filtro");
        al.getStyleClass().add("etiqueta-filtro");
        HBox periodo = new HBox(8, del, desde, al, hasta);
        periodo.setAlignment(Pos.CENTER_LEFT);

        barra = new BarraHerramientas();
        barra.buscador("Buscar folio, descripción, usuario o id");
        barra.agregar(periodo);
        tipo = barra.filtro("Tipo", new ComboBox<>(FXCollections.observableArrayList(new ArrayList<>(TIPOS.values()))),
                TIPOS.get(""), t -> t);
        tipo.setValue(TIPOS.get(""));
        // La búsqueda consulta la base: espera a que se termine de escribir.
        espera.setOnFinished(e -> cargar());
        barra.setAlCambiar(espera::playFromStart);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(BitacoraRepository.Registro::id);
        tabla.fecha("Fecha", BitacoraRepository.Registro::fecha, Formatos.FECHA_HORA).ancho(146);
        tabla.texto("Folio", BitacoraRepository.Registro::folio).ancho(160);
        tabla.etiqueta("Acción", BitacoraRepository.Registro::accion, AccionBitacora::descripcion,
                HistorialPagina::tono).ancho(230);
        tabla.textoLargo("Descripción", BitacoraRepository.Registro::descripcion).flexible(300);
        tabla.dosLineas("Usuario", BitacoraRepository.Registro::usuario, BitacoraRepository.Registro::sucursal).ancho(200);
        tabla.setVacio(new EstadoVacio("mdi2h-history", "No hay acciones con estos filtros.",
                "Amplía el periodo o cambia el tipo de acción."));
        tabla.setSinResultados(new EstadoVacio("mdi2h-history", "No hay acciones con estos filtros.", null));
        tabla.setAlActivar(r -> {
            mostrarDetalle(r);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, r) -> {
            if (r != null && maestro.estaAbierto()) {
                mostrarDetalle(r);
            }
        });

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 700);
        maestro.setAlCerrar(() -> tabla.tabla().requestFocus());
        pagina.getChildren().add(maestro);
        return Ui.ajustable(pagina, maestro, 320);
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
        String prefijo = TIPOS.entrySet().stream().filter(e -> e.getValue().equals(tipo.getValue()))
                .map(Map.Entry::getKey).findFirst().orElse("");
        String texto = barra.campoBusqueda().getText();
        tabla.cargando("Buscando en el historial…");
        carga.cargar(() -> a.admin().bitacora().buscar(d, h, texto, prefijo.isEmpty() ? null : prefijo), lista -> {
            tabla.setDatos(lista);
            barra.setResultados(Texto.plural(lista.size(), "acción", "acciones")
                    + (lista.size() >= LIMITE ? " (se muestran las 2,000 más recientes)" : ""));
            if (maestro.estaAbierto() && tabla.seleccionado() != null) {
                mostrarDetalle(tabla.seleccionado());
            }
        }, e -> tabla.error("No se pudo consultar el historial.", AdminContexto.mensaje(e), this::cargar));
    }

    private static Etiqueta.Tono tono(AccionBitacora accion) {
        String nombre = accion.name();
        if (nombre.contains("ELIMINAD") || nombre.contains("CANCELADA") || nombre.contains("CANCELACION")
                || nombre.contains("BLOQUEADO") || nombre.contains("RECHAZADA") || nombre.equals("MERMA")) {
            return Etiqueta.Tono.PELIGRO;
        }
        if (nombre.startsWith("DEVOLUCION") || nombre.equals("NOTA_CREDITO") || nombre.equals("AJUSTE_INVENTARIO")
                || nombre.equals("ACLARACION_SURTIDO")) {
            return Etiqueta.Tono.AVISO;
        }
        if (nombre.contains("CREAD") || nombre.contains("SURTIDO") || nombre.contains("ENTRADA") || nombre.contains("ABONO")
                || nombre.contains("AUTORIZADO") || nombre.equals("COMPRA")) {
            return Etiqueta.Tono.EXITO;
        }
        return Etiqueta.Tono.NEUTRO;
    }

    private void mostrarDetalle(BitacoraRepository.Registro r) {
        detalle.setTitulo("Folio " + r.folio(), FECHA_SEGUNDOS.format(r.fecha().atZone(java.time.ZoneId.systemDefault())));
        detalle.setEtiquetas(Etiqueta.de(r.accion().descripcion(), tono(r.accion())));
        Label descripcion = new Label(r.descripcion());
        descripcion.setWrapText(true);
        descripcion.getStyleClass().add("dialogo-texto");
        detalle.setContenido(
                PanelDetalle.seccion("Descripción", descripcion),
                PanelDetalle.seccion("Quién", PanelDetalle.dato("Usuario", r.usuario()),
                        PanelDetalle.dato("Sucursal", r.sucursal())),
                PanelDetalle.seccion("Identificadores", campo("Tabla", r.entidad()),
                        campo("Id del registro", r.entidadId()), campo("Id de la acción", r.id())));
        detalle.setAcciones();
    }

    /** Valor seleccionable para copiarlo (ids). */
    private static Node campo(String etiqueta, String valor) {
        TextField t = new TextField(valor == null ? "—" : valor);
        t.setEditable(false);
        t.getStyleClass().addAll("campo-solo-lectura", "texto-id");
        t.setAccessibleText(etiqueta);
        Label l = new Label(etiqueta);
        l.getStyleClass().add("dato-etiqueta");
        return new VBox(4, l, t);
    }
}
