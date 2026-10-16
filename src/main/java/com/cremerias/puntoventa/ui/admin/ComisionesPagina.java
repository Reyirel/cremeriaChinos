package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.ToggleSwitch;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.ComisionService;
import com.cremerias.puntoventa.service.admin.UsuarioService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.CampoMasa;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.Pestanas;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Masa;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reglas de comisión y comisiones ganadas por los empleados. */
public class ComisionesPagina extends Pagina {

    private final Cargador carga = new Cargador();
    private final Cargador calculo = new Cargador();
    private Pestanas pestanas;
    private ToggleButton pestanaReglas;
    private ToggleButton pestanaGanadas;
    private BarraHerramientas barraReglas;
    private TablaDatos<ComisionService.Regla> reglas;
    private TablaDatos<ComisionService.Ganada> ganadas;
    private DatePicker desde;
    private DatePicker hasta;
    private ResumenMetricas totales;
    private VBox vistaReglas;
    private VBox vistaGanadas;
    private StackPane zona;

    public ComisionesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Comisiones";
    }

    @Override
    public TextField busqueda() {
        return pestanaReglas.isSelected() ? barraReglas.campoBusqueda() : null;
    }

    @Override
    protected Node construir() {
        Button nueva = Ui.principal("Nueva regla", "mdi2p-plus");
        nueva.setOnAction(e -> formulario(null));
        VBox pagina = Ui.pagina("Comisiones",
                "Premios por llegar a una meta de producto o de venta, o por cada cantidad vendida.", nueva);
        pestanas = new Pestanas();
        pestanaReglas = pestanas.agregar("Reglas");
        pestanaGanadas = pestanas.agregar("Comisiones ganadas");

        barraReglas = new BarraHerramientas();
        barraReglas.buscador("Buscar regla, producto o empleado");
        barraReglas.setAlCambiar(this::filtrar);
        reglas = new TablaDatos<>(Diseno.Densidad.AMPLIA);
        reglas.setClave(ComisionService.Regla::id);
        reglas.dosLineas("Regla", ComisionService.Regla::nombre, r -> r.tipo().nombre()).ancho(240);
        reglas.textoLargo("Condición", ComisionService.Regla::descripcion).flexible(280);
        reglas.texto("Periodo", r -> r.periodo().nombre()).ancho(110);
        reglas.dosLineas("Aplica a", r -> r.usuario() == null ? "Todos los empleados" : r.usuario(),
                r -> r.sucursal() == null ? "Todas las sucursales" : r.sucursal()).ancho(220);
        reglas.etiqueta("Estado", r -> r.activo() ? 0 : 1, v -> v == 0 ? "Activa" : "Inactiva",
                v -> v == 0 ? Etiqueta.Tono.EXITO : Etiqueta.Tono.NEUTRO).ancho(120);
        reglas.acciones(
                Accion.<ComisionService.Regla>boton("mdi2p-pencil-outline", "Editar", this::formulario),
                Accion.<ComisionService.Regla>boton("mdi2t-trash-can-outline", "Eliminar", this::eliminar).peligrosa());
        reglas.marcar("atenuada", r -> !r.activo());
        reglas.setVacio(new EstadoVacio("mdi2h-hand-coin-outline", "No hay reglas de comisión.",
                "Crea la primera con «Nueva regla».").accion("Nueva regla", "mdi2p-plus", () -> formulario(null)));
        reglas.setSinResultados(EstadoVacio.sinResultados("No encontramos reglas con esa búsqueda.", barraReglas::limpiar));
        reglas.setAlActivar(this::formulario);
        vistaReglas = Ui.panelTabla(barraReglas, reglas);

        desde = new DatePicker(LocalDate.now().withDayOfMonth(1));
        hasta = new DatePicker(LocalDate.now());
        desde.setEditable(false);
        hasta.setEditable(false);
        desde.setPrefWidth(150);
        hasta.setPrefWidth(150);
        Button calcular = Ui.principal("Calcular", "mdi2c-calculator-variant-outline");
        calcular.setOnAction(e -> calcular());
        Label del = new Label("Del");
        Label al = new Label("al");
        del.getStyleClass().add("etiqueta-filtro");
        al.getStyleClass().add("etiqueta-filtro");
        BarraHerramientas barraGanadas = new BarraHerramientas();
        HBox periodo = new HBox(8, del, desde, al, hasta, calcular);
        periodo.setAlignment(Pos.CENTER_LEFT);
        barraGanadas.agregar(periodo);
        ganadas = new TablaDatos<>(Diseno.Densidad.NORMAL);
        ganadas.texto("Empleado", ComisionService.Ganada::usuario).ancho(220);
        ganadas.dosLineas("Regla", g -> g.regla().nombre(), g -> g.regla().descripcion()).flexible(260);
        ganadas.texto("Periodo", ComisionService.Ganada::periodo).ancho(200);
        ganadas.texto("Logrado", ComisionService.Ganada::logrado).ancho(150);
        ganadas.dinero("Comisión", ComisionService.Ganada::comisionCentavos).ancho(130).clase("columna-fuerte");
        ganadas.setVacio(new EstadoVacio("mdi2c-calculator-variant-outline", "Elige el periodo y presiona «Calcular».",
                "Se calcula con las ventas registradas de cada empleado."));
        ganadas.setDatos(List.of());
        totales = new ResumenMetricas();
        totales.managedProperty().bind(totales.visibleProperty());
        totales.setVisible(false);
        VBox panelGanadas = Ui.panelTabla(barraGanadas, ganadas);
        VBox.setVgrow(panelGanadas, javafx.scene.layout.Priority.ALWAYS);
        vistaGanadas = new VBox(Diseno.ESPACIO_L, totales, panelGanadas);

        zona = new StackPane(vistaReglas);
        pestanas.seleccionProperty().addListener((o, x, y) ->
                zona.getChildren().setAll(pestanaReglas.isSelected() ? vistaReglas : vistaGanadas));
        pagina.getChildren().addAll(pestanas, zona);
        return Ui.ajustable(pagina, zona, 320);
    }

    @Override
    public void alMostrar() {
        reglas.cargando("Cargando reglas…");
        carga.cargar(() -> a.admin().comisiones().reglas(), lista -> {
            reglas.setDatos(lista);
            Pestanas.setContador(pestanaReglas, lista.size(), false);
            filtrar();
        }, e -> reglas.error("No se pudieron cargar las reglas.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void filtrar() {
        String t = barraReglas.busqueda();
        reglas.setFiltro(r -> t.isEmpty() || Texto.contiene(r.nombre(), t) || Texto.contiene(r.producto(), t)
                || Texto.contiene(r.usuario(), t) || Texto.contiene(r.sucursal(), t));
        long activas = reglas.datos().stream().filter(ComisionService.Regla::activo).count();
        barraReglas.setResultados(Texto.plural(reglas.total(), "regla", "reglas") + " · " + activas
                + (activas == 1 ? " activa" : " activas"));
    }

    private void calcular() {
        LocalDate d = desde.getValue();
        LocalDate h = hasta.getValue();
        if (d == null || h == null || h.isBefore(d)) {
            a.avisos().advertencia("Revisa las fechas: «del» debe ser antes de «al».");
            return;
        }
        ganadas.setDatos(List.of());
        ganadas.cargando("Calculando comisiones…");
        calculo.cargar(() -> a.admin().comisiones().calcular(d, h), lista -> {
            ganadas.setVacio(new EstadoVacio("mdi2c-calculator-variant-outline", "Nadie alcanzó comisión en estas fechas.",
                    "Revisa el periodo o las reglas activas."));
            ganadas.setDatos(lista);
            Pestanas.setContador(pestanaGanadas, lista.size(), false);
            Map<String, Long> porEmpleado = new LinkedHashMap<>();
            for (ComisionService.Ganada g : lista) {
                porEmpleado.merge(g.usuario(), g.comisionCentavos(), Long::sum);
            }
            totales.getChildren().clear();
            if (!porEmpleado.isEmpty()) {
                long total = porEmpleado.values().stream().mapToLong(Long::longValue).sum();
                totales.agregar("Total a pagar").principal().setValor(Dinero.formatear(total))
                        .setContexto(Formatos.FECHA_CORTA.format(d) + " – " + Formatos.FECHA_CORTA.format(h));
                porEmpleado.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(4)
                        .forEach(e -> totales.agregar(e.getKey()).setValor(Dinero.formatear(e.getValue())));
            }
            totales.setVisible(!porEmpleado.isEmpty());
        }, e -> ganadas.error("No se pudieron calcular las comisiones.", AdminContexto.mensaje(e), this::calcular));
    }

    private void eliminar(ComisionService.Regla r) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar la regla «" + r.nombre() + "»?",
                "Dejará de calcularse. Queda registrada en el historial.", "Eliminar regla", true,
                () -> a.enFondo("Regla eliminada: " + r.nombre(), () -> a.admin().comisiones().eliminar(r, a.usuario()),
                        this::alMostrar));
    }

    private void formulario(ComisionService.Regla r) {
        boolean nueva = r == null;
        Dialogo d = new Dialogo(nueva ? "Nueva regla de comisión" : "Editar regla", nueva ? null : r.nombre(),
                "mdi2h-hand-coin-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(660);
        TextField nombre = new TextField(nueva ? "" : r.nombre());
        nombre.setPromptText("Ej. Meta de queso Oaxaca");
        ComboBox<ComisionService.Tipo> tipo = new ComboBox<>(FXCollections.observableArrayList(ComisionService.Tipo.values()));
        tipo.setValue(nueva ? ComisionService.Tipo.META_CANTIDAD : r.tipo());
        List<ProductoCatalogo> productos = a.admin().productos().listar().stream().filter(ProductoCatalogo::activo).toList();
        ComboBox<ProductoCatalogo> producto = SelectorProducto.crear(productos);
        if (!nueva && r.productoId() != null) {
            productos.stream().filter(p -> p.id().equals(r.productoId())).findFirst().ifPresent(producto::setValue);
        }
        ComboBox<ComisionService.Medida> medida = new ComboBox<>(
                FXCollections.observableArrayList(ComisionService.Medida.values()));
        medida.setValue(nueva ? ComisionService.Medida.GRAMOS : r.medida());
        CampoMasa gramos = new CampoMasa();
        TextField cantidad = new TextField();
        Campos.soloNumeros(cantidad, 0);
        cantidad.setAlignment(Pos.CENTER_RIGHT);
        HBox piezas = Formulario.conUnidad(cantidad, "pzas");
        CampoDinero importeMeta = new CampoDinero().normal();
        CampoDinero comision = new CampoDinero().normal();
        ComboBox<ComisionService.Periodo> periodo = new ComboBox<>(FXCollections.observableArrayList(ComisionService.Periodo.values()));
        periodo.setValue(nueva ? ComisionService.Periodo.DIARIO : r.periodo());

        Usuario todos = new Usuario(null, null, "Todos los empleados", "", "", Rol.CAJERO, true, false, 0, null, null);
        List<Usuario> empleados = new ArrayList<>();
        empleados.add(todos);
        a.admin().usuarios().listar().stream().map(UsuarioService.Fila::usuario)
                .filter(u -> u.rol() != Rol.ADMINISTRADOR).forEach(empleados::add);
        ComboBox<Usuario> empleado = new ComboBox<>(FXCollections.observableArrayList(empleados));
        empleado.setConverter(Combos.usuario());
        empleado.setValue(nueva || r.usuarioId() == null ? todos
                : empleados.stream().filter(u -> r.usuarioId().equals(u.id())).findFirst().orElse(todos));
        Sucursal todas = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);
        List<Sucursal> sucursales = new ArrayList<>();
        sucursales.add(todas);
        sucursales.addAll(a.admin().sucursales().listar());
        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(sucursales));
        sucursal.setConverter(Combos.sucursal());
        sucursal.setValue(nueva || r.sucursalId() == null ? todas
                : sucursales.stream().filter(s -> r.sucursalId().equals(s.id())).findFirst().orElse(todas));
        ToggleSwitch activa = new ToggleSwitch("Regla activa");
        activa.setSelected(nueva || r.activo());

        Formulario.Campo campoNombre = Formulario.campo("Nombre", nombre);
        Formulario.Campo campoCantidad = Formulario.campo("Cantidad", new VBox());
        VBox contenedorCantidad = (VBox) campoCantidad.getChildren().get(1);
        Formulario.Campo campoProducto = Formulario.campo("Producto", producto);
        VBox bloqueProducto = new VBox(12, campoProducto,
                Formulario.fila(Formulario.campo("Medir en", medida), campoCantidad));
        Formulario.Campo campoImporte = Formulario.campo("Importe vendido para ganar la comisión", importeMeta);
        VBox bloqueImporte = new VBox(12, campoImporte);
        Formulario.Campo campoComision = Formulario.campo("Comisión", comision);
        // Explicación sencilla del tipo elegido, con un ejemplo
        Label explicacionTitulo = Ui.texto("", "explicacion-regla-titulo");
        Label explicacionTexto = Ui.texto("", "explicacion-regla-texto");
        Label explicacionEjemplo = Ui.texto("", "explicacion-regla-ejemplo");
        VBox explicacion = new VBox(6, explicacionTitulo, explicacionTexto, explicacionEjemplo);
        explicacion.getStyleClass().add("explicacion-regla");
        Runnable actualizar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            Unidad u = p == null ? Unidad.PZA : p.unidad();
            if (u == Unidad.KG && medida.getValue() == ComisionService.Medida.UNIDADES) {
                medida.setValue(ComisionService.Medida.GRAMOS);
            }
            boolean enGramos = medida.getValue() == ComisionService.Medida.GRAMOS;
            contenedorCantidad.getChildren().setAll(enGramos ? gramos : piezas);
            campoCantidad.ayuda(!enGramos || p == null ? null
                    : p.gramajeGramos() == null ? "Este producto aún no tiene gramaje: captúralo en Productos."
                    : "Cuenta el gramaje neto vendido: " + Masa.formatear(p.gramajeNeto())
                    + (u == Unidad.KG ? " por kilo" : " por pieza") + ".");
            boolean total = tipo.getValue() == ComisionService.Tipo.META_TOTAL;
            bloqueProducto.setVisible(!total);
            bloqueProducto.setManaged(!total);
            bloqueImporte.setVisible(total);
            bloqueImporte.setManaged(total);
            String enElPeriodo = switch (periodo.getValue()) {
                case DIARIO -> "en el día";
                case SEMANAL -> "en la semana";
                case MENSUAL -> "en el mes";
            };
            switch (tipo.getValue()) {
                case META_CANTIDAD -> {
                    explicacionTitulo.setText("Un premio si llega a la meta");
                    explicacionTexto.setText("Se paga una sola vez cuando el empleado vende la cantidad que pongas. "
                            + "Si vende de más, el premio es el mismo.");
                    explicacionEjemplo.setText("Ejemplo: «Si vendes 10 leches " + enElPeriodo + ", ganas $50».\n"
                            + "•  Vende 7 leches → $0 (no llegó)\n"
                            + "•  Vende 10 leches → $50\n"
                            + "•  Vende 30 leches → $50 (el premio no crece)");
                }
                case POR_CANTIDAD -> {
                    explicacionTitulo.setText("Se paga cada vez que junta la cantidad");
                    explicacionTexto.setText("Se paga la comisión por cada vez que el empleado junta la cantidad que "
                            + "pongas. Entre más vende, más gana.");
                    explicacionEjemplo.setText("Ejemplo: «Por cada 10 leches que vendas " + enElPeriodo
                            + ", ganas $50».\n"
                            + "•  Vende 7 leches → $0 (aún no junta 10)\n"
                            + "•  Vende 10 leches → $50 (juntó 10 una vez)\n"
                            + "•  Vende 30 leches → $150 (juntó 10 tres veces)");
                }
                case META_TOTAL -> {
                    explicacionTitulo.setText("Un premio si llega a cierto importe vendido");
                    explicacionTexto.setText("Se paga una sola vez cuando el empleado vende en total, de cualquier "
                            + "producto, el importe que pongas. Si vende de más, el premio es el mismo.");
                    explicacionEjemplo.setText("Ejemplo: «Si vendes $5,000 " + enElPeriodo + ", ganas $100».\n"
                            + "•  Vende $3,000 → $0 (no llegó)\n"
                            + "•  Vende $5,000 → $100\n"
                            + "•  Vende $9,000 → $100 (el premio no crece)");
                }
            }
        };
        tipo.valueProperty().addListener((o, x, y) -> actualizar.run());
        periodo.valueProperty().addListener((o, x, y) -> actualizar.run());
        medida.valueProperty().addListener((o, x, y) -> actualizar.run());
        producto.valueProperty().addListener((o, x, y) -> {
            campoProducto.setError(null);
            actualizar.run();
        });
        nombre.textProperty().addListener((o, x, y) -> campoNombre.setError(null));
        comision.textProperty().addListener((o, x, y) -> campoComision.setError(null));
        if (!nueva) {
            if (r.tipo() == ComisionService.Tipo.META_TOTAL) {
                importeMeta.setCentavos(r.importeMetaCentavos());
            } else if (r.medida() == ComisionService.Medida.GRAMOS) {
                gramos.setGramos(r.cantidadMeta());
            } else if (r.unidad() != null) {
                cantidad.setText(Cantidades.aCaptura(r.unidad(), r.cantidadMeta()));
            }
            comision.setCentavos(r.comisionCentavos());
        }
        actualizar.run();
        d.setContenido(Formulario.fila(campoNombre, Formulario.campo("Tipo", tipo)), explicacion,
                Formulario.seccionSeparada("Condición", null, bloqueProducto, bloqueImporte,
                        Formulario.fila(campoComision, Formulario.campo("Periodo", periodo))),
                Formulario.seccionSeparada("A quién aplica", null,
                        Formulario.fila(Formulario.campo("Empleado", empleado), Formulario.campo("Sucursal", sucursal)),
                        activa));
        Runnable guardar = () -> {
            boolean valido = true;
            if (nombre.getText() == null || nombre.getText().isBlank()) {
                campoNombre.setError("Escribe el nombre de la regla.");
                valido = false;
            }
            ProductoCatalogo p = SelectorProducto.valor(producto);
            if (tipo.getValue() != ComisionService.Tipo.META_TOTAL && p == null) {
                campoProducto.setError("Elige el producto.");
                valido = false;
            }
            if (comision.centavosOCero() <= 0) {
                campoComision.setError("Escribe cuánto se paga.");
                valido = false;
            }
            if (!valido) {
                return;
            }
            BigDecimal meta = medida.getValue() == ComisionService.Medida.GRAMOS ? gramos.gramos().orElse(null)
                    : p == null ? null : Cantidades.desdeCaptura(p.unidad(), cantidad.getText()).orElse(null);
            var regla = new ComisionService.Regla(nueva ? null : r.id(), nueva ? null : r.folio(), nombre.getText(),
                    tipo.getValue(), medida.getValue(), p == null ? null : p.id(), p == null ? null : p.nombre(),
                    p == null ? null : p.unidad(), meta, importeMeta.centavosOCero(), comision.centavosOCero(),
                    periodo.getValue(), empleado.getValue().id(), null, sucursal.getValue().id(), null, activa.isSelected());
            a.guardar(d, (nueva ? "Regla creada: " : "Regla actualizada: ") + nombre.getText().strip(),
                    () -> a.admin().comisiones().guardar(regla, a.usuario()), this::alMostrar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton(nueva ? "Crear regla" : "Guardar cambios", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(nombre);
        d.vigilarCambios(nombre, tipo, producto, medida, gramos, cantidad, importeMeta, comision, periodo, empleado,
                sucursal, activa);
        a.dialogos().mostrar(d);
    }
}
