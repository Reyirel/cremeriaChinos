package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
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
import com.cremerias.puntoventa.ui.componentes.Segmentado;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;

/** Alta, edición, habilitación y borrado de sucursales. */
public class SucursalesPagina extends Pagina {

    /** Contexto real de una sucursal para decidir antes de deshabilitarla o eliminarla. */
    private record Contexto(long saldoCredito, List<UsuarioService.Fila> empleados, ReglaPrecio.Canal canal) {
    }

    private final Cargador carga = new Cargador();
    private final Cargador cargaDetalle = new Cargador();
    private BarraHerramientas barra;
    private TablaDatos<Sucursal> tabla;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;

    public SucursalesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Sucursales";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        Button nueva = Ui.principal("Nueva sucursal", "mdi2p-plus");
        nueva.setOnAction(e -> formulario(null));
        VBox pagina = Ui.pagina("Sucursales", "Tiendas que surte el almacén. El código las distingue aunque tengan el mismo nombre.",
                nueva);

        barra = new BarraHerramientas();
        barra.buscador("Buscar nombre, código o dirección");
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        tabla.setClave(Sucursal::id);
        tabla.dosLineas("Sucursal", Sucursal::nombre, s -> s.direccion() == null || s.direccion().isBlank()
                ? "Sin dirección" : s.direccion()).flexible(300);
        tabla.texto("Código", Sucursal::codigo).ancho(130).clase("columna-fuerte")
                .ayuda("Aparece en los folios de venta de sus cajas.");
        tabla.texto("Teléfono", s -> s.telefono() == null || s.telefono().isBlank() ? "—" : s.telefono()).ancho(160);
        var columnaEstado = tabla.etiqueta("Estado", s -> s.activo() ? 0 : 1, v -> v == 0 ? "Activa" : "Deshabilitada",
                v -> v == 0 ? Etiqueta.Tono.EXITO : Etiqueta.Tono.NEUTRO).ancho(150);
        var columnaAcciones = tabla.acciones(
                Accion.<Sucursal>boton("mdi2p-pencil-outline", "Editar", this::formulario),
                Accion.<Sucursal>menu("mdi2p-percent-outline", s -> "Canal de precios…", this::canal)
                        .si(s -> !s.esAlmacen()),
                Accion.<Sucursal>menu("mdi2e-eye-off-outline", s -> s.activo() ? "Deshabilitar…" : "Habilitar…",
                        this::cambiarActivo),
                Accion.<Sucursal>menu("mdi2t-trash-can-outline", s -> "Eliminar…", this::eliminar).peligrosa());
        tabla.marcar("atenuada", s -> !s.activo());
        tabla.setVacio(new EstadoVacio("mdi2s-storefront-outline", "Aún no hay sucursales.",
                "Crea la primera con «Nueva sucursal».").accion("Nueva sucursal", "mdi2p-plus", () -> formulario(null)));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos sucursales con esa búsqueda.", barra::limpiar));
        tabla.setAlActivar(s -> {
            mostrarDetalle(s);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, s) -> {
            if (s != null && maestro.estaAbierto()) {
                mostrarDetalle(s);
            }
        });

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 560);
        maestro.setAlCerrar(() -> tabla.tabla().requestFocus());
        maestro.setAnchoCompleto(tabla.anchoNecesario());
        maestro.tablaReducidaProperty().addListener((o, x, reducida) -> {
            columnaEstado.columna().setVisible(!reducida);
            columnaAcciones.columna().setVisible(!reducida);
        });
        pagina.getChildren().add(maestro);
        return Ui.ajustable(pagina, maestro, 300);
    }

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        tabla.cargando("Cargando sucursales…");
        carga.cargar(() -> a.admin().sucursales().listar(), lista -> {
            tabla.setDatos(lista);
            filtrar();
            if (maestro.estaAbierto()) {
                if (tabla.seleccionado() != null) {
                    mostrarDetalle(tabla.seleccionado());
                } else {
                    maestro.cerrar();
                }
            }
        }, e -> tabla.error("No se pudieron cargar las sucursales.", AdminContexto.mensaje(e), this::cargar));
    }

    private void filtrar() {
        String t = barra.busqueda();
        tabla.setFiltro(s -> t.isEmpty() || Texto.contiene(s.nombre(), t) || Texto.contiene(s.codigo(), t)
                || Texto.contiene(s.direccion(), t));
        long activas = tabla.datos().stream().filter(Sucursal::activo).count();
        barra.setResultados(Texto.plural(tabla.total(), "sucursal", "sucursales") + " · " + activas
                + (activas == 1 ? " activa" : " activas"));
    }

    private void mostrarDetalle(Sucursal s) {
        detalle.setTitulo(s.nombre(), "Código " + s.codigo());
        detalle.setEtiquetas(s.activo() ? Etiqueta.de("Activa", Etiqueta.Tono.EXITO) : Etiqueta.de("Deshabilitada", Etiqueta.Tono.NEUTRO));
        VBox datos = PanelDetalle.seccion("Datos",
                PanelDetalle.dato("Dirección", s.direccion()),
                PanelDetalle.dato("Teléfono", s.telefono()));
        VBox operacion = PanelDetalle.seccion("Operación", Ui.texto("Cargando…", "texto-secundario"));
        Label id = new Label(s.id());
        id.getStyleClass().add("texto-id");
        id.setWrapText(true);
        detalle.setContenido(datos, operacion, PanelDetalle.seccion("Identificador interno", id));

        Button editar = Ui.principal("Editar", "mdi2p-pencil-outline");
        editar.setOnAction(e -> formulario(s));
        Button activar = Ui.secundario(s.activo() ? "Deshabilitar" : "Habilitar",
                s.activo() ? "mdi2e-eye-off-outline" : "mdi2e-eye-outline");
        activar.setOnAction(e -> cambiarActivo(s));
        Button eliminar = Ui.boton("Eliminar", "mdi2t-trash-can-outline", "flat", "danger");
        eliminar.setOnAction(e -> eliminar(s));
        if (s.esAlmacen()) {
            detalle.setAcciones(editar, activar, eliminar);
        } else {
            Button canal = Ui.secundario("Canal de precios", "mdi2p-percent-outline");
            canal.setOnAction(e -> canal(s));
            detalle.setAcciones(editar, canal, activar, eliminar);
        }

        cargaDetalle.cargar(() -> contexto(s), c -> {
            long supervisores = c.empleados().stream().filter(f -> f.usuario().rol() == Rol.SUPERVISOR).count();
            operacion.getChildren().setAll(operacion.getChildren().getFirst(),
                    PanelDetalle.dato("Saldo de crédito con el almacén",
                            c.saldoCredito() > 0 ? Dinero.formatear(c.saldoCredito()) : "Sin adeudo"),
                    PanelDetalle.dato("Canal de precios", s.esAlmacen() ? "—" : c.canal().nombre()),
                    PanelDetalle.dato("Empleados activos", c.empleados().size() + (supervisores > 0
                            ? " (" + supervisores + (supervisores == 1 ? " supervisor)" : " supervisores)") : "")));
        }, e -> operacion.getChildren().set(1, Ui.texto("No se pudo cargar: " + AdminContexto.mensaje(e), "texto-error")));
    }

    private Contexto contexto(Sucursal s) {
        long saldo = a.admin().credito().saldo(s.id());
        List<UsuarioService.Fila> empleados = a.admin().usuarios().listar().stream()
                .filter(f -> s.id().equals(f.usuario().sucursalId()) && f.usuario().activo()).toList();
        return new Contexto(saldo, empleados, s.esAlmacen() ? null : a.admin().sucursales().canal(s.id()));
    }

    /** Con qué margen se le cobra lo surtido: como cremería o como ruta de la Sierra. */
    private void canal(Sucursal s) {
        Cargador.ejecutar(() -> a.admin().sucursales().canal(s.id()), actual -> {
            Dialogo d = new Dialogo("Canal de precios", s.nombre() + " · ahora: " + actual.nombre(),
                    "mdi2p-percent-outline", Dialogo.Tono.ACENTO);
            d.setPrefWidth(560);
            Segmentado<ReglaPrecio.Canal> canal = new Segmentado<ReglaPrecio.Canal>()
                    .opcion(ReglaPrecio.Canal.BODEGA_SUCURSAL, ReglaPrecio.Canal.BODEGA_SUCURSAL.nombre())
                    .opcion(ReglaPrecio.Canal.RUTA_SIERRA, ReglaPrecio.Canal.RUTA_SIERRA.nombre());
            canal.setValor(actual);
            Label ayuda = Ui.texto(actual.descripcion() + ".", "campo-ayuda");
            canal.valorProperty().addListener((o, x, c) -> ayuda.setText(c.descripcion() + "."));
            d.setContenido(Formulario.campo("Se le surte como", new VBox(6, canal, ayuda)),
                    Formulario.nota("mdi2i-information-outline", "Los surtidos siguientes se cobran con las reglas de "
                            + "margen de ese canal (Precios y márgenes). Los surtidos ya registrados no cambian.", null));
            Runnable guardar = () -> {
                ReglaPrecio.Canal elegido = canal.getValor();
                if (elegido == actual) {
                    d.cerrar();
                    return;
                }
                a.guardar(d, "Canal de precios de " + s.nombre() + ": " + elegido.nombre(),
                        () -> a.admin().sucursales().cambiarCanal(s, elegido, a.usuario()), this::cargar);
            };
            d.agregarBoton("Cancelar", null, d::cancelar, "flat");
            d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
            d.setAlCancelar(d::cerrar);
            d.setAlConfirmar(guardar);
            d.vigilarCambios(canal);
            a.dialogos().mostrar(d);
        }, e -> a.avisos().error(AdminContexto.mensaje(e)));
    }

    private void formulario(Sucursal s) {
        boolean nueva = s == null;
        Dialogo d = new Dialogo(nueva ? "Nueva sucursal" : "Editar sucursal",
                nueva ? "Cada cambio queda en el historial con su folio." : s.nombre(),
                "mdi2s-storefront-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        TextField codigo = new TextField(nueva ? "" : s.codigo());
        codigo.setPromptText("Ej. NORTE");
        TextField nombre = new TextField(nueva ? "" : s.nombre());
        nombre.setPromptText("Ej. Cremería Norte");
        TextField direccion = new TextField(nueva || s.direccion() == null ? "" : s.direccion());
        direccion.setPromptText("Calle, número y colonia");
        TextField telefono = new TextField(nueva || s.telefono() == null ? "" : s.telefono());
        Formulario.Campo campoCodigo = Formulario.campo("Código", codigo)
                .ayuda("Aparece en los folios de venta de sus cajas (ej. NORTE-1987-000001).");
        Formulario.Campo campoNombre = Formulario.campo("Nombre", nombre);
        codigo.textProperty().addListener((o, x, y) -> campoCodigo.setError(null));
        nombre.textProperty().addListener((o, x, y) -> campoNombre.setError(null));
        d.setContenido(Formulario.fila(campoCodigo, campoNombre),
                Formulario.opcional("Dirección", direccion), Formulario.opcional("Teléfono", telefono));
        Runnable guardar = () -> {
            boolean valido = true;
            if (codigo.getText() == null || codigo.getText().isBlank()) {
                campoCodigo.setError("Escribe el código de la sucursal.");
                valido = false;
            }
            if (nombre.getText() == null || nombre.getText().isBlank()) {
                campoNombre.setError("Escribe el nombre de la sucursal.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            a.guardar(d, (nueva ? "Sucursal creada: " : "Sucursal actualizada: ") + nombre.getText().strip(),
                    () -> a.admin().sucursales().guardar(nueva ? null : s.id(), codigo.getText(), nombre.getText(),
                            direccion.getText(), telefono.getText(), a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nueva ? "Crear sucursal" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(nueva ? codigo : nombre);
        d.vigilarCambios(codigo, nombre, direccion, telefono);
        a.dialogos().mostrar(d);
    }

    /** Deshabilitar es reversible y conserva todo; se explica qué deja de pasar. */
    private void cambiarActivo(Sucursal s) {
        DialogoConfirmacion.mostrar(a.dialogos(), s.activo() ? "¿Deshabilitar " + s.nombre() + "?" : "¿Habilitar " + s.nombre() + "?",
                s.activo() ? "Ya no aparecerá para surtir ni en los listados de sucursales activas. Sus ventas, cortes, "
                        + "crédito e historial se conservan, y puedes volver a habilitarla cuando quieras."
                        : "Volverá a aparecer para surtir y en los listados de sucursales activas.",
                s.activo() ? "Deshabilitar" : "Habilitar", false,
                () -> a.enFondo((s.activo() ? "Sucursal deshabilitada: " : "Sucursal habilitada: ") + s.nombre(),
                        () -> a.admin().sucursales().cambiarActivo(s, !s.activo(), a.usuario()), this::cargar));
    }

    /** Eliminar la quita del sistema; la confirmación dice qué tiene pendiente. */
    private void eliminar(Sucursal s) {
        Cargador.ejecutar(() -> contexto(s), c -> {
            StringBuilder texto = new StringBuilder("Dejará de aparecer en el sistema. Sus ventas, cortes y movimientos "
                    + "se conservan en el historial.");
            if (c.saldoCredito() > 0) {
                texto.append(" Todavía debe ").append(Dinero.formatear(c.saldoCredito())).append(" de crédito al almacén.");
            }
            if (!c.empleados().isEmpty()) {
                texto.append(" Tiene ").append(Texto.plural(c.empleados().size(), "empleado activo", "empleados activos"))
                        .append(" asignados.");
            }
            if (s.activo()) {
                texto.append(" Si solo dejará de operar por un tiempo, mejor deshabilítala.");
            }
            DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar " + s.nombre() + " (" + s.codigo() + ")?",
                    texto.toString(), "Eliminar sucursal", true,
                    () -> a.enFondo("Sucursal eliminada: " + s.nombre(),
                            () -> a.admin().sucursales().eliminar(s, a.usuario()), () -> {
                                maestro.cerrar();
                                cargar();
                            }));
        }, e -> a.avisos().error(AdminContexto.mensaje(e)));
    }
}
