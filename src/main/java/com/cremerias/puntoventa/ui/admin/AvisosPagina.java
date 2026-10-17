package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.service.AccesoService;
import com.cremerias.puntoventa.service.SinVentaService;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.List;

/**
 * Inicio del administrador: lo que requiere atención, en orden de prioridad (órdenes por
 * aprobar, accesos bloqueados y productos sin venta). Solo aparecen las secciones con algo
 * pendiente; si no hay nada, un estado compacto lo dice.
 */
public class AvisosPagina extends Pagina {

    private static final int MAXIMO_ORDENES = 5;

    /** Todo lo que se muestra, cargado de una vez fuera del hilo de la interfaz. */
    private record Pendientes(List<OrdenRepository.Orden> ordenes, List<AccesoService.Bloqueo> bloqueos,
                              List<SinVentaService.SinVenta> sinVenta, List<Sucursal> sucursales, int negativos,
                              int diferencias) {
    }

    private final Cargador carga = new Cargador();
    private VBox secciones;
    private ComboBox<Sucursal> sucursalAviso;
    private TextField dias;
    private Label resultadoDias;
    private TablaDatos<SinVentaService.SinVenta> tablaSinVenta;

    public AvisosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Avisos";
    }

    @Override
    protected Node construir() {
        VBox pagina = Ui.pagina("Avisos", "Lo que requiere tu atención. Se revisa solo cada 30 segundos.");
        secciones = new VBox(Diseno.ESPACIO_L);
        secciones.getChildren().add(EstadoVacio.cargando("Revisando pendientes…"));

        tablaSinVenta = new TablaDatos<>(Diseno.Densidad.COMPACTA);
        tablaSinVenta.setClave(s -> s.sucursalId() + "/" + s.productoId());
        tablaSinVenta.dosLineas("Producto", SinVentaService.SinVenta::producto,
                s -> s.sucursal()).flexible(220);
        tablaSinVenta.cantidad("Existencia", SinVentaService.SinVenta::existencia, SinVentaService.SinVenta::unidad).ancho(120);
        tablaSinVenta.formato("Sin venderse", SinVentaService.SinVenta::sinVender,
                (s, v) -> AvisoSinVenta.formatear(v)).ancho(140).clase("columna-fuerte");
        tablaSinVenta.fecha("Última venta", SinVentaService.SinVenta::ultimaVenta, Formatos.FECHA_HORA).ancho(150);
        tablaSinVenta.texto("Plazo", s -> s.plazo() + (s.avisoDelProducto() ? " (del producto)" : " (de la sucursal)"))
                .ancho(200);
        tablaSinVenta.tabla().setPrefHeight(48 + 6 * 44);

        // Ajuste: plazo de días sin venta por sucursal
        sucursalAviso = new ComboBox<>();
        sucursalAviso.setConverter(Combos.sucursal());
        sucursalAviso.setPromptText("Sucursal");
        sucursalAviso.valueProperty().addListener((o, x, y) -> cargarDias());
        dias = new TextField();
        Campos.soloNumeros(dias, 0);
        dias.setPrefColumnCount(4);
        dias.setAlignment(Pos.CENTER_RIGHT);
        dias.setAccessibleText("Días sin venta");
        Button guardarDias = Ui.secundario("Guardar", "mdi2c-check");
        guardarDias.setOnAction(e -> guardarDias());
        resultadoDias = new Label();
        resultadoDias.getStyleClass().add("campo-ayuda");
        Label en = new Label("En");
        Label si = new Label("avisar si un producto con existencia no se vende en");
        Label diasTexto = new Label("días");
        for (Label l : List.of(en, si, diasTexto)) {
            l.getStyleClass().add("etiqueta-filtro");
        }
        HBox fila = new HBox(8, en, sucursalAviso, si, dias, diasTexto, guardarDias);
        fila.setAlignment(Pos.CENTER_LEFT);
        VBox ajustes = Ui.panel("Aviso de productos sin venta",
                "Los productos con aviso propio (se define en el producto) usan su plazo y avisan solo a quien se eligió.",
                null, fila, resultadoDias);

        pagina.getChildren().addAll(secciones, ajustes);
        return Ui.desplazable(pagina);
    }

    @Override
    public void alMostrar() {
        carga.cargar(() -> new Pendientes(a.admin().ordenes().pendientes(), a.ctx().auth().accesos().pendientes(),
                a.admin().alertas().productosSinVenta(), a.admin().sucursales().activas(),
                a.ctx().reconciliacion().negativos(a.usuario()).size(),
                a.admin().recepciones().diferencias(true, a.usuario()).size()), this::pintar,
                e -> secciones.getChildren().setAll(EstadoVacio.error("No se pudieron revisar los pendientes.",
                        AdminContexto.mensaje(e), this::alMostrar)));
    }

    private void pintar(Pendientes p) {
        List<Node> nodos = new ArrayList<>();
        if (p.ordenes().isEmpty() && p.bloqueos().isEmpty() && p.sinVenta().isEmpty() && p.negativos() == 0
                && p.diferencias() == 0) {
            nodos.add(todoAlDia());
        }
        if (p.negativos() > 0 || p.diferencias() > 0) {
            nodos.add(seccionInventario(p.negativos(), p.diferencias()));
        }
        if (!p.ordenes().isEmpty()) {
            nodos.add(seccionOrdenes(p.ordenes()));
        }
        if (!p.bloqueos().isEmpty()) {
            nodos.add(seccionBloqueos(p.bloqueos()));
        }
        if (!p.sinVenta().isEmpty()) {
            tablaSinVenta.setDatos(p.sinVenta());
            Button ofertar = Ui.boton("Productos por sucursal", "mdi2a-arrow-right", "flat", "small");
            ofertar.setOnAction(e -> a.irA().accept("productos-sucursal"));
            VBox panel = Ui.panel("Productos sin venta · " + p.sinVenta().size(),
                    "Llevan su plazo sin venderse en la sucursal: conviene ofertarlos.", ofertar);
            VBox tabla = Ui.panelTabla(null, tablaSinVenta);
            tabla.getStyleClass().setAll();
            panel.getChildren().add(tabla);
            Diseno.recortarRedondeado(panel, Diseno.RADIO_PANEL);
            nodos.add(panel);
        }
        secciones.getChildren().setAll(nodos);

        Sucursal actual = sucursalAviso.getValue();
        sucursalAviso.setItems(FXCollections.observableArrayList(p.sucursales()));
        if (actual != null) {
            p.sucursales().stream().filter(s -> s.id().equals(actual.id())).findFirst().ifPresent(sucursalAviso::setValue);
        } else if (!p.sucursales().isEmpty()) {
            sucursalAviso.setValue(p.sucursales().getFirst());
        }
    }

    private Node todoAlDia() {
        Label titulo = new Label("Todo al día");
        titulo.getStyleClass().add("panel-titulo");
        Label texto = new Label("No hay órdenes por aprobar, accesos bloqueados ni productos sin venta.");
        texto.getStyleClass().add("panel-descripcion");
        texto.setWrapText(true);
        VBox textos = new VBox(2, titulo, texto);
        HBox.setHgrow(textos, Priority.ALWAYS);
        Button atendidas = Ui.boton("Órdenes atendidas", "mdi2c-clipboard-list-outline", "flat");
        atendidas.setOnAction(e -> a.irA().accept("ordenes"));
        Button historial = Ui.boton("Historial", "mdi2h-history", "flat");
        historial.setOnAction(e -> a.irA().accept("historial"));
        HBox h = new HBox(14, new FontIcon("mdi2c-check-circle-outline"), textos, atendidas, historial);
        h.getStyleClass().add("todo-al-dia");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** Existencias que quedaron negativas al sincronizar y diferencias de entrega sin aclarar. */
    private Node seccionInventario(int negativos, int diferencias) {
        VBox lista = new VBox();
        if (negativos > 0) {
            lista.getChildren().add(tarea(Texto.plural(negativos, "producto con existencia negativa",
                            "productos con existencia negativa"),
                    "Dos cajas vendieron lo mismo sin conexión. Reacomoda los lotes o cuenta y ajusta.", "Revisar",
                    () -> a.irA().accept("ajustes")));
        }
        if (diferencias > 0) {
            lista.getChildren().add(tarea(Texto.plural(diferencias, "diferencia de surtido por aclarar",
                            "diferencias de surtido por aclarar"),
                    "Una sucursal recibió menos de lo enviado: decide si regresó a la bodega o se perdió.", "Aclarar",
                    () -> a.irA().accept("surtidos")));
        }
        VBox panel = Ui.panel("Inventario y surtidos", "Diferencias que no se corrigen solas.", null, lista);
        quitarRelleno(panel);
        return panel;
    }

    private static HBox tarea(String titulo, String detalle, String accion, Runnable ir) {
        Label t = new Label(titulo);
        t.getStyleClass().add("tarea-titulo");
        Label d = new Label(detalle);
        d.getStyleClass().add("tarea-detalle");
        d.setWrapText(true);
        VBox textos = new VBox(2, t, d);
        textos.setMinWidth(0);
        HBox.setHgrow(textos, Priority.ALWAYS);
        Button boton = Ui.boton(accion, null, "small");
        boton.setOnAction(e -> ir.run());
        HBox fila = new HBox(12, textos, boton);
        fila.getStyleClass().add("tarea");
        fila.setAlignment(Pos.CENTER_LEFT);
        return fila;
    }

    private Node seccionOrdenes(List<OrdenRepository.Orden> ordenes) {
        Button ir = Ui.boton("Ir a órdenes", "mdi2a-arrow-right", "flat", "small");
        ir.setOnAction(e -> a.irA().accept("ordenes"));
        VBox lista = new VBox();
        for (OrdenRepository.Orden o : ordenes.stream().limit(MAXIMO_ORDENES).toList()) {
            Label producto = new Label(o.producto());
            producto.getStyleClass().add("tarea-titulo");
            Label detalle = new Label(o.sucursal() + " · quedan " + cantidad(o, o.existencia()) + " (mínimo "
                    + cantidad(o, o.minimo()) + ") · " + Formatos.cuando(o.creadoEn()));
            detalle.getStyleClass().add("tarea-detalle");
            VBox textos = new VBox(2, producto, detalle);
            textos.setMinWidth(0);
            HBox.setHgrow(textos, Priority.ALWAYS);
            Label pide = Etiqueta.simple("Pide " + cantidad(o, o.cantidadSugerida()), Etiqueta.Tono.ACENTO);
            Button revisar = Ui.boton("Revisar", null, "small");
            revisar.setOnAction(e -> a.irA().accept("ordenes"));
            HBox fila = new HBox(12, textos, pide, revisar);
            fila.getStyleClass().add("tarea");
            fila.setAlignment(Pos.CENTER_LEFT);
            lista.getChildren().add(fila);
        }
        VBox panel = Ui.panel("Órdenes por aprobar · " + ordenes.size(),
                "Sucursales que llegaron a su mínimo y esperan su surtido.", ir, lista);
        if (ordenes.size() > MAXIMO_ORDENES) {
            Label mas = Ui.texto("Y " + (ordenes.size() - MAXIMO_ORDENES) + " más en Órdenes de reabasto.", "campo-ayuda");
            panel.getChildren().add(mas);
        }
        quitarRelleno(panel);
        return panel;
    }

    private Node seccionBloqueos(List<AccesoService.Bloqueo> bloqueos) {
        VBox lista = new VBox();
        for (AccesoService.Bloqueo b : bloqueos) {
            Label nombre = new Label(b.usuario());
            nombre.getStyleClass().add("tarea-titulo");
            Label detalle = new Label(b.sucursal() + " · " + b.motivo().descripcion() + " (" + b.detalle() + ") · "
                    + Formatos.cuando(b.fecha()));
            detalle.getStyleClass().add("tarea-detalle");
            detalle.setWrapText(true);
            VBox textos = new VBox(2, nombre, detalle);
            textos.setMinWidth(0);
            HBox.setHgrow(textos, Priority.ALWAYS);
            Button dar = Ui.boton("Dar acceso", "mdi2a-account-check-outline", "accent", "small");
            dar.setOnAction(e -> darAcceso(b));
            HBox fila = new HBox(12, textos, dar);
            fila.getStyleClass().add("tarea");
            fila.setAlignment(Pos.CENTER_LEFT);
            lista.getChildren().add(fila);
        }
        VBox panel = Ui.panel("Accesos bloqueados por horario · " + bloqueos.size(),
                "No pueden entrar hasta que les des acceso (también puede hacerlo un supervisor de su sucursal).", null, lista);
        quitarRelleno(panel);
        return panel;
    }

    /** Las listas van de borde a borde del panel. */
    private static void quitarRelleno(VBox panel) {
        if (panel.getChildren().size() > 1 && panel.getChildren().get(1) instanceof VBox cuerpo) {
            cuerpo.setStyle("-fx-padding: 0 0 4 0;");
        }
    }

    private static String cantidad(OrdenRepository.Orden o, java.math.BigDecimal valor) {
        return Cantidades.formatear(Unidad.valueOf(o.unidad()), valor);
    }

    private void cargarDias() {
        Sucursal s = sucursalAviso.getValue();
        resultadoDias.setText("");
        if (s == null) {
            dias.setText("");
            return;
        }
        Cargador.ejecutar(() -> a.admin().alertas().diasSinVenta(s.id()), d -> dias.setText(String.valueOf(d)),
                e -> resultadoDias.setText("No se pudo leer el plazo: " + AdminContexto.mensaje(e)));
    }

    private void guardarDias() {
        Sucursal s = sucursalAviso.getValue();
        if (s == null) {
            resultadoDias.setText("Elige la sucursal.");
            return;
        }
        int valor = dias.getText().isBlank() ? 0 : Integer.parseInt(dias.getText());
        a.enFondo("Plazo de aviso guardado para " + s.nombre() + ": " + Texto.plural(valor, "día", "días"),
                () -> a.admin().alertas().guardarDiasSinVenta(s, valor, a.usuario()), this::alMostrar);
    }

    private void darAcceso(AccesoService.Bloqueo b) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Dar acceso a " + b.usuario() + "?",
                b.motivo().descripcion() + " (" + b.detalle() + "). Podrá iniciar sesión una vez.",
                "Dar acceso", false,
                () -> a.enFondo("Acceso autorizado: " + b.usuario(),
                        () -> a.ctx().auth().accesos().autorizar(b.id(), a.usuario()), this::alMostrar));
    }
}
