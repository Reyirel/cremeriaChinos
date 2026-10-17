package com.cremerias.puntoventa.ui.caja;

import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.DevolucionService;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoTicket;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.Errores;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TicketTexto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Devolución de un cliente sobre un ticket: qué regresa, en qué estado y cómo se le reembolsa. Lo
 * que vuelve en buen estado regresa al inventario; lo dañado o caducado no. Lo autoriza un
 * supervisor (si quien atiende es cajero) y se entrega un comprobante.
 */
final class DialogoDevolucion {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Renglón del ticket con lo que se captura para devolver. */
    private static final class Renglon {
        final DevolucionService.RenglonDevolvible r;
        final TextField cantidad = new TextField();
        final ComboBox<DevolucionService.EstadoProducto> estado = new ComboBox<>(FXCollections.observableArrayList(
                DevolucionService.EstadoProducto.values()));
        final Label importe = new Label("—");

        Renglon(DevolucionService.RenglonDevolvible r) {
            this.r = r;
            estado.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(DevolucionService.EstadoProducto e) {
                    return e == null ? "" : e.corto();
                }

                @Override
                public DevolucionService.EstadoProducto fromString(String s) {
                    return estado.getValue();
                }
            });
            estado.setValue(DevolucionService.EstadoProducto.VENDIBLE);
            estado.setMaxWidth(Double.MAX_VALUE);
        }

        /** Cantidad en las unidades del ticket (kilos o piezas de la presentación). */
        Optional<BigDecimal> capturada() {
            return Cantidades.desdeCaptura(r.unidad(), cantidad.getText()).filter(c -> c.signum() > 0);
        }

        /** Reembolso de este renglón como lo calcula el servicio (el último resto, exacto). */
        long reembolso() {
            Optional<BigDecimal> c = capturada();
            if (c.isEmpty() || c.get().compareTo(r.disponible()) > 0) {
                return 0;
            }
            return c.get().compareTo(r.disponible()) == 0 ? r.importeCentavos() - r.importeDevueltoCentavos()
                    : BigDecimal.valueOf(r.importeCentavos()).multiply(c.get())
                    .divide(r.cantidadVendida(), 0, RoundingMode.HALF_UP).longValue();
        }
    }

    private DialogoDevolucion() {
    }

    /**
     * @param autorizar pide la autorización de un supervisor (o la da quien ya lo es) y continúa
     * @param alTerminar al registrar (para refrescar la caja); recibe el resultado
     */
    static void mostrar(Dialogos dialogos, Avisos avisos, DevolucionService servicio, DevolucionService.VentaDevolvible venta,
                        Turno turno, Usuario usuario, String sucursal, BiConsumer<String, Consumer<Usuario>> autorizar,
                        Consumer<DevolucionService.Resultado> alTerminar) {
        Dialogo d = new Dialogo("Devolución del ticket " + venta.folio(),
                FECHA.format(venta.fecha().atZone(ZoneId.systemDefault())) + " · Total " + Dinero.formatear(venta.totalCentavos())
                        + (venta.devueltoCentavos() > 0 ? " · Ya se devolvió " + Dinero.formatear(venta.devueltoCentavos()) : ""),
                "mdi2k-keyboard-return", Dialogo.Tono.ADVERTENCIA);
        d.setPrefWidth(860);
        String devolucionId = Ids.nuevo();

        List<Renglon> renglones = new ArrayList<>();
        GridPane grid = new GridPane(12, 8);
        grid.getStyleClass().add("grid-surtido");
        for (double ancho : new double[]{260, 110, 150, 170, 100}) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPrefWidth(ancho);
            col.setMinWidth(Region.USE_PREF_SIZE);
            grid.getColumnConstraints().add(col);
        }
        grid.getColumnConstraints().getFirst().setHgrow(Priority.ALWAYS);
        String[] titulos = {"Producto", "Se puede", "Devuelve", "Estado", "Reembolso"};
        for (int i = 0; i < titulos.length; i++) {
            Label t = new Label(titulos[i]);
            t.getStyleClass().add("grid-surtido-encabezado");
            grid.add(t, i, 0);
        }
        Label total = new Label("$0.00");
        Runnable recalcular = () -> {
            long suma = 0;
            for (Renglon x : renglones) {
                long importe = x.reembolso();
                x.importe.setText(x.capturada().isEmpty() ? "—" : Dinero.formatear(importe));
                suma += importe;
            }
            total.setText(Dinero.formatear(suma));
        };
        int fila = 1;
        for (DevolucionService.RenglonDevolvible r : venta.renglones()) {
            Renglon x = new Renglon(r);
            renglones.add(x);
            Label nombre = new Label(r.descripcion());
            nombre.getStyleClass().add("celda-principal");
            nombre.setWrapText(true);
            Label precio = new Label(Dinero.formatear(r.precioCentavos()) + (r.unidad() == Unidad.KG ? " / kg" : " c/u"));
            precio.getStyleClass().add("celda-secundaria");
            Label disponible = new Label(r.disponible().signum() > 0 ? Cantidades.formatear(r.unidad(), r.disponible())
                    : "Ya se devolvió");
            disponible.getStyleClass().add("celda-secundaria");
            Campos.soloNumeros(x.cantidad, 0);
            x.cantidad.setPrefColumnCount(5);
            x.cantidad.setAlignment(Pos.CENTER_RIGHT);
            x.cantidad.setPromptText("0");
            x.cantidad.getStyleClass().add("devolucion-cantidad");
            x.cantidad.setDisable(r.disponible().signum() <= 0);
            x.cantidad.setAccessibleText("Cantidad a devolver de " + r.descripcion());
            x.cantidad.textProperty().addListener((o, a, b) -> recalcular.run());
            Label unidad = new Label(Cantidades.unidadCaptura(r.unidad()));
            unidad.getStyleClass().add("unidad-campo");
            HBox cantidad = new HBox(6, x.cantidad, unidad);
            cantidad.setAlignment(Pos.CENTER_LEFT);
            x.estado.setDisable(r.disponible().signum() <= 0);
            x.importe.getStyleClass().add("celda-principal");
            grid.addRow(fila++, new VBox(1, nombre, precio), disponible, cantidad, x.estado, x.importe);
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(260, 56 + 52.0 * venta.renglones().size()));
        scroll.getStyleClass().add("scroll-surtido");

        Segmentado<DevolucionService.Tipo> tipo = new Segmentado<DevolucionService.Tipo>()
                .opcion(DevolucionService.Tipo.DEVOLUCION, "Devolución", "mdi2c-cash-refund")
                .opcion(DevolucionService.Tipo.CAMBIO, "Cambio por otros productos", "mdi2s-swap-horizontal");
        Label ayudaTipo = new Label("Se le regresa su dinero al cliente.");
        ayudaTipo.getStyleClass().add("campo-ayuda");
        ayudaTipo.setWrapText(true);
        Segmentado<DevolucionService.Metodo> metodo = new Segmentado<>();
        for (DevolucionService.Metodo m : DevolucionService.Metodo.values()) {
            metodo.opcion(m, m.nombre());
        }
        tipo.valorProperty().addListener((o, a, b) -> {
            boolean cambio = b == DevolucionService.Tipo.CAMBIO;
            ayudaTipo.setText(cambio ? "Registra lo que regresa; luego cobra los productos nuevos como una venta normal y "
                    + "se ligará a este cambio. El reembolso se entrega como de costumbre." : "Se le regresa su dinero al cliente.");
        });
        TextField motivo = new TextField();
        motivo.setId("devolucion-motivo");
        motivo.setPromptText("Ej. Venía echado a perder");
        Formulario.Campo campoMotivo = Formulario.campo("Motivo", motivo);
        motivo.textProperty().addListener((o, a, b) -> campoMotivo.setError(null));
        VBox resumen = Formulario.resumen("Reembolso al cliente", total,
                etiqueta("Lo que regresa en buen estado vuelve al inventario; lo dañado o caducado no se vuelve a vender "
                        + "y queda como pérdida al costo."));

        d.setContenido(scroll, Formulario.campo("Tipo", new VBox(6, tipo, ayudaTipo)),
                Formulario.campo("Cómo se reembolsa", metodo), campoMotivo, resumen);

        Runnable registrar = () -> {
            List<DevolucionService.LineaDevolucion> lineas = new ArrayList<>();
            for (Renglon x : renglones) {
                Optional<BigDecimal> c = x.capturada();
                if (c.isPresent()) {
                    if (c.get().compareTo(x.r.disponible()) > 0) {
                        d.mostrarError("De " + x.r.descripcion() + " solo se pueden devolver "
                                + Cantidades.formatear(x.r.unidad(), x.r.disponible()) + ".");
                        x.cantidad.requestFocus();
                        return;
                    }
                    lineas.add(new DevolucionService.LineaDevolucion(x.r.ventaDetalleId(), c.get(), x.estado.getValue()));
                }
            }
            if (lineas.isEmpty()) {
                d.mostrarError("Escribe cuánto devuelve de al menos un producto.");
                return;
            }
            if (motivo.getText() == null || motivo.getText().isBlank()) {
                campoMotivo.setError("Escribe por qué devuelve.");
                return;
            }
            String textoMotivo = motivo.getText();
            DevolucionService.Tipo t = tipo.getValor();
            DevolucionService.Metodo m = metodo.getValor();
            autorizar.accept("devolución del ticket " + venta.folio(), autorizador -> d.enviar(
                    () -> servicio.registrar(devolucionId, venta.ventaId(), lineas, t, m, textoMotivo, turno, usuario, autorizador),
                    resultado -> {
                        d.cerrar();
                        avisos.exito("Devolución " + resultado.folio() + " registrada"
                                + (resultado.reembolsoCentavos() > 0 ? ". Entrega " + Dinero.formatear(resultado.reembolsoCentavos())
                                + " (" + resultado.metodo().nombre().toLowerCase() + ")." : "."));
                        alTerminar.accept(resultado);
                        DialogoTicket.mostrar(dialogos, avisos, "Comprobante " + resultado.folio(),
                                "Reembolso " + Dinero.formatear(resultado.reembolsoCentavos()),
                                TicketTexto.lineasDevolucion(resultado, sucursal), null);
                    }));
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.separarBotones();
        d.agregarBoton("Registrar devolución", "mdi2c-check", registrar, "accent");
        d.setAlCancelar(d::cerrar);
        renglones.stream().filter(x -> !x.cantidad.isDisabled()).findFirst().ifPresent(x -> d.setFocoInicial(x.cantidad));
        d.vigilarCambios(motivo);
        dialogos.mostrar(d);
    }

    private static Label etiqueta(String texto) {
        Label l = new Label(texto);
        l.getStyleClass().add("campo-ayuda");
        l.setWrapText(true);
        return l;
    }

    /** Mensaje para el usuario de un error del servicio. */
    static String mensaje(Throwable e) {
        return Errores.mensaje(e);
    }
}
