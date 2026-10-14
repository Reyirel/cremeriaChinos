package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.IndicadoresRepository;
import com.cremerias.puntoventa.service.admin.CreditoService;
import com.cremerias.puntoventa.service.admin.GastoService;
import com.cremerias.puntoventa.service.admin.NominaService;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Masa;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Indicadores de desempeño por sucursal (venta, costo, utilidad, kilos, KPIs y avance de meta
 * mensual), calculados directo de las ventas, lotes y movimientos de inventario ya registrados
 * en el POS.
 *
 * <p>Un SUPERVISOR solo puede consultar su propia sucursal: esa restricción se valida aquí, no
 * solo se oculta en la pantalla, para que ninguna otra pantalla pueda saltársela.
 */
public class IndicadoresService {

    /** Los tres números que no se pueden derivar de las operaciones y salen de una sola consulta. */
    private record Crudos(long ventaNeta, long costoVenta, Optional<Long> metaMensual) {
    }

    /** Rango de instantes [inicio, fin) de un periodo, en la zona local. */
    private record Rango(Instant inicio, Instant fin) {
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

    /** Hora del día (0–23) y lo que se vendió en esa hora, sumado en todo el periodo. */
    public record HoraVenta(int hora, long totalCentavos) {
    }

    /** Ventas, artículos y clientes del periodo, calculados de las ventas COMPLETADAS. */
    public record VentasDetalle(
            int tickets,
            long ticketPromedioCentavos,
            double articulosPorTicket,
            long valorPromedioArticuloCentavos,
            double clientesPorDia,
            Optional<Double> clientesPorEmpleadoDiario,
            List<HoraVenta> horasMayores,
            List<HoraVenta> horasMenores) {
    }

    /** Kilos vendidos del periodo (gramaje neto) contra la meta de kilos de la sucursal, y su desglose por marca. */
    public record KilosResumen(BigDecimal kilosVendidosGramos, Optional<BigDecimal> metaKilosGramos,
                               Optional<BigDecimal> diferenciaGramos, List<IndicadoresRepository.MarcaKilos> porMarca) {
    }

    /** Meta capturada de una sucursal/año/mes, con los 4 valores del diálogo "Capturar meta". */
    public record MetaCapturada(long ventaCentavos, Optional<BigDecimal> kilosGramos,
                                Optional<BigDecimal> articulosTicket, Optional<Double> utilidadPct) {
    }

    /** Un KPI del tablero: lo logrado contra su meta, ya con el texto listo para pantalla. */
    public record Kpi(String nombre, String logrado, String meta, Optional<Double> avancePct, int ponderacion) {

        /** % de avance aportado al total, con el avance de cada KPI topado en 100% de su peso. */
        public Optional<Double> avancePonderado() {
            return avancePct.map(a -> Math.min(Math.max(a, 0), 100.0) * ponderacion / 100.0);
        }
    }

    public record KpisResumen(List<Kpi> kpis, Optional<Double> avanceTotalPonderado) {
    }

    /** Utilidad neta del periodo: bruta menos gastos, nómina y mermas valuadas al costo. Solo ADMINISTRADOR. */
    public record UtilidadResumen(long gastosCentavos, long nominaCentavos, long mermasCentavos, long utilidadNeta,
                                  double margenNetoPct) {
    }

    /** Inventario al costo de la sucursal en el periodo, y su saldo de crédito con el almacén. */
    public record InventarioResumen(long inventarioInicialCentavos, long inventarioFinalCentavos,
                                    long inventarioPromedioCentavos, Optional<Double> diasInventario,
                                    long saldoCreditoCentavos) {
    }

    /** Una fila del consolidado (todas las sucursales), con el mismo cálculo que {@link #calcularUtilidad}. */
    public record ConsolidadoFila(Sucursal sucursal, long ventaNeta, long costoVenta, long utilidadBruta,
                                  long gastosCentavos, long utilidadNeta, Optional<Double> cumplimientoPct,
                                  Optional<Double> avanceTotalPonderado) {
    }

    private final Database database;
    private final String dispositivoId;
    private final IndicadoresRepository indicadores = new IndicadoresRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();
    private final GastoService gastoService;
    private final NominaService nominaService;
    private final CreditoService creditoService;

    public IndicadoresService(Database database, String dispositivoId, GastoService gastoService,
                              NominaService nominaService, CreditoService creditoService) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.gastoService = gastoService;
        this.nominaService = nominaService;
        this.creditoService = creditoService;
    }

    public Resumen calcular(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAlcance(quien, sucursalId);
        validarRango(desde, hasta);
        ZoneId zona = ZoneId.systemDefault();
        Rango periodo = rango(desde, hasta, zona);
        // La meta mensual solo tiene sentido cuando el periodo cae dentro de un único mes calendario.
        boolean unMes = mismoMes(desde, hasta);

        Crudos c = database.con(con -> new Crudos(
                indicadores.ventaNeta(con, sucursalId, periodo.inicio(), periodo.fin()),
                indicadores.costoVenta(con, sucursalId, periodo.inicio(), periodo.fin()),
                unMes ? indicadores.meta(con, sucursalId, desde.getYear(), desde.getMonthValue())
                        .map(IndicadoresRepository.MetaFila::ventaCentavos) : Optional.empty()));

        long utilidadBruta = c.ventaNeta() - c.costoVenta();
        double margenPct = c.ventaNeta() == 0 ? 0 : utilidadBruta * 100.0 / c.ventaNeta();

        int diasEnPeriodo = diasTranscurridos(desde, hasta, zona);
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

    /**
     * Tickets, ticket promedio, artículos por ticket, clientes por día y horas de mayor/menor
     * venta del periodo. "Clientes" se toma como tickets: el POS no distingue clientes únicos.
     */
    public VentasDetalle calcularVentasDetalle(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAlcance(quien, sucursalId);
        validarRango(desde, hasta);
        ZoneId zona = ZoneId.systemDefault();
        Rango periodo = rango(desde, hasta, zona);

        List<IndicadoresRepository.VentaFila> ventasDelPeriodo =
                database.con(c -> indicadores.ventasDelPeriodo(c, sucursalId, periodo.inicio(), periodo.fin()));
        int lineas = database.con(c -> indicadores.lineasVendidas(c, sucursalId, periodo.inicio(), periodo.fin()));
        int empleados = database.con(c -> indicadores.empleadosActivos(c, sucursalId));

        int tickets = ventasDelPeriodo.size();
        long ventaNeta = ventasDelPeriodo.stream().mapToLong(IndicadoresRepository.VentaFila::totalCentavos).sum();
        long ticketPromedio = tickets == 0 ? 0 : ventaNeta / tickets;
        double articulosPorTicket = tickets == 0 ? 0 : lineas / (double) tickets;
        long valorPromedioArticulo = lineas == 0 ? 0 : ventaNeta / lineas;

        int dias = diasTranscurridos(desde, hasta, zona);
        double clientesPorDia = tickets / (double) dias;
        Optional<Double> clientesPorEmpleado = empleados == 0 ? Optional.empty() : Optional.of(clientesPorDia / empleados);

        // Solo horas con al menos un ticket: de madrugada, sin operación, dominarían trivialmente el mínimo.
        Map<Integer, Long> porHora = new HashMap<>();
        for (IndicadoresRepository.VentaFila v : ventasDelPeriodo) {
            porHora.merge(v.fecha().atZone(zona).getHour(), v.totalCentavos(), Long::sum);
        }
        List<HoraVenta> porTotalDesc = porHora.entrySet().stream()
                .map(e -> new HoraVenta(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(HoraVenta::totalCentavos).reversed())
                .toList();
        List<HoraVenta> horasMayores = porTotalDesc.stream().limit(2).toList();
        List<HoraVenta> horasMenores = porTotalDesc.reversed().stream().limit(2).toList();

        return new VentasDetalle(tickets, ticketPromedio, articulosPorTicket, valorPromedioArticulo, clientesPorDia,
                clientesPorEmpleado, horasMayores, horasMenores);
    }

    /** Kilos vendidos del periodo (gramaje neto) contra la meta de kilos, si el periodo es un solo mes. */
    public KilosResumen calcularKilos(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAlcance(quien, sucursalId);
        validarRango(desde, hasta);
        ZoneId zona = ZoneId.systemDefault();
        Rango periodo = rango(desde, hasta, zona);
        boolean unMes = mismoMes(desde, hasta);

        BigDecimal kilos = database.con(c -> indicadores.kilosVendidosGramos(c, sucursalId, periodo.inicio(), periodo.fin()));
        Optional<BigDecimal> meta = unMes
                ? database.con(c -> indicadores.meta(c, sucursalId, desde.getYear(), desde.getMonthValue()))
                        .flatMap(f -> Optional.ofNullable(f.kilosGramos()))
                : Optional.empty();
        Optional<BigDecimal> diferencia = meta.map(kilos::subtract);
        List<IndicadoresRepository.MarcaKilos> porMarca =
                database.con(c -> indicadores.kilosPorMarca(c, sucursalId, periodo.inicio(), periodo.fin()));
        return new KilosResumen(kilos, meta, diferencia, porMarca);
    }

    /**
     * Logrado, meta, % de avance y % ponderado de cada KPI, más el avance total ponderado.
     * El KPI de utilidad usa el margen bruto (venta − costo) mientras no exista la utilidad neta
     * (gastos, nómina y mermas al costo llegan en una fase posterior).
     */
    public KpisResumen calcularKpis(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAlcance(quien, sucursalId);
        Resumen r = calcular(quien, sucursalId, desde, hasta);
        VentasDetalle vd = calcularVentasDetalle(quien, sucursalId, desde, hasta);
        KilosResumen kr = calcularKilos(quien, sucursalId, desde, hasta);
        Optional<MetaCapturada> meta = mismoMes(desde, hasta)
                ? metaCapturada(quien, sucursalId, desde.getYear(), desde.getMonthValue()) : Optional.empty();
        IndicadoresRepository.Ponderaciones pond = database.con(indicadores::ponderaciones);

        Kpi ventaKpi = new Kpi("Venta", Dinero.formatear(r.ventaNeta()),
                meta.map(m -> Dinero.formatear(m.ventaCentavos())).orElse("—"),
                meta.map(m -> m.ventaCentavos() == 0 ? 0.0 : r.ventaNeta() * 100.0 / m.ventaCentavos()),
                pond.venta());

        Kpi kilosKpi = new Kpi("Kilos vendidos", Masa.formatear(kr.kilosVendidosGramos()),
                kr.metaKilosGramos().map(Masa::formatear).orElse("—"),
                kr.metaKilosGramos().map(m -> m.signum() == 0 ? 0.0 : kr.kilosVendidosGramos().doubleValue() * 100.0 / m.doubleValue()),
                pond.kilos());

        Optional<BigDecimal> metaArticulos = meta.flatMap(MetaCapturada::articulosTicket);
        Kpi articulosKpi = new Kpi("Artículos por ticket", formatearUno(vd.articulosPorTicket()),
                metaArticulos.map(m -> formatearUno(m.doubleValue())).orElse("—"),
                metaArticulos.map(m -> m.signum() == 0 ? 0.0 : vd.articulosPorTicket() * 100.0 / m.doubleValue()),
                pond.articulosTicket());

        Optional<Double> metaUtilidad = meta.flatMap(MetaCapturada::utilidadPct);
        Kpi utilidadKpi = new Kpi("Utilidad", formatearPorcentaje(r.margenPct()),
                metaUtilidad.map(IndicadoresService::formatearPorcentaje).orElse("—"),
                metaUtilidad.map(m -> m == 0 ? 0.0 : r.margenPct() * 100.0 / m),
                pond.utilidad());

        List<Kpi> kpis = List.of(ventaKpi, kilosKpi, articulosKpi, utilidadKpi);
        List<Double> ponderados = kpis.stream().map(Kpi::avancePonderado).flatMap(Optional::stream).toList();
        Optional<Double> avanceTotal = ponderados.isEmpty() ? Optional.empty()
                : Optional.of(ponderados.stream().mapToDouble(x -> x).sum());
        return new KpisResumen(kpis, avanceTotal);
    }

    /**
     * Utilidad neta del periodo: utilidad bruta − gastos − nómina − mermas (valuadas al costo PEPS,
     * igual que el costo de venta). Solo el administrador: gastos y nómina son suyos en exclusiva.
     */
    public UtilidadResumen calcularUtilidad(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAdmin(quien);
        validarRango(desde, hasta);
        Resumen r = calcular(quien, sucursalId, desde, hasta);
        ZoneId zona = ZoneId.systemDefault();
        Rango periodo = rango(desde, hasta, zona);

        long gastosCentavos = gastoService.totalPeriodo(quien, sucursalId, desde, hasta);
        long nominaCentavos = nominaService.totalPeriodo(quien, sucursalId, desde, hasta);
        long mermasCentavos = database.con(c -> indicadores.mermasCentavos(c, sucursalId, periodo.inicio(), periodo.fin()));
        long utilidadNeta = r.utilidadBruta() - gastosCentavos - nominaCentavos - mermasCentavos;
        double margenNetoPct = r.ventaNeta() == 0 ? 0 : utilidadNeta * 100.0 / r.ventaNeta();
        return new UtilidadResumen(gastosCentavos, nominaCentavos, mermasCentavos, utilidadNeta, margenNetoPct);
    }

    /**
     * Inventario al costo de la sucursal (inicial, final y promedio del periodo), días de
     * inventario y saldo de crédito con el almacén. Visible también para el supervisor de la
     * sucursal: a diferencia de la utilidad neta, no depende de gastos ni nómina.
     */
    public InventarioResumen calcularInventario(Usuario quien, String sucursalId, LocalDate desde, LocalDate hasta) {
        validarAlcance(quien, sucursalId);
        validarRango(desde, hasta);
        ZoneId zona = ZoneId.systemDefault();
        Rango periodo = rango(desde, hasta, zona);

        long inicial = database.con(c -> indicadores.valorInventarioAntesDe(c, sucursalId, periodo.inicio()));
        long finalInventario = database.con(c -> indicadores.valorInventarioAntesDe(c, sucursalId, periodo.fin()));
        long promedio = (inicial + finalInventario) / 2;

        long costoVenta = database.con(c -> indicadores.costoVenta(c, sucursalId, periodo.inicio(), periodo.fin()));
        long costoVentaDiario = costoVenta / diasTranscurridos(desde, hasta, zona);
        Optional<Double> diasInventario = costoVentaDiario == 0 ? Optional.empty()
                : Optional.of(promedio / (double) costoVentaDiario);

        long saldoCredito = creditoService.saldo(sucursalId);
        return new InventarioResumen(inicial, finalInventario, promedio, diasInventario, saldoCredito);
    }

    /** Venta, costo, utilidad bruta, gastos y utilidad neta de todas las sucursales. Solo el administrador. */
    public List<ConsolidadoFila> calcularConsolidado(Usuario quien, List<Sucursal> sucursales, LocalDate desde,
                                                      LocalDate hasta) {
        validarAdmin(quien);
        List<ConsolidadoFila> filas = new ArrayList<>();
        for (Sucursal s : sucursales) {
            Resumen r = calcular(quien, s.id(), desde, hasta);
            UtilidadResumen u = calcularUtilidad(quien, s.id(), desde, hasta);
            KpisResumen kpis = calcularKpis(quien, s.id(), desde, hasta);
            filas.add(new ConsolidadoFila(s, r.ventaNeta(), r.costoVenta(), r.utilidadBruta(), u.gastosCentavos(),
                    u.utilidadNeta(), r.cumplimientoPct(), kpis.avanceTotalPonderado()));
        }
        return filas;
    }

    /** Meta capturada de esa sucursal/año/mes, para precargar el diálogo de edición. */
    public Optional<MetaCapturada> metaCapturada(Usuario quien, String sucursalId, int anio, int mes) {
        validarAlcance(quien, sucursalId);
        return database.con(c -> indicadores.meta(c, sucursalId, anio, mes)).map(f -> new MetaCapturada(
                f.ventaCentavos(), Optional.ofNullable(f.kilosGramos()), Optional.ofNullable(f.articulosTicket()),
                Optional.ofNullable(f.utilidadPct())));
    }

    /** Solo el Superadministrador (rol ADMINISTRADOR) puede capturar la meta mensual. */
    public String guardarMeta(Usuario quien, String sucursalId, int anio, int mes, long metaCentavos,
                               BigDecimal kilosGramos, BigDecimal articulosTicket, Double utilidadPct) {
        if (quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede capturar la meta mensual.");
        }
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El mes no es válido.");
        }
        if (metaCentavos < 0) {
            throw new IllegalArgumentException("La meta no puede ser negativa.");
        }
        if (kilosGramos != null && kilosGramos.signum() < 0) {
            throw new IllegalArgumentException("La meta de kilos no puede ser negativa.");
        }
        if (articulosTicket != null && articulosTicket.signum() < 0) {
            throw new IllegalArgumentException("La meta de artículos por ticket no puede ser negativa.");
        }
        if (utilidadPct != null && (utilidadPct < 0 || utilidadPct > 100)) {
            throw new IllegalArgumentException("La meta de utilidad debe estar entre 0 y 100%.");
        }
        return database.enTransaccion(c -> {
            indicadores.guardarMeta(c, sucursalId, anio, mes, metaCentavos, kilosGramos, articulosTicket, utilidadPct);
            return bitacora.registrar(c, AccionBitacora.META_MENSUAL_ACTUALIZADA, quien, dispositivoId,
                    "metas_sucursal", sucursalId, "Meta de " + mes + "/" + anio + ": " + Dinero.formatear(metaCentavos));
        });
    }

    private static void validarAlcance(Usuario quien, String sucursalId) {
        if (quien.rol() != Rol.ADMINISTRADOR && !quien.sucursalId().equals(sucursalId)) {
            throw new IllegalStateException("No tienes acceso a los indicadores de esa sucursal.");
        }
    }

    /** La utilidad neta y el consolidado dependen de gastos y nómina, exclusivos del administrador. */
    private static void validarAdmin(Usuario quien) {
        if (quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede ver la utilidad neta.");
        }
    }

    private static void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            throw new IllegalArgumentException("El rango de fechas no es válido.");
        }
    }

    private static boolean mismoMes(LocalDate desde, LocalDate hasta) {
        return desde.getYear() == hasta.getYear() && desde.getMonthValue() == hasta.getMonthValue();
    }

    private static Rango rango(LocalDate desde, LocalDate hasta, ZoneId zona) {
        return new Rango(desde.atStartOfDay(zona).toInstant(), hasta.plusDays(1).atStartOfDay(zona).toInstant());
    }

    /** Días ya transcurridos del periodo (no cuenta los días futuros si el periodo llega hasta hoy o después). */
    private static int diasTranscurridos(LocalDate desde, LocalDate hasta, ZoneId zona) {
        LocalDate hoy = LocalDate.now(zona);
        LocalDate finTranscurrido = hasta.isAfter(hoy) ? hoy : hasta;
        return (int) Math.max(1, ChronoUnit.DAYS.between(desde, finTranscurrido) + 1);
    }

    private static String formatearUno(double valor) {
        return String.format(Locale.US, "%.1f", valor);
    }

    private static String formatearPorcentaje(double valor) {
        return String.format(Locale.US, "%.1f%%", valor);
    }
}
