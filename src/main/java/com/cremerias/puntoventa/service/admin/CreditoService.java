package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Crédito de cada sucursal con el almacén: cargos (surtidos a crédito) y abonos. */
public class CreditoService {

    public record Saldo(Sucursal sucursal, long cargos, long abonos) {
        public long saldo() {
            return cargos - abonos;
        }
    }

    public record Movimiento(String id, String folio, String tipo, long montoCentavos, String nota, Instant fecha,
                             String usuario) {
    }

    /** Abono registrado: para el ticket. */
    public record Abono(String folio, Sucursal sucursal, long montoCentavos, long saldoAnterior, long saldoNuevo,
                        String nota, Instant fecha, String usuario) {
    }

    private final Database database;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public CreditoService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public List<Saldo> saldos(List<Sucursal> sucursales) {
        return database.con(c -> {
            List<Saldo> lista = new ArrayList<>();
            for (Sucursal s : sucursales) {
                lista.add(new Saldo(s, suma(c, s.id(), "CARGO"), suma(c, s.id(), "ABONO")));
            }
            return lista;
        });
    }

    public List<Movimiento> movimientos(String sucursalId) {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT m.id, m.folio, m.tipo, m.monto_centavos, m.nota, m.fecha, u.nombre_completo
                    FROM movimientos_credito m JOIN usuarios u ON u.id = m.usuario_id
                    WHERE m.sucursal_id = ? ORDER BY m.fecha DESC""")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Movimiento> lista = new ArrayList<>();
                    while (rs.next()) {
                        lista.add(new Movimiento(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getString(5), Tiempo.leer(rs.getString(6)), rs.getString(7)));
                    }
                    return lista;
                }
            }
        });
    }

    public Abono abonar(Sucursal sucursal, long monto, String nota, Usuario quien) {
        if (monto <= 0) {
            throw new IllegalArgumentException("El abono debe ser mayor a cero.");
        }
        return database.enTransaccion(c -> {
            long saldo = saldo(c, sucursal.id());
            if (monto > saldo) {
                throw new IllegalArgumentException("El abono no puede ser mayor al saldo (" + Dinero.formatear(saldo) + ").");
            }
            String folio = bitacora.siguienteFolio(c, AccionBitacora.ABONO_CREDITO.prefijo(), dispositivoId);
            String id = Ids.nuevo();
            String ahora = Tiempo.ahora();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO movimientos_credito (id, folio, sucursal_id, tipo, monto_centavos, nota, usuario_id, fecha)
                    VALUES (?, ?, ?, 'ABONO', ?, ?, ?, ?)""")) {
                ps.setString(1, id);
                ps.setString(2, folio);
                ps.setString(3, sucursal.id());
                ps.setLong(4, monto);
                ps.setString(5, nota == null || nota.isBlank() ? null : nota.strip());
                ps.setString(6, quien.id());
                ps.setString(7, ahora);
                ps.executeUpdate();
            }
            bitacora.registrar(c, AccionBitacora.ABONO_CREDITO, quien, dispositivoId, "movimientos_credito", id,
                    sucursal.nombre() + ": abono de " + Dinero.formatear(monto) + ", saldo " + Dinero.formatear(saldo)
                            + " → " + Dinero.formatear(saldo - monto), folio);
            return new Abono(folio, sucursal, monto, saldo, saldo - monto, nota, Tiempo.leer(ahora), quien.nombreCompleto());
        });
    }

    static long saldo(Connection c, String sucursalId) throws SQLException {
        return suma(c, sucursalId, "CARGO") - suma(c, sucursalId, "ABONO");
    }

    private static long suma(Connection c, String sucursalId, String tipo) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(monto_centavos), 0) FROM movimientos_credito WHERE sucursal_id = ? AND tipo = ?")) {
            ps.setString(1, sucursalId);
            ps.setString(2, tipo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
