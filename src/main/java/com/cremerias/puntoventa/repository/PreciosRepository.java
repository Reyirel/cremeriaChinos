package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.Escala;
import com.cremerias.puntoventa.model.ReglaPrecio;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reglas de precio por canal y escalas de mayoreo. */
public class PreciosRepository {

    private static final String SELECT_REGLA = """
            SELECT id, folio, canal, tipo, porcentaje, categoria_id, producto_id, presentacion_id, sucursal_id,
                   vigente_desde, vigente_hasta, notas, activo
            FROM reglas_precio WHERE eliminado_en IS NULL""";

    private static final String SELECT_ESCALA = """
            SELECT id, folio, producto_id, presentacion_id, sucursal_id, nivel, cantidad_minima, tipo, descuento_pct,
                   precio_centavos, vigente_desde, vigente_hasta, activo
            FROM escalas_precio WHERE eliminado_en IS NULL""";

    // ------------------------------------------------------------------ reglas

    public List<ReglaPrecio> reglas(Connection c) throws SQLException {
        return consultarReglas(c, SELECT_REGLA + " ORDER BY canal, vigente_desde DESC, folio");
    }

    /** Reglas activas del canal (de cualquier vigencia; se filtra por fecha al aplicar). */
    public List<ReglaPrecio> reglasDeCanal(Connection c, ReglaPrecio.Canal canal) throws SQLException {
        return consultarReglas(c, SELECT_REGLA + " AND activo = 1 AND canal = ?", canal.name());
    }

    public Optional<ReglaPrecio> regla(Connection c, String id) throws SQLException {
        List<ReglaPrecio> lista = consultarReglas(c, SELECT_REGLA + " AND id = ?", id);
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.getFirst());
    }

    public void insertarRegla(Connection c, ReglaPrecio r, String usuarioId) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reglas_precio (id, folio, canal, tipo, porcentaje, categoria_id, producto_id, presentacion_id,
                                           sucursal_id, vigente_desde, vigente_hasta, notas, activo, usuario_id,
                                           creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)""")) {
            ps.setString(1, r.id());
            ps.setString(2, r.folio());
            ps.setString(3, r.canal().name());
            ps.setString(4, r.tipo().name());
            ps.setBigDecimal(5, r.porcentaje());
            ps.setString(6, r.categoriaId());
            ps.setString(7, r.productoId());
            ps.setString(8, r.presentacionId());
            ps.setString(9, r.sucursalId());
            ps.setString(10, r.vigenteDesde().toString());
            ps.setString(11, r.vigenteHasta() == null ? null : r.vigenteHasta().toString());
            ps.setString(12, r.notas());
            ps.setString(13, usuarioId);
            ps.setString(14, ahora);
            ps.setString(15, ahora);
            ps.executeUpdate();
        }
    }

    public void actualizarRegla(Connection c, ReglaPrecio r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE reglas_precio SET canal = ?, tipo = ?, porcentaje = ?, categoria_id = ?, producto_id = ?,
                       presentacion_id = ?, sucursal_id = ?, vigente_desde = ?, vigente_hasta = ?, notas = ?,
                       actualizado_en = ?
                WHERE id = ?""")) {
            ps.setString(1, r.canal().name());
            ps.setString(2, r.tipo().name());
            ps.setBigDecimal(3, r.porcentaje());
            ps.setString(4, r.categoriaId());
            ps.setString(5, r.productoId());
            ps.setString(6, r.presentacionId());
            ps.setString(7, r.sucursalId());
            ps.setString(8, r.vigenteDesde().toString());
            ps.setString(9, r.vigenteHasta() == null ? null : r.vigenteHasta().toString());
            ps.setString(10, r.notas());
            ps.setString(11, Tiempo.ahora());
            ps.setString(12, r.id());
            ps.executeUpdate();
        }
    }

    public void desactivarRegla(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE reglas_precio SET activo = 0, actualizado_en = ? WHERE id = ?")) {
            ps.setString(1, Tiempo.ahora());
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private List<ReglaPrecio> consultarReglas(Connection c, String sql, String... parametros) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setString(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ReglaPrecio> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new ReglaPrecio(rs.getString("id"), rs.getString("folio"),
                            ReglaPrecio.Canal.valueOf(rs.getString("canal")), ReglaPrecio.Tipo.valueOf(rs.getString("tipo")),
                            decimal(rs, "porcentaje"), rs.getString("categoria_id"), rs.getString("producto_id"),
                            rs.getString("presentacion_id"), rs.getString("sucursal_id"),
                            LocalDate.parse(rs.getString("vigente_desde")), fecha(rs.getString("vigente_hasta")),
                            rs.getString("notas"), rs.getInt("activo") == 1));
                }
                return lista;
            }
        }
    }

    // ------------------------------------------------------------------ escalas

    public List<Escala> escalas(Connection c) throws SQLException {
        return consultarEscalas(c, SELECT_ESCALA + " ORDER BY producto_id, cantidad_minima");
    }

    public List<Escala> escalasDeProducto(Connection c, String productoId) throws SQLException {
        return consultarEscalas(c, SELECT_ESCALA + " AND producto_id = ? ORDER BY cantidad_minima", productoId);
    }

    /** Escalas activas que pueden aplicar en la sucursal (las suyas y las de todas las sucursales). */
    public List<Escala> escalasDeSucursal(Connection c, String sucursalId) throws SQLException {
        return consultarEscalas(c, SELECT_ESCALA + " AND activo = 1 AND (sucursal_id IS NULL OR sucursal_id = ?)",
                sucursalId);
    }

    public void insertarEscala(Connection c, Escala e, String usuarioId) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO escalas_precio (id, folio, producto_id, presentacion_id, sucursal_id, nivel, cantidad_minima,
                                            tipo, descuento_pct, precio_centavos, vigente_desde, vigente_hasta, activo,
                                            usuario_id, creado_en, actualizado_en)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)""")) {
            ps.setString(1, e.id());
            ps.setString(2, e.folio());
            ps.setString(3, e.productoId());
            ps.setString(4, e.presentacionId());
            ps.setString(5, e.sucursalId());
            ps.setString(6, e.nivel().name());
            ps.setBigDecimal(7, e.cantidadMinima());
            ps.setString(8, e.tipo().name());
            if (e.descuentoPct() == null) {
                ps.setNull(9, Types.REAL);
            } else {
                ps.setBigDecimal(9, e.descuentoPct());
            }
            if (e.precioCentavos() == null) {
                ps.setNull(10, Types.INTEGER);
            } else {
                ps.setLong(10, e.precioCentavos());
            }
            ps.setString(11, e.vigenteDesde().toString());
            ps.setString(12, e.vigenteHasta() == null ? null : e.vigenteHasta().toString());
            ps.setString(13, usuarioId);
            ps.setString(14, ahora);
            ps.setString(15, ahora);
            ps.executeUpdate();
        }
    }

    public void desactivarEscala(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE escalas_precio SET activo = 0, actualizado_en = ? WHERE id = ?")) {
            ps.setString(1, Tiempo.ahora());
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private List<Escala> consultarEscalas(Connection c, String sql, String... parametros) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                ps.setString(i + 1, parametros[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Escala> lista = new ArrayList<>();
                while (rs.next()) {
                    long precio = rs.getLong("precio_centavos");
                    Long precioCentavos = rs.wasNull() ? null : precio;
                    lista.add(new Escala(rs.getString("id"), rs.getString("folio"), rs.getString("producto_id"),
                            rs.getString("presentacion_id"), rs.getString("sucursal_id"),
                            Escala.Nivel.valueOf(rs.getString("nivel")), decimal(rs, "cantidad_minima"),
                            Escala.Tipo.valueOf(rs.getString("tipo")), decimal(rs, "descuento_pct"), precioCentavos,
                            LocalDate.parse(rs.getString("vigente_desde")), fecha(rs.getString("vigente_hasta")),
                            rs.getInt("activo") == 1));
                }
                return lista;
            }
        }
    }

    /** Porcentajes y cantidades sin ceros de más (23.0 → 23, 12.50 → 12.5). */
    private static BigDecimal decimal(ResultSet rs, String columna) throws SQLException {
        double valor = rs.getDouble(columna);
        if (rs.wasNull()) {
            return null;
        }
        BigDecimal limpio = BigDecimal.valueOf(valor).stripTrailingZeros();
        return limpio.scale() < 0 ? limpio.setScale(0) : limpio;
    }

    private static LocalDate fecha(String texto) {
        return texto == null ? null : LocalDate.parse(texto);
    }
}
