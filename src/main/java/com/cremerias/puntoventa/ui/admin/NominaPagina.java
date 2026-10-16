package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Nomina;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.NominaService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.CampoDinero;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Salario y comida de cada empleado por semana, por sucursal. */
public class NominaPagina extends Pagina {

    private static final DateTimeFormatter SEMANA = DateTimeFormatter.ofPattern("d MMM yyyy", Formatos.ES);

    private final Cargador carga = new Cargador();
    private BarraHerramientas barra;
    private ComboBox<Sucursal> sucursal;
    private ComboBox<YearMonth> mes;
    private Button capturar;
    private TablaDatos<Nomina> tabla;
    private ResumenMetricas.Metrica metricaTotal;
    private ResumenMetricas.Metrica metricaSalario;
    private ResumenMetricas.Metrica metricaComida;

    public NominaPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Nómina";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        capturar = Ui.principal("Capturar nómina", "mdi2p-plus");
        capturar.setOnAction(e -> formulario(null));
        capturar.setDisable(true);
        VBox pagina = Ui.pagina("Nómina", "Salario y comida de cada empleado por semana (de lunes a domingo).", capturar);

        ResumenMetricas resumen = new ResumenMetricas();
        metricaTotal = resumen.agregar("Total del mes").principal();
        metricaSalario = resumen.agregar("Salarios");
        metricaComida = resumen.agregar("Comida");

        sucursal = new ComboBox<>();
        sucursal.setConverter(Combos.sucursal());
        sucursal.setPromptText("Elige la sucursal");
        sucursal.setAccessibleText("Sucursal");
        sucursal.valueProperty().addListener((o, x, y) -> cargar());
        mes = new ComboBox<>(FXCollections.observableArrayList(
                IntStream.rangeClosed(0, 11).mapToObj(i -> YearMonth.now().minusMonths(i)).toList()));
        mes.setValue(YearMonth.now());
        mes.setConverter(Combos.mes());
        mes.setAccessibleText("Mes");
        mes.valueProperty().addListener((o, x, y) -> cargar());

        barra = new BarraHerramientas();
        barra.agregar(sucursal);
        barra.agregar(mes);
        barra.buscador("Buscar empleado");
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(n -> n.usuarioId() + "/" + n.semanaInicio());
        tabla.texto("Empleado", Nomina::usuario).flexible(220);
        tabla.formato("Semana del", Nomina::semanaInicio, (n, v) -> SEMANA.format(v)).ancho(150);
        tabla.dinero("Salario", Nomina::salarioCentavos).ancho(130);
        tabla.dinero("Comida", Nomina::comidaCentavos).ancho(130);
        tabla.dinero("Total", Nomina::total).ancho(130).clase("columna-fuerte");
        tabla.acciones(Accion.<Nomina>boton("mdi2p-pencil-outline", "Editar", this::formulario));
        tabla.setVacio(new EstadoVacio("mdi2a-account-cash-outline", "No hay nómina capturada en este mes.",
                "Captura el salario y la comida de cada empleado por semana.")
                .accion("Capturar nómina", "mdi2p-plus", () -> formulario(null)));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos a ese empleado en la nómina del mes.", barra::limpiar));
        tabla.setAlActivar(this::formulario);

        VBox panel = Ui.panelTabla(barra, tabla);
        pagina.getChildren().addAll(resumen, panel);
        return Ui.ajustable(pagina, panel, 300);
    }

    @Override
    public void alMostrar() {
        Cargador.ejecutar(() -> a.admin().sucursales().listar(), lista -> {
            Sucursal actual = sucursal.getValue();
            sucursal.setItems(FXCollections.observableArrayList(lista));
            Sucursal elegida = actual == null ? (lista.isEmpty() ? null : lista.getFirst())
                    : lista.stream().filter(s -> s.id().equals(actual.id())).findFirst().orElse(null);
            if (sucursal.getValue() == elegida) {
                cargar();
            } else {
                sucursal.setValue(elegida);
            }
        }, e -> tabla.error("No se pudieron cargar las sucursales.", AdminContexto.mensaje(e), this::alMostrar));
    }

    private void cargar() {
        capturar.setDisable(sucursal.getValue() == null);
        Sucursal s = sucursal.getValue();
        YearMonth m = mes.getValue();
        if (s == null || m == null) {
            return;
        }
        tabla.cargando("Cargando nómina…");
        carga.cargar(() -> a.admin().nomina().listar(a.usuario(), s.id(), m), lista -> {
            tabla.setDatos(lista);
            long salario = lista.stream().mapToLong(Nomina::salarioCentavos).sum();
            long comida = lista.stream().mapToLong(Nomina::comidaCentavos).sum();
            long empleados = lista.stream().map(Nomina::usuarioId).distinct().count();
            metricaTotal.setValor(Dinero.formatear(salario + comida))
                    .setContexto(Combos.mes().toString(m) + " · " + Texto.plural(empleados, "empleado", "empleados"));
            metricaSalario.setValor(Dinero.formatear(salario)).setContexto(null);
            metricaComida.setValor(Dinero.formatear(comida)).setContexto(null);
            filtrar();
        }, e -> tabla.error("No se pudo cargar la nómina.", AdminContexto.mensaje(e), this::cargar));
    }

    private void filtrar() {
        String t = barra.busqueda();
        tabla.setFiltro(n -> t.isEmpty() || Texto.contiene(n.usuario(), t));
        barra.setResultados(Texto.plural(tabla.mostrados(), "registro", "registros") + " · "
                + Dinero.formatear(tabla.visibles().stream().mapToLong(Nomina::total).sum()));
    }

    private List<Usuario> empleadosDeSucursal(String sucursalId) {
        return a.admin().usuarios().listar().stream()
                .map(f -> f.usuario())
                .filter(u -> u.rol() != Rol.ADMINISTRADOR && sucursalId.equals(u.sucursalId()))
                .toList();
    }

    private void formulario(Nomina n) {
        boolean nueva = n == null;
        Sucursal s = sucursal.getValue();
        if (s == null) {
            return;
        }
        List<Usuario> empleados = empleadosDeSucursal(s.id());
        List<LocalDate> semanas = NominaService.semanasDelMes(mes.getValue());

        Dialogo d = new Dialogo(nueva ? "Capturar nómina" : "Editar nómina", Formatos.sucursal(s), "mdi2a-account-cash-outline",
                Dialogo.Tono.ACENTO);
        d.setPrefWidth(560);
        ComboBox<Usuario> empleado = new ComboBox<>(FXCollections.observableArrayList(empleados));
        empleado.setConverter(Combos.usuario());
        ComboBox<LocalDate> semana = new ComboBox<>(FXCollections.observableArrayList(semanas));
        semana.setConverter(Combos.de(f -> "Semana del " + SEMANA.format(f)));
        CampoDinero salario = new CampoDinero().normal();
        CampoDinero comida = new CampoDinero().normal();
        Label total = new Label("$0.00");
        Formulario.Campo campoEmpleado = Formulario.campo("Empleado", empleado);
        Label existente = Ui.texto("", "campo-ayuda");
        existente.managedProperty().bind(existente.visibleProperty());
        existente.setVisible(false);

        Runnable sumar = () -> total.setText(Dinero.formatear(salario.centavosOCero() + comida.centavosOCero()));
        salario.textProperty().addListener((o, x, y) -> sumar.run());
        comida.textProperty().addListener((o, x, y) -> sumar.run());

        Runnable precargar = () -> {
            Usuario u = empleado.getValue();
            LocalDate sem = semana.getValue();
            if (u == null || sem == null) {
                return;
            }
            Optional<Nomina> ya = a.admin().nomina().capturada(a.usuario(), s.id(), u.id(), sem);
            salario.setCentavos(ya.map(Nomina::salarioCentavos).orElse(0L));
            comida.setCentavos(ya.map(Nomina::comidaCentavos).orElse(0L));
            existente.setText(ya.isPresent() ? "Esa semana ya estaba capturada: al guardar se reemplaza." : "");
            existente.setVisible(ya.isPresent());
        };

        if (nueva) {
            empleado.valueProperty().addListener((o, x, y) -> {
                campoEmpleado.setError(null);
                precargar.run();
            });
            semana.valueProperty().addListener((o, x, y) -> precargar.run());
            if (!empleados.isEmpty()) {
                empleado.setValue(empleados.getFirst());
            }
            if (!semanas.isEmpty()) {
                semana.setValue(semanas.getFirst());
            }
        } else {
            empleados.stream().filter(u -> u.id().equals(n.usuarioId())).findFirst().ifPresent(empleado::setValue);
            semana.setValue(n.semanaInicio());
            empleado.setDisable(true);
            semana.setDisable(true);
            salario.setCentavos(n.salarioCentavos());
            comida.setCentavos(n.comidaCentavos());
        }
        sumar.run();
        if (empleados.isEmpty()) {
            campoEmpleado.setError("Esta sucursal no tiene empleados: dalos de alta en Usuarios y horarios.");
        }
        d.setContenido(Formulario.fila(campoEmpleado, Formulario.campo("Semana", semana)), existente,
                Formulario.fila(Formulario.campo("Salario", salario), Formulario.campo("Comida", comida)),
                Formulario.resumen("Total de la semana", total));

        Runnable guardar = () -> {
            Usuario u = empleado.getValue();
            LocalDate sem = semana.getValue();
            if (u == null) {
                campoEmpleado.setError("Elige al empleado.");
                return;
            }
            long sal = salario.centavosOCero();
            long com = comida.centavosOCero();
            a.guardar(d, (nueva ? "Nómina capturada: " : "Nómina actualizada: ") + u.nombreCompleto(),
                    () -> a.admin().nomina().guardar(a.usuario(), s.id(), u.id(), sem, sal, com), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Guardar", "mdi2c-check", guardar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(salario);
        d.vigilarCambios(empleado, semana, salario, comida);
        a.dialogos().mostrar(d);
    }
}
