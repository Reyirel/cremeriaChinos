package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.HorarioDia;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Horarios de los empleados y bloqueos de acceso. Aplica a cajeros y supervisores que tengan
 * horario asignado; el administrador no tiene restricción.
 */
public class AccesoService {

    private static final Logger log = LoggerFactory.getLogger(AccesoService.class);

    public record Bloqueo(String id, String folio, String usuarioId, String usuario, String sucursalId,
                          String sucursal, ReglasHorario.Motivo motivo, String detalle, Instant fecha) {
    }

    private final Database database;
    private final Clock reloj;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public AccesoService(Database database, Clock reloj, String dispositivoId) {
        this.database = database;
        this.reloj = reloj;
        this.dispositivoId = dispositivoId;
    }

    // ---------------------------------------------------------------------
    // Horarios
    // ---------------------------------------------------------------------

    public Map<DayOfWeek, HorarioDia> horario(String usuarioId) {
        return database.con(c -> horario(c, usuarioId));
    }

    private Map<DayOfWeek, HorarioDia> horario(Connection c, String usuarioId) throws SQLException {
        Map<DayOfWeek, HorarioDia> semana = new EnumMap<>(DayOfWeek.class);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT dia_semana, labora, entrada, salida, descanso_inicio, descanso_fin
                FROM horarios WHERE usuario_id = ?""")) {
            ps.setString(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DayOfWeek dia = DayOfWeek.of(rs.getInt(1));
                    semana.put(dia, new HorarioDia(dia, rs.getBoolean(2), hora(rs.getString(3)), hora(rs.getString(4)),
                            hora(rs.getString(5)), hora(rs.getString(6))));
                }
            }
        }
        return semana;
    }

    public boolean tieneHorario(Connection c, String usuarioId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM horarios WHERE usuario_id = ? LIMIT 1")) {
            ps.setString(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Guarda los 7 días de la semana. Devuelve el folio. */
    public String guardarHorario(Usuario empleado, List<HorarioDia> semana, Usuario quien) {
        for (HorarioDia dia : semana) {
            String error = dia.validar();
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
        }
        return database.enTransaccion(c -> {
            String ahora = Tiempo.ahora();
            for (HorarioDia dia : semana) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO horarios (id, usuario_id, dia_semana, labora, entrada, salida, descanso_inicio,
                                              descanso_fin, creado_en, actualizado_en)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (usuario_id, dia_semana) DO UPDATE SET
                            labora = excluded.labora, entrada = excluded.entrada, salida = excluded.salida,
                            descanso_inicio = excluded.descanso_inicio, descanso_fin = excluded.descanso_fin,
                            actualizado_en = excluded.actualizado_en""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, empleado.id());
                    ps.setInt(3, dia.dia().getValue());
                    ps.setBoolean(4, dia.labora());
                    ps.setString(5, texto(dia.labora() ? dia.entrada() : null));
                    ps.setString(6, texto(dia.labora() ? dia.salida() : null));
                    ps.setString(7, texto(dia.tieneDescanso() ? dia.descansoInicio() : null));
                    ps.setString(8, texto(dia.tieneDescanso() ? dia.descansoFin() : null));
                    ps.setString(9, ahora);
                    ps.setString(10, ahora);
                    ps.executeUpdate();
                }
            }
            long dias = semana.stream().filter(HorarioDia::labora).count();
            return bitacora.registrar(c, AccionBitacora.HORARIO_ACTUALIZADO, quien, dispositivoId, "usuarios",
                    empleado.id(), "Horario de " + empleado.nombreCompleto() + ": " + dias + " días laborables");
        });
    }

    // ---------------------------------------------------------------------
    // Entrada y salida
    // ---------------------------------------------------------------------

    private static boolean aplica(Usuario usuario) {
        return usuario.rol() != Rol.ADMINISTRADOR;
    }

    /**
     * Revisa si el empleado puede entrar ahora. Si tiene un permiso autorizado lo usa; si no
     * cumple su horario crea un bloqueo pendiente. Debe llamarse dentro de la transacción del login.
     */
    public Optional<Bloqueo> verificarEntrada(Connection c, Usuario usuario) throws SQLException {
        if (!aplica(usuario) || !tieneHorario(c, usuario.id())) {
            return Optional.empty();
        }
        if (usarPermiso(c, usuario.id())) {
            return Optional.empty();
        }
        Optional<Bloqueo> pendiente = pendienteDe(c, usuario.id());
        if (pendiente.isPresent()) {
            return pendiente;
        }
        ZonedDateTime ahora = ZonedDateTime.now(reloj);
        HorarioDia hoy = horario(c, usuario.id()).get(ahora.getDayOfWeek());
        Instant inicioDelDia = ahora.toLocalDate().atStartOfDay(ahora.getZone()).toInstant();
        boolean yaEntro = existe(c, "SELECT 1 FROM sesiones WHERE usuario_id = ? AND inicio >= ? LIMIT 1",
                usuario.id(), inicioDelDia);
        LocalTime ultimaSalida = ultimaSalida(c, usuario.id(), inicioDelDia, ahora);
        Optional<ReglasHorario.Motivo> motivo = ReglasHorario.alEntrar(hoy, ahora.toLocalTime(), yaEntro, ultimaSalida);
        if (motivo.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(crearBloqueo(c, usuario, motivo.get(), detalle(hoy, ahora.toLocalTime())));
    }

    /** Al cerrar sesión: si sale antes de tiempo, su siguiente entrada queda bloqueada. */
    public Optional<Bloqueo> registrarSalida(Connection c, Usuario usuario) throws SQLException {
        if (!aplica(usuario) || !tieneHorario(c, usuario.id()) || pendienteDe(c, usuario.id()).isPresent()) {
            return Optional.empty();
        }
        ZonedDateTime ahora = ZonedDateTime.now(reloj);
        HorarioDia hoy = horario(c, usuario.id()).get(ahora.getDayOfWeek());
        Optional<ReglasHorario.Motivo> motivo = ReglasHorario.alSalir(hoy, ahora.toLocalTime());
        if (motivo.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(crearBloqueo(c, usuario, motivo.get(), detalle(hoy, ahora.toLocalTime())));
    }

    private Bloqueo crearBloqueo(Connection c, Usuario usuario, ReglasHorario.Motivo motivo, String detalle)
            throws SQLException {
        String id = Ids.nuevo();
        String folio = bitacora.siguienteFolio(c, AccionBitacora.ACCESO_BLOQUEADO.prefijo(), dispositivoId);
        Instant ahora = reloj.instant();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bloqueos_acceso (id, folio, usuario_id, motivo, detalle, fecha, dispositivo_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, id);
            ps.setString(2, folio);
            ps.setString(3, usuario.id());
            ps.setString(4, motivo.name());
            ps.setString(5, detalle);
            ps.setString(6, Tiempo.formatear(ahora));
            ps.setString(7, dispositivoId);
            ps.executeUpdate();
        }
        bitacora.registrar(c, AccionBitacora.ACCESO_BLOQUEADO, usuario, dispositivoId, "bloqueos_acceso", id,
                usuario.nombreCompleto() + ": " + motivo.descripcion() + " (" + detalle + ")", folio);
        log.warn("Acceso bloqueado {} para {}: {}", folio, usuario.usuario(), motivo);
        return new Bloqueo(id, folio, usuario.id(), usuario.nombreCompleto(), usuario.sucursalId(), null, motivo,
                detalle, ahora);
    }

    private boolean usarPermiso(Connection c, String usuarioId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE bloqueos_acceso SET usado_en = ?
                WHERE id = (SELECT id FROM bloqueos_acceso
                            WHERE usuario_id = ? AND estado = 'AUTORIZADO' AND usado_en IS NULL
                            ORDER BY fecha LIMIT 1)""")) {
            ps.setString(1, Tiempo.formatear(reloj.instant()));
            ps.setString(2, usuarioId);
            return ps.executeUpdate() > 0;
        }
    }

    // ---------------------------------------------------------------------
    // Autorización
    // ---------------------------------------------------------------------

    public List<Bloqueo> pendientes() {
        return database.con(c -> consultar(c, "WHERE b.estado = 'PENDIENTE'", null));
    }

    public int contarPendientes() {
        return pendientes().size();
    }

    /** Solo los de una sucursal (para que un supervisor apruebe desde su propio panel). */
    public List<Bloqueo> pendientesDeSucursal(String sucursalId) {
        return database.con(c -> consultar(c, "WHERE b.estado = 'PENDIENTE' AND u.sucursal_id = ?", sucursalId));
    }

    private Optional<Bloqueo> pendienteDe(Connection c, String usuarioId) throws SQLException {
        List<Bloqueo> lista = consultar(c, "WHERE b.estado = 'PENDIENTE' AND b.usuario_id = ?", usuarioId);
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.getFirst());
    }

    /**
     * Da el permiso para que el empleado entre. Lo puede dar un administrador o un supervisor
     * de la misma sucursal. Devuelve el folio de la autorización.
     */
    public String autorizar(String bloqueoId, Usuario autorizador) {
        return database.enTransaccion(c -> autorizar(c, bloqueoId, autorizador));
    }

    public String autorizar(Connection c, String bloqueoId, Usuario autorizador) throws SQLException {
        Bloqueo bloqueo = consultar(c, "WHERE b.id = ?", bloqueoId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No existe el bloqueo."));
        boolean supervisorDeLaSucursal = autorizador.rol() == Rol.SUPERVISOR && autorizador.sucursalId() != null
                && autorizador.sucursalId().equals(bloqueo.sucursalId());
        if (autorizador.rol() != Rol.ADMINISTRADOR && !supervisorDeLaSucursal) {
            throw new IllegalStateException("Solo un administrador o un supervisor de su sucursal puede dar acceso.");
        }
        if (autorizador.id().equals(bloqueo.usuarioId())) {
            throw new IllegalStateException("No puedes autorizar tu propio acceso.");
        }
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE bloqueos_acceso SET estado = 'AUTORIZADO', autorizado_por = ?, autorizado_en = ?
                WHERE id = ? AND estado = 'PENDIENTE'""")) {
            ps.setString(1, autorizador.id());
            ps.setString(2, Tiempo.formatear(reloj.instant()));
            ps.setString(3, bloqueoId);
            if (ps.executeUpdate() != 1) {
                throw new IllegalStateException("Este acceso ya había sido autorizado.");
            }
        }
        return bitacora.registrar(c, AccionBitacora.ACCESO_AUTORIZADO, autorizador, dispositivoId, "bloqueos_acceso",
                bloqueoId, autorizador.nombreCompleto() + " autorizó el acceso de " + bloqueo.usuario()
                        + " (bloqueo " + bloqueo.folio() + ")");
    }

    private List<Bloqueo> consultar(Connection c, String filtro, String parametro) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT b.id, b.folio, b.usuario_id, u.nombre_completo, u.sucursal_id, COALESCE(s.nombre, ''),
                       b.motivo, b.detalle, b.fecha
                FROM bloqueos_acceso b
                JOIN usuarios u ON u.id = b.usuario_id
                LEFT JOIN sucursales s ON s.id = u.sucursal_id
                """ + filtro + " ORDER BY b.fecha")) {
            if (parametro != null) {
                ps.setString(1, parametro);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Bloqueo> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Bloqueo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), ReglasHorario.Motivo.valueOf(rs.getString(7)),
                            rs.getString(8), Tiempo.leer(rs.getString(9))));
                }
                return lista;
            }
        }
    }

    // ---------------------------------------------------------------------

    private LocalTime ultimaSalida(Connection c, String usuarioId, Instant inicioDelDia, ZonedDateTime ahora)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT MAX(fin) FROM sesiones
                WHERE usuario_id = ? AND fin >= ? AND motivo_cierre IN ('CIERRE_USUARIO', 'CIERRE_APLICACION')""")) {
            ps.setString(1, usuarioId);
            ps.setString(2, Tiempo.formatear(inicioDelDia));
            try (ResultSet rs = ps.executeQuery()) {
                String fin = rs.next() ? rs.getString(1) : null;
                return fin == null ? null : Tiempo.leer(fin).atZone(ahora.getZone()).toLocalTime();
            }
        }
    }

    private static boolean existe(Connection c, String sql, String usuarioId, Instant desde) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, usuarioId);
            ps.setString(2, Tiempo.formatear(desde));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String detalle(HorarioDia hoy, LocalTime ahora) {
        String hora = ahora.withSecond(0).withNano(0).toString();
        if (hoy == null || !hoy.labora()) {
            return "a las " + hora + ", día sin horario";
        }
        String horario = hoy.entrada() + "–" + hoy.salida()
                + (hoy.tieneDescanso() ? ", descanso " + hoy.descansoInicio() + "–" + hoy.descansoFin() : "");
        return "a las " + hora + ", horario " + horario;
    }

    private static LocalTime hora(String texto) {
        return texto == null ? null : LocalTime.parse(texto);
    }

    private static String texto(LocalTime hora) {
        return hora == null ? null : hora.toString();
    }
}
