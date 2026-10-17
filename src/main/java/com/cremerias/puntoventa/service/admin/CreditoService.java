package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.service.Permisos;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crédito de cada sucursal con el almacén: cargos (surtidos a crédito, al precio interno) y abonos.
 * Un abono es un pago o una nota de crédito (devolución, diferencia de entrega, cancelación de un
 * surtido, ajuste); el saldo siempre es cargos − abonos y nunca queda negativo.
 */
public class CreditoService {

    /** Concepto de un movimiento de crédito. */
    public enum Concepto {
        SURTIDO("Surtido a crédito"), PAGO("Abono"), DEVOLUCION("Nota de crédito por devolución"),
        DIFERENCIA_ENTREGA("Nota de crédito por diferencia de entrega"),
        CANCELACION("Nota de crédito por cancelación"), AJUSTE("Ajuste de saldo");

        private final String nombre;

        Concepto(String nombre) {
            this.nombre = nombre;
        }

        public String nombre() {
            return nombre;
        }
    }

    public record Saldo(Sucursal sucursal, long cargos, long abonos) {
        public long saldo() {
            return cargos - abonos;
        }
    }

    /**
     * @param surtidoId    surtido del cargo (o del que se cancela/acredita), si aplica
     * @param referenciaId documento que originó el movimiento (surtido, devolución, aclaración…)
     */
    public record Movimiento(String id, String folio, String tipo, long montoCentavos, String nota, Instant fecha,
                             String usuario, Concepto concepto, String surtidoId, String referenciaId) {

        public boolean esCargo() {
            return "CARGO".equals(tipo);
        }
    }

    /** Abono registrado: para el ticket. */
    public record Abono(String folio, Sucursal sucursal, long montoCentavos, long saldoAnterior, long saldoNuevo,
                        String nota, Instant fecha, String usuario) {
    }

    /**
     * Cargo con lo que aún falta pagar. Los abonos se aplican primero a la nota que mencionan (una
     * cancelación o diferencia de ese surtido) y el resto a los cargos más antiguos.
     */
    public record NotaPendiente(String cargoId, String folio, String surtidoId, Instant fecha, long importeCentavos,
                                long abonadoCentavos, long pendienteCentavos, long dias) {
    }

    /** Saldo pendiente por antigüedad de la nota. */
    public record Antiguedad(long de0a30, long de31a60, long de61a90, long masDe90) {
        public long total() {
            return de0a30 + de31a60 + de61a90 + masDe90;
        }
    }

    private final Database database;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public CreditoService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Saldo (cargos − abonos) de una sola sucursal, para el módulo Indicadores. */
    public long saldo(String sucursalId) {
        return database.con(c -> saldo(c, sucursalId));
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
        return database.con(c -> movimientos(c, sucursalId));
    }

    private static List<Movimiento> movimientos(Connection c, String sucursalId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT m.id, m.folio, m.tipo, m.monto_centavos, m.nota, m.fecha, u.nombre_completo, m.concepto,
                       m.surtido_id, m.referencia_id
                FROM movimientos_credito m JOIN usuarios u ON u.id = m.usuario_id
                WHERE m.sucursal_id = ? ORDER BY m.fecha DESC, m.rowid DESC""")) {
            ps.setString(1, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Movimiento> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Movimiento(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5), Tiempo.leer(rs.getString(6)), rs.getString(7),
                            concepto(rs.getString(3), rs.getString(8)), rs.getString(9), rs.getString(10)));
                }
                return lista;
            }
        }
    }

    public Abono abonar(Sucursal sucursal, long monto, String nota, Usuario quien) {
        Permisos.exigirAdministrador(quien, "registrar abonos");
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
            String ahora = insertarMovimiento(c, id, folio, sucursal.id(), "ABONO", Concepto.PAGO.name(), monto, null, null,
                    nota == null || nota.isBlank() ? null : nota.strip(), quien.id());
            bitacora.registrar(c, AccionBitacora.ABONO_CREDITO, quien, dispositivoId, "movimientos_credito", id,
                    sucursal.nombre() + ": abono de " + Dinero.formatear(monto) + ", saldo " + Dinero.formatear(saldo)
                            + " → " + Dinero.formatear(saldo - monto), folio);
            return new Abono(folio, sucursal, monto, saldo, saldo - monto, nota, Tiempo.leer(ahora), quien.nombreCompleto());
        });
    }

    /**
     * Nota de crédito dentro de otra operación (devolución, diferencia, cancelación). Si el monto pasa
     * del saldo solo se acredita hasta el saldo: no hay anticipos. Devuelve lo acreditado.
     */
    static long notaDeCredito(Connection c, BitacoraRepository bitacora, String dispositivoId, String sucursalId,
                              String sucursalNombre, Concepto concepto, long monto, String surtidoId, String referenciaId,
                              String nota, Usuario quien) throws SQLException {
        long saldo = saldo(c, sucursalId);
        long acreditado = Math.min(monto, Math.max(0, saldo));
        if (acreditado <= 0) {
            return 0;
        }
        String folio = bitacora.siguienteFolio(c, AccionBitacora.NOTA_CREDITO.prefijo(), dispositivoId);
        String id = Ids.nuevo();
        insertarMovimiento(c, id, folio, sucursalId, "ABONO", concepto.name(), acreditado, surtidoId, referenciaId, nota,
                quien.id());
        bitacora.registrar(c, AccionBitacora.NOTA_CREDITO, quien, dispositivoId, "movimientos_credito", id,
                sucursalNombre + ": " + concepto.nombre().toLowerCase() + " por " + Dinero.formatear(acreditado)
                        + (acreditado < monto ? " (de " + Dinero.formatear(monto) + "; el saldo no alcanzaba)" : "")
                        + ", saldo " + Dinero.formatear(saldo) + " → " + Dinero.formatear(saldo - acreditado), folio);
        return acreditado;
    }

    /** Inserta un movimiento de crédito. Devuelve la fecha con que quedó. */
    static String insertarMovimiento(Connection c, String id, String folio, String sucursalId, String tipo,
                                     String concepto, long monto, String surtidoId, String referenciaId, String nota,
                                     String usuarioId) throws SQLException {
        String ahora = Tiempo.ahora();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO movimientos_credito (id, folio, sucursal_id, tipo, monto_centavos, surtido_id, nota, usuario_id,
                                                 fecha, concepto, referencia_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, folio);
            ps.setString(3, sucursalId);
            ps.setString(4, tipo);
            ps.setLong(5, monto);
            ps.setString(6, surtidoId);
            ps.setString(7, nota);
            ps.setString(8, usuarioId);
            ps.setString(9, ahora);
            ps.setString(10, concepto);
            ps.setString(11, referenciaId);
            ps.executeUpdate();
        }
        return ahora;
    }

    /** Cargos con lo que aún se debe de cada uno (los ya pagados no aparecen), del más antiguo al más nuevo. */
    public List<NotaPendiente> notasPendientes(String sucursalId, LocalDate hoy) {
        return database.con(c -> notasPendientes(movimientos(c, sucursalId), hoy));
    }

    static List<NotaPendiente> notasPendientes(List<Movimiento> movimientos, LocalDate hoy) {
        List<Movimiento> cargos = new ArrayList<>(movimientos.stream().filter(Movimiento::esCargo).toList());
        cargos.sort((a, b) -> a.fecha().compareTo(b.fecha()));
        Map<String, long[]> abonado = new LinkedHashMap<>();
        cargos.forEach(cg -> abonado.put(cg.id(), new long[]{0}));
        long libre = 0;
        // Primero las notas de crédito que mencionan su surtido; el resto (pagos) al cargo más antiguo.
        for (Movimiento m : movimientos) {
            if (m.esCargo()) {
                continue;
            }
            long monto = m.montoCentavos();
            if (m.concepto() != Concepto.PAGO && m.surtidoId() != null) {
                for (Movimiento cargo : cargos) {
                    if (m.surtidoId().equals(cargo.surtidoId())) {
                        long[] a = abonado.get(cargo.id());
                        long aplica = Math.min(monto, cargo.montoCentavos() - a[0]);
                        a[0] += aplica;
                        monto -= aplica;
                        break;
                    }
                }
            }
            libre += monto;
        }
        for (Movimiento cargo : cargos) {
            long[] a = abonado.get(cargo.id());
            long aplica = Math.min(libre, cargo.montoCentavos() - a[0]);
            a[0] += aplica;
            libre -= aplica;
        }
        List<NotaPendiente> pendientes = new ArrayList<>();
        for (Movimiento cargo : cargos) {
            long pagado = abonado.get(cargo.id())[0];
            long pendiente = cargo.montoCentavos() - pagado;
            if (pendiente > 0) {
                long dias = ChronoUnit.DAYS.between(cargo.fecha().atZone(ZoneId.systemDefault()).toLocalDate(), hoy);
                pendientes.add(new NotaPendiente(cargo.id(), cargo.folio(), cargo.surtidoId(), cargo.fecha(),
                        cargo.montoCentavos(), pagado, pendiente, Math.max(0, dias)));
            }
        }
        return pendientes;
    }

    /** Saldo pendiente de la sucursal por antigüedad de sus notas: 0–30, 31–60, 61–90 y más de 90 días. */
    public Antiguedad antiguedad(String sucursalId, LocalDate hoy) {
        return antiguedad(notasPendientes(sucursalId, hoy));
    }

    static Antiguedad antiguedad(List<NotaPendiente> notas) {
        long a = 0;
        long b = 0;
        long c = 0;
        long d = 0;
        for (NotaPendiente n : notas) {
            if (n.dias() <= 30) {
                a += n.pendienteCentavos();
            } else if (n.dias() <= 60) {
                b += n.pendienteCentavos();
            } else if (n.dias() <= 90) {
                c += n.pendienteCentavos();
            } else {
                d += n.pendienteCentavos();
            }
        }
        return new Antiguedad(a, b, c, d);
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

    private static Concepto concepto(String tipo, String texto) {
        if (texto == null) {
            return "CARGO".equals(tipo) ? Concepto.SURTIDO : Concepto.PAGO;
        }
        return Concepto.valueOf(texto);
    }
}
