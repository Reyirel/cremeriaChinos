package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.AccesoService;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.admin.AdminContexto;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
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
    private final VBox pagina;
    private final HBox indicadores = new HBox(14);
    private final TableView<AccesoService.Bloqueo> tabla;

    public AccesosVista(Navegador navegador) {
        this.ctx = navegador.contexto();
        this.supervisor = ctx.sesionActual().obtener().orElseThrow().usuario();
        this.dialogos = navegador.dialogos();
        this.avisos = navegador.avisos();
        this.sucursalId = supervisor.sucursalId();

        pagina = Ui.pagina("Accesos", sucursalId == null ? "Tu usuario no tiene una sucursal asignada."
                : "Empleados de tu sucursal que intentaron entrar fuera de su horario.");
        tabla = Ui.tabla("Nadie tiene el acceso bloqueado.");
        tabla.getColumns().setAll(List.of(
                Ui.texto("Folio", 150, AccesoService.Bloqueo::folio),
                Ui.texto("Empleado", 200, AccesoService.Bloqueo::usuario),
                Ui.nodo("Motivo", 320, b -> Ui.dosLineas(b.motivo().descripcion(), b.detalle())),
                Ui.texto("Fecha", 150, b -> FECHA.format(b.fecha().atZone(ZoneId.systemDefault()))),
                Ui.nodo("", 150, b -> {
                    Button dar = Ui.boton("Dar acceso", "mdi2a-account-check-outline", "accent", "small");
                    dar.setOnAction(e -> darAcceso(b));
                    return dar;
                })));
        pagina.getChildren().addAll(indicadores, tabla);
    }

    public Node nodo() {
        return pagina;
    }

    public void actualizar() {
        List<AccesoService.Bloqueo> pendientes = sucursalId == null ? List.of()
                : ctx.auth().accesos().pendientesDeSucursal(sucursalId);
        tabla.setItems(FXCollections.observableArrayList(pendientes));
        indicadores.getChildren().setAll(
                Ui.indicador("Accesos bloqueados", String.valueOf(pendientes.size()), "mdi2a-account-lock-outline"));
    }

    private void darAcceso(AccesoService.Bloqueo b) {
        DialogoConfirmacion.mostrar(dialogos, "¿Dar acceso a " + b.usuario() + "?",
                b.motivo().descripcion() + " (" + b.detalle() + "). Podrá iniciar sesión una vez.",
                "Dar acceso", false, () -> {
                    try {
                        String folio = ctx.auth().accesos().autorizar(b.id(), supervisor);
                        avisos.exito("Acceso autorizado (folio " + folio + ").");
                    } catch (RuntimeException e) {
                        avisos.error(AdminContexto.mensaje(e));
                    }
                    actualizar();
                });
    }
}
