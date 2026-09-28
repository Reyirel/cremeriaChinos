package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;

/** Alta, edición, habilitación y borrado de sucursales. */
public class SucursalesPagina extends Pagina {

    private TableView<Sucursal> tabla;

    public SucursalesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        var nueva = Ui.principal("Nueva sucursal", "mdi2p-plus");
        nueva.setOnAction(e -> formulario(null));
        VBox pagina = Ui.pagina("Sucursales", "Tiendas que surte el almacén. Deshabilitar una sucursal la oculta sin perder su historial.", nueva);

        tabla = Ui.tabla("Aún no hay sucursales. Crea la primera con «Nueva sucursal».");
        tabla.getColumns().setAll(List.of(
                Ui.texto("Código", 100, Sucursal::codigo),
                Ui.nodo("Sucursal", 260, s -> Ui.dosLineas(s.nombre(), s.direccion())),
                Ui.texto("Teléfono", 140, s -> s.telefono() == null ? "" : s.telefono()),
                Ui.nodo("Estado", 120, s -> s.activo() ? Ui.chip("Activa", "exito") : Ui.chip("Deshabilitada", "neutro")),
                Ui.id(Sucursal::id),
                Ui.nodo("", 140, s -> Ui.acciones(
                        Ui.accion("mdi2p-pencil-outline", "Editar", () -> formulario(s)),
                        Ui.accion(s.activo() ? "mdi2e-eye-off-outline" : "mdi2e-eye-outline",
                                s.activo() ? "Deshabilitar" : "Habilitar", () -> cambiarActivo(s)),
                        Ui.accion("mdi2t-trash-can-outline", "Eliminar", () -> eliminar(s), "danger")))));
        pagina.getChildren().add(tabla);
        return pagina;
    }

    @Override
    public void alMostrar() {
        tabla.setItems(FXCollections.observableArrayList(a.admin().sucursales().listar()));
    }

    private void formulario(Sucursal s) {
        Dialogo d = new Dialogo(s == null ? "Nueva sucursal" : "Editar sucursal",
                s == null ? "Se le asignará un id único y cada cambio quedará con folio." : s.nombre(),
                "mdi2s-storefront-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(520);
        TextField codigo = new TextField(s == null ? "" : s.codigo());
        codigo.setPromptText("Ej. NORTE");
        TextField nombre = new TextField(s == null ? "" : s.nombre());
        nombre.setPromptText("Ej. Cremería Norte");
        TextField direccion = new TextField(s == null || s.direccion() == null ? "" : s.direccion());
        TextField telefono = new TextField(s == null || s.telefono() == null ? "" : s.telefono());
        Label ayuda = Ui.texto("El código aparece en los folios de venta de sus cajas (ej. NORTE-1987-000001).", "texto-ayuda");
        d.setContenido(Ui.fila(Ui.campo("Código", codigo), Ui.campo("Nombre", nombre)), ayuda,
                Ui.campo("Dirección", direccion), Ui.campo("Teléfono", telefono));
        Runnable guardar = () -> {
            if (a.ejecutar(s == null ? "Sucursal creada" : "Sucursal actualizada",
                    () -> a.admin().sucursales().guardar(s == null ? null : s.id(), codigo.getText(), nombre.getText(),
                            direccion.getText(), telefono.getText(), a.usuario()))) {
                d.cerrar();
                alMostrar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(s == null ? codigo : nombre);
        a.dialogos().mostrar(d);
    }

    private void cambiarActivo(Sucursal s) {
        DialogoConfirmacion.mostrar(a.dialogos(), s.activo() ? "¿Deshabilitar " + s.nombre() + "?" : "¿Habilitar " + s.nombre() + "?",
                s.activo() ? "No aparecerá para surtir ni en los listados activos. Su historial se conserva."
                        : "Volverá a estar disponible.",
                s.activo() ? "Deshabilitar" : "Habilitar", s.activo(), () -> {
                    a.ejecutar(s.activo() ? "Sucursal deshabilitada" : "Sucursal habilitada",
                            () -> a.admin().sucursales().cambiarActivo(s, !s.activo(), a.usuario()));
                    alMostrar();
                });
    }

    private void eliminar(Sucursal s) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar " + s.nombre() + "?",
                "Dejará de aparecer en el sistema. Sus ventas, cortes y movimientos se conservan con su id "
                        + s.id() + ".", "Eliminar", true, () -> {
                    a.ejecutar("Sucursal eliminada", () -> a.admin().sucursales().eliminar(s, a.usuario()));
                    alMostrar();
                });
    }
}
