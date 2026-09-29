package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.IndicadoresRepository;
import com.cremerias.puntoventa.util.Dinero;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Indicadores de desempeño por sucursal (venta, costo, utilidad, avance de meta mensual),
 * calculados directo de las ventas, lotes y movimientos de inventario ya registrados en el POS.
 *
 * <p>Un SUPERVISOR solo puede consultar su propia sucursal: esa restricción se valida aquí, no
 * solo se oculta en la pantalla, para que ninguna otra pantalla pueda saltársela.
 */
public class IndicadoresService {

    /** Los tres números que no se pueden derivar de las operaciones y salen de una sola consulta. */
    private record Crudos(long ventaNeta, long costoVenta, Optional<Long> metaMensual) {
    }

    public record Resumen(
            long ventaNeta,
            long costoVenta,
            long utilidadBruta,
            double margenPct,
            int diasEnPeriodo,
            long promedioDiario,
            Optional<Long> metaMensual,
            Optional<Double> cumplimientoPct,
            Optional<Long> diferenciaVsMeta,
            Optional<Integer> diasDelMes,
            Optional<Long> metaDiaria) {

        /** Cuánto falta para la meta (0 si ya se alcanzó o no hay meta para el periodo). */
        public long faltanteMeta() {
            return diferenciaVsMeta.map(d -> Math.max(0, -d)).orElse(0L);
        }
    }

    private final Database database;
    private final String dispositivoId;
    private final IndicadoresRepository indicadores = new IndicadoresRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public IndicadoresService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public Resumen calcular(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAlcance(quien, sucursalId);
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            throw new IllegalArgumentException("El rango de fechas no es válido.");
        }
        ZoneId zona = ZoneId.systemDefault();
        Instant inicio = desde.atStartOfDay(zona).toInstant();
        Instant fin = hasta.plusDays(1).atStartOfDay(zona).toInstant();
        // La meta mensual solo tiene sentido cuando el periodo cae dentro de un único mes calendario.
        boolean unMes = desde.getYear() == hasta.getYear() && desde.getMonthValue() == hasta.getMonthValue();

        Crudos c = database.con(con -> new Crudos(
                indicadores.ventaNeta(con, sucursalId, inicio, fin),
                indicadores.costoVenta(con, sucursalId, inicio, fin),
                unMes ? indicadores.metaCentavos(con, sucursalId, desde.getYear(), desde.getMonthValue())
                        : Optional.empty()));

        long utilidadBruta = c.ventaNeta() - c.costoVenta();
        double margenPct = c.ventaNeta() == 0 ? 0 : utilidadBruta * 100.0 / c.ventaNeta();

        LocalDate hoy = LocalDate.now(zona);
        LocalDate finTranscurrido = hasta.isAfter(hoy) ? hoy : hasta;
        int diasEnPeriodo = (int) Math.max(1, ChronoUnit.DAYS.between(desde, finTranscurrido) + 1);
        long promedioDiario = c.ventaNeta() / diasEnPeriodo;

        Optional<Double> cumplimientoPct = Optional.empty();
        Optional<Long> diferenciaVsMeta = Optional.empty();
        Optional<Integer> diasDelMes = Optional.empty();
        Optional<Long> metaDiaria = Optional.empty();
        if (c.metaMensual().isPresent()) {
            long meta = c.metaMensual().get();
            cumplimientoPct = Optional.of(meta == 0 ? 0.0 : c.ventaNeta() * 100.0 / meta);
            diferenciaVsMeta = Optional.of(c.ventaNeta() - meta);
            int dias = YearMonth.of(desde.getYear(), desde.getMonthValue()).lengthOfMonth();
            diasDelMes = Optional.of(dias);
            metaDiaria = Optional.of(meta / dias);
        }
        return new Resumen(c.ventaNeta(), c.costoVenta(), utilidadBruta, margenPct, diasEnPeriodo, promedioDiario,
                c.metaMensual(), cumplimientoPct, diferenciaVsMeta, diasDelMes, metaDiaria);
    }

    /** Meta capturada de esa sucursal/año/mes, para precargar el diálogo de edición. */
    public Optional<Long> metaMensual(Usuario quien, String sucursalId, int anio, int mes) {
        validarAlcance(quien, sucursalId);
        return database.con(c -> indicadores.metaCentavos(c, sucursalId, anio, mes));
    }

    /** Solo el Superadministrador (rol ADMINISTRADOR) puede capturar la meta mensual. */
    public String guardarMeta(Usuario quien, String sucursalId, int anio, int mes, long metaCentavos) {
        if (quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede capturar la meta mensual.");
        }
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El mes no es válido.");
        }
        if (metaCentavos < 0) {
            throw new IllegalArgumentException("La meta no puede ser negativa.");
        }
        return database.enTransaccion(c -> {
            indicadores.guardarMeta(c, sucursalId, anio, mes, metaCentavos);
            return bitacora.registrar(c, AccionBitacora.META_MENSUAL_ACTUALIZADA, quien, dispositivoId,
                    "metas_sucursal", sucursalId, "Meta de " + mes + "/" + anio + ": " + Dinero.formatear(metaCentavos));
        });
    }

    private static void validarAlcance(Usuario quien, String sucursalId) {
        if (quien.rol() != Rol.ADMINISTRADOR && !quien.sucursalId().equals(sucursalId)) {
            throw new IllegalStateException("No tienes acceso a los indicadores de esa sucursal.");
        }
    }
}
