package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.util.CodigoBarras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Catálogo de la caja en memoria para que la búsqueda sea instantánea (sin importar
 * mayúsculas ni acentos). Cada artículo es una presentación con su precio por lote en esta
 * sucursal; solo se muestran las presentaciones que tienen precio. La sucursal es la de quien
 * entra a la caja (su turno y sus ventas son de esa sucursal). Se recarga al entrar a la caja y
 * después de cada venta para reflejar existencias, surtidos y cambios de lote.
 */
public class CatalogoService {

    /** Producto encontrado por código; {@code cantidad} viene de la etiqueta de báscula si aplica. */
    public record Escaneo(Producto producto, BigDecimal cantidad) {
    }

    private record Indexado(Producto producto, String nombreNormalizado) {
    }

    private final Database database;
    private volatile String sucursalId;
    private final LoteRepository lotes = new LoteRepository();

    private volatile List<Indexado> indice = List.of();
    private volatile Map<String, Producto> porCodigo = Map.of();
    private volatile Map<String, Producto> porClave = Map.of();
    private volatile Map<String, Producto> porCodigoInventario = Map.of();
    private volatile Map<String, Producto> porId = Map.of();

    public CatalogoService(Database database, String sucursalId) {
        this.database = database;
        this.sucursalId = sucursalId;
    }

    public String sucursalId() {
        return sucursalId;
    }

    /** Cambia a la sucursal de quien entra a la caja y carga su catálogo. */
    public void cargarSucursal(String sucursalId) {
        this.sucursalId = sucursalId;
        recargar();
    }

    public void recargar() {
        String sucursal = sucursalId;
        List<Producto> lista = sucursal == null ? List.of() : database.con(c -> cargar(c, sucursal));
        List<Indexado> nuevoIndice = new ArrayList<>(lista.size());
        Map<String, Producto> codigos = new HashMap<>();
        Map<String, Producto> claves = new HashMap<>();
        Map<String, Producto> codigosInventario = new HashMap<>();
        Map<String, Producto> ids = new HashMap<>();
        for (Producto p : lista) {
            nuevoIndice.add(new Indexado(p, normalizar(p.nombre())));
            if (p.codigoBarras() != null) {
                codigos.put(p.codigoBarras(), p);
            }
            // La clave/PLU apunta a la presentación a granel (o la primera) del producto.
            if (p.clave() != null) {
                claves.merge(p.clave().toLowerCase(Locale.ROOT), p,
                        (actual, nuevo) -> actual.unidad() == Unidad.KG ? actual : nuevo.unidad() == Unidad.KG ? nuevo : actual);
            }
            if (p.codigoInventario() != null) {
                codigosInventario.merge(p.codigoInventario().toLowerCase(Locale.ROOT), p,
                        (actual, nuevo) -> actual.unidad() == Unidad.KG ? actual : nuevo.unidad() == Unidad.KG ? nuevo : actual);
            }
            ids.put(p.id(), p);
        }
        indice = List.copyOf(nuevoIndice);
        porCodigo = Map.copyOf(codigos);
        porClave = Map.copyOf(claves);
        porCodigoInventario = Map.copyOf(codigosInventario);
        porId = Map.copyOf(ids);
    }

    private List<Producto> cargar(Connection c, String sucursalId) throws SQLException {
        Map<String, List<LoteRepository.Lote>> lotesPorProducto = new HashMap<>();
        for (LoteRepository.Lote lote : lotes.deSucursal(c, sucursalId)) {
            lotesPorProducto.computeIfAbsent(lote.productoId(), k -> new ArrayList<>()).add(lote);
        }
        Map<String, Map<String, Long>> precios = lotes.preciosDeSucursal(c, sucursalId);
        Map<String, BigDecimal> existencias = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, existencia FROM v_existencias WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existencias.put(rs.getString(1), BigDecimal.valueOf(rs.getDouble(2)).setScale(3, RoundingMode.HALF_UP));
                }
            }
        }
        Map<String, BigDecimal> minimos = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, minimo FROM limites_sucursal WHERE sucursal_id = ?")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    minimos.put(rs.getString(1), BigDecimal.valueOf(rs.getDouble(2)).setScale(3, RoundingMode.HALF_UP));
                }
            }
        }

        List<Producto> lista = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT pr.id, pr.producto_id, pr.nombre AS presentacion, pr.tipo, pr.factor, pr.codigo_barras,
                       pr.es_principal, p.nombre, p.clave, p.codigo_inventario, p.unidad, c.nombre AS categoria
                FROM presentaciones pr
                JOIN productos p ON p.id = pr.producto_id
                LEFT JOIN categorias c ON c.id = p.categoria_id
                WHERE pr.activo = 1 AND pr.eliminado_en IS NULL AND p.activo = 1 AND p.eliminado_en IS NULL
                ORDER BY p.nombre COLLATE NOCASE, pr.es_principal DESC, pr.nombre""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String productoId = rs.getString("producto_id");
                PreciosLote.Tramos tramos = PreciosLote.de(lotesPorProducto.getOrDefault(productoId, List.of()),
                        precios, rs.getString("id"));
                if (tramos == null) {
                    continue; // Sin precio en esta sucursal: aún no se ha surtido.
                }
                boolean principal = rs.getBoolean("es_principal");
                String nombre = rs.getString("nombre") + (principal ? "" : " · " + rs.getString("presentacion"));
                lista.add(new Producto(
                        rs.getString("id"), productoId, rs.getString("codigo_barras"), rs.getString("clave"),
                        rs.getString("codigo_inventario"), nombre, rs.getString("categoria"),
                        "GRANEL".equals(rs.getString("tipo")) ? Unidad.KG : Unidad.PZA,
                        Unidad.valueOf(rs.getString("unidad")),
                        BigDecimal.valueOf(rs.getDouble("factor")).setScale(3, RoundingMode.HALF_UP),
                        tramos.precioActual(),
                        existencias.getOrDefault(productoId, BigDecimal.ZERO.setScale(3)),
                        minimos.getOrDefault(productoId, BigDecimal.ZERO),
                        tramos.tramos(), tramos.extra()));
            }
        }
        return lista;
    }

    public Optional<Producto> porId(String id) {
        return Optional.ofNullable(porId.get(id));
    }

    /** Código de barras exacto, clave/PLU exacta, código de inventario exacto o etiqueta de báscula. */
    public Optional<Escaneo> resolverCodigo(String texto) {
        String codigo = texto.strip();
        if (codigo.isEmpty()) {
            return Optional.empty();
        }
        Producto producto = porCodigo.get(codigo);
        if (producto == null) {
            producto = porClave.get(codigo.toLowerCase(Locale.ROOT));
        }
        if (producto == null) {
            producto = porCodigoInventario.get(codigo.toLowerCase(Locale.ROOT));
        }
        if (producto != null) {
            return Optional.of(new Escaneo(producto, null));
        }
        return CodigoBarras.leerEtiquetaBascula(codigo).flatMap(etiqueta -> {
            Producto pesado = porClave.get(etiqueta.plu());
            return pesado == null || pesado.unidad() != Unidad.KG
                    ? Optional.empty()
                    : Optional.of(new Escaneo(pesado, etiqueta.kilos()));
        });
    }

    /** Búsqueda por nombre (todas las palabras deben aparecer) o por inicio de clave/código. */
    public List<Producto> buscar(String texto, int limite) {
        String consulta = normalizar(texto);
        if (consulta.isBlank()) {
            return List.of();
        }
        String[] palabras = consulta.split("\\s+");
        record Resultado(Producto producto, int puntaje) {
        }
        List<Resultado> resultados = new ArrayList<>();
        for (Indexado item : indice) {
            Producto p = item.producto();
            int puntaje = puntaje(item.nombreNormalizado(), consulta, palabras);
            if (puntaje < 0 && p.clave() != null && p.clave().toLowerCase(Locale.ROOT).startsWith(consulta)) {
                puntaje = 0;
            }
            if (puntaje < 0 && p.codigoInventario() != null
                    && p.codigoInventario().toLowerCase(Locale.ROOT).startsWith(consulta)) {
                puntaje = 0;
            }
            if (puntaje < 0 && p.codigoBarras() != null && p.codigoBarras().startsWith(consulta)) {
                puntaje = 3;
            }
            if (puntaje >= 0) {
                resultados.add(new Resultado(p, puntaje));
            }
        }
        return resultados.stream()
                .sorted(Comparator.comparingInt(Resultado::puntaje)
                        .thenComparing(r -> r.producto().nombre(), String.CASE_INSENSITIVE_ORDER))
                .limit(limite)
                .map(Resultado::producto)
                .toList();
    }

    private static int puntaje(String nombre, String consulta, String[] palabras) {
        for (String palabra : palabras) {
            if (!nombre.contains(palabra)) {
                return -1;
            }
        }
        if (nombre.startsWith(consulta)) {
            return 0;
        }
        return nombre.contains(" " + palabras[0]) ? 1 : 2;
    }

    static String normalizar(String texto) {
        String sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
