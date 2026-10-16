package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.PasswordTextField;
import com.cremerias.puntoventa.model.HorarioDia;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.UsuarioService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Usuarios: alta con rol y sucursal, edición, contraseña, horario, habilitación y borrado. */
public class UsuariosPagina extends Pagina {

    /** Estado del usuario en una sola palabra (para ordenar y filtrar). */
    private enum Estado {
        ACTIVO("Activo", Etiqueta.Tono.EXITO), BLOQUEADO("Acceso bloqueado", Etiqueta.Tono.PELIGRO),
        DESHABILITADO("Deshabilitado", Etiqueta.Tono.NEUTRO);

        final String texto;
        final Etiqueta.Tono tono;

        Estado(String texto, Etiqueta.Tono tono) {
            this.texto = texto;
            this.tono = tono;
        }
    }

    private static final String TODOS_ROLES = "Todos los roles";

    private final Cargador carga = new Cargador();
    private final Cargador cargaDetalle = new Cargador();
    private BarraHerramientas barra;
    private ComboBox<String> rol;
    private TablaDatos<UsuarioService.Fila> tabla;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;

    public UsuariosPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Usuarios y horarios";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        Button nuevo = Ui.principal("Nuevo usuario", "mdi2p-plus");
        nuevo.setOnAction(e -> formulario(null));
        VBox pagina = Ui.pagina("Usuarios y horarios",
                "Cada empleado tiene un rol y una sucursal. Si tiene horario y no lo cumple, su acceso se bloquea.", nuevo);

        barra = new BarraHerramientas();
        barra.buscador("Buscar nombre o usuario");
        List<String> roles = new ArrayList<>(List.of(TODOS_ROLES));
        for (Rol r : Rol.values()) {
            roles.add(r.nombre());
        }
        rol = barra.filtro("Rol", new ComboBox<>(FXCollections.observableArrayList(roles)), TODOS_ROLES, r -> r);
        rol.setValue(TODOS_ROLES);
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(f -> f.usuario().id());
        tabla.dosLineas("Empleado", f -> f.usuario().nombreCompleto(), f -> "Usuario: " + f.usuario().usuario()).flexible(220);
        tabla.nodo("Rol", f -> Etiqueta.conIcono(f.usuario().rol().nombre(), f.usuario().rol().icono(), Etiqueta.Tono.NEUTRO))
                .ancho(170);
        tabla.textoLargo("Sucursal", UsuarioService.Fila::sucursal).ancho(226);
        var columnaHorario = tabla.etiqueta("Horario", f -> f.usuario().rol() == Rol.ADMINISTRADOR ? 2 : f.tieneHorario() ? 0 : 1,
                v -> v == 0 ? "Con horario" : v == 1 ? "Sin horario" : "Sin restricción",
                v -> v == 0 ? Etiqueta.Tono.INFO : Etiqueta.Tono.NEUTRO).ancho(150);
        var columnaEstado = tabla.etiqueta("Estado", UsuariosPagina::estado, e -> e.texto, e -> e.tono).ancho(170);
        var columnaAcciones = tabla.acciones(
                Accion.<UsuarioService.Fila>boton("mdi2p-pencil-outline", "Editar", f -> formulario(f.usuario())),
                Accion.<UsuarioService.Fila>boton("mdi2c-clock-edit-outline", "Horario",
                        f -> DialogoHorario.mostrar(a, f.usuario(), this::cargar)),
                Accion.<UsuarioService.Fila>menu("mdi2k-key-outline", f -> "Cambiar contraseña…", f -> password(f.usuario())),
                Accion.<UsuarioService.Fila>menu("mdi2e-eye-off-outline",
                        f -> f.usuario().activo() ? "Deshabilitar…" : "Habilitar…", f -> cambiarActivo(f.usuario())),
                Accion.<UsuarioService.Fila>menu("mdi2t-trash-can-outline", f -> "Eliminar…", f -> eliminar(f.usuario()))
                        .peligrosa());
        tabla.marcar("atenuada", f -> !f.usuario().activo());
        tabla.setVacio(new EstadoVacio("mdi2a-account-group-outline", "No hay usuarios.", null)
                .accion("Nuevo usuario", "mdi2p-plus", () -> formulario(null)));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos usuarios con estos filtros.", barra::limpiar));
        tabla.setAlActivar(f -> {
            mostrarDetalle(f);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, f) -> {
            if (f != null && maestro.estaAbierto()) {
                mostrarDetalle(f);
            }
        });

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 640);
        maestro.setAlCerrar(() -> {
            cargaDetalle.cancelar();
            tabla.tabla().requestFocus();
        });
        maestro.setAnchoCompleto(tabla.anchoNecesario());
        maestro.tablaReducidaProperty().addListener((o, x, reducida) -> {
            columnaHorario.columna().setVisible(!reducida);
            columnaEstado.columna().setVisible(!reducida);
            columnaAcciones.columna().setVisible(!reducida);
        });
        pagina.getChildren().add(maestro);
        return Ui.ajustable(pagina, maestro, 320);
    }

    private static Estado estado(UsuarioService.Fila f) {
        return !f.usuario().activo() ? Estado.DESHABILITADO : f.accesoBloqueado() ? Estado.BLOQUEADO : Estado.ACTIVO;
    }

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        tabla.cargando("Cargando usuarios…");
        carga.cargar(() -> a.admin().usuarios().listar(), lista -> {
            tabla.setDatos(lista);
            filtrar();
            if (maestro.estaAbierto()) {
                if (tabla.seleccionado() != null) {
                    mostrarDetalle(tabla.seleccionado());
                } else {
                    maestro.cerrar();
                }
            }
        }, e -> tabla.error("No se pudieron cargar los usuarios.", AdminContexto.mensaje(e), this::cargar));
    }

    private void filtrar() {
        String t = barra.busqueda();
        String r = rol.getValue() == null ? TODOS_ROLES : rol.getValue();
        tabla.setFiltro(f -> (t.isEmpty() || Texto.contiene(f.usuario().nombreCompleto(), t)
                || Texto.contiene(f.usuario().usuario(), t))
                && (TODOS_ROLES.equals(r) || f.usuario().rol().nombre().equals(r)));
        long bloqueados = tabla.datos().stream().filter(f -> estado(f) == Estado.BLOQUEADO).count();
        barra.setResultados(Texto.plural(tabla.total(), "usuario", "usuarios")
                + (bloqueados > 0 ? " · " + bloqueados + " con acceso bloqueado" : ""));
    }

    private void mostrarDetalle(UsuarioService.Fila f) {
        Usuario u = f.usuario();
        detalle.setTitulo(u.nombreCompleto(), "Usuario: " + u.usuario());
        Estado e = estado(f);
        detalle.setEtiquetas(Etiqueta.conIcono(u.rol().nombre(), u.rol().icono(), Etiqueta.Tono.NEUTRO), Etiqueta.de(e.texto, e.tono));
        VBox datos = PanelDetalle.seccion("Datos",
                PanelDetalle.dato("Sucursal", f.sucursal()),
                PanelDetalle.dato("Último acceso", u.ultimoAcceso() == null ? "Nunca" : Formatos.fechaHora(u.ultimoAcceso())),
                PanelDetalle.dato("Intentos fallidos", String.valueOf(u.intentosFallidos())));
        List<Node> secciones = new ArrayList<>(List.of(datos));
        if (u.bloqueado(Instant.now())) {
            secciones.add(Formulario.nota("mdi2l-lock-outline", "Bloqueado por intentos fallidos hasta "
                    + Formatos.fechaHora(u.bloqueadoHasta()) + ". Cambiar su contraseña quita el bloqueo.", "aviso"));
        }
        VBox horario = PanelDetalle.seccion("Horario", Ui.texto(u.rol() == Rol.ADMINISTRADOR
                ? "Sin restricción de horario." : "Cargando…", "texto-secundario"));
        secciones.add(horario);
        Label id = new Label(u.id());
        id.getStyleClass().add("texto-id");
        id.setWrapText(true);
        secciones.add(PanelDetalle.seccion("Identificador interno", id));
        detalle.setContenido(secciones.toArray(Node[]::new));

        Button editar = Ui.principal("Editar", "mdi2p-pencil-outline");
        editar.setOnAction(x -> formulario(u));
        Button verHorario = Ui.secundario("Horario", "mdi2c-clock-edit-outline");
        verHorario.setOnAction(x -> DialogoHorario.mostrar(a, u, this::cargar));
        Button clave = Ui.secundario("Contraseña", "mdi2k-key-outline");
        clave.setOnAction(x -> password(u));
        detalle.setAcciones(editar, verHorario, clave);

        if (u.rol() != Rol.ADMINISTRADOR) {
            cargaDetalle.cargar(() -> a.ctx().auth().accesos().horario(u.id()), mapa -> llenarHorario(horario, mapa),
                    err -> horario.getChildren().set(1, Ui.texto("No se pudo cargar el horario.", "texto-error")));
        }
    }

    private static void llenarHorario(VBox seccion, Map<DayOfWeek, HorarioDia> mapa) {
        if (mapa.isEmpty()) {
            seccion.getChildren().set(1, Ui.texto("Sin horario: puede entrar a cualquier hora.", "texto-secundario"));
            return;
        }
        List<Node> filas = new ArrayList<>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            HorarioDia h = mapa.getOrDefault(dia, HorarioDia.descanso(dia));
            String horas = !h.labora() ? "Descansa"
                    : h.entrada() + " – " + h.salida() + (h.tieneDescanso() ? " · comida " + h.descansoInicio() + "–" + h.descansoFin() : "");
            filas.add(PanelDetalle.dato(h.nombreDia(), horas));
        }
        VBox v = new VBox(4);
        v.getChildren().addAll(filas);
        seccion.getChildren().set(1, v);
    }

    private void formulario(Usuario u) {
        boolean nuevo = u == null;
        Dialogo d = new Dialogo(nuevo ? "Nuevo usuario" : "Editar usuario",
                nuevo ? "Elige su rol y la sucursal donde trabajará." : u.nombreCompleto(),
                "mdi2a-account-group-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(580);
        TextField nombre = new TextField(nuevo ? "" : u.nombreCompleto());
        TextField usuario = new TextField(nuevo ? "" : u.usuario());
        usuario.setPromptText("Ej. mlopez");
        PasswordTextField password = new PasswordTextField();
        PasswordTextField confirmar = new PasswordTextField();
        ComboBox<Rol> rolCombo = new ComboBox<>(FXCollections.observableArrayList(Rol.values()));
        rolCombo.setConverter(Combos.de(Rol::nombre));
        Sucursal almacen = a.admin().sucursales().almacen().orElse(null);
        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(a.admin().sucursales().activas()));
        sucursal.setConverter(Combos.sucursal());
        sucursal.setPromptText("Elige la sucursal");
        Formulario.Campo campoRol = Formulario.campo("Rol", rolCombo);
        Formulario.Campo campoSucursal = Formulario.campo("Sucursal", sucursal);
        rolCombo.valueProperty().addListener((o, x, r) -> {
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
            campoRol.ayuda(r == null ? null : switch (r) {
                case ADMINISTRADOR -> "Trabaja en el almacén central y administra todo el sistema.";
                case SUPERVISOR -> "Vende y hace los cortes de las cajas de su sucursal.";
                case CAJERO -> "Vende y cobra en la caja de su sucursal.";
            });
        });
        rolCombo.setValue(nuevo ? Rol.CAJERO : u.rol());
        if (!nuevo && u.rol() != Rol.ADMINISTRADOR) {
            sucursal.getItems().stream().filter(s -> s.id().equals(u.sucursalId())).findFirst().ifPresent(sucursal::setValue);
        }
        Formulario.Campo campoNombre = Formulario.campo("Nombre completo", nombre);
        Formulario.Campo campoUsuario = Formulario.campo("Usuario para iniciar sesión", usuario);
        Formulario.Campo campoPassword = Formulario.campo("Contraseña", password);
        Formulario.Campo campoConfirmar = Formulario.campo("Confirmar contraseña", confirmar);
        nombre.textProperty().addListener((o, x, y) -> campoNombre.setError(null));
        usuario.textProperty().addListener((o, x, y) -> campoUsuario.setError(null));
        confirmar.passwordProperty().addListener((o, x, y) -> campoConfirmar.setError(null));
        sucursal.valueProperty().addListener((o, x, y) -> campoSucursal.setError(null));
        VBox contenido = new VBox(14, campoNombre, campoUsuario);
        if (nuevo) {
            contenido.getChildren().add(Formulario.fila(campoPassword, campoConfirmar));
        }
        contenido.getChildren().add(Formulario.fila(campoRol, campoSucursal));
        d.setContenido(contenido);
        Runnable guardar = () -> {
            boolean valido = true;
            if (nombre.getText() == null || nombre.getText().isBlank()) {
                campoNombre.setError("Escribe el nombre completo.");
                valido = false;
            }
            if (usuario.getText() == null || usuario.getText().isBlank()) {
                campoUsuario.setError("Escribe el usuario con el que entrará.");
                valido = false;
            }
            if (nuevo && !password.getPassword().equals(confirmar.getPassword())) {
                campoConfirmar.setError("Las contraseñas no coinciden.");
                valido = false;
            }
            if (rolCombo.getValue() != Rol.ADMINISTRADOR && sucursal.getValue() == null) {
                campoSucursal.setError("Elige la sucursal donde trabajará.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            String sucursalId = sucursal.getValue() == null ? null : sucursal.getValue().id();
            char[] clave = password.getPassword().toCharArray();
            Rol rolElegido = rolCombo.getValue();
            String nombreTexto = nombre.getText();
            String usuarioTexto = usuario.getText();
            a.guardar(d, (nuevo ? "Usuario creado: " : "Usuario actualizado: ") + nombreTexto.strip(),
                    () -> nuevo
                            ? a.admin().usuarios().crear(nombreTexto, usuarioTexto, clave, rolElegido, sucursalId, a.usuario())
                            : a.admin().usuarios().editar(u, nombreTexto, usuarioTexto, rolElegido, sucursalId, a.usuario()),
                    this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nuevo ? "Crear usuario" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(nombre);
        d.vigilarCambios(nombre, usuario, password, confirmar, rolCombo, sucursal);
        a.dialogos().mostrar(d);
    }

    private void password(Usuario u) {
        Dialogo d = new Dialogo("Cambiar contraseña", u.nombreCompleto(), "mdi2k-key-outline", Dialogo.Tono.ACENTO);
        PasswordTextField password = new PasswordTextField();
        PasswordTextField confirmar = new PasswordTextField();
        Formulario.Campo campoConfirmar = Formulario.campo("Confirmar", confirmar);
        confirmar.passwordProperty().addListener((o, x, y) -> campoConfirmar.setError(null));
        d.setContenido(Formulario.campo("Nueva contraseña", password).ayuda("También quita el bloqueo por intentos fallidos."),
                campoConfirmar);
        Runnable guardar = () -> {
            if (!password.getPassword().equals(confirmar.getPassword())) {
                campoConfirmar.setError("Las contraseñas no coinciden.");
                return;
            }
            char[] clave = password.getPassword().toCharArray();
            a.guardar(d, "Contraseña cambiada: " + u.nombreCompleto(),
                    () -> a.admin().usuarios().cambiarPassword(u, clave, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Guardar contraseña", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(password);
        d.vigilarCambios(password, confirmar);
        a.dialogos().mostrar(d);
    }

    private void cambiarActivo(Usuario u) {
        DialogoConfirmacion.mostrar(a.dialogos(), (u.activo() ? "¿Deshabilitar a " : "¿Habilitar a ") + u.nombreCompleto() + "?",
                u.activo() ? "No podrá iniciar sesión hasta que lo vuelvas a habilitar. Sus ventas y su historial se conservan."
                        : "Podrá volver a iniciar sesión.",
                u.activo() ? "Deshabilitar" : "Habilitar", false,
                () -> a.enFondo((u.activo() ? "Usuario deshabilitado: " : "Usuario habilitado: ") + u.nombreCompleto(),
                        () -> a.admin().usuarios().cambiarActivo(u, !u.activo(), a.usuario()), this::cargar));
    }

    private void eliminar(Usuario u) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar a " + u.nombreCompleto() + "?",
                "Ya no podrá entrar y no aparecerá en los listados. Sus ventas y acciones se conservan en el historial."
                        + (u.activo() ? " Si solo dejará de trabajar un tiempo, mejor deshabilítalo." : ""),
                "Eliminar usuario", true,
                () -> a.enFondo("Usuario eliminado: " + u.nombreCompleto(),
                        () -> a.admin().usuarios().eliminar(u, a.usuario()), () -> {
                            maestro.cerrar();
                            cargar();
                        }));
    }
}
