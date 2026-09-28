package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historial de todas las acciones, cada una con su folio e id. */
public class HistorialPagina extends Pagina {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Map<String, String> TIPOS = new LinkedHashMap<>();

    static {
        TIPOS.put(null, "Todas las acciones");
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

    private TableView<BitacoraRepository.Registro> tabla;
    private DatePicker desde;
    private DatePicker hasta;
    private ComboBox<String> tipo;
    private TextField buscador;
    private Label conteo;

    public HistorialPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Historial", "Cada acción del sistema tiene un folio y el id único del registro afectado.");
        desde = new DatePicker(LocalDate.now().minusDays(7));
        hasta = new DatePicker(LocalDate.now());
        desde.setEditable(false);
        hasta.setEditable(false);
        tipo = new ComboBox<>(FXCollections.observableArrayList(new ArrayList<>(TIPOS.values())));
        tipo.setValue(TIPOS.get(null));
        buscador = Ui.buscador("Folio, descripción, usuario o id");
        conteo = Ui.texto("", "texto-ayuda");
        desde.valueProperty().addListener((o, x, y) -> cargar());
        hasta.valueProperty().addListener((o, x, y) -> cargar());
        tipo.valueProperty().addListener((o, x, y) -> cargar());
        buscador.setOnAction(e -> cargar());
        buscador.textProperty().addListener((o, x, y) -> {
            if (y.isBlank()) {
                cargar();
            }
        });
        HBox filtros = new HBox(10, Ui.campo("Desde", desde), Ui.campo("Hasta", hasta), Ui.campo("Tipo", tipo),
                Ui.campo("Buscar (Enter)", buscador));
        filtros.setAlignment(Pos.BOTTOM_LEFT);
        tabla = Ui.tabla("No hay acciones con estos filtros.");
        tabla.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, BitacoraRepository.Registro::folio),
                Ui.texto("Fecha", 175, r -> FECHA.format(r.fecha().atZone(ZoneId.systemDefault()))),
                Ui.nodo("Acción", 220, r -> Ui.chip(r.accion().descripcion(), tono(r.accion()))),
                Ui.texto("Descripción", 380, BitacoraRepository.Registro::descripcion),
                Ui.nodo("Usuario", 180, r -> Ui.dosLineas(r.usuario(), r.sucursal())),
                Ui.id(BitacoraRepository.Registro::entidadId)));
        tabla.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tabla.getSelectionModel().getSelectedItem() != null) {
                detalle(tabla.getSelectionModel().getSelectedItem());
            }
        });
        pagina.getChildren().addAll(filtros, conteo, tabla);
        return pagina;
    }

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        if (desde.getValue() == null || hasta.getValue() == null) {
            return;
        }
        // "Todas las acciones" tiene clave nula: por eso no se usa findFirst() (no admite nulos).
        String prefijo = null;
        for (Map.Entry<String, String> e : TIPOS.entrySet()) {
            if (e.getValue().equals(tipo.getValue())) {
                prefijo = e.getKey();
                break;
            }
        }
        List<BitacoraRepository.Registro> lista = a.admin().bitacora().buscar(desde.getValue(), hasta.getValue(),
                buscador.getText(), prefijo);
        tabla.setItems(FXCollections.observableArrayList(lista));
        conteo.setText(lista.size() + (lista.size() == 1 ? " acción" : " acciones")
                + (lista.size() >= 2000 ? " (se muestran las 2,000 más recientes)" : "") + " · doble clic para ver el detalle");
    }

    private static String tono(AccionBitacora accion) {
        String nombre = accion.name();
        if (nombre.contains("ELIMINAD") || nombre.contains("CANCELADA") || nombre.contains("BLOQUEADO")
                || nombre.contains("RECHAZADA") || nombre.equals("MERMA")) {
            return "peligro";
        }
        if (nombre.contains("CREAD") || nombre.contains("SURTIDO") || nombre.contains("ENTRADA") || nombre.contains("ABONO")
                || nombre.contains("AUTORIZADO")) {
            return "exito";
        }
        return "neutro";
    }

    private void detalle(BitacoraRepository.Registro r) {
        Dialogo d = new Dialogo("Folio " + r.folio(), r.accion().descripcion(), "mdi2h-history", Dialogo.Tono.INFO);
        d.setPrefWidth(640);
        GridPane grid = new GridPane(14, 10);
        String[][] datos = {
                {"Fecha", FECHA.format(r.fecha().atZone(ZoneId.systemDefault()))},
                {"Usuario", r.usuario()},
                {"Sucursal", r.sucursal()},
                {"Descripción", r.descripcion()},
                {"Tabla", r.entidad()},
                {"Id del registro", r.entidadId() == null ? "" : r.entidadId()},
                {"Id de la acción", r.id()}};
        for (int i = 0; i < datos.length; i++) {
            Label etiqueta = new Label(datos[i][0]);
            etiqueta.getStyleClass().add("campo-etiqueta");
            TextField valor = new TextField(datos[i][1]);
            valor.setEditable(false);
            valor.getStyleClass().add("campo-solo-lectura");
            valor.setPrefColumnCount(36);
            grid.addRow(i, etiqueta, valor);
        }
        d.setContenido(grid);
        d.agregarBoton("Cerrar", null, d::cerrar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(d::cerrar);
        a.dialogos().mostrar(d);
    }
}
