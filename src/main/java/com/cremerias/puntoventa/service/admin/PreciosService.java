package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.PreciosRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.service.Permisos;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reglas de precio por canal (márgenes) y escalas de mayoreo.
 *
 * <ul>
 *   <li>Una regla convierte un costo en precio con un porcentaje (recargo sobre el costo o margen
 *       sobre el precio). Gana la más específica; sin regla, el precio es el costo (0 %).</li>
 *   <li>El canal {@code BODEGA_SUCURSAL} (o {@code RUTA_SIERRA}) da el precio interno con el que se
 *       carga un surtido a la sucursal; el canal {@code PUBLICO} solo sugiere el precio al surtir.</li>
 *   <li>Las escalas de mayoreo bajan el precio por volumen en la caja (ver {@code model.Escalas}).</li>
 * </ul>
 * Solo el administrador cambia reglas y escalas; todo queda en la bitácora.
 */
public class PreciosService {

    /** Datos capturados de una regla; {@code id} nulo = regla nueva. */
    public record DatosRegla(String id, ReglaPrecio.Canal canal, ReglaPrecio.Tipo tipo, BigDecimal porcentaje,
                             String categoriaId, String productoId, String presentacionId, String sucursalId,
                             LocalDate desde, LocalDate hasta, String notas) {
    }

    /** Datos capturados de una escala de mayoreo. */
    public record DatosEscala(String productoId, String presentacionId, String sucursalId, Escala.Nivel nivel,
                              BigDecimal cantidadMinima, Escala.Tipo tipo, BigDecimal descuentoPct, Long precioCentavos,
                              LocalDate desde, LocalDate hasta) {
    }

    /** Precio de un canal para un producto: costo base actual del almacén, regla aplicada y precio por presentación. */
    public record FilaLista(ProductoCatalogo producto, long costoBaseCentavos, ReglaPrecio regla,
                            Map<Presentacion, Long> precios) {
    }

    private final Database database;
    private final String dispositivoId;
    private final String almacenId;
    private final PreciosRepository precios = new PreciosRepository();
    private final ProductoRepository productos = new ProductoRepository();
    private final LoteRepository lotes = new LoteRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public PreciosService(Database database, String dispositivoId, String almacenId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.almacenId = almacenId;
    }

    // ================================================================== reglas

    public List<ReglaPrecio> reglas() {
        return database.con(precios::reglas);
    }

    /** Guarda una regla (nueva o editada). Devuelve el folio. */
    public String guardarRegla(DatosRegla d, Usuario quien) {
        Permisos.exigirAdministrador(quien, "cambiar márgenes y precios");
        validar(d);
        return database.enTransaccion(c -> {
            ReglaPrecio anterior = d.id() == null ? null : precios.regla(c, d.id())
                    .orElseThrow(() -> new IllegalArgumentException("La regla ya no existe."));
            String id = anterior == null ? Ids.nuevo() : anterior.id();
            String folio = anterior == null
                    ? bitacora.siguienteFolio(c, AccionBitacora.REGLA_PRECIO_GUARDADA.prefijo(), dispositivoId)
                    : anterior.folio();
            ReglaPrecio regla = new ReglaPrecio(id, folio, d.canal(), d.tipo(), d.porcentaje(), vacio(d.categoriaId()),
                    vacio(d.productoId()), vacio(d.presentacionId()), vacio(d.sucursalId()), d.desde(), d.hasta(),
                    vacio(d.notas()), true);
            for (ReglaPrecio otra : precios.reglasDeCanal(c, d.canal())) {
                if (!otra.id().equals(id) && mismoAlcance(otra, regla) && seCruzan(otra.vigenteDesde(),
                        otra.vigenteHasta(), regla.vigenteDesde(), regla.vigenteHasta())) {
                    throw new IllegalArgumentException("Ya hay una regla para ese mismo alcance y fechas (folio "
                            + otra.folio() + ", " + porcentaje(otra) + "). Desactívala o cambia las fechas.");
                }
            }
            if (regla.presentacionId() != null && !presentacionDe(c, regla.presentacionId(), regla.productoId())) {
                throw new IllegalArgumentException("La presentación no es de ese producto.");
            }
            if (anterior == null) {
                precios.insertarRegla(c, regla, quien.id());
            } else {
                precios.actualizarRegla(c, regla);
            }
            bitacora.registrar(c, AccionBitacora.REGLA_PRECIO_GUARDADA, quien, dispositivoId, "reglas_precio", id,
                    (anterior == null ? "Nueva regla " : "Regla editada ") + d.canal().nombre() + ": "
                            + porcentaje(regla) + " · " + alcance(c, regla) + " · " + vigencia(regla)
                            + (anterior == null ? "" : " (antes " + porcentaje(anterior) + ", " + vigencia(anterior) + ")"),
                    anterior == null ? folio : bitacora.siguienteFolio(c, AccionBitacora.REGLA_PRECIO_GUARDADA.prefijo(),
                            dispositivoId));
            return folio;
        });
    }

    public String desactivarRegla(ReglaPrecio regla, Usuario quien) {
        Permisos.exigirAdministrador(quien, "cambiar márgenes y precios");
        return database.enTransaccion(c -> {
            precios.desactivarRegla(c, regla.id());
            return bitacora.registrar(c, AccionBitacora.REGLA_PRECIO_DESACTIVADA, quien, dispositivoId, "reglas_precio",
                    regla.id(), "Regla " + regla.folio() + " desactivada: " + regla.canal().nombre() + " "
                            + porcentaje(regla) + " · " + alcance(c, regla));
        });
    }

    /** Regla que aplica a un producto/presentación en una sucursal y fecha, o vacío (0 %). */
    public Optional<ReglaPrecio> reglaAplicable(ReglaPrecio.Canal canal, String productoId, String categoriaId,
                                                String presentacionId, String sucursalId, LocalDate dia) {
        return database.con(c -> reglaAplicable(c, canal, productoId, categoriaId, presentacionId, sucursalId, dia));
    }

    /** Igual que {@link #reglaAplicable(ReglaPrecio.Canal, String, String, String, String, LocalDate)}, dentro de otra transacción. */
    Optional<ReglaPrecio> reglaAplicable(Connection c, ReglaPrecio.Canal canal, String productoId, String categoriaId,
                                         String presentacionId, String sucursalId, LocalDate dia) throws SQLException {
        return elegir(precios.reglasDeCanal(c, canal), productoId, categoriaId, presentacionId, sucursalId, dia);
    }

    /**
     * La regla más específica que cubre el artículo y está vigente; a igual especificidad, la que
     * empezó más recientemente.
     */
    public static Optional<ReglaPrecio> elegir(List<ReglaPrecio> reglas, String productoId, String categoriaId,
                                               String presentacionId, String sucursalId, LocalDate dia) {
        return reglas.stream()
                .filter(r -> r.vigenteEn(dia) && r.cubre(productoId, categoriaId, presentacionId, sucursalId))
                .max(Comparator.comparingInt(ReglaPrecio::especificidad)
                        .thenComparing(ReglaPrecio::vigenteDesde)
                        .thenComparing(ReglaPrecio::folio));
    }

    /** Precio de un costo con la regla (o el mismo costo si no hay regla). */
    public static long precio(Optional<ReglaPrecio> regla, long costoCentavos) {
        return regla.map(r -> r.precioDesde(costoCentavos)).orElse(costoCentavos);
    }

    /**
     * Precio al público sugerido para cada presentación activa: costo por unidad base × factor de la
     * presentación, con la regla del canal público. Solo las presentaciones que tienen regla.
     */
    public Map<String, Long> preciosPublicosSugeridos(ProductoCatalogo producto, long costoBaseCentavos,
                                                      String sucursalId, LocalDate dia) {
        return database.con(c -> {
            List<ReglaPrecio> reglas = precios.reglasDeCanal(c, ReglaPrecio.Canal.PUBLICO);
            Map<String, Long> sugeridos = new LinkedHashMap<>();
            for (Presentacion p : producto.presentaciones()) {
                if (!p.activo()) {
                    continue;
                }
                Optional<ReglaPrecio> regla = elegir(reglas, producto.id(), producto.categoriaId(), p.id(), sucursalId, dia);
                if (regla.isPresent()) {
                    long costoPresentacion = BigDecimal.valueOf(costoBaseCentavos).multiply(p.factor())
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    sugeridos.put(p.id(), regla.get().precioDesde(costoPresentacion));
                }
            }
            return sugeridos;
        });
    }

    /**
     * Lista de precios de un canal: por producto activo, el costo del lote más antiguo con
     * existencia en el almacén (el que saldría primero), la regla y el precio de cada presentación.
     * Productos sin existencia en el almacén usan el costo de su último lote.
     */
    public List<FilaLista> listaDePrecios(ReglaPrecio.Canal canal, String sucursalId, LocalDate dia) {
        return database.con(c -> {
            List<ReglaPrecio> reglas = precios.reglasDeCanal(c, canal);
            Map<String, List<LoteRepository.Lote>> porProducto = new HashMap<>();
            if (almacenId != null) {
                for (LoteRepository.Lote l : lotes.deSucursal(c, almacenId)) {
                    porProducto.computeIfAbsent(l.productoId(), k -> new ArrayList<>()).add(l);
                }
            }
            List<FilaLista> filas = new ArrayList<>();
            for (ProductoCatalogo p : productos.listar(c)) {
                if (!p.activo()) {
                    continue;
                }
                List<LoteRepository.Lote> suyos = porProducto.getOrDefault(p.id(), List.of());
                long costo = suyos.stream().filter(l -> l.existencia().signum() > 0).findFirst()
                        .or(() -> suyos.isEmpty() ? Optional.empty() : Optional.of(suyos.getLast()))
                        .map(LoteRepository.Lote::costoUnitarioCentavos).orElse(0L);
                Optional<ReglaPrecio> general = elegir(reglas, p.id(), p.categoriaId(), null, sucursalId, dia);
                Map<Presentacion, Long> porPresentacion = new LinkedHashMap<>();
                for (Presentacion pr : p.presentaciones()) {
                    if (!pr.activo()) {
                        continue;
                    }
                    Optional<ReglaPrecio> regla = elegir(reglas, p.id(), p.categoriaId(), pr.id(), sucursalId, dia);
                    if (regla.isEmpty() && canal == ReglaPrecio.Canal.PUBLICO) {
                        // Al público sin regla no hay precio sugerido: el costo no es un precio de venta.
                        continue;
                    }
                    long costoPresentacion = BigDecimal.valueOf(costo).multiply(pr.factor())
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    porPresentacion.put(pr, precio(regla, costoPresentacion));
                }
                filas.add(new FilaLista(p, costo, general.orElse(null), porPresentacion));
            }
            return filas;
        });
    }

    // ================================================================== escalas

    public List<Escala> escalas() {
        return database.con(precios::escalas);
    }

    public List<Escala> escalasDeProducto(String productoId) {
        return database.con(c -> precios.escalasDeProducto(c, productoId));
    }

    /** Guarda una escala de mayoreo. Devuelve el folio. */
    public String guardarEscala(DatosEscala d, Usuario quien) {
        Permisos.exigirAdministrador(quien, "cambiar precios de mayoreo");
        validar(d);
        return database.enTransaccion(c -> {
            if (d.presentacionId() != null && !presentacionDe(c, d.presentacionId(), d.productoId())) {
                throw new IllegalArgumentException("La presentación no es de ese producto.");
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.ESCALA_PRECIO_GUARDADA.prefijo(), dispositivoId);
            Escala nueva = new Escala(Ids.nuevo(), folio, d.productoId(), vacio(d.presentacionId()),
                    vacio(d.sucursalId()), d.nivel(), d.cantidadMinima(), d.tipo(),
                    d.tipo() == Escala.Tipo.DESCUENTO_PCT ? d.descuentoPct() : null,
                    d.tipo() == Escala.Tipo.PRECIO_FIJO ? d.precioCentavos() : null, d.desde(), d.hasta(), true);
            List<Escala> mismoAlcance = precios.escalasDeProducto(c, d.productoId()).stream()
                    .filter(e -> e.activo() && Objects.equals(e.presentacionId(), nueva.presentacionId())
                            && Objects.equals(e.sucursalId(), nueva.sucursalId())
                            && seCruzan(e.vigenteDesde(), e.vigenteHasta(), nueva.vigenteDesde(), nueva.vigenteHasta()))
                    .toList();
            validarContraOtras(nueva, mismoAlcance);
            precios.insertarEscala(c, nueva, quien.id());
            ProductoCatalogo producto = productos.porId(c, d.productoId()).orElseThrow();
            bitacora.registrar(c, AccionBitacora.ESCALA_PRECIO_GUARDADA, quien, dispositivoId, "escalas_precio",
                    nueva.id(), producto.nombre() + ": " + d.nivel().nombre() + " desde "
                            + Cantidades.formatear(producto.unidad(), d.cantidadMinima()) + " · " + valor(nueva),
                    folio);
            return folio;
        });
    }

    public String desactivarEscala(Escala escala, Usuario quien) {
        Permisos.exigirAdministrador(quien, "cambiar precios de mayoreo");
        return database.enTransaccion(c -> {
            precios.desactivarEscala(c, escala.id());
            return bitacora.registrar(c, AccionBitacora.ESCALA_PRECIO_DESACTIVADA, quien, dispositivoId,
                    "escalas_precio", escala.id(), "Escala " + escala.folio() + " desactivada: "
                            + escala.nivel().nombre() + " · " + valor(escala));
        });
    }

    /**
     * Sin contradicciones entre niveles del mismo alcance: un nivel por alcance y fechas, súper
     * mayoreo empieza en una cantidad mayor que mayoreo y no da un precio peor.
     */
    static void validarContraOtras(Escala nueva, List<Escala> mismoAlcance) {
        for (Escala otra : mismoAlcance) {
            if (otra.nivel() == nueva.nivel()) {
                throw new IllegalArgumentException("Ya hay una escala de " + nueva.nivel().nombre().toLowerCase()
                        + " para ese alcance y fechas (folio " + otra.folio() + "). Desactívala primero.");
            }
            Escala mayoreo = nueva.nivel() == Escala.Nivel.MAYOREO ? nueva : otra;
            Escala superMayoreo = nueva.nivel() == Escala.Nivel.SUPER_MAYOREO ? nueva : otra;
            if (superMayoreo.cantidadMinima().compareTo(mayoreo.cantidadMinima()) <= 0) {
                throw new IllegalArgumentException("Súper mayoreo debe empezar en una cantidad mayor que mayoreo ("
                        + mayoreo.cantidadMinima().toPlainString() + ").");
            }
            if (mayoreo.tipo() == superMayoreo.tipo()) {
                boolean peor = mayoreo.tipo() == Escala.Tipo.DESCUENTO_PCT
                        ? superMayoreo.descuentoPct().compareTo(mayoreo.descuentoPct()) < 0
                        : superMayoreo.precioCentavos() > mayoreo.precioCentavos();
                if (peor) {
                    throw new IllegalArgumentException("Súper mayoreo no puede dar un precio peor que mayoreo.");
                }
            }
        }
    }

    // ================================================================== validación y textos

    private static void validar(DatosRegla d) {
        if (d.canal() == null || d.tipo() == null) {
            throw new IllegalArgumentException("Elige el canal y el tipo de porcentaje.");
        }
        if (d.porcentaje() == null || d.porcentaje().signum() < 0) {
            throw new IllegalArgumentException("Escribe un porcentaje de 0 o más.");
        }
        if (d.tipo() == ReglaPrecio.Tipo.MARGEN_PRECIO && d.porcentaje().compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new IllegalArgumentException("El margen sobre el precio debe ser menor a 100 %.");
        }
        if (d.porcentaje().compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new IllegalArgumentException("El recargo no puede pasar de 1000 %.");
        }
        if (vacio(d.categoriaId()) != null && vacio(d.productoId()) != null) {
            throw new IllegalArgumentException("Una regla es para una categoría o para un producto, no ambos.");
        }
        if (vacio(d.presentacionId()) != null && vacio(d.productoId()) == null) {
            throw new IllegalArgumentException("Para una presentación elige también su producto.");
        }
        validarFechas(d.desde(), d.hasta());
    }

    private static void validar(DatosEscala d) {
        if (d.productoId() == null || d.productoId().isBlank()) {
            throw new IllegalArgumentException("Elige el producto.");
        }
        if (d.nivel() == null || d.nivel() == Escala.Nivel.MENUDEO) {
            throw new IllegalArgumentException("Elige mayoreo o súper mayoreo (menudeo es el precio normal).");
        }
        if (d.cantidadMinima() == null || d.cantidadMinima().signum() <= 0) {
            throw new IllegalArgumentException("La cantidad mínima debe ser mayor a cero.");
        }
        if (d.tipo() == null) {
            throw new IllegalArgumentException("Elige descuento o precio fijo.");
        }
        if (d.tipo() == Escala.Tipo.DESCUENTO_PCT && (d.descuentoPct() == null || d.descuentoPct().signum() <= 0
                || d.descuentoPct().compareTo(BigDecimal.valueOf(100)) >= 0)) {
            throw new IllegalArgumentException("El descuento debe ser mayor a 0 % y menor a 100 %.");
        }
        if (d.tipo() == Escala.Tipo.PRECIO_FIJO) {
            if (d.precioCentavos() == null || d.precioCentavos() <= 0) {
                throw new IllegalArgumentException("El precio fijo debe ser mayor a cero.");
            }
            if (vacio(d.presentacionId()) == null) {
                throw new IllegalArgumentException("Un precio fijo es por presentación: elige la presentación.");
            }
        }
        validarFechas(d.desde(), d.hasta());
    }

    private static void validarFechas(LocalDate desde, LocalDate hasta) {
        if (desde == null) {
            throw new IllegalArgumentException("Elige desde qué fecha aplica.");
        }
        if (hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException("La fecha final no puede ser anterior a la inicial.");
        }
    }

    static boolean seCruzan(LocalDate desde1, LocalDate hasta1, LocalDate desde2, LocalDate hasta2) {
        boolean primeroTerminaAntes = hasta1 != null && hasta1.isBefore(desde2);
        boolean segundoTerminaAntes = hasta2 != null && hasta2.isBefore(desde1);
        return !primeroTerminaAntes && !segundoTerminaAntes;
    }

    private static boolean mismoAlcance(ReglaPrecio a, ReglaPrecio b) {
        return Objects.equals(a.categoriaId(), b.categoriaId()) && Objects.equals(a.productoId(), b.productoId())
                && Objects.equals(a.presentacionId(), b.presentacionId()) && Objects.equals(a.sucursalId(), b.sucursalId());
    }

    private static boolean presentacionDe(Connection c, String presentacionId, String productoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM presentaciones WHERE id = ? AND producto_id = ?")) {
            ps.setString(1, presentacionId);
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** "23 % sobre costo" o "18 % de margen". */
    public static String porcentaje(ReglaPrecio r) {
        return r.porcentaje().toPlainString() + (r.tipo() == ReglaPrecio.Tipo.RECARGO_COSTO ? " % sobre costo" : " % de margen");
    }

    public static String vigencia(ReglaPrecio r) {
        return "desde " + r.vigenteDesde() + (r.vigenteHasta() == null ? "" : " hasta " + r.vigenteHasta());
    }

    public static String valor(Escala e) {
        return e.tipo() == Escala.Tipo.DESCUENTO_PCT ? e.descuentoPct().toPlainString() + " % de descuento"
                : Dinero.formatear(e.precioCentavos()) + " por unidad";
    }

    private String alcance(Connection c, ReglaPrecio r) throws SQLException {
        List<String> partes = new ArrayList<>();
        if (r.productoId() != null) {
            partes.add(productos.porId(c, r.productoId()).map(ProductoCatalogo::nombre).orElse("producto"));
        } else if (r.categoriaId() != null) {
            partes.add("categoría " + nombre(c, "categorias", r.categoriaId()));
        }
        if (r.presentacionId() != null) {
            partes.add(nombre(c, "presentaciones", r.presentacionId()));
        }
        if (r.sucursalId() != null) {
            partes.add(nombre(c, "sucursales", r.sucursalId()));
        }
        return partes.isEmpty() ? "todos los productos" : String.join(" · ", partes);
    }

    private static String nombre(Connection c, String tabla, String id) throws SQLException {
        // tabla es fija (categorias, presentaciones o sucursales), no viene de datos.
        try (PreparedStatement ps = c.prepareStatement("SELECT nombre FROM " + tabla + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "—";
            }
        }
    }

    private static String vacio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
