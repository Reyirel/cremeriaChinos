package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.CorteRealizado;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.TurnoRepository;
import com.cremerias.puntoventa.util.Dinero;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Apertura de caja, entradas/retiros de efectivo y corte. */
public class CajaService {

    private static final Logger log = LoggerFactory.getLogger(CajaService.class);

    public enum TipoMovimiento { ENTRADA, RETIRO }

    private final Database database;
    private final String dispositivoId;
    private final TurnoRepository turnos = new TurnoRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public CajaService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public Optional<Turno> turnoAbierto() {
        return database.con(c -> turnos.abierto(c, dispositivoId));
    }

    public Turno abrir(Sesion sesion, long fondoInicial) {
        if (fondoInicial < 0) {
            throw new IllegalArgumentException("El fondo no puede ser negativo.");
        }
        return database.enTransaccion(c -> {
            if (turnos.abierto(c, dispositivoId).isPresent()) {
                throw new IllegalStateException("Ya hay un turno abierto en esta caja.");
            }
            turnos.abrir(c, sesion.usuario().sucursalId(), dispositivoId, sesion.usuario().id(), fondoInicial);
            log.info("Caja abierta por {} con fondo {}", sesion.usuario().usuario(), Dinero.formatear(fondoInicial));
            return turnos.abierto(c, dispositivoId).orElseThrow();
        });
    }

    public void registrarMovimiento(Turno turno, TipoMovimiento tipo, long monto, String concepto, Usuario usuario,
                                    Usuario autorizador) {
        if (monto <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a cero.");
        }
        if (concepto == null || concepto.isBlank()) {
            throw new IllegalArgumentException("Escribe el concepto del movimiento.");
        }
        database.enTransaccion(c -> {
            if (tipo == TipoMovimiento.RETIRO) {
                long disponible = turnos.resumen(c, turno).efectivoEsperado();
                if (monto > disponible) {
                    throw new IllegalArgumentException("Solo hay " + Dinero.formatear(disponible) + " en efectivo en la caja.");
                }
            }
            turnos.registrarMovimiento(c, turno.id(), tipo.name(), monto, concepto.strip(), usuario.id(),
                    autorizador == null ? null : autorizador.id());
            bitacora.registrar(c, AccionBitacora.MOVIMIENTO_CAJA, usuario, dispositivoId, "turnos_caja", turno.id(),
                    (tipo == TipoMovimiento.RETIRO ? "Retiro" : "Entrada") + " de " + Dinero.formatear(monto)
                            + " en " + turno.dispositivoNombre() + ": " + concepto.strip()
                            + (autorizador == null ? "" : " (autorizó " + autorizador.nombreCompleto() + ")"));
            return null;
        });
        log.info("{} de efectivo por {}: {}", tipo, Dinero.formatear(monto), concepto);
    }

    public ResumenTurno resumen(Turno turno) {
        return database.con(c -> turnos.resumen(c, turno));
    }

    /**
     * Cierra el turno con el efectivo contado. Lo puede hacer el mismo cajero del turno,
     * un supervisor de la misma sucursal o un administrador.
     */
    public ResumenTurno cerrar(Turno turno, long efectivoContado, String notas, Usuario quien) {
        validarPermisoCorte(turno, quien);
        if (efectivoContado < 0) {
            throw new IllegalArgumentException("El efectivo contado no puede ser negativo.");
        }
        return database.enTransaccion(c -> {
            ResumenTurno resumen = turnos.resumen(c, turno);
            turnos.cerrar(c, turno.id(), resumen.efectivoEsperado(), efectivoContado,
                    notas == null || notas.isBlank() ? null : notas.strip(), quien.id());
            long diferencia = efectivoContado - resumen.efectivoEsperado();
            bitacora.registrar(c, AccionBitacora.CORTE_CAJA, quien, dispositivoId, "turnos_caja", turno.id(),
                    "Corte de " + turno.dispositivoNombre() + " (" + turno.usuarioNombre() + "): vendido "
                            + Dinero.formatear(resumen.totalVentas()) + ", esperado "
                            + Dinero.formatear(resumen.efectivoEsperado()) + ", contado "
                            + Dinero.formatear(efectivoContado)
                            + (diferencia == 0 ? "" : diferencia < 0 ? ", faltante " + Dinero.formatear(-diferencia)
                            : ", sobrante " + Dinero.formatear(diferencia)));
            log.info("Corte de caja de {} ({}) hecho por {}: esperado {}, contado {}", turno.dispositivoNombre(),
                    turno.usuarioNombre(), quien.usuario(), Dinero.formatear(resumen.efectivoEsperado()),
                    Dinero.formatear(efectivoContado));
            return resumen;
        });
    }

    static void validarPermisoCorte(Turno turno, Usuario quien) {
        boolean esDueno = turno.usuarioId().equals(quien.id());
        boolean supervisorDeLaSucursal = quien.rol() == Rol.SUPERVISOR
                && quien.sucursalId() != null && quien.sucursalId().equals(turno.sucursalId());
        if (!esDueno && !supervisorDeLaSucursal && quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException(quien.rol() == Rol.SUPERVISOR
                    ? "Solo puedes hacer el corte de las cajas de tu sucursal."
                    : "No tienes permiso para hacer el corte de esta caja.");
        }
    }

    /** Turno abierto por id (para confirmar que nadie le hizo el corte mientras tanto). */
    public Optional<Turno> abiertoPorId(String turnoId) {
        return database.con(c -> turnos.abiertoPorId(c, turnoId));
    }

    /** Cajas abiertas de la sucursal (o de todas si es nula), con sus totales. */
    public List<ResumenTurno> abiertosDeSucursal(String sucursalId) {
        return database.con(c -> {
            List<ResumenTurno> lista = new ArrayList<>();
            for (Turno t : turnos.abiertosDeSucursal(c, sucursalId)) {
                lista.add(turnos.resumen(c, t));
            }
            return lista;
        });
    }

    /** Cortes realizados en la sucursal (o en todas si es nula) durante un día (hora local). */
    public List<CorteRealizado> cortesDelDia(String sucursalId, LocalDate dia) {
        ZoneId zona = ZoneId.systemDefault();
        Instant desde = dia.atStartOfDay(zona).toInstant();
        Instant hasta = dia.plusDays(1).atStartOfDay(zona).toInstant();
        return database.con(c -> turnos.cerradosDeSucursal(c, sucursalId, desde, hasta));
    }

    public String dispositivoId() {
        return dispositivoId;
    }
}
