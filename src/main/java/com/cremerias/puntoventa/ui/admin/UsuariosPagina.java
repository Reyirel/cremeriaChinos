package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.PasswordTextField;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.UsuarioService;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/** Usuarios: alta con rol y sucursal, edición, contraseña, horario, habilitación y borrado. */
public class UsuariosPagina extends Pagina {

    private TableView<UsuarioService.Fila> tabla;
    private TextField buscador;
    private List<UsuarioService.Fila> todos = List.of();

    public UsuariosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        var nuevo = Ui.principal("Nuevo usuario", "mdi2p-plus");
        nuevo.setOnAction(e -> formulario(null));
        VBox pagina = Ui.pagina("Usuarios y horarios",
                "Cada empleado tiene un rol y una sucursal. Si tiene horario, su acceso se bloquea al no cumplirlo.", nuevo);
        buscador = Ui.buscador("Buscar por nombre o usuario");
        buscador.textProperty().addListener((o, x, y) -> filtrar());
        tabla = Ui.tabla("No hay usuarios.");
        tabla.getColumns().setAll(List.of(
                Ui.nodo("Empleado", 240, f -> Ui.dosLineas(f.usuario().nombreCompleto(), f.usuario().usuario())),
                Ui.nodo("Rol", 140, f -> Ui.chip(f.usuario().rol().nombre(), switch (f.usuario().rol()) {
                    case ADMINISTRADOR -> "morado";
                    case SUPERVISOR -> "azul";
                    case CAJERO -> "exito";
                })),
                Ui.texto("Sucursal", 170, UsuarioService.Fila::sucursal),
                Ui.nodo("Horario", 120, f -> f.usuario().rol() == Rol.ADMINISTRADOR ? Ui.chip("Sin restricción", "neutro")
                        : f.tieneHorario() ? Ui.chip("Asignado", "acento") : Ui.chip("Sin horario", "neutro")),
                Ui.nodo("Estado", 150, f -> !f.usuario().activo() ? Ui.chip("Deshabilitado", "neutro")
                        : f.accesoBloqueado() ? Ui.chip("Acceso bloqueado", "peligro") : Ui.chip("Activo", "exito")),
                Ui.id(f -> f.usuario().id()),
                Ui.nodo("", 200, f -> Ui.acciones(
                        Ui.accion("mdi2p-pencil-outline", "Editar", () -> formulario(f.usuario())),
                        Ui.accion("mdi2c-clock-edit-outline", "Horario", () -> DialogoHorario.mostrar(a, f.usuario(), this::alMostrar)),
                        Ui.accion("mdi2k-key-outline", "Cambiar contraseña", () -> password(f.usuario())),
                        Ui.accion(f.usuario().activo() ? "mdi2e-eye-off-outline" : "mdi2e-eye-outline",
                                f.usuario().activo() ? "Deshabilitar" : "Habilitar", () -> cambiarActivo(f.usuario())),
                        Ui.accion("mdi2t-trash-can-outline", "Eliminar", () -> eliminar(f.usuario()), "danger")))));
        pagina.getChildren().addAll(new HBox(10, buscador), tabla);
        return pagina;
    }

    @Override
    public void alMostrar() {
        todos = a.admin().usuarios().listar();
        filtrar();
    }

    private void filtrar() {
        String t = buscador.getText() == null ? "" : buscador.getText().strip().toLowerCase(Locale.ROOT);
        tabla.setItems(FXCollections.observableArrayList(todos.stream()
                .filter(f -> t.isEmpty() || f.usuario().nombreCompleto().toLowerCase(Locale.ROOT).contains(t)
                        || f.usuario().usuario().toLowerCase(Locale.ROOT).contains(t))
                .toList()));
    }

    private void formulario(Usuario u) {
        boolean nuevo = u == null;
        Dialogo d = new Dialogo(nuevo ? "Nuevo usuario" : "Editar usuario",
                nuevo ? "Elige su rol y la sucursal donde trabajará." : u.nombreCompleto(),
                "mdi2a-account-group-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        TextField nombre = new TextField(nuevo ? "" : u.nombreCompleto());
        TextField usuario = new TextField(nuevo ? "" : u.usuario());
        PasswordTextField password = new PasswordTextField();
        PasswordTextField confirmar = new PasswordTextField();
        ComboBox<Rol> rol = new ComboBox<>(FXCollections.observableArrayList(Rol.values()));
        rol.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Rol r) {
                return r == null ? "" : r.nombre();
            }

            @Override
            public Rol fromString(String s) {
                return null;
            }
        });
        Sucursal almacen = a.admin().sucursales().almacen().orElse(null);
        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(a.admin().sucursales().activas()));
        Label ayudaRol = Ui.texto("", "texto-ayuda");
        rol.valueProperty().addListener((o, x, r) -> {
            boolean admin = r == Rol.ADMINISTRADOR;
            sucursal.setDisable(admin);
            if (admin) {
                sucursal.getItems().setAll(almacen);
                sucursal.setValue(almacen);
            } else {
                Sucursal actual = sucursal.getValue();
                sucursal.getItems().setAll(a.admin().sucursales().activas());
                sucursal.setValue(actual != null && !actual.esAlmacen() ? actual : null);
            }
            ayudaRol.setText(r == null ? "" : switch (r) {
                case ADMINISTRADOR -> "Trabaja en el almacén central y administra todo el sistema.";
                case SUPERVISOR -> "Vende y hace los cortes de las cajas de su sucursal.";
                case CAJERO -> "Vende y cobra en la caja de su sucursal.";
            });
        });
        rol.setValue(nuevo ? Rol.CAJERO : u.rol());
        if (!nuevo && u.rol() != Rol.ADMINISTRADOR) {
            sucursal.getItems().stream().filter(s -> s.id().equals(u.sucursalId())).findFirst().ifPresent(sucursal::setValue);
        }
        VBox contenido = new VBox(14, Ui.campo("Nombre completo", nombre), Ui.campo("Usuario para iniciar sesión", usuario));
        if (nuevo) {
            contenido.getChildren().add(Ui.fila(Ui.campo("Contraseña", password), Ui.campo("Confirmar contraseña", confirmar)));
        }
        contenido.getChildren().addAll(Ui.fila(Ui.campo("Rol", rol), Ui.campo("Sucursal", sucursal)), ayudaRol);
        d.setContenido(contenido);
        Runnable guardar = () -> {
            if (nuevo && !password.getPassword().equals(confirmar.getPassword())) {
                a.avisos().error("Las contraseñas no coinciden.");
                return;
            }
            String sucursalId = sucursal.getValue() == null ? null : sucursal.getValue().id();
            boolean ok = nuevo
                    ? a.ejecutar("Usuario creado", () -> a.admin().usuarios().crear(nombre.getText(), usuario.getText(),
                    password.getPassword().toCharArray(), rol.getValue(), sucursalId, a.usuario()))
                    : a.ejecutar("Usuario actualizado", () -> a.admin().usuarios().editar(u, nombre.getText(),
                    usuario.getText(), rol.getValue(), sucursalId, a.usuario()));
            if (ok) {
                d.cerrar();
                alMostrar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(nombre);
        a.dialogos().mostrar(d);
    }

    private void password(Usuario u) {
        Dialogo d = new Dialogo("Cambiar contraseña", u.nombreCompleto(), "mdi2k-key-outline", Dialogo.Tono.ACENTO);
        PasswordTextField password = new PasswordTextField();
        PasswordTextField confirmar = new PasswordTextField();
        d.setContenido(Ui.campo("Nueva contraseña", password), Ui.campo("Confirmar", confirmar),
                Ui.texto("También se quita el bloqueo por intentos fallidos.", "texto-ayuda"));
        Runnable guardar = () -> {
            if (!password.getPassword().equals(confirmar.getPassword())) {
                a.avisos().error("Las contraseñas no coinciden.");
                return;
            }
            if (a.ejecutar("Contraseña cambiada",
                    () -> a.admin().usuarios().cambiarPassword(u, password.getPassword().toCharArray(), a.usuario()))) {
                d.cerrar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(password);
        a.dialogos().mostrar(d);
    }

    private void cambiarActivo(Usuario u) {
        DialogoConfirmacion.mostrar(a.dialogos(), (u.activo() ? "¿Deshabilitar a " : "¿Habilitar a ") + u.nombreCompleto() + "?",
                u.activo() ? "No podrá iniciar sesión hasta que lo vuelvas a habilitar." : "Podrá volver a iniciar sesión.",
                u.activo() ? "Deshabilitar" : "Habilitar", u.activo(), () -> {
                    a.ejecutar(u.activo() ? "Usuario deshabilitado" : "Usuario habilitado",
                            () -> a.admin().usuarios().cambiarActivo(u, !u.activo(), a.usuario()));
                    alMostrar();
                });
    }

    private void eliminar(Usuario u) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar a " + u.nombreCompleto() + "?",
                "Ya no podrá entrar y no aparecerá en los listados. Sus ventas y acciones se conservan con su id "
                        + u.id() + ".", "Eliminar", true, () -> {
                    a.ejecutar("Usuario eliminado", () -> a.admin().usuarios().eliminar(u, a.usuario()));
                    alMostrar();
                });
    }
}
