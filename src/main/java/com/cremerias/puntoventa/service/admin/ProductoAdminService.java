package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Masa;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Catálogo de productos del almacén: alta, edición, habilitación y borrado (lógico). */
public class ProductoAdminService {

    /**
     * Datos capturados en el formulario. {@code id} nulo = producto nuevo.
     * Gramaje y merma en gramos, por unidad base (por pieza, o por kilo en productos por kilo).
     * {@code avisoSinVenta} es opcional (nulo = sin aviso propio).
     */
    public record Datos(String id, String nombre, String categoria, String clave, String codigoInventario,
                        Unidad unidad, boolean sujetoMerma, Disponibilidad disponibilidad, Temporada temporada,
                        List<Presentacion> presentaciones, List<String> presentacionesEliminadas,
                        BigDecimal gramajeGramos, BigDecimal mermaGramos, AvisoSinVenta avisoSinVenta) {
    }

    private final Database database;
    private final Clock reloj;
    private final String dispositivoId;
    private final ProductoRepository productos = new ProductoRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public ProductoAdminService(Database database, Clock reloj, String dispositivoId) {
        this.database = database;
        this.reloj = reloj;
        this.dispositivoId = dispositivoId;
    }

    public List<ProductoCatalogo> listar() {
        return database.con(productos::listar);
    }

    public List<ProductoRepository.Categoria> categorias() {
        return database.con(productos::categorias);
    }

    /** Guarda el producto con sus presentaciones. Devuelve el folio. */
    public String guardar(Datos d, Usuario quien) {
        validar(d);
        return database.enTransaccion(c -> {
            String clave = d.clave() == null || d.clave().isBlank() ? null : d.clave().strip();
            var claveUsada = productos.usoDeClave(c, clave, d.id() == null ? "" : d.id());
            if (claveUsada.isPresent()) {
                throw new IllegalArgumentException("La clave " + clave + " ya la usa " + claveUsada.get() + ".");
            }
            String codigoInventario = d.codigoInventario() == null || d.codigoInventario().isBlank()
                    ? null : d.codigoInventario().strip();
            var codigoInventarioUsado = productos.usoDeCodigoInventario(c, codigoInventario, d.id() == null ? "" : d.id());
            if (codigoInventarioUsado.isPresent()) {
                throw new IllegalArgumentException("El código de inventario " + codigoInventario + " ya lo usa "
                        + codigoInventarioUsado.get() + ".");
            }
            for (Presentacion p : d.presentaciones()) {
                var codigoUsado = productos.usoDeCodigo(c, p.codigoBarras(), p.id() == null ? "" : p.id());
                if (codigoUsado.isPresent()) {
                    throw new IllegalArgumentException("El código " + p.codigoBarras() + " ya lo usa "
                            + codigoUsado.get() + ".");
                }
            }
            String categoriaId = d.categoria() == null || d.categoria().isBlank() ? null
                    : productos.categoria(c, d.categoria());
            boolean nuevo = d.id() == null;
            String id = nuevo ? Ids.nuevo() : d.id();
            BigDecimal gramaje = d.gramajeGramos();
            BigDecimal merma = d.sujetoMerma() && d.mermaGramos() != null ? d.mermaGramos() : BigDecimal.ZERO;
            if (nuevo) {
                productos.insertar(c, id, d.nombre().strip(), categoriaId, clave, codigoInventario, d.unidad(),
                        d.sujetoMerma(), d.disponibilidad(), gramaje, merma);
            } else {
                productos.actualizar(c, id, d.nombre().strip(), categoriaId, clave, codigoInventario, d.sujetoMerma(),
                        d.disponibilidad(), gramaje, merma);
            }
            productos.guardarAviso(c, id, d.avisoSinVenta());
            Temporada temporada = d.disponibilidad() == Disponibilidad.TEMPORADA ? d.temporada() : null;
            productos.guardarTemporada(c, id, temporada);
            if (temporada != null) {
                productos.cambiarActivo(c, id, temporada.vigente(LocalDate.now(reloj)));
            }
            for (String eliminada : d.presentacionesEliminadas()) {
                productos.eliminarPresentacion(c, eliminada);
            }
            for (Presentacion p : d.presentaciones()) {
                Presentacion normal = new Presentacion(p.id() == null ? Ids.nuevo() : p.id(), id, p.nombre().strip(),
                        p.granel(), p.factor(), p.codigoBarras(), p.principal(), p.activo());
                if (p.id() == null) {
                    productos.insertarPresentacion(c, normal);
                } else {
                    productos.actualizarPresentacion(c, normal);
                }
            }
            return bitacora.registrar(c, nuevo ? AccionBitacora.PRODUCTO_CREADO : AccionBitacora.PRODUCTO_EDITADO,
                    quien, dispositivoId, "productos", id, d.nombre().strip() + " · " + d.presentaciones().size()
                            + " presentación(es) · " + d.disponibilidad().nombre() + " · gramaje "
                            + Masa.formatear(gramaje) + (d.unidad() == Unidad.KG ? " por kg" : "")
                            + (d.sujetoMerma() ? " · merma " + Masa.formatear(merma) + " (neto "
                            + Masa.formatear(gramaje.subtract(merma)) + ")" : "")
                            + (d.avisoSinVenta() == null ? "" : " · aviso si no se vende en "
                            + d.avisoSinVenta().describir() + " (" + d.avisoSinVenta().destinatarios() + ")"));
        });
    }

    public String cambiarActivo(ProductoCatalogo producto, boolean activo, Usuario quien) {
        if (producto.disponibilidad() == Disponibilidad.REGULAR) {
            throw new IllegalArgumentException(
                    "Los productos regulares no se deshabilitan a mano; cambia su disponibilidad si ya no se vende.");
        }
        return database.enTransaccion(c -> {
            productos.cambiarActivo(c, producto.id(), activo);
            return bitacora.registrar(c, activo ? AccionBitacora.PRODUCTO_HABILITADO : AccionBitacora.PRODUCTO_DESHABILITADO,
                    quien, dispositivoId, "productos", producto.id(), producto.nombre());
        });
    }

    /** Habilita de nuevo un producto de temporada ya vencido, con su nueva ventana de fechas. */
    public String renovarTemporada(ProductoCatalogo producto, Temporada nueva, Usuario quien) {
        if (producto.disponibilidad() != Disponibilidad.TEMPORADA) {
            throw new IllegalArgumentException("Solo los productos de temporada tienen ventana de fechas.");
        }
        validarTemporada(nueva);
        boolean activo = nueva.vigente(LocalDate.now(reloj));
        return database.enTransaccion(c -> {
            productos.guardarTemporada(c, producto.id(), nueva);
            productos.cambiarActivo(c, producto.id(), activo);
            return bitacora.registrar(c, AccionBitacora.PRODUCTO_HABILITADO, quien, dispositivoId, "productos",
                    producto.id(), producto.nombre() + " · nueva temporada del " + nueva.desde() + " al " + nueva.fin()
                            + (activo ? "" : " (empieza después)")
                            + (nueva.repite() ? " (se repite " + nueva.describirRepeticion() + ")" : ""));
        });
    }

    /**
     * Revisa los productos de temporada: deshabilita los que ya vencieron y, si se repiten,
     * calcula su siguiente ciclo (reactivándolos de una vez si ya empezó). También reactiva los
     * que ya vencidos y repetidos cayeron dentro de su ventana. No borra nada.
     *
     * @return cuántos productos cambiaron de estado
     */
    public int revisarTemporadas() {
        LocalDate hoy = LocalDate.now(reloj);
        return database.enTransaccion(c -> {
            int cambios = 0;
            for (ProductoCatalogo p : productos.listar(c)) {
                if (p.disponibilidad() != Disponibilidad.TEMPORADA || p.temporada() == null) {
                    continue;
                }
                Temporada t = p.temporada();
                boolean activo = p.activo();
                if (activo && t.vencida(hoy)) {
                    productos.cambiarActivo(c, p.id(), false);
                    bitacora.registrar(c, AccionBitacora.PRODUCTO_DESHABILITADO, null, dispositivoId, "productos",
                            p.id(), p.nombre() + " · terminó la temporada (" + t.fin() + ")");
                    activo = false;
                    cambios++;
                }
                if (t.repite() && t.vencida(hoy)) {
                    t = t.siguienteVigente(hoy);
                    productos.guardarTemporada(c, p.id(), t);
                }
                if (!activo && t.vigente(hoy)) {
                    productos.cambiarActivo(c, p.id(), true);
                    bitacora.registrar(c, AccionBitacora.PRODUCTO_HABILITADO, null, dispositivoId, "productos",
                            p.id(), p.nombre() + " · empieza la temporada (" + t.desde() + ")");
                    cambios++;
                }
            }
            return cambios;
        });
    }

    public String eliminar(ProductoCatalogo producto, Usuario quien) {
        return database.enTransaccion(c -> {
            productos.eliminar(c, producto.id());
            return bitacora.registrar(c, AccionBitacora.PRODUCTO_ELIMINADO, quien, dispositivoId, "productos",
                    producto.id(), producto.nombre());
        });
    }

    private static void validarTemporada(Temporada t) {
        if (t == null || t.desde() == null || t.fin() == null) {
            throw new IllegalArgumentException("Elige cuándo empieza y termina la temporada.");
        }
        if (!t.fin().isAfter(t.desde())) {
            throw new IllegalArgumentException("La temporada debe terminar después de que empieza.");
        }
        if ((t.repetirCada() == null) != (t.repetirUnidad() == null)) {
            throw new IllegalArgumentException("Falta la unidad o la cantidad para repetir la temporada.");
        }
        if (t.repetirCada() != null && t.repetirCada() <= 0) {
            throw new IllegalArgumentException("La repetición debe ser mayor a cero.");
        }
    }

    private static void validar(Datos d) {
        if (d.nombre() == null || d.nombre().isBlank()) {
            throw new IllegalArgumentException("Escribe el nombre del producto.");
        }
        if (d.gramajeGramos() == null || d.gramajeGramos().signum() <= 0) {
            throw new IllegalArgumentException(d.unidad() == Unidad.KG
                    ? "Escribe el gramaje de referencia por kilo (normalmente 1000 g)."
                    : "Escribe el gramaje del producto (lo que pesa una pieza).");
        }
        if (d.sujetoMerma()) {
            if (d.mermaGramos() == null || d.mermaGramos().signum() <= 0) {
                throw new IllegalArgumentException("Escribe la merma del producto o desactiva «Sujeto a merma».");
            }
            if (d.mermaGramos().compareTo(d.gramajeGramos()) >= 0) {
                throw new IllegalArgumentException(
                        "La merma debe ser menor que el gramaje (" + Masa.formatear(d.gramajeGramos()) + ").");
            }
        }
        if (d.disponibilidad() == Disponibilidad.TEMPORADA) {
            validarTemporada(d.temporada());
        }
        AvisoSinVenta aviso = d.avisoSinVenta();
        if (aviso != null) {
            if (aviso.plazo() <= 0 || aviso.unidad() == null) {
                throw new IllegalArgumentException("Escribe en cuánto tiempo sin venderse se debe avisar.");
            }
            if (aviso.duracion().compareTo(AvisoSinVenta.MAXIMO) > 0) {
                throw new IllegalArgumentException("El plazo del aviso puede ser de hasta un año.");
            }
            if (!aviso.administrador() && !aviso.supervisor() && !aviso.caja()) {
                throw new IllegalArgumentException("Elige a quién avisar: administrador, supervisor o caja.");
            }
        }
        if (d.presentaciones().isEmpty()) {
            throw new IllegalArgumentException("Agrega al menos una presentación.");
        }
        if (d.presentaciones().stream().filter(Presentacion::principal).count() != 1) {
            throw new IllegalArgumentException("Marca una sola presentación como principal.");
        }
        Set<String> nombres = new HashSet<>();
        Set<String> codigos = new HashSet<>();
        for (Presentacion p : d.presentaciones()) {
            if (p.nombre() == null || p.nombre().isBlank()) {
                throw new IllegalArgumentException("Todas las presentaciones necesitan nombre.");
            }
            if (!nombres.add(p.nombre().strip().toLowerCase())) {
                throw new IllegalArgumentException("Hay dos presentaciones llamadas \"" + p.nombre().strip() + "\".");
            }
            if (p.factor() == null || p.factor().signum() <= 0) {
                throw new IllegalArgumentException("La presentación \"" + p.nombre() + "\" necesita su contenido.");
            }
            if (p.granel() && d.unidad() != Unidad.KG) {
                throw new IllegalArgumentException("Solo los productos por kilo se pueden vender a granel.");
            }
            if (p.codigoBarras() != null && !p.codigoBarras().isBlank() && !codigos.add(p.codigoBarras().strip())) {
                throw new IllegalArgumentException("El código " + p.codigoBarras() + " está repetido.");
            }
        }
    }
}
