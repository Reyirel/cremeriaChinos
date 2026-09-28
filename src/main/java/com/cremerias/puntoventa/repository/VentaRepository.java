package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.VentaResumen;
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

public class VentaRepository {

    public record NuevaVenta(String id, String folio, String turnoId, String sucursalId, String dispositivoId,
                             String usuarioId, BigDecimal articulos, long total, long pagado, long cambio) {
    }

    public void insertar(Connection c, NuevaVenta v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ventas (id, folio, turno_id, sucursal_id, dispositivo_id, usuario_id, fecha, articulos,
                                    total_centavos, pagado_centavos, cambio_centavos)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, v.id());
            ps.setString(2, v.folio());
            ps.setString(3, v.turnoId());
            ps.setString(4, v.sucursalId());
            ps.setString(5, v.dispositivoId());
            ps.setString(6, v.usuarioId());
            ps.setString(7, Tiempo.ahora());
            ps.setBigDecimal(8, v.articulos());
            ps.setLong(9, v.total());
            ps.setLong(10, v.pagado());
            ps.setLong(11, v.cambio());
            ps.executeUpdate();
        }
    }

    public void insertarDetalle(Connection c, String ventaId, int renglon, String productoId, String presentacionId,
                                String descripcion, Unidad unidad, BigDecimal cantidad, BigDecimal cantidadBase,
                                long precio, long importe) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO venta_detalle (id, venta_id, renglon, producto_id, presentacion_id, descripcion, unidad,
                                           cantidad, cantidad_base, precio_unitario_centavos, importe_centavos)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, ventaId);
            ps.setInt(3, renglon);
            ps.setString(4, productoId);
            ps.setString(5, presentacionId);
            ps.setString(6, descripcion);
            ps.setString(7, unidad.name());
            ps.setBigDecimal(8, cantidad);
            ps.setBigDecimal(9, cantidadBase);
            ps.setLong(10, precio);
            ps.setLong(11, importe);
            ps.executeUpdate();
        }
    }

    public void insertarPago(Connection c, String ventaId, MetodoPago metodo, long monto, long recibido,
                             String referencia) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO venta_pagos (id, venta_id, metodo, monto_centavos, recibido_centavos, referencia)
                VALUES (?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, ventaId);
            ps.setString(3, metodo.name());
            ps.setLong(4, monto);
            ps.setLong(5, recibido);
            ps.setString(6, referencia);
            ps.executeUpdate();
        }
    }

    public List<VentaResumen> delTurno(Connection c, String turnoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT v.id, v.folio, v.fecha, v.articulos, v.total_centavos, v.estado,
                       (SELECT GROUP_CONCAT(metodo, ',') FROM venta_pagos p WHERE p.venta_id = v.id) AS metodos
                FROM ventas v WHERE v.turno_id = ? ORDER BY v.fecha DESC""")) {
            ps.setString(1, turnoId);
            try (ResultSet rs = ps.executeQuery()) {
                List<VentaResumen> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new VentaResumen(rs.getString("id"), rs.getString("folio"),
                            Tiempo.leer(rs.getString("fecha")), ProductoRepository.decimal(rs, "articulos"),
                            rs.getLong("total_centavos"), nombresMetodos(rs.getString("metodos")),
                            "CANCELADA".equals(rs.getString("estado"))));
                }
                return lista;
            }
        }
    }

    private static String nombresMetodos(String metodos) {
        if (metodos == null) {
            return "";
        }
        List<String> nombres = new ArrayList<>();
        for (String m : metodos.split(",")) {
            String nombre = MetodoPago.valueOf(m).nombre();
            if (!nombres.contains(nombre)) {
                nombres.add(nombre);
            }
        }
        return String.join(" + ", nombres);
    }

    public record DatosCancelacion(String turnoId, String sucursalId, boolean cancelada) {
    }

    public Optional<DatosCancelacion> datosCancelacion(Connection c, String ventaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT turno_id, sucursal_id, estado FROM ventas WHERE id = ?")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new DatosCancelacion(rs.getString(1), rs.getString(2),
                        "CANCELADA".equals(rs.getString(3))))
                        : Optional.empty();
            }
        }
    }

    public void marcarCancelada(Connection c, String ventaId, String usuarioId, String autorizadaPor, String motivo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE ventas SET estado = 'CANCELADA', cancelada_en = ?, cancelada_por = ?, autorizada_por = ?,
                                  motivo_cancelacion = ?
                WHERE id = ? AND estado = 'COMPLETADA'""")) {
            ps.setString(1, Tiempo.ahora());
            ps.setString(2, usuarioId);
            ps.setString(3, autorizadaPor);
            ps.setString(4, motivo);
            ps.setString(5, ventaId);
            ps.executeUpdate();
        }
    }

    /** Lo que salió de cada lote en una venta, para regresarlo si se cancela. */
    public record SalidaLote(String productoId, String loteId, BigDecimal cantidad) {
    }

    public List<SalidaLote> salidasDeVenta(Connection c, String ventaId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT producto_id, lote_id, -cantidad FROM movimientos_inventario
                WHERE referencia_id = ? AND tipo = 'VENTA'""")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SalidaLote> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new SalidaLote(rs.getString(1), rs.getString(2), ProductoRepository.decimal(rs, 3)));
                }
                return lista;
            }
        }
    }

    public Optional<Ticket> ticket(Connection c, String ventaId) throws SQLException {
        String folio;
        String fecha;
        String sucursal;
        String cajero;
        long total;
        long cambio;
        BigDecimal articulos;
        boolean cancelada;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT v.folio, v.fecha, COALESCE(s.nombre, ''), u.nombre_completo, v.total_centavos,
                       v.cambio_centavos, v.articulos, v.estado
                FROM ventas v
                JOIN usuarios u ON u.id = v.usuario_id
                LEFT JOIN sucursales s ON s.id = v.sucursal_id
                WHERE v.id = ?""")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                folio = rs.getString(1);
                fecha = rs.getString(2);
                sucursal = rs.getString(3);
                cajero = rs.getString(4);
                total = rs.getLong(5);
                cambio = rs.getLong(6);
                articulos = ProductoRepository.decimal(rs, "articulos");
                cancelada = "CANCELADA".equals(rs.getString(8));
            }
        }

        List<Ticket.Renglon> renglones = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT descripcion, unidad, cantidad, precio_unitario_centavos, importe_centavos
                FROM venta_detalle WHERE venta_id = ? ORDER BY renglon""")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Unidad unidad = Unidad.valueOf(rs.getString(2));
                    renglones.add(new Ticket.Renglon(rs.getString(1), unidad,
                            unidad.normalizar(ProductoRepository.decimal(rs, "cantidad")), rs.getLong(4), rs.getLong(5)));
                }
            }
        }

        List<Ticket.PagoTicket> pagos = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT metodo, recibido_centavos, referencia FROM venta_pagos WHERE venta_id = ?")) {
            ps.setString(1, ventaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pagos.add(new Ticket.PagoTicket(MetodoPago.valueOf(rs.getString(1)), rs.getLong(2), rs.getString(3)));
                }
            }
        }
        return Optional.of(new Ticket(ventaId, folio, Tiempo.leer(fecha), sucursal, cajero, renglones, total, pagos,
                cambio, articulos, cancelada));
    }
}
