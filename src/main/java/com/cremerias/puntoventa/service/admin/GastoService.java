package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Gasto;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.GastoRepository;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Gastos fijos y variables de cada sucursal (renta, servicios, nómina patronal, etc.), para medir
 * la utilidad neta. Solo el administrador los captura y consulta; se valida aquí, no solo en la UI.
 */
public class GastoService {

    private final Database database;
    private final String dispositivoId;
    private final GastoRepository gastos = new GastoRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public GastoService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public List<Gasto> listar(Usuario quien, String sucursalId, YearMonth mes) {
        validarAdmin(quien);
        ZoneId zona = ZoneId.systemDefault();
        Instant desde = mes.atDay(1).atStartOfDay(zona).toInstant();
        Instant hasta = mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant();
        return database.con(c -> gastos.listar(c, sucursalId, desde, hasta));
    }

    /** Suma de gastos del periodo (cualquier rango, no solo un mes), para la utilidad neta de Indicadores. */
    public long totalPeriodo(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAdmin(quien);
        ZoneId zona = ZoneId.systemDefault();
        Instant inicio = desde.atStartOfDay(zona).toInstant();
        Instant fin = hasta.plusDays(1).atStartOfDay(zona).toInstant();
        return database.con(c -> gastos.sumar(c, sucursalId, inicio, fin));
    }

    /** Crea (id nulo) o edita un gasto. Devuelve el folio. */
    public String guardar(Gasto g, Usuario quien) {
        validarAdmin(quien);
        validar(g);
        boolean nuevo = g.id() == null;
        return database.enTransaccion(c -> {
            // La bitácora exige un folio único por registro; el del gasto solo se asigna al crearlo y ya no cambia.
            String folioBitacora = bitacora.siguienteFolio(c, AccionBitacora.GASTO_CREADO.prefijo(), dispositivoId);
            Gasto aGuardar = nuevo ? new Gasto(Ids.nuevo(), folioBitacora, g.sucursalId(), g.fecha(), g.categoria(),
                    g.tipo(), g.concepto(), g.montoCentavos(), g.nota(), quien.id(), quien.nombreCompleto()) : g;
            if (nuevo) {
                gastos.insertar(c, aGuardar);
            } else {
                gastos.actualizar(c, aGuardar);
            }
            return bitacora.registrar(c, nuevo ? AccionBitacora.GASTO_CREADO : AccionBitacora.GASTO_EDITADO, quien,
                    dispositivoId, "gastos", aGuardar.id(), descripcion(aGuardar), folioBitacora);
        });
    }

    public String eliminar(Gasto g, Usuario quien) {
        validarAdmin(quien);
        return database.enTransaccion(c -> {
            gastos.eliminar(c, g.id());
            return bitacora.registrar(c, AccionBitacora.GASTO_ELIMINADO, quien, dispositivoId, "gastos", g.id(),
                    descripcion(g));
        });
    }

    private static String descripcion(Gasto g) {
        return g.categoria().nombre() + " (" + g.tipo().nombre() + "): " + g.concepto() + ", "
                + Dinero.formatear(g.montoCentavos());
    }

    private static void validar(Gasto g) {
        if (g.sucursalId() == null) {
            throw new IllegalArgumentException("Elige la sucursal.");
        }
        if (g.fecha() == null) {
            throw new IllegalArgumentException("Elige la fecha del gasto.");
        }
        if (g.categoria() == null) {
            throw new IllegalArgumentException("Elige la categoría.");
        }
        if (g.tipo() == null) {
            throw new IllegalArgumentException("Elige si es fijo o variable.");
        }
        if (g.concepto() == null || g.concepto().isBlank()) {
            throw new IllegalArgumentException("Escribe el concepto del gasto.");
        }
        if (g.montoCentavos() <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a cero.");
        }
    }

    private static void validarAdmin(Usuario quien) {
        if (quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede ver y capturar gastos.");
        }
    }
}
