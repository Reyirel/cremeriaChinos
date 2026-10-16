package com.cremerias.puntoventa.ui.supervisor;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.service.InventarioSucursalService;
import com.cremerias.puntoventa.service.InventarioSucursalService.Fila;
import com.cremerias.puntoventa.service.SinVentaService.SinVenta;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.admin.Ui;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.BarraHerramientas;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Diseno;
import com.cremerias.puntoventa.ui.componentes.Errores;
import com.cremerias.puntoventa.ui.componentes.EstadoVacio;
import com.cremerias.puntoventa.ui.componentes.Etiqueta;
import com.cremerias.puntoventa.ui.componentes.ResumenMetricas;
import com.cremerias.puntoventa.ui.componentes.TablaDatos;
import com.cremerias.puntoventa.ui.componentes.Texto;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Productos de la sucursal del supervisor, solo para consulta: existencia, precios, mínimo/máximo
 * y los que hay que ofertar porque llevan su plazo sin venderse. No hay nada que editar; la
 * existencia baja con cada venta registrada en caja.
 */
public class ProductosVista {

    private enum Estado {
        TODOS("Todos los estados"), OFERTAR("Para ofertar"), EN_MINIMO("En mínimo"), AGOTADOS("Agotados"),
        CON_EXISTENCIA("Con existencia");

        final String texto;

        Estado(String texto) {
            this.texto = texto;
        }
    }

    /** Productos y avisos de venta, cargados juntos. */
    private record Datos(List<Fila> filas, Map<String, SinVenta> ofertar) {
    }

    private final AppContext ctx;
    private final Avisos avisos;
    private final String sucursalId;
    private final Cargador carga = new Cargador();
    private final ScrollPane raiz;
    private final BarraHerramientas barra = new BarraHerramientas();
    private final ComboBox<Estado> estado;
    private final TablaDatos<Fila> tabla = new TablaDatos<>(Diseno.Densidad.NORMAL);
    private final ResumenMetricas.Metrica metricaProductos;
    private final ResumenMetricas.Metrica metricaOfertar;
    private final ResumenMetricas.Metrica metricaMinimo;
    private final ResumenMetricas.Metrica metricaAgotados;
    /** Productos que llevan su plazo sin venderse (avisos para el supervisor), por id. */
    private Map<String, SinVenta> paraOfertar = Map.of();

    public ProductosVista(Navegador navegador) {
        this.ctx = navegador.contexto();
        this.avisos = navegador.avisos();
        this.sucursalId = ctx.sesionActual().obtener().orElseThrow().usuario().sucursalId();

        Button actualizar = Ui.secundario("Actualizar", "mdi2r-refresh");
        actualizar.setOnAction(e -> {
            actualizar();
            avisos.info("Información actualizada.");
        });
        VBox pagina = Ui.pagina("Productos", sucursalId == null
                ? "Tu usuario no tiene una sucursal asignada."
                : ctx.nombreSucursal(sucursalId) + " · solo consulta. La existencia baja con cada venta registrada en caja.",
                actualizar);

        ResumenMetricas resumen = new ResumenMetricas();
        metricaProductos = resumen.agregar("Productos").principal();
        metricaOfertar = resumen.agregar("Para ofertar");
        metricaMinimo = resumen.agregar("En su mínimo");
        metricaAgotados = resumen.agregar("Agotados");

        barra.buscador("Buscar por nombre, clave o código");
        estado = barra.filtro("Estado", new ComboBox<>(FXCollections.observableArrayList(Estado.values())), Estado.TODOS,
                e -> e.texto);
        estado.setValue(Estado.TODOS);
        barra.setAlCambiar(this::filtrar);

        tabla.setClave(f -> f.producto().id());
        tabla.dosLineas("Producto", f -> f.producto().nombre(), ProductosVista::detalle).flexible(260);
        tabla.cantidad("Existencia", Fila::existencia, f -> f.producto().unidad()).ancho(130);
        tabla.dosLineas("Precio de venta", ProductosVista::precioPrincipal, ProductosVista::otrosPrecios).ancho(240);
        tabla.formato("Mín. / máx.", Fila::maximo, (f, v) -> v.signum() == 0 ? "Sin control"
                : Cantidades.formatear(f.producto().unidad(), f.minimo()) + " / " + Cantidades.formatear(f.producto().unidad(), v))
                .numero().ancho(170).ayuda("Con el máximo en 0 el producto no pide reabastecimiento solo.");
        tabla.nodo("Estado", this::estado).ancho(200)
                .ayuda("Solo se marcan los agotados, los que hay que ofertar y los que están en su mínimo.");
        tabla.setVacio(new EstadoVacio("mdi2p-package-variant-closed", sucursalId == null
                ? "Tu usuario no tiene una sucursal asignada." : "Esta sucursal aún no tiene productos surtidos.", null));
        tabla.setSinResultados(EstadoVacio.sinResultados("No encontramos productos con estos filtros.", barra::limpiar));

        VBox panel = Ui.panelTabla(barra, tabla);
        pagina.getChildren().addAll(resumen, panel);
        raiz = Ui.ajustable(pagina, panel, 300);
    }

    public Node nodo() {
        return raiz;
    }

    public void actualizar() {
        if (sucursalId == null) {
            tabla.setDatos(List.of());
            return;
        }
        tabla.cargando("Cargando productos…");
        carga.cargar(() -> new Datos(
                // Primero los que hay que reabastecer: ahí llega el supervisor desde el aviso de mínimos.
                ctx.inventarioSucursal().listar(sucursalId).stream()
                        .sorted(Comparator.comparing((Fila f) -> !f.porReabastecer())).toList(),
                ctx.sinVenta().pendientes(Rol.SUPERVISOR, sucursalId).stream()
                        .collect(Collectors.toMap(SinVenta::productoId, s -> s, (a, b) -> a))), d -> {
            paraOfertar = d.ofertar();
            tabla.setDatos(d.filas());
            long enMinimo = d.filas().stream().filter(Fila::enMinimo).count();
            long agotados = d.filas().stream().filter(Fila::agotado).count();
            metricaProductos.setValor(String.valueOf(d.filas().size()));
            metricaOfertar.setValor(String.valueOf(paraOfertar.size()))
                    .setContexto(paraOfertar.isEmpty() ? "Nada que ofertar" : "Llevan su plazo sin venderse")
                    .setTono(paraOfertar.isEmpty() ? null : Etiqueta.Tono.AVISO);
            metricaMinimo.setValor(String.valueOf(enMinimo)).setTono(enMinimo > 0 ? Etiqueta.Tono.AVISO : null);
            metricaAgotados.setValor(String.valueOf(agotados)).setTono(agotados > 0 ? Etiqueta.Tono.PELIGRO : null);
            filtrar();
        }, e -> tabla.error("No se pudieron cargar los productos.", Errores.mensaje(e), this::actualizar));
    }

    private void filtrar() {
        String t = barra.busqueda();
        Estado e = estado.getValue() == null ? Estado.TODOS : estado.getValue();
        tabla.setFiltro(f -> (t.isEmpty() || coincide(f, t)) && switch (e) {
            case TODOS -> true;
            case OFERTAR -> paraOfertar.containsKey(f.producto().id());
            case EN_MINIMO -> f.enMinimo();
            case AGOTADOS -> f.agotado();
            case CON_EXISTENCIA -> !f.agotado();
        });
        barra.setResultados(tabla.mostrados() == tabla.total() ? Texto.plural(tabla.total(), "producto", "productos")
                : tabla.mostrados() + " de " + Texto.plural(tabla.total(), "producto", "productos"));
    }

    private static boolean coincide(Fila f, String texto) {
        var p = f.producto();
        return Stream.concat(Stream.of(p.nombre(), p.clave(), p.codigoInventario()),
                        p.presentaciones().stream().map(pr -> pr.codigoBarras()))
                .anyMatch(v -> Texto.contiene(v, texto));
    }

    /** Solo las excepciones: agotado, para ofertar (con el tiempo sin venta) o en mínimo. */
    private Node estado(Fila f) {
        HBox chips = new HBox(4);
        chips.setAlignment(Pos.CENTER_LEFT);
        if (f.agotado()) {
            chips.getChildren().add(Etiqueta.de("Agotado", Etiqueta.Tono.PELIGRO));
            return chips;
        }
        SinVenta s = paraOfertar.get(f.producto().id());
        if (s != null) {
            Label ofertar = Etiqueta.de("Ofertar · " + AvisoSinVenta.formatear(s.sinVender()), Etiqueta.Tono.AVISO);
            ofertar.setTooltip(new Tooltip("Plazo sin venta: " + s.plazo()));
            chips.getChildren().add(ofertar);
        }
        if (f.enMinimo()) {
            chips.getChildren().add(Etiqueta.de("En mínimo", Etiqueta.Tono.AVISO));
        }
        return chips;
    }

    /** Categoría y clave/PLU debajo del nombre. */
    private static String detalle(Fila f) {
        var p = f.producto();
        return Stream.of(p.categoria(), p.clave() == null ? null : "Clave " + p.clave())
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(" · "));
    }

    private static String precioPrincipal(Fila f) {
        List<InventarioSucursalService.Precio> precios = f.precios();
        return precios.isEmpty() ? "Sin precio" : precio(precios.getFirst());
    }

    private static String otrosPrecios(Fila f) {
        return f.precios().stream().skip(1).map(ProductosVista::precio).collect(Collectors.joining(" · "));
    }

    private static String precio(InventarioSucursalService.Precio p) {
        return p.presentacion().nombre() + (p.presentacion().granel() ? " (kg)" : "") + ": "
                + Dinero.formatear(p.precioCentavos());
    }
}
