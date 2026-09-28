package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.VentaEspera;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VentaEsperaRepository {

    public record Renglon(String productoId, String presentacionId, BigDecimal cantidad) {
    }

    public String guardar(Connection c, String tipo, String usuarioId, String nota, List<Renglon> renglones,
                          long totalCentavos) throws SQLException {
        String id = Ids.nuevo();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ventas_espera (id, tipo, usuario_id, nota, creado_en, total_centavos) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, tipo);
            ps.setString(3, usuarioId);
            ps.setString(4, nota);
            ps.setString(5, Tiempo.ahora());
            ps.setLong(6, totalCentavos);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ventas_espera_detalle (id, venta_espera_id, renglon, producto_id, presentacion_id, cantidad)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            int n = 1;
            for (Renglon r : renglones) {
                ps.setString(1, Ids.nuevo());
                ps.setString(2, id);
                ps.setInt(3, n++);
                ps.setString(4, r.productoId());
                ps.setString(5, r.presentacionId());
                ps.setBigDecimal(6, r.cantidad());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return id;
    }

    public List<VentaEspera> listarEspera(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT e.id, e.nota, e.creado_en, COUNT(d.id), e.total_centavos
                FROM ventas_espera e
                LEFT JOIN ventas_espera_detalle d ON d.venta_espera_id = e.id
                WHERE e.tipo = 'ESPERA'
                GROUP BY e.id ORDER BY e.creado_en""");
             ResultSet rs = ps.executeQuery()) {
            List<VentaEspera> lista = new ArrayList<>();
            while (rs.next()) {
                lista.add(new VentaEspera(rs.getString(1), rs.getString(2), Tiempo.leer(rs.getString(3)),
                        rs.getInt(4), rs.getLong(5)));
            }
            return lista;
        }
    }

    public int contarEspera(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ventas_espera WHERE tipo = 'ESPERA'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public List<Renglon> renglones(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT producto_id, presentacion_id, cantidad FROM ventas_espera_detalle WHERE venta_espera_id = ? ORDER BY renglon")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                List<Renglon> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Renglon(rs.getString(1), rs.getString(2), ProductoRepository.decimal(rs, "cantidad")));
                }
                return lista;
            }
        }
    }

    public Optional<String> autoguardado(Connection c, String usuarioId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM ventas_espera WHERE tipo = 'AUTOGUARDADO' AND usuario_id = ?")) {
            ps.setString(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public void eliminar(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM ventas_espera WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public void eliminarAutoguardado(Connection c, String usuarioId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ventas_espera WHERE tipo = 'AUTOGUARDADO' AND usuario_id = ?")) {
            ps.setString(1, usuarioId);
            ps.executeUpdate();
        }
    }
}
