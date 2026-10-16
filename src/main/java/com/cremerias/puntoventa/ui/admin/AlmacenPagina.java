package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.service.InventarioSucursalService;
import com.cremerias.puntoventa.service.admin.AlmacenService;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Campos;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.Formulario;
import com.cremerias.puntoventa.ui.componentes.MaestroDetalle;
import com.cremerias.puntoventa.ui.componentes.PanelDetalle;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.TablaDatos.Accion;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Inventario del almacén central por lotes. Pantalla de referencia del sistema de diseño:
 * resumen compacto, tabla con búsqueda y filtros, y detalle del producto elegido con sus
 * presentaciones, lotes y últimos movimientos. Registrar entrada, ver lotes y registrar merma.
 */
public class AlmacenPagina extends Pagina {

    /** Filtro de estado de la tabla. */
    private enum Estado {
        TODOS("Todos los estados"), CON_EXISTENCIA("Con existencia"), AGOTADOS("Agotados"),
        VARIOS_LOTES("Con más de un lote");

        final String texto;

        Estado(String texto) {
            this.texto = texto;
        }
    }

    /** Lo que se señala en la columna de estado (solo excepciones; lo normal va sin etiqueta). */
    private enum Situacion {
        AGOTADO("Agotado", Etiqueta.Tono.PELIGRO), DESHABILITADO("Deshabilitado", Etiqueta.Tono.NEUTRO),
        NORMAL(null, Etiqueta.Tono.NEUTRO);

        final String texto;
        final Etiqueta.Tono tono;

        Situacion(String texto, Etiqueta.Tono tono) {
            this.texto = texto;
            this.tono = tono;
        }
    }

    /** Lotes y movimientos del producto en el detalle (se cargan aparte). */
    private record DatosDetalle(List<LoteRepository.Lote> lotes, List<InventarioSucursalService.Movimiento> movimientos) {
    }

    private static final String TODAS = "Todas las categorías";
    private static final int LOTES_EN_DETALLE = 4;

    private final Cargador carga = new Cargador();
    private final Cargador cargaDetalle = new Cargador();
    private TablaDatos<AlmacenService.Existencia> tabla;
    private BarraHerramientas barra;
    private ComboBox<Estado> estado;
    private ComboBox<String> categoria;
    private ResumenMetricas.Metrica metricaValor;
    private ResumenMetricas.Metrica metricaConExistencia;
    private ResumenMetricas.Metrica metricaAgotados;
    private PanelDetalle detalle;
    private MaestroDetalle maestro;
    private Map<String, BigDecimal> existencias = Map.of();

    public AlmacenPagina(AdminContexto a) {
        super(a);
    }

    @Override
    public String titulo() {
        return "Inventario del almacén";
    }

    @Override
    public TextField busqueda() {
        return barra.campoBusqueda();
    }

    @Override
    protected Node construir() {
        Button registrar = Ui.principal("Registrar entrada", "mdi2t-tray-arrow-down");
        registrar.setOnAction(e -> registrarEntrada(null));
        VBox pagina = Ui.pagina("Inventario del almacén",
                "Existencias por lote, valuadas al costo. Al surtir sale primero el lote más antiguo.", registrar);

        ResumenMetricas resumen = new ResumenMetricas();
        metricaValor = resumen.agregar("Valor del inventario").principal();
        metricaConExistencia = resumen.agregar("Con existencia");
        metricaAgotados = resumen.agregar("Agotados");

        barra = new BarraHerramientas();
        barra.buscador("Buscar producto, clave o código");
        estado = barra.filtro("Estado", new ComboBox<>(FXCollections.observableArrayList(Estado.values())),
                Estado.TODOS, e -> e.texto);
        estado.setValue(Estado.TODOS);
        categoria = barra.filtro("Categoría", new ComboBox<>(FXCollections.observableArrayList(TODAS)), TODAS, c -> c);
        categoria.setValue(TODAS);
        barra.setAlCambiar(this::filtrar);

        tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
        tabla.setClave(e -> e.producto().id());
        tabla.dosLineas("Producto", e -> e.producto().nombre(), e -> Formatos.codigos(e.producto(), true)).flexible(220);
        tabla.cantidad("Existencia", AlmacenService.Existencia::existencia, e -> e.producto().unidad()).ancho(120);
        tabla.numero("Lotes", AlmacenService.Existencia::lotesConExistencia, String::valueOf).ancho(76)
                .ayuda("Lotes con existencia");
        tabla.formato("Costo actual", AlmacenService.Existencia::costoActualCentavos,
                        (e, v) -> e.lotesConExistencia() == 0 ? "—" : Dinero.formatear(v) + Formatos.porUnidad(e.producto().unidad()))
                .numero().ancho(136)
                .ayuda("Costo del lote más antiguo con existencia: es el que sale primero.");
        tabla.dinero("Valor", AlmacenService.Existencia::valorCentavos).ancho(124)
                .ayuda("Existencia de cada lote por su costo.");
        var columnaEstado = tabla.etiqueta("Estado", this::situacion, s -> s.texto, s -> s.tono).ancho(130);
        var columnaAcciones = tabla.acciones(
                Accion.<AlmacenService.Existencia>boton("mdi2t-tray-arrow-down", "Registrar entrada",
                        e -> registrarEntrada(e.producto())),
                Accion.<AlmacenService.Existencia>boton("mdi2l-layers-outline", "Ver lotes", e -> lotes(e.producto())),
                Accion.<AlmacenService.Existencia>menu("mdi2d-delete-variant", e -> "Registrar merma", e -> merma(e.producto()))
                        .si(e -> e.producto().sujetoMerma()).peligrosa());
        tabla.setVacio(new EstadoVacio("mdi2p-package-variant", "Aún no hay productos en el catálogo.",
                "Da de alta los productos para registrar sus entradas al almacén.")
                .accion("Ir a Productos", "mdi2a-arrow-right", () -> a.irA().accept("productos")));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos productos con estos filtros.", barra::limpiar));
        tabla.setAlActivar(e -> {
            mostrarDetalle(e);
            maestro.abrir();
        });
        tabla.seleccionadoProperty().addListener((o, x, e) -> {
            if (e != null && maestro.estaAbierto()) {
                mostrarDetalle(e);
            }
        });

        detalle = new PanelDetalle();
        maestro = new MaestroDetalle(Ui.panelTabla(barra, tabla), detalle, 640);
        maestro.setAlCerrar(() -> {
            cargaDetalle.cancelar();
            tabla.tabla().requestFocus();
        });
        // Si con el detalle acoplado no cabe todo, se quitan las acciones y el estado: ya están en el panel.
        maestro.setAnchoCompleto(tabla.anchoNecesario());
        maestro.tablaReducidaProperty().addListener((o, x, reducida) -> {
            columnaEstado.columna().setVisible(!reducida);
            columnaAcciones.columna().setVisible(!reducida);
        });
        pagina.getChildren().addAll(resumen, maestro);
        return Ui.ajustable(pagina, maestro, 320);
    }

    @Override
    public void alMostrar() {
        cargar();
    }

    private void cargar() {
        tabla.cargando("Cargando inventario…");
        carga.cargar(() -> a.admin().almacen().existencias(), lista -> {
            existencias = lista.stream().collect(Collectors.toMap(e -> e.producto().id(),
                    AlmacenService.Existencia::existencia, (x, y) -> x));
            actualizarCategorias(lista);
            actualizarResumen(lista);
            tabla.setDatos(lista.stream().filter(e -> e.producto().activo() || e.existencia().signum() > 0).toList());
            filtrar();
            if (maestro.estaAbierto() && tabla.seleccionado() != null) {
                mostrarDetalle(tabla.seleccionado());
            }
        }, e -> {
            if (tabla.sinDatos()) {
                tabla.error("No se pudo cargar el inventario.", AdminContexto.mensaje(e), this::cargar);
            } else {
                a.avisos().errorConAccion("No se pudo actualizar el inventario: " + AdminContexto.mensaje(e),
                        "Reintentar", this::cargar);
            }
        });
    }

    private void actualizarCategorias(List<AlmacenService.Existencia> lista) {
        List<String> categorias = new ArrayList<>();
        categorias.add(TODAS);
        lista.stream().map(e -> e.producto().categoria()).filter(c -> c != null && !c.isBlank()).distinct()
                .sorted(Texto.ORDEN).forEach(categorias::add);
        if (!categorias.equals(categoria.getItems())) {
            String actual = categoria.getValue();
            categoria.setItems(FXCollections.observableArrayList(categorias));
            categoria.setValue(categorias.contains(actual) ? actual : TODAS);
        }
    }

    private void actualizarResumen(List<AlmacenService.Existencia> lista) {
        long valor = lista.stream().mapToLong(AlmacenService.Existencia::valorCentavos).sum();
        int lotes = lista.stream().mapToInt(AlmacenService.Existencia::lotesConExistencia).sum();
        long activos = lista.stream().filter(e -> e.producto().activo()).count();
        long conExistencia = lista.stream().filter(e -> e.producto().activo() && e.existencia().signum() > 0).count();
        long agotados = lista.stream().filter(e -> e.producto().activo() && e.existencia().signum() <= 0).count();
        metricaValor.setValor(Dinero.formatear(valor)).setContexto("Al costo · " + Texto.plural(lotes, "lote", "lotes")
                + " con existencia");
        metricaConExistencia.setValor(String.valueOf(conExistencia))
                .setContexto("De " + Texto.plural(activos, "producto activo", "productos activos"));
        metricaAgotados.setValor(String.valueOf(agotados))
                .setContexto(agotados == 0 ? "Todos los activos tienen existencia" : "Activos sin existencia en el almacén")
                .setTono(agotados > 0 ? Etiqueta.Tono.PELIGRO : null);
    }

    private void filtrar() {
        String t = barra.busqueda();
        Estado e = estado.getValue() == null ? Estado.TODOS : estado.getValue();
        String cat = categoria.getValue() == null ? TODAS : categoria.getValue();
        tabla.setFiltro(x -> coincide(x.producto(), t)
                && switch (e) {
                    case TODOS -> true;
                    case CON_EXISTENCIA -> x.existencia().signum() > 0;
                    case AGOTADOS -> x.existencia().signum() <= 0;
                    case VARIOS_LOTES -> x.lotesConExistencia() > 1;
                }
                && (TODAS.equals(cat) || cat.equals(x.producto().categoria())));
        long valor = tabla.visibles().stream().mapToLong(AlmacenService.Existencia::valorCentavos).sum();
        barra.setResultados(tabla.mostrados() == tabla.total()
                ? Texto.plural(tabla.total(), "producto", "productos")
                : tabla.mostrados() + " de " + Texto.plural(tabla.total(), "producto", "productos")
                + " · " + Dinero.formatear(valor));
    }

    static boolean coincide(ProductoCatalogo p, String busqueda) {
        if (busqueda.isEmpty()) {
            return true;
        }
        return Texto.contiene(p.nombre(), busqueda) || Texto.empieza(p.clave(), busqueda)
                || Texto.empieza(p.codigoInventario(), busqueda) || Texto.contiene(p.marca(), busqueda)
                || p.presentaciones().stream().anyMatch(pr -> Texto.empieza(pr.codigoBarras(), busqueda));
    }

    private Situacion situacion(AlmacenService.Existencia e) {
        if (!e.producto().activo()) {
            return Situacion.DESHABILITADO;
        }
        return e.existencia().signum() <= 0 ? Situacion.AGOTADO : Situacion.NORMAL;
    }

    // ------------------------------------------------------------------ detalle

    private void mostrarDetalle(AlmacenService.Existencia e) {
        ProductoCatalogo p = e.producto();
        Unidad u = p.unidad();
        detalle.setTitulo(p.nombre(), Formatos.codigos(p, true)
                + (p.marca() == null || p.marca().isBlank() ? "" : " · " + p.marca()));
        List<Node> etiquetas = new ArrayList<>();
        etiquetas.add(Etiqueta.simple(Formatos.seVende(u), Etiqueta.Tono.NEUTRO));
        Situacion s = situacion(e);
        if (s.texto != null) {
            etiquetas.add(Etiqueta.de(s.texto, s.tono));
        }
        if (p.sujetoMerma()) {
            etiquetas.add(Etiqueta.simple("Sujeto a merma", Etiqueta.Tono.NEUTRO));
        }
        detalle.setEtiquetas(etiquetas.toArray(Node[]::new));

        String[] partes = Formatos.cantidadPartida(u, e.existencia());
        VBox existencia = PanelDetalle.cifra("Existencia en el almacén", partes[0], partes[1]);
        VBox datos = new VBox(6,
                PanelDetalle.dato("Valor al costo", Dinero.formatear(e.valorCentavos())),
                PanelDetalle.dato("Costo del lote actual", e.lotesConExistencia() == 0 ? "—"
                        : Dinero.formatear(e.costoActualCentavos()) + Formatos.porUnidad(u)),
                PanelDetalle.dato("Lotes con existencia", String.valueOf(e.lotesConExistencia())));

        List<Node> filasPresentacion = new ArrayList<>();
        for (Presentacion pr : p.presentaciones()) {
            String contenido = pr.granel() ? "A granel, por kilo" : "Contiene " + Cantidades.formatear(u, pr.factor());
            String codigo = pr.codigoBarras() == null || pr.codigoBarras().isBlank() ? null : pr.codigoBarras();
            Node marca = !pr.activo() ? Etiqueta.simple("Inactiva", Etiqueta.Tono.NEUTRO)
                    : pr.principal() ? Etiqueta.simple("Principal", Etiqueta.Tono.ACENTO) : null;
            filasPresentacion.add(PanelDetalle.filaLista(pr.nombre(), codigo == null ? contenido : contenido + " · " + codigo,
                    null, marca));
        }
        VBox presentaciones = PanelDetalle.seccion("Presentaciones", filasPresentacion.isEmpty()
                ? Ui.texto("Sin presentaciones.", "texto-secundario") : PanelDetalle.lista(filasPresentacion));

        VBox lotes = PanelDetalle.seccion("Lotes con existencia", Ui.texto("Cargando lotes…", "texto-secundario"));
        VBox movimientos = PanelDetalle.seccion("Últimos movimientos", Ui.texto("Cargando movimientos…", "texto-secundario"));
        detalle.setContenido(existencia, datos, presentaciones, lotes, movimientos);

        Button entrada = Ui.principal("Registrar entrada", "mdi2t-tray-arrow-down");
        entrada.setOnAction(x -> registrarEntrada(p));
        Button verLotes = Ui.secundario("Ver lotes", "mdi2l-layers-outline");
        verLotes.setOnAction(x -> lotes(p));
        List<Node> acciones = new ArrayList<>(List.of(entrada, verLotes));
        if (p.sujetoMerma()) {
            Button merma = Ui.boton("Merma", "mdi2d-delete-variant", "flat", "danger");
            merma.setOnAction(x -> merma(p));
            acciones.add(merma);
        }
        detalle.setAcciones(acciones.toArray(Node[]::new));

        String almacen = a.admin().almacen().almacenId();
        cargaDetalle.cargar(() -> new DatosDetalle(a.admin().almacen().lotes(p.id(), almacen),
                        a.ctx().inventarioSucursal().movimientos(a.usuario(), p.id(), almacen, 6)),
                d -> {
                    llenarLotes(lotes, p, d.lotes());
                    llenarMovimientos(movimientos, u, d.movimientos());
                },
                err -> {
                    lotes.getChildren().set(1, Ui.texto("No se pudieron cargar los lotes.", "texto-error"));
                    movimientos.getChildren().set(1, Ui.texto("No se pudieron cargar los movimientos.", "texto-error"));
                });
    }

    private void llenarLotes(VBox seccion, ProductoCatalogo p, List<LoteRepository.Lote> todos) {
        List<LoteRepository.Lote> conExistencia = todos.stream().filter(l -> l.existencia().signum() > 0).toList();
        List<Node> filas = new ArrayList<>();
        for (LoteRepository.Lote l : conExistencia.stream().limit(LOTES_EN_DETALLE).toList()) {
            String titulo = l.folio() == null ? Formatos.origenLote(l.origen()) : l.folio();
            filas.add(PanelDetalle.filaLista(titulo,
                    "Ingresó " + Formatos.fechaCorta(l.fechaIngreso()) + " · " + Dinero.formatear(l.costoUnitarioCentavos())
                            + Formatos.porUnidad(p.unidad()),
                    Cantidades.formatear(p.unidad(), l.existencia()), null));
        }
        Node contenido = filas.isEmpty()
                ? Ui.texto("No hay lotes con existencia en el almacén.", "texto-secundario")
                : PanelDetalle.lista(filas);
        seccion.getChildren().set(1, contenido);
        if (conExistencia.size() > LOTES_EN_DETALLE) {
            Button todosLotes = Ui.boton("Ver los " + conExistencia.size() + " lotes", "mdi2l-layers-outline", "flat", "small");
            todosLotes.setOnAction(e -> lotes(p));
            if (seccion.getChildren().size() > 2) {
                seccion.getChildren().set(2, todosLotes);
            } else {
                seccion.getChildren().add(todosLotes);
            }
        } else if (seccion.getChildren().size() > 2) {
            seccion.getChildren().remove(2);
        }
    }

    private void llenarMovimientos(VBox seccion, Unidad u, List<InventarioSucursalService.Movimiento> lista) {
        if (lista.isEmpty()) {
            seccion.getChildren().set(1, Ui.texto("Sin movimientos registrados.", "texto-secundario"));
            return;
        }
        List<Node> filas = new ArrayList<>();
        for (InventarioSucursalService.Movimiento m : lista) {
            String signo = m.cantidad().signum() < 0 ? "−" : "+";
            String cantidad = signo + Cantidades.formatear(u, m.cantidad().abs());
            String notas = m.notas() == null || m.notas().isBlank() ? "" : " · " + m.notas();
            filas.add(PanelDetalle.filaLista(Formatos.movimiento(m.tipo()), Formatos.cuando(m.fecha()) + notas, cantidad, null));
        }
        seccion.getChildren().set(1, PanelDetalle.lista(filas));
    }

    // ------------------------------------------------------------------ acciones

    private void registrarEntrada(ProductoCatalogo fijo) {
        DialogoEntradaAlmacen.mostrar(a, fijo, existencias::get, this::cargar);
    }

    private void lotes(ProductoCatalogo producto) {
        Dialogo d = new Dialogo("Lotes de " + producto.nombre(),
                "Se surten y venden en orden: primero el lote más antiguo con existencia.", "mdi2l-layers-outline",
                Dialogo.Tono.INFO);
        d.setPrefWidth(900);
        List<Sucursal> ubicaciones = a.admin().sucursales().ubicaciones();
        ComboBox<Sucursal> ubicacion = new ComboBox<>(FXCollections.observableArrayList(ubicaciones));
        ubicacion.setConverter(Combos.sucursal());
        TablaDatos<LoteRepository.Lote> lista = new TablaDatos<>(Diseno.Densidad.COMPACTA);
        lista.setClave(LoteRepository.Lote::id);
        lista.dosLineas("Lote", l -> l.folio() == null ? Formatos.origenLote(l.origen()) : l.folio(),
                l -> Formatos.origenLote(l.origen())).flexible(170);
        lista.fecha("Ingreso", LoteRepository.Lote::fechaIngreso, Formatos.FECHA_HORA).ancho(150);
        lista.cantidad("Cantidad inicial", LoteRepository.Lote::cantidadInicial, l -> producto.unidad()).ancho(140);
        lista.cantidad("Existencia", LoteRepository.Lote::existencia, l -> producto.unidad()).ancho(130);
        lista.formato("Costo", LoteRepository.Lote::costoUnitarioCentavos,
                (l, v) -> Dinero.formatear(v) + Formatos.porUnidad(producto.unidad())).numero().ancho(130);
        lista.texto("ID del lote", LoteRepository.Lote::id).ancho(300).clase("columna-tenue").oculta();
        lista.marcar("atenuada", l -> l.existencia().signum() <= 0);
        lista.setVacio(new EstadoVacio("mdi2l-layers-off-outline", "No hay lotes en esta ubicación.", null).compacto());
        lista.tabla().setPrefHeight(340);
        Cargador cargaLotes = new Cargador();
        Runnable cargar = () -> {
            Sucursal u = ubicacion.getValue();
            if (u == null) {
                return;
            }
            lista.cargando("Cargando lotes…");
            cargaLotes.cargar(() -> a.admin().almacen().lotes(producto.id(), u.id()), lista::setDatos,
                    e -> lista.error("No se pudieron cargar los lotes.", AdminContexto.mensaje(e), null));
        };
        ubicacion.valueProperty().addListener((o, x, u) -> cargar.run());
        ubicacion.setValue(ubicaciones.isEmpty() ? null : ubicaciones.getFirst());
        VBox tablaPanel = new VBox(lista.tabla());
        tablaPanel.getStyleClass().add("panel-tabla");
        Diseno.recortarRedondeado(tablaPanel, Diseno.RADIO_PANEL);
        d.setContenido(Formulario.campo("Ubicación", ubicacion), tablaPanel,
                Ui.texto("Los lotes agotados se muestran atenuados. El id de cada lote está en el menú de columnas.",
                        "campo-ayuda"));
        d.agregarBoton("Cerrar", null, d::cerrar, "accent");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(d::cerrar);
        a.dialogos().mostrar(d);
    }

    private void merma(ProductoCatalogo producto) {
        Dialogo d = new Dialogo("Registrar merma", producto.nombre(), "mdi2d-delete-variant", Dialogo.Tono.PELIGRO);
        d.setPrefWidth(540);
        List<Sucursal> ubicaciones = a.admin().sucursales().ubicaciones();
        ComboBox<Sucursal> ubicacion = new ComboBox<>(FXCollections.observableArrayList(ubicaciones));
        ubicacion.setConverter(Combos.sucursal());
        ubicacion.setValue(ubicaciones.isEmpty() ? null : ubicaciones.getFirst());
        Formulario.Campo campoUbicacion = Formulario.campo("Ubicación", ubicacion);
        TextField cantidad = new TextField();
        Campos.soloNumeros(cantidad, 0);
        cantidad.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        cantidad.setPromptText("0");
        Formulario.Campo campoCantidad = Formulario.campo("Cantidad que se pierde",
                Formulario.conUnidad(cantidad, Cantidades.unidadCaptura(producto.unidad())));
        TextField motivo = new TextField();
        motivo.setPromptText("Ej. Se echó a perder, se cayó, caducó");
        Formulario.Campo campoMotivo = Formulario.campo("Motivo", motivo);
        Runnable actualizar = () -> {
            Sucursal u = ubicacion.getValue();
            if (u == null) {
                return;
            }
            Cargador.ejecutar(() -> a.admin().almacen().existencia(producto.id(), u.id()),
                    ex -> campoUbicacion.ayuda("Existencia en " + u.nombre() + ": " + Cantidades.formatear(producto.unidad(), ex)),
                    err -> campoUbicacion.ayuda(null));
        };
        ubicacion.valueProperty().addListener((o, x, y) -> actualizar.run());
        cantidad.textProperty().addListener((o, x, y) -> campoCantidad.setError(null));
        motivo.textProperty().addListener((o, x, y) -> campoMotivo.setError(null));
        actualizar.run();
        d.setContenido(Formulario.nota("mdi2a-alert-outline",
                        "Se descuenta del lote más antiguo de esa ubicación y queda en el historial con su costo.", "peligro"),
                campoUbicacion, campoCantidad, campoMotivo);
        Runnable guardar = () -> {
            BigDecimal c = Cantidades.desdeCaptura(producto.unidad(), cantidad.getText()).orElse(BigDecimal.ZERO);
            boolean valido = true;
            if (c.signum() <= 0) {
                campoCantidad.setError("Escribe una cantidad mayor a cero.");
                valido = false;
            }
            if (motivo.getText() == null || motivo.getText().isBlank()) {
                campoMotivo.setError("Escribe por qué se pierde.");
                valido = false;
            }
            Sucursal u = ubicacion.getValue();
            if (!valido || u == null) {
                return;
            }
            String texto = motivo.getText();
            a.guardar(d, "Merma registrada: " + producto.nombre(),
                    () -> a.admin().almacen().registrarMerma(producto, u, c, texto, a.usuario()), this::cargar);
        };
        d.agregarBoton("Cancelar", null, d::cancelar, "flat");
        d.agregarBoton("Registrar merma", "mdi2c-check", guardar, "danger");
        d.setAlCancelar(d::cerrar);
        d.setAlConfirmar(guardar);
        d.setFocoInicial(cantidad);
        d.vigilarCambios(cantidad, motivo);
        a.dialogos().mostrar(d);
    }
}
