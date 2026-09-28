package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Catálogo de productos, categorías y presentaciones (administrado por el almacén). */
public class ProductoRepository {

    public record Categoria(String id, String nombre) {
        @Override
        public String toString() {
            return nombre;
        }
    }

    public List<ProductoCatalogo> listar(Connection c) throws SQLException {
        Map<String, List<Presentacion>> presentaciones = presentaciones(c, null);
        List<ProductoCatalogo> lista = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.id, p.nombre, p.categoria_id, c.nombre AS categoria, p.clave, p.unidad, p.sujeto_merma,
                       p.disponibilidad, p.activo, p.gramaje_gramos, p.merma_gramos
                FROM productos p LEFT JOIN categorias c ON c.id = p.categoria_id
                WHERE p.eliminado_en IS NULL
                ORDER BY p.nombre COLLATE NOCASE""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(mapear(rs, presentaciones.getOrDefault(rs.getString("id"), List.of())));
            }
        }
        return lista;
    }

    public Optional<ProductoCatalogo> porId(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT p.id, p.nombre, p.categoria_id, c.nombre AS categoria, p.clave, p.unidad, p.sujeto_merma,
                       p.disponibilidad, p.activo, p.gramaje_gramos, p.merma_gramos
                FROM productos p LEFT JOIN categorias c ON c.id = p.categoria_id
                WHERE p.id = ? AND p.eliminado_en IS NULL""")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(mapear(rs, presentaciones(c, id).getOrDefault(id, List.of())))
                        : Optional.empty();
            }
        }
    }

    private Map<String, List<Presentacion>> presentaciones(Connection c, String productoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, producto_id, nombre, tipo, factor, codigo_barras, es_principal, activo
                FROM presentaciones
                WHERE eliminado_en IS NULL AND (? IS NULL OR producto_id = ?)
                ORDER BY es_principal DESC, factor, nombre""")) {
            ps.setString(1, productoId);
            ps.setString(2, productoId);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, List<Presentacion>> mapa = new LinkedHashMap<>();
                while (rs.next()) {
                    mapa.computeIfAbsent(rs.getString("producto_id"), k -> new ArrayList<>()).add(new Presentacion(
                            rs.getString("id"), rs.getString("producto_id"), rs.getString("nombre"),
                            "GRANEL".equals(rs.getString("tipo")), decimal(rs, "factor"),
                            rs.getString("codigo_barras"), rs.getBoolean("es_principal"), rs.getBoolean("activo")));
                }
                return mapa;
            }
        }
    }

    public List<Categoria> categorias(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, nombre FROM categorias WHERE eliminado_en IS NULL ORDER BY orden, nombre");
             ResultSet rs = ps.executeQuery()) {
            List<Categoria> lista = new ArrayList<>();
            while (rs.next()) {
                lista.add(new Categoria(rs.getString(1), rs.getString(2)));
            }
            return lista;
        }
    }

    /** Devuelve el id de la categoría con ese nombre, creándola si no existe. */
    public String categoria(Connection c, String nombre) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM categorias WHERE nombre = ? COLLATE NOCASE")) {
            ps.setString(1, nombre.strip());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }
        String id = Ids.nuevo();
        insertarCategoria(c, id, nombre.strip(), null, 99);
        return id;
    }

    public void insertarCategoria(Connection c, String id, String nombre, String color, int orden)
            throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO categorias (id, nombre, color, orden, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, nombre);
            ps.setString(3, color);
            ps.setInt(4, orden);
            ps.setString(5, ahora);
            ps.setString(6, ahora);
            ps.executeUpdate();
        }
    }

    public void insertar(Connection c, String id, String nombre, String categoriaId, String clave, Unidad unidad,
                         boolean sujetoMerma, Disponibilidad disponibilidad, BigDecimal gramaje, BigDecimal merma)
            throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO productos (id, nombre, categoria_id, clave, unidad, precio_centavos, sujeto_merma,
                                       disponibilidad, gramaje_gramos, merma_gramos, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, nombre);
            ps.setString(3, categoriaId);
            ps.setString(4, clave);
            ps.setString(5, unidad.name());
            ps.setBoolean(6, sujetoMerma);
            ps.setString(7, disponibilidad.name());
            ps.setBigDecimal(8, gramaje);
            ps.setBigDecimal(9, merma);
            ps.setString(10, ahora);
            ps.setString(11, ahora);
            ps.executeUpdate();
        }
    }

    public void actualizar(Connection c, String id, String nombre, String categoriaId, String clave,
                           boolean sujetoMerma, Disponibilidad disponibilidad, BigDecimal gramaje, BigDecimal merma)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE productos SET nombre = ?, categoria_id = ?, clave = ?, sujeto_merma = ?, disponibilidad = ?,
                                     gramaje_gramos = ?, merma_gramos = ?, actualizado_en = ?
                WHERE id = ?""")) {
            ps.setString(1, nombre);
            ps.setString(2, categoriaId);
            ps.setString(3, clave);
            ps.setBoolean(4, sujetoMerma);
            ps.setString(5, disponibilidad.name());
            ps.setBigDecimal(6, gramaje);
            ps.setBigDecimal(7, merma);
            ps.setString(8, Tiempo.ahora());
            ps.setString(9, id);
            ps.executeUpdate();
        }
    }

    public void cambiarActivo(Connection c, String id, boolean activo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET activo = ?, actualizado_en = ? WHERE id = ?")) {
            ps.setBoolean(1, activo);
            ps.setString(2, Tiempo.ahora());
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    /** Borrado lógico: el producto deja de verse pero su historial se conserva. */
    public void eliminar(Connection c, String id) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET eliminado_en = ?, activo = 0, clave = NULL, actualizado_en = ? WHERE id = ?")) {
            ps.setString(1, ahora);
            ps.setString(2, ahora);
            ps.setString(3, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE presentaciones SET eliminado_en = ?, activo = 0, codigo_barras = NULL, actualizado_en = ?
                WHERE producto_id = ? AND eliminado_en IS NULL""")) {
            ps.setString(1, ahora);
            ps.setString(2, ahora);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public void insertarPresentacion(Connection c, Presentacion p) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO presentaciones (id, producto_id, nombre, tipo, factor, codigo_barras, es_principal, activo,
                                            creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, p.id());
            ps.setString(2, p.productoId());
            ps.setString(3, p.nombre());
            ps.setString(4, p.granel() ? "GRANEL" : "UNIDAD");
            ps.setBigDecimal(5, p.factor());
            ps.setString(6, vacioANull(p.codigoBarras()));
            ps.setBoolean(7, p.principal());
            ps.setBoolean(8, p.activo());
            ps.setString(9, ahora);
            ps.setString(10, ahora);
            ps.executeUpdate();
        }
    }

    public void actualizarPresentacion(Connection c, Presentacion p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE presentaciones SET nombre = ?, tipo = ?, factor = ?, codigo_barras = ?, es_principal = ?,
                                          activo = ?, actualizado_en = ?
                WHERE id = ?""")) {
            ps.setString(1, p.nombre());
            ps.setString(2, p.granel() ? "GRANEL" : "UNIDAD");
            ps.setBigDecimal(3, p.factor());
            ps.setString(4, vacioANull(p.codigoBarras()));
            ps.setBoolean(5, p.principal());
            ps.setBoolean(6, p.activo());
            ps.setString(7, Tiempo.ahora());
            ps.setString(8, p.id());
            ps.executeUpdate();
        }
    }

    public void eliminarPresentacion(Connection c, String id) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE presentaciones SET eliminado_en = ?, activo = 0, codigo_barras = NULL, actualizado_en = ?
                WHERE id = ?""")) {
            ps.setString(1, ahora);
            ps.setString(2, ahora);
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    /** Nombre del producto que ya usa esa clave o código de barras (para avisar duplicados). */
    public Optional<String> usoDeClave(Connection c, String clave, String excluirProductoId) throws SQLException {
        return uso(c, "SELECT nombre FROM productos WHERE clave = ? COLLATE NOCASE AND id <> ?", clave, excluirProductoId);
    }

    public Optional<String> usoDeCodigo(Connection c, String codigo, String excluirPresentacionId) throws SQLException {
        return uso(c, """
                SELECT p.nombre || ' · ' || pr.nombre FROM presentaciones pr JOIN productos p ON p.id = pr.producto_id
                WHERE pr.codigo_barras = ? AND pr.id <> ?""", codigo, excluirPresentacionId);
    }

    private static Optional<String> uso(Connection c, String sql, String valor, String excluir) throws SQLException {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, valor.strip());
            ps.setString(2, excluir == null ? "" : excluir);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public int contar(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM productos");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public Map<String, String> nombres(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, nombre FROM productos");
             ResultSet rs = ps.executeQuery()) {
            Map<String, String> mapa = new HashMap<>();
            while (rs.next()) {
                mapa.put(rs.getString(1), rs.getString(2));
            }
            return mapa;
        }
    }

    private static ProductoCatalogo mapear(ResultSet rs, List<Presentacion> presentaciones) throws SQLException {
        return new ProductoCatalogo(rs.getString("id"), rs.getString("nombre"), rs.getString("categoria_id"),
                rs.getString("categoria"), rs.getString("clave"), Unidad.valueOf(rs.getString("unidad")),
                rs.getBoolean("sujeto_merma"), Disponibilidad.valueOf(rs.getString("disponibilidad")),
                rs.getBoolean("activo"), List.copyOf(presentaciones), gramos(rs, "gramaje_gramos"),
                gramos(rs, "merma_gramos") == null ? BigDecimal.ZERO : gramos(rs, "merma_gramos"));
    }

    /** Gramos con hasta 6 decimales (se admiten microgramos); nulo si no se ha capturado. */
    private static BigDecimal gramos(ResultSet rs, String columna) throws SQLException {
        double valor = rs.getDouble(columna);
        return rs.wasNull() ? null : BigDecimal.valueOf(valor).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String vacioANull(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    static BigDecimal decimal(ResultSet rs, String columna) throws SQLException {
        return BigDecimal.valueOf(rs.getDouble(columna)).setScale(3, RoundingMode.HALF_UP);
    }

    static BigDecimal decimal(ResultSet rs, int columna) throws SQLException {
        return BigDecimal.valueOf(rs.getDouble(columna)).setScale(3, RoundingMode.HALF_UP);
    }
}
