package com.cremerias.puntoventa.ui.admin;

import atlantafx.base.controls.ToggleSwitch;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.ComisionService;
import com.cremerias.puntoventa.service.admin.UsuarioService;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.CampoMasa;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.DialogoConfirmacion;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reglas de comisión y comisiones ganadas por los empleados. */
public class ComisionesPagina extends Pagina {

    private TableView<ComisionService.Regla> reglas;
    private TableView<ComisionService.Ganada> ganadas;
    private DatePicker desde;
    private DatePicker hasta;
    private FlowPane totales;

    public ComisionesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        var nueva = Ui.principal("Nueva regla", "mdi2p-plus");
        nueva.setOnAction(e -> formulario(null));
        VBox pagina = Ui.pagina("Comisiones",
                "Por alcanzar una cantidad de un producto (en gramos o piezas), un total vendido, o por cada cantidad vendida.",
                nueva);
        reglas = Ui.tabla("No hay reglas de comisión.");
        reglas.setPrefHeight(260);
        VBox.setVgrow(reglas, Priority.NEVER);
        reglas.getColumns().setAll(List.of(
                Ui.nodo("Regla", 220, r -> Ui.dosLineas(r.nombre(), r.tipo().nombre())),
                Ui.texto("Condición", 330, ComisionService.Regla::descripcion),
                Ui.texto("Periodo", 90, r -> r.periodo().nombre()),
                Ui.texto("Aplica a", 200, r -> (r.usuario() == null ? "Todos los empleados" : r.usuario())
                        + (r.sucursal() == null ? "" : " · " + r.sucursal())),
                Ui.nodo("Estado", 100, r -> r.activo() ? Ui.chip("Activa", "exito") : Ui.chip("Inactiva", "neutro")),
                Ui.texto("Folio", 140, ComisionService.Regla::folio),
                Ui.nodo("", 90, r -> Ui.acciones(
                        Ui.accion("mdi2p-pencil-outline", "Editar", () -> formulario(r)),
                        Ui.accion("mdi2t-trash-can-outline", "Eliminar", () -> eliminar(r), "danger")))));

        desde = new DatePicker(LocalDate.now().withDayOfMonth(1));
        hasta = new DatePicker(LocalDate.now());
        desde.setEditable(false);
        hasta.setEditable(false);
        Button calcular = Ui.principal("Calcular", "mdi2h-hand-coin-outline");
        calcular.setOnAction(e -> calcular());
        HBox filtros = new HBox(10, Ui.campo("Desde", desde), Ui.campo("Hasta", hasta), calcular);
        filtros.setAlignment(Pos.BOTTOM_LEFT);
        totales = new FlowPane(10, 10);
        ganadas = Ui.tabla("Elige las fechas y presiona «Calcular».");
        ganadas.setPrefHeight(320);
        ganadas.getColumns().setAll(List.of(
                Ui.texto("Empleado", 200, ComisionService.Ganada::usuario),
                Ui.texto("Regla", 200, g -> g.regla().nombre()),
                Ui.texto("Periodo", 220, ComisionService.Ganada::periodo),
                Ui.texto("Logrado", 150, ComisionService.Ganada::logrado),
                Ui.dinero("Comisión", 130, ComisionService.Ganada::comisionCentavos)));

        pagina.getChildren().addAll(reglas, Ui.tarjeta("Comisiones ganadas", filtros, totales, ganadas));
        ScrollPane scroll = new ScrollPane(pagina);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("cortes-scroll");
        return scroll;
    }

    @Override
    public void alMostrar() {
        reglas.setItems(FXCollections.observableArrayList(a.admin().comisiones().reglas()));
    }

    private void calcular() {
        if (desde.getValue() == null || hasta.getValue() == null || hasta.getValue().isBefore(desde.getValue())) {
            a.avisos().error("Revisa las fechas.");
            return;
        }
        List<ComisionService.Ganada> lista = a.admin().comisiones().calcular(desde.getValue(), hasta.getValue());
        ganadas.setItems(FXCollections.observableArrayList(lista));
        Map<String, Long> porEmpleado = new LinkedHashMap<>();
        for (ComisionService.Ganada g : lista) {
            porEmpleado.merge(g.usuario(), g.comisionCentavos(), Long::sum);
        }
        totales.getChildren().clear();
        porEmpleado.forEach((usuario, total) -> totales.getChildren().add(Ui.chip(usuario + ": " + Dinero.formatear(total), "acento")));
        if (porEmpleado.isEmpty()) {
            totales.getChildren().add(Ui.texto("Nadie alcanzó comisión en estas fechas.", "texto-ayuda"));
        }
    }

    private void eliminar(ComisionService.Regla r) {
        DialogoConfirmacion.mostrar(a.dialogos(), "¿Eliminar la regla «" + r.nombre() + "»?",
                "Dejará de calcularse. Queda registrada en el historial.", "Eliminar", true, () -> {
                    a.ejecutar("Regla eliminada", () -> a.admin().comisiones().eliminar(r, a.usuario()));
                    alMostrar();
                });
    }

    private void formulario(ComisionService.Regla r) {
        boolean nueva = r == null;
        Dialogo d = new Dialogo(nueva ? "Nueva regla de comisión" : "Editar regla", nueva ? null : r.nombre(),
                "mdi2h-hand-coin-outline", Dialogo.Tono.ACENTO);
        d.setPrefWidth(620);
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
        Label unidad = new Label("pzas");
        HBox piezas = new HBox(8, cantidad, unidad);
        piezas.setAlignment(Pos.CENTER_LEFT);
        CampoDinero importeMeta = new CampoDinero();
        CampoDinero comision = new CampoDinero();
        ComboBox<ComisionService.Periodo> periodo = new ComboBox<>(FXCollections.observableArrayList(ComisionService.Periodo.values()));
        periodo.setValue(nueva ? ComisionService.Periodo.DIARIO : r.periodo());

        Usuario todos = new Usuario(null, null, "Todos los empleados", "", "", Rol.CAJERO, true, false, 0, null, null);
        List<Usuario> empleados = new ArrayList<>();
        empleados.add(todos);
        a.admin().usuarios().listar().stream().map(UsuarioService.Fila::usuario)
                .filter(u -> u.rol() != Rol.ADMINISTRADOR).forEach(empleados::add);
        ComboBox<Usuario> empleado = new ComboBox<>(FXCollections.observableArrayList(empleados));
        empleado.setConverter(new StringConverter<>() {
            @Override
            public String toString(Usuario u) {
                return u == null ? "" : u.nombreCompleto();
            }

            @Override
            public Usuario fromString(String s) {
                return null;
            }
        });
        empleado.setValue(nueva || r.usuarioId() == null ? todos
                : empleados.stream().filter(u -> r.usuarioId().equals(u.id())).findFirst().orElse(todos));
        Sucursal todas = new Sucursal(null, "", "Todas las sucursales", null, null, true, false);
        List<Sucursal> sucursales = new ArrayList<>();
        sucursales.add(todas);
        sucursales.addAll(a.admin().sucursales().listar());
        ComboBox<Sucursal> sucursal = new ComboBox<>(FXCollections.observableArrayList(sucursales));
        sucursal.setValue(nueva || r.sucursalId() == null ? todas
                : sucursales.stream().filter(s -> r.sucursalId().equals(s.id())).findFirst().orElse(todas));
        ToggleSwitch activa = new ToggleSwitch("Regla activa");
        activa.setSelected(nueva || r.activo());

        Label ayudaGramaje = Ui.texto("", "texto-ayuda");
        VBox campoCantidad = new VBox(6);
        VBox bloqueProducto = new VBox(12, Ui.campo("Producto", producto),
                Ui.fila(Ui.campo("Medir en", medida), Ui.campo("Cantidad", campoCantidad)), ayudaGramaje);
        VBox bloqueImporte = new VBox(12, Ui.campo("Importe vendido para ganar la comisión", importeMeta));
        Label explicacion = Ui.texto("", "texto-ayuda");
        Runnable actualizar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            Unidad u = p == null ? Unidad.PZA : p.unidad();
            if (u == Unidad.KG && medida.getValue() == ComisionService.Medida.UNIDADES) {
                medida.setValue(ComisionService.Medida.GRAMOS);
            }
            boolean enGramos = medida.getValue() == ComisionService.Medida.GRAMOS;
            campoCantidad.getChildren().setAll(enGramos ? gramos : piezas);
            ayudaGramaje.setText(!enGramos || p == null ? ""
                    : p.gramajeGramos() == null ? "Este producto aún no tiene gramaje: captúralo en Productos."
                    : "Cuenta el gramaje neto vendido: " + Masa.formatear(p.gramajeNeto())
                    + (u == Unidad.KG ? " por kilo" : " por pieza") + ".");
            boolean total = tipo.getValue() == ComisionService.Tipo.META_TOTAL;
            bloqueProducto.setVisible(!total);
            bloqueProducto.setManaged(!total);
            bloqueImporte.setVisible(total);
            bloqueImporte.setManaged(total);
            explicacion.setText(switch (tipo.getValue()) {
                case META_CANTIDAD -> "Gana la comisión una vez por periodo al vender esa cantidad del producto.";
                case META_TOTAL -> "Gana la comisión una vez por periodo al vender ese importe en total.";
                case POR_CANTIDAD -> "Gana la comisión por cada vez que junta esa cantidad vendida del producto en el periodo.";
            });
        };
        tipo.valueProperty().addListener((o, x, y) -> actualizar.run());
        medida.valueProperty().addListener((o, x, y) -> actualizar.run());
        producto.valueProperty().addListener((o, x, y) -> actualizar.run());
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
        d.setContenido(Ui.fila(Ui.campo("Nombre", nombre), Ui.campo("Tipo", tipo)), explicacion, bloqueProducto,
                bloqueImporte, Ui.fila(Ui.campo("Comisión", comision), Ui.campo("Periodo", periodo)),
                Ui.fila(Ui.campo("Empleado", empleado), Ui.campo("Sucursal", sucursal)), activa);
        Runnable guardar = () -> {
            ProductoCatalogo p = SelectorProducto.valor(producto);
            BigDecimal meta = medida.getValue() == ComisionService.Medida.GRAMOS ? gramos.gramos().orElse(null)
                    : p == null ? null : Cantidades.desdeCaptura(p.unidad(), cantidad.getText()).orElse(null);
            var regla = new ComisionService.Regla(nueva ? null : r.id(), nueva ? null : r.folio(), nombre.getText(),
                    tipo.getValue(), medida.getValue(), p == null ? null : p.id(), p == null ? null : p.nombre(),
                    p == null ? null : p.unidad(), meta, importeMeta.centavosOCero(), comision.centavosOCero(),
                    periodo.getValue(), empleado.getValue().id(), null, sucursal.getValue().id(), null, activa.isSelected());
            if (a.ejecutar(nueva ? "Regla creada" : "Regla actualizada", () -> a.admin().comisiones().guardar(regla, a.usuario()))) {
                d.cerrar();
                alMostrar();
            }
        };
        d.agregarBoton("Cancelar", null, d::cerrar, "flat");
        d.agregarBoton("Guardar regla", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setFocoInicial(nombre);
        a.dialogos().mostrar(d);
    }
}
