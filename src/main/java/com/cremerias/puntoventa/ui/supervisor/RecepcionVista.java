package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.Errores;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Ids;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Surtidos que la bodega envió a la sucursal del supervisor y que falta confirmar. Se captura lo
 * que llegó de cada producto; si falta algo, sale de la sucursal y el administrador lo aclara.
 */
public class RecepcionVista {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final AppContext ctx;
    private final Usuario supervisor;
    private final Dialogos dialogos;
    private final Avisos avisos;
    private final String sucursalId;
    private final Runnable alCambiar;
    private final Cargador carga = new Cargador();
    private final Node raiz;
    private final TablaDatos<RecepcionSurtidoService.PorRecibir> tabla = new TablaDatos<>(Diseno.Densidad.AMPLIA);

    public RecepcionVista(Navegador navegador, Runnable alCambiar) {
        this.ctx = navegador.contexto();
        this.supervisor = ctx.sesionActual().obtener().orElseThrow().usuario();
        this.dialogos = navegador.dialogos();
        this.avisos = navegador.avisos();
        this.sucursalId = supervisor.sucursalId();
        this.alCambiar = alCambiar;

        VBox pagina = Ui.pagina("Recepción de surtidos", sucursalId == null ? "Tu usuario no tiene una sucursal asignada."
                : "Confirma lo que llegó de la bodega. Si falta algo, escribe lo que sí llegó: el administrador lo aclara.");
        tabla.setClave(RecepcionSurtidoService.PorRecibir::surtidoId);
        tabla.fecha("Enviado", RecepcionSurtidoService.PorRecibir::fecha, FECHA).ancho(150);
        tabla.dosLineas("Surtido", RecepcionSurtidoService.PorRecibir::folio,
                p -> "CREDITO".equals(p.formaPago()) ? "A crédito" : "De contado").ancho(220);
        tabla.textoLargo("Productos", p -> String.join(" · ", p.lineas().stream()
                .map(l -> l.producto() + " " + Cantidades.formatear(l.unidad(), l.enviada())).toList())).flexible(280);
        tabla.nodo("", p -> {
            Button recibir = Ui.boton("Recibir", "mdi2t-truck-check-outline", "accent", "small");
            recibir.setOnAction(e -> recibir(p));
            HBox h = new HBox(recibir);
            h.setAlignment(Pos.CENTER_RIGHT);
            return h;
        }).ancho(140).minimo(140);
        tabla.setAlActivar(this::recibir);
        tabla.setVacio(new EstadoVacio("mdi2t-truck-check-outline", "No hay surtidos por recibir.",
                "Cuando la bodega te envíe mercancía, confírmala aquí al llegar.").tono("exito"));
        VBox panel = Ui.panelTabla(null, tabla);
        pagina.getChildren().add(panel);
        raiz = Ui.ajustable(pagina, panel, 260);
    }

    public Node nodo() {
        return raiz;
    }

    public void actualizar() {
        if (sucursalId == null) {
            tabla.setDatos(List.of());
            return;
        }
        tabla.cargando("Buscando surtidos por recibir…");
        carga.cargar(() -> ctx.admin().recepciones().pendientes(sucursalId, supervisor), tabla::setDatos,
                e -> tabla.error("No se pudieron cargar los surtidos.", Errores.mensaje(e), this::actualizar));
    }

    /** Cuántos surtidos esperan confirmación (para el contador del menú). */
    public int pendientes() {
        return sucursalId == null ? 0 : ctx.admin().recepciones().pendientes(sucursalId, supervisor).size();
    }

    private void recibir(RecepcionSurtidoService.PorRecibir p) {
        Dialogo d = new Dialogo("Recibir surtido " + p.folio(), "Escribe lo que llegó de cada producto. Ya viene lleno con "
                + "lo enviado: cámbialo solo si falta algo.", "mdi2t-truck-check-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(800);
        String recepcionId = Ids.nuevo();
        GridPane grid = new GridPane(14, 8);
        grid.getStyleClass().add("grid-surtido");
        for (double ancho : new double[]{240, 110, 160, 170}) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPrefWidth(ancho);
            col.setMinWidth(Region.USE_PREF_SIZE);
            grid.getColumnConstraints().add(col);
        }
        grid.getColumnConstraints().getFirst().setHgrow(Priority.ALWAYS);
        String[] titulos = {"Producto", "Enviado", "Llegó", "Diferencia"};
        for (int i = 0; i < titulos.length; i++) {
            Label t = new Label(titulos[i]);
            t.getStyleClass().add("grid-surtido-encabezado");
            grid.add(t, i, 0);
        }
        List<TextField> campos = new ArrayList<>();
        List<Label> diferencias = new ArrayList<>();
        Label resumen = new Label("Llegó completo.");
        Runnable recalcular = () -> {
            int faltan = 0;
            int invalidas = 0;
            for (int i = 0; i < p.lineas().size(); i++) {
                RecepcionSurtidoService.LineaEnviada l = p.lineas().get(i);
                Optional<BigDecimal> llego = Cantidades.desdeCaptura(l.unidad(), campos.get(i).getText());
                BigDecimal dif = llego.map(x -> l.enviada().subtract(x)).orElse(null);
                boolean invalida = llego.isEmpty() || llego.get().compareTo(l.enviada()) > 0;
                campos.get(i).pseudoClassStateChanged(ERROR, invalida);
                Label etiqueta = diferencias.get(i);
                if (invalida) {
                    invalidas++;
                    Etiqueta.aplicar(etiqueta, llego.isEmpty() ? "Escribe cuánto llegó" : "Más de lo enviado", Etiqueta.Tono.PELIGRO);
                } else if (dif.signum() == 0) {
                    Etiqueta.aplicar(etiqueta, "Completo", Etiqueta.Tono.EXITO);
                } else {
                    faltan++;
                    Etiqueta.aplicar(etiqueta, "Faltan " + Cantidades.formatear(l.unidad(), dif), Etiqueta.Tono.AVISO);
                }
            }
            resumen.setText(invalidas > 0 ? "Revisa las cantidades marcadas: lo que llegó no puede ser más de lo enviado."
                    : faltan == 0 ? "Llegó completo." : faltantes(faltan) + " Se reportará como diferencia: sale de "
                    + "tu existencia y el administrador decide si regresó a la bodega o se perdió.");
        };
        int fila = 1;
        for (RecepcionSurtidoService.LineaEnviada l : p.lineas()) {
            Label nombre = new Label(l.producto());
            nombre.getStyleClass().add("celda-principal");
            nombre.setWrapText(true);
            Label enviada = new Label(Cantidades.formatear(l.unidad(), l.enviada()));
            TextField llego = new TextField(Cantidades.aCaptura(l.unidad(), l.enviada()));
            Campos.soloNumeros(llego, 0);
            llego.setPrefColumnCount(6);
            llego.setAlignment(Pos.CENTER_RIGHT);
            llego.getStyleClass().add("recepcion-llego");
            llego.setAccessibleText("Cantidad que llegó de " + l.producto());
            llego.textProperty().addListener((o, a, b) -> recalcular.run());
            Label unidad = new Label(Cantidades.unidadCaptura(l.unidad()));
            unidad.getStyleClass().add("unidad-campo");
            HBox celda = new HBox(6, llego, unidad);
            celda.setAlignment(Pos.CENTER_LEFT);
            Label diferencia = Etiqueta.de("Completo", Etiqueta.Tono.EXITO);
            campos.add(llego);
            diferencias.add(diferencia);
            grid.addRow(fila++, nombre, enviada, celda, diferencia);
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(300, 52 + 46.0 * p.lineas().size()));
        scroll.getStyleClass().add("scroll-surtido");
        TextField notas = new TextField();
        notas.setPromptText("Ej. La caja de crema venía golpeada");
        resumen.setWrapText(true);
        resumen.getStyleClass().add("campo-ayuda");
        d.setContenido(scroll, resumen, Formulario.opcional("Notas", notas));
        recalcular.run();

        Runnable confirmar = () -> {
            List<RecepcionSurtidoService.LineaRecibida> lineas = new ArrayList<>();
            for (int i = 0; i < p.lineas().size(); i++) {
                RecepcionSurtidoService.LineaEnviada l = p.lineas().get(i);
                Optional<BigDecimal> llego = Cantidades.desdeCaptura(l.unidad(), campos.get(i).getText());
                if (llego.isEmpty() || llego.get().compareTo(l.enviada()) > 0) {
                    d.mostrarError("Revisa lo que llegó de " + l.producto() + ": no puede ser más de lo enviado.");
                    campos.get(i).requestFocus();
                    return;
                }
                lineas.add(new RecepcionSurtidoService.LineaRecibida(l.surtidoDetalleId(), llego.get()));
            }
            String texto = notas.getText();
            d.enviar(() -> ctx.admin().recepciones().recibir(recepcionId, p.surtidoId(), lineas, texto, supervisor), folio -> {
                d.cerrar();
                avisos.exito("Surtido " + p.folio() + " recibido · Folio " + folio);
                ctx.catalogo().recargar();
                ctx.sincronizador().revisarAhora();
                actualizar();
                alCambiar.run();
            });
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Confirmar recepción", "mdi2c-check", confirmar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(confirmar);
        if (!campos.isEmpty()) {
            d.setFocoInicial(campos.getFirst());
        }
        dialogos.mostrar(d);
    }

    private static String faltantes(int faltan) {
        return faltan == 1 ? "Falta 1 producto." : "Faltan " + faltan + " productos.";
    }
}
