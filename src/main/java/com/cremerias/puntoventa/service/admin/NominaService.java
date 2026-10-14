package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Nomina;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.NominaRepository;
import com.cremerias.puntoventa.util.Dinero;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Salario y comida de cada empleado por semana (lunes de inicio). Una sola captura por
 * sucursal/empleado/semana: volver a capturarla corrige la anterior (no hay baja, igual que la
 * meta mensual). Solo el administrador; se valida aquí, no solo en la UI.
 */
public class NominaService {

    private final Database database;
    private final String dispositivoId;
    private final NominaRepository nomina = new NominaRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public NominaService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Lunes de cada semana del mes (la primera semana puede empezar antes del día 1, no aquí). */
    public static List<LocalDate> semanasDelMes(YearMonth mes) {
        List<LocalDate> semanas = new ArrayList<>();
        LocalDate lunes = mes.atDay(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate ultimo = mes.atEndOfMonth();
        while (!lunes.isAfter(ultimo)) {
            semanas.add(lunes);
            lunes = lunes.plusWeeks(1);
        }
        return semanas;
    }

    public List<Nomina> listar(Usuario quien, String sucursalId, YearMonth mes) {
        validarAdmin(quien);
        return database.con(c -> nomina.listar(c, sucursalId, mes.atDay(1), mes.atEndOfMonth()));
    }

    /** Suma de nómina del periodo (cualquier rango), para la utilidad neta de Indicadores. */
    public long totalPeriodo(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAdmin(quien);
        return database.con(c -> nomina.sumar(c, sucursalId, desde, hasta));
    }

    /** Lo capturado para ese empleado y semana, para precargar el diálogo de edición. */
    public Optional<Nomina> capturada(Usuario quien, String sucursalId, String usuarioId, LocalDate semanaInicio) {
        validarAdmin(quien);
        return database.con(c -> nomina.buscar(c, sucursalId, usuarioId, semanaInicio));
    }

    public String guardar(Usuario quien, String sucursalId, String usuarioId, LocalDate semanaInicio,
                          long salarioCentavos, long comidaCentavos) {
        validarAdmin(quien);
        if (sucursalId == null || usuarioId == null) {
            throw new IllegalArgumentException("Elige la sucursal y el empleado.");
        }
        if (semanaInicio == null || semanaInicio.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("La semana debe empezar en lunes.");
        }
        if (salarioCentavos < 0 || comidaCentavos < 0) {
            throw new IllegalArgumentException("El salario y la comida no pueden ser negativos.");
        }
        return database.enTransaccion(c -> {
            boolean existia = nomina.buscar(c, sucursalId, usuarioId, semanaInicio).isPresent();
            nomina.guardar(c, sucursalId, usuarioId, semanaInicio, salarioCentavos, comidaCentavos);
            return bitacora.registrar(c, existia ? AccionBitacora.NOMINA_EDITADA : AccionBitacora.NOMINA_CAPTURADA,
                    quien, dispositivoId, "nomina_semanal", sucursalId, "Semana del " + semanaInicio + ": "
                            + Dinero.formatear(salarioCentavos + comidaCentavos));
        });
    }

    private static void validarAdmin(Usuario quien) {
        if (quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede capturar la nómina.");
        }
    }
}
