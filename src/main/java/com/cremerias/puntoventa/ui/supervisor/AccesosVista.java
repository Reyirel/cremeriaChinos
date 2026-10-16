package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.AccesoService;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.Errores;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Empleados de la sucursal del supervisor con el acceso bloqueado por horario. Se aprueba desde
 * aquí mismo (sin tener que ir a la caja bloqueada a teclear la contraseña).
 */
public class AccesosVista {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AppContext ctx;
    private final Usuario supervisor;
    private final Dialogos dialogos;
    private final Avisos avisos;
    private final String sucursalId;
    private final Cargador carga = new Cargador();
    private final Node raiz;
    private final TablaDatos<AccesoService.Bloqueo> tabla = new TablaDatos<>(Diseno.Densidad.AMPLIA);

    public AccesosVista(Navegador navegador) {
        this.ctx = navegador.contexto();
        this.supervisor = ctx.sesionActual().obtener().orElseThrow().usuario();
        this.dialogos = navegador.dialogos();
        this.avisos = navegador.avisos();
        this.sucursalId = supervisor.sucursalId();

        VBox pagina = Ui.pagina("Accesos", sucursalId == null ? "Tu usuario no tiene una sucursal asignada."
                : "Empleados de tu sucursal que intentaron entrar fuera de su horario. Puedes darles acceso una vez.");
        tabla.setClave(AccesoService.Bloqueo::id);
        tabla.fecha("Fecha", AccesoService.Bloqueo::fecha, FECHA).ancho(150);
        tabla.dosLineas("Empleado", AccesoService.Bloqueo::usuario, AccesoService.Bloqueo::folio).ancho(240);
        tabla.dosLineas("Motivo", b -> b.motivo().descripcion(), AccesoService.Bloqueo::detalle).flexible(280);
        tabla.nodo("", b -> {
            Button dar = Ui.boton("Dar acceso", "mdi2a-account-check-outline", "accent", "small");
            dar.setOnAction(e -> darAcceso(b));
            HBox h = new HBox(dar);
            h.setAlignment(Pos.CENTER_RIGHT);
            return h;
        }).ancho(150).minimo(150);
        tabla.setVacio(new EstadoVacio("mdi2a-account-check-outline", "Nadie tiene el acceso bloqueado.",
                "Aquí aparecerán los empleados que intenten entrar fuera de su horario.").tono("exito"));
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
        tabla.cargando("Revisando accesos…");
        carga.cargar(() -> ctx.auth().accesos().pendientesDeSucursal(sucursalId), tabla::setDatos,
                e -> tabla.error("No se pudieron revisar los accesos.", Errores.mensaje(e), this::actualizar));
    }

    private void darAcceso(AccesoService.Bloqueo b) {
        DialogoConfirmacion.mostrar(dialogos, "¿Dar acceso a " + b.usuario() + "?",
                b.motivo().descripcion() + " (" + b.detalle() + "). Podrá iniciar sesión una vez.",
                "Dar acceso", false, () -> Cargador.ejecutar(() -> ctx.auth().accesos().autorizar(b.id(), supervisor),
                        folio -> {
                            avisos.exito("Acceso autorizado para " + b.usuario() + " · Folio " + folio);
                            actualizar();
                        },
                        e -> avisos.error(Errores.mensaje(e))));
    }
}
