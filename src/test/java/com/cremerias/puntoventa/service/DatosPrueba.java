package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AvisoSinVenta;
import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.HorarioDia;
import com.cremerias.puntoventa.model.LineaVenta;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Tarifa;
import com.cremerias.puntoventa.model.Temporada;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.OrdenRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.admin.ComisionService;
import com.cremerias.puntoventa.service.admin.LimitesService;
import com.cremerias.puntoventa.service.admin.ProductoAdminService;
import com.cremerias.puntoventa.service.admin.SurtidoService;
import com.cremerias.puntoventa.util.CodigoBarras;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;

/**
 * Datos de prueba completos: simula semanas de operación de una cadena de cremerías con los mismos
 * servicios que usa la app, así los folios, lotes PEPS, bitácora, órdenes automáticas, cortes y
 * bloqueos quedan igual que si se hubieran capturado en pantalla.
 *
 * <p>Parte de una base que solo tiene al administrador y el almacén central (como queda al
 * limpiarla) y crea: sucursales y cajas, supervisores y cajeros con horario, el catálogo con todos
 * los tipos de producto (granel, pieza, varias presentaciones, gramaje y merma, edición especial,
 * temporada, avisos sin venta), entradas al almacén, surtidos en efectivo y a crédito con abonos,
 * mínimos y máximos, ventas diarias con cancelaciones y movimientos de caja, cortes, mermas,
 * reglas de comisión, metas mensuales y accesos bloqueados por horario.
 */
final class DatosPrueba {

    /** Contraseña de todos los usuarios que se crean. */
    static final String PASSWORD = "prueba123";

    private static final Logger log = LoggerFactory.getLogger(DatosPrueba.class);
    private static final ZoneId ZONA = ZoneId.systemDefault();
    private static final double LINEAS_PROMEDIO = 2.2;

    /** Lo que quedó en la base al terminar. */
    record Resumen(int sucursales, int usuarios, int productos, int ventas, int canceladas, int turnos,
                   int turnosAbiertos, int surtidos, int ordenes, int ordenesPendientes, int mermas,
                   int bloqueosPendientes, int registrosBitacora) {
    }

    // ---------------------------------------------------------------------
    // Configuración del catálogo, sucursales y personal
    // ---------------------------------------------------------------------

    /** Cómo pesa el cliente un producto a granel. */
    private enum Pesada { QUESO, CARNE, FINO, CREMA, PIEZA }

    /** Presentación: precio base en centavos y qué parte de las ventas del producto se lleva. */
    private record Pres(String nombre, boolean granel, BigDecimal factor, long precio, double participacion,
                        int maxPiezas) {
    }

    /** Producto. {@code costo} por unidad base; {@code sucursales} con las letras C, N y M. */
    private record Prod(String nombre, String categoria, String clave, String codigoInventario, Unidad unidad,
                        int gramaje, int merma, Disponibilidad disponibilidad, Temporada temporada,
                        AvisoSinVenta aviso, long costo, double popularidad, String sucursales, Pesada pesada,
                        List<Pres> presentaciones) {

        boolean sujetoMerma() {
            return merma > 0;
        }

        Prod conAviso(AvisoSinVenta nuevo) {
            return new Prod(nombre, categoria, clave, codigoInventario, unidad, gramaje, merma, disponibilidad,
                    temporada, nuevo, costo, popularidad, sucursales, pesada, presentaciones);
        }

        Prod edicionEspecial() {
            return new Prod(nombre, categoria, clave, codigoInventario, unidad, gramaje, merma,
                    Disponibilidad.EDICION_ESPECIAL, null, aviso, costo, popularidad, sucursales, pesada, presentaciones);
        }

        Prod deTemporada(Temporada t) {
            return new Prod(nombre, categoria, clave, codigoInventario, unidad, gramaje, merma,
                    Disponibilidad.TEMPORADA, t, aviso, costo, popularidad, sucursales, pesada, presentaciones);
        }

        Prod conMerma(int gramos) {
            return new Prod(nombre, categoria, clave, codigoInventario, unidad, gramaje, gramos, disponibilidad,
                    temporada, aviso, costo, popularidad, sucursales, pesada, presentaciones);
        }

        /** Múltiplo en que se surte (cajas de 12, paquetes de 6...). */
        int multiplo() {
            int m = 1;
            for (Pres p : presentaciones) {
                if (!p.granel() && unidad == Unidad.PZA && p.factor().intValue() > m && p.factor().intValue() <= 12) {
                    m = p.factor().intValue();
                }
            }
            return m;
        }
    }

    private static final class Suc {
        final String codigo;
        final String nombre;
        final String direccion;
        final String telefono;
        final char letra;
        final double ventasDia;
        final Set<DayOfWeek> diasSurtido;
        final int diasSinVenta;
        final Map<String, Double> factorCategoria;
        final List<Caja> cajas = new ArrayList<>();
        final Map<String, BigDecimal> existencia = new HashMap<>();
        final Map<Prod, Double> demanda = new LinkedHashMap<>();
        Map<String, Map<String, Long>> precios = Map.of();
        double ticketEstimado;
        Sucursal sucursal;
        Usuario supervisor;
        CatalogoService catalogo;

        Suc(String codigo, String nombre, String direccion, String telefono, char letra, double ventasDia,
            Set<DayOfWeek> diasSurtido, int diasSinVenta, Map<String, Double> factorCategoria) {
            this.codigo = codigo;
            this.nombre = nombre;
            this.direccion = direccion;
            this.telefono = telefono;
            this.letra = letra;
            this.ventasDia = ventasDia;
            this.diasSurtido = diasSurtido;
            this.diasSinVenta = diasSinVenta;
            this.factorCategoria = factorCategoria;
        }
    }

    private record Caja(String id, boolean local, AuthService auth, CajaService caja, VentaService ventas) {
    }

    /** Turno de trabajo en una caja. */
    private record Plan(Suc suc, Caja caja, Usuario empleado, LocalTime inicio, LocalTime fin, double participacion) {
    }

    private record Empleado(String nombre, String usuario, Rol rol, String sucursal, List<HorarioDia> horario) {
    }

    // ---------------------------------------------------------------------

    private final Database db;
    private final LocalDate desde;
    private final LocalDate hoy;
    private final Instant hasta;
    private final Random r;
    private final PasswordHasher hasherFinal;
    private final PasswordHasher hasherRapido = new PasswordHasher(4);
    private final RelojSimulado reloj;
    private final LocalDate fechaAumentoPrecios;
    private final LocalDate fechaAumentoCostos;
    private final LocalDate bajaJorge;

    private final List<Prod> catalogoConfig = new ArrayList<>();
    private final Map<Prod, String> idDe = new HashMap<>();
    private final Map<String, Prod> prodPorId = new HashMap<>();
    private final Map<String, Pres> presPorId = new HashMap<>();
    private final Map<Prod, Map<Pres, String>> idPresentacion = new HashMap<>();
    private Map<String, ProductoCatalogo> productos = Map.of();
    private final List<Suc> sucursales = new ArrayList<>();
    private final Map<String, Usuario> usuarios = new LinkedHashMap<>();
    private final Set<String> conHorario = new HashSet<>();
    private final LoteRepository lotes = new LoteRepository();

    private Administracion adm;
    private IndicadoresService indicadores;
    private Usuario admin;
    private Usuario encargadaAlmacen;
    private String almacenId;
    private Caja cajaLocal;
    private Sucursal sur;

    /**
     * @param desde       primer día con ventas (la configuración se captura unos días antes)
     * @param hasta       hasta dónde se simula (normalmente "ahora"); nada queda con fecha posterior
     * @param hasherFinal con qué costo quedan las contraseñas de los usuarios creados
     */
    DatosPrueba(Database db, LocalDate desde, Instant hasta, long semilla, PasswordHasher hasherFinal) {
        this.db = db;
        this.desde = desde;
        this.hasta = hasta;
        this.hoy = hasta.atZone(ZONA).toLocalDate();
        this.r = new Random(semilla);
        this.hasherFinal = hasherFinal;
        this.reloj = new RelojSimulado(desde.minusDays(3).atTime(9, 0).atZone(ZONA).toInstant());
        this.fechaAumentoPrecios = hoy.minusDays(14);
        this.fechaAumentoCostos = hoy.minusDays(20);
        long dias = ChronoUnit.DAYS.between(desde, hoy);
        this.bajaJorge = desde.plusDays(dias / 2).with(TemporalAdjusters.nextOrSame(MONDAY));
        if (!desde.isBefore(hoy)) {
            throw new IllegalArgumentException("El periodo debe empezar antes de hoy.");
        }
    }

    Resumen generar() {
        Tiempo.usarReloj(reloj);
        try {
            preparar();
            configurarCatalogo();
            crearSucursalesYCajas();
            crearPersonal();
            crearProductos();
            estimarDemanda();
            configurarAlertasMetasYComisiones();
            inventarioInicial();
            for (LocalDate dia = desde; !dia.isAfter(hoy); dia = dia.plusDays(1)) {
                simularDia(dia);
            }
            ventaEnEspera();
            asegurarCajaLocalLibre();
        } finally {
            Tiempo.usarReloj(null);
        }
        endurecerContrasenas();
        return resumen();
    }

    // ---------------------------------------------------------------------
    // Preparación
    // ---------------------------------------------------------------------

    private void preparar() {
        db.con(c -> {
            if (contar(c, "SELECT COUNT(*) FROM sucursales WHERE es_almacen = 0") > 0
                    || contar(c, "SELECT COUNT(*) FROM productos") > 0
                    || contar(c, "SELECT COUNT(*) FROM ventas") > 0) {
                throw new IllegalStateException("La base ya tiene sucursales, productos o ventas: los datos de prueba"
                        + " solo se cargan en una base limpia (solo administrador y almacén).");
            }
            return null;
        });
        String adminId = db.con(c -> texto(c, """
                SELECT id FROM usuarios WHERE rol = 'ADMINISTRADOR' AND eliminado_en IS NULL AND activo = 1
                ORDER BY creado_en LIMIT 1"""));
        if (adminId == null) {
            throw new IllegalStateException("No hay un administrador activo.");
        }
        admin = db.con(c -> new UsuarioRepository().porId(c, adminId)).orElseThrow();
        almacenId = db.con(c -> texto(c, "SELECT id FROM sucursales WHERE es_almacen = 1 LIMIT 1"));
        String local = db.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, null));
        adm = new Administracion(db, hasherRapido, reloj, local);
        indicadores = new IndicadoresService(db, local);
        cajaLocal = caja(local, true);
    }

    private Caja caja(String id, boolean local) {
        return new Caja(id, local, new AuthService(db, hasherRapido, reloj, id), new CajaService(db, id),
                new VentaService(db, id));
    }

    private void configurarCatalogo() {
        int anio = hoy.getYear();
        // --- Quesos a granel (la clave es el PLU de la báscula) ---
        catalogoConfig.add(granel("Queso Oaxaca", "Quesos", "101", "INV-Q01", 30, 13500, 10, "CNM", Pesada.QUESO, 19000,
                pres("Bola 1 kg", "1", 18500, 0.15, 2), pres("Medio kilo", "0.5", 9800, 0.05, 2)));
        catalogoConfig.add(granel("Queso panela", "Quesos", "102", "INV-Q02", 40, 10500, 8, "CNM", Pesada.QUESO, 15000,
                pres("Pieza 400 g", "0.4", 6200, 0.25, 3)));
        catalogoConfig.add(granel("Queso manchego", "Quesos", "103", "INV-Q03", 15, 16000, 6, "CNM", Pesada.QUESO, 22000,
                pres("Rebanado 250 g", "0.25", 5800, 0.2, 3)));
        catalogoConfig.add(granel("Queso cotija añejo", "Quesos", "104", "INV-Q04", 20, 17000, 3, "CNM", Pesada.FINO, 24000));
        catalogoConfig.add(granel("Queso asadero", "Quesos", "105", "INV-Q05", 25, 12000, 4, "CNM", Pesada.QUESO, 17000));
        catalogoConfig.add(granel("Queso chihuahua", "Quesos", "106", "INV-Q06", 15, 15000, 6, "CNM", Pesada.QUESO, 21000,
                pres("Rebanado 200 g", "0.2", 4500, 0.2, 3)));
        catalogoConfig.add(granel("Requesón", "Quesos", "107", "INV-Q07", 50, 6000, 4, "CNM", Pesada.CREMA, 9000)
                .conAviso(new AvisoSinVenta(2, AvisoSinVenta.Unidad.DIAS, false, true, true)));
        catalogoConfig.add(granel("Queso fresco de rancho", "Quesos", "108", "INV-Q08", 45, 8500, 5, "CNM", Pesada.QUESO, 12000));
        catalogoConfig.add(granel("Queso de cabra con finas hierbas", "Quesos", "109", "INV-Q09", 20, 26000, 1.2, "CN",
                Pesada.FINO, 38000).edicionEspecial()
                .conAviso(new AvisoSinVenta(1, AvisoSinVenta.Unidad.SEMANAS, true, true, false)));
        catalogoConfig.add(granel("Queso añejo al vino tinto", "Quesos", "110", "INV-Q10", 25, 29000, 1, "C",
                Pesada.FINO, 42000).edicionEspecial());
        catalogoConfig.add(granel("Queso azul", "Quesos", "111", "INV-Q11", 15, 32000, 0.1, "C", Pesada.FINO, 45000)
                .conAviso(new AvisoSinVenta(3, AvisoSinVenta.Unidad.DIAS, false, true, true)));
        // --- Cremas y lácteos a granel ---
        catalogoConfig.add(granel("Crema de rancho a granel", "Cremas y lácteos", "201", "INV-C01", 10, 5500, 7, "CNM",
                Pesada.CREMA, 8000));
        catalogoConfig.add(granel("Mantequilla a granel", "Cremas y lácteos", "202", "INV-C02", 0, 12500, 3, "CNM",
                Pesada.CARNE, 18000));
        catalogoConfig.add(granel("Jocoque seco", "Cremas y lácteos", "203", "INV-C03", 20, 7500, 2, "CM", Pesada.CREMA, 11000));
        // --- Carnes frías a granel ---
        catalogoConfig.add(granel("Jamón de pierna", "Carnes frías", "301", "INV-CF01", 35, 11000, 7, "CNM", Pesada.CARNE,
                16000, pres("Paquete 250 g", "0.25", 4200, 0.2, 3)));
        catalogoConfig.add(granel("Jamón de pavo", "Carnes frías", "302", "INV-CF02", 30, 9500, 6, "CNM", Pesada.CARNE, 14000));
        catalogoConfig.add(granel("Chorizo de la casa", "Carnes frías", "303", "INV-CF03", 20, 9500, 4, "CNM", Pesada.CARNE, 14000));
        catalogoConfig.add(granel("Salchicha de pavo a granel", "Carnes frías", "304", "INV-CF04", 10, 6500, 5, "CNM",
                Pesada.CARNE, 9500));
        catalogoConfig.add(granel("Tocino ahumado", "Carnes frías", "305", "INV-CF05", 40, 13500, 3, "CN", Pesada.CARNE, 19500));
        catalogoConfig.add(granel("Queso de puerco", "Carnes frías", "306", "INV-CF06", 25, 9000, 2, "CM", Pesada.CARNE, 13000));
        catalogoConfig.add(granel("Chicharrón prensado", "Carnes frías", "307", "INV-CF07", 15, 11000, 2, "CM", Pesada.CARNE, 16000));
        catalogoConfig.add(granel("Aceitunas verdes a granel", "Abarrotes", "401", "INV-A01", 0, 9500, 1, "CN", Pesada.FINO, 15000));
        // --- Por pieza (con código de barras) ---
        catalogoConfig.add(pieza("Leche entera 1 L", "Cremas y lácteos", "INV-C10", 1030, 2100, 9, "CNM",
                pres("Pieza", "1", 2800, 0.9, 4), pres("Caja 12 pzas", "12", 32000, 0.1, 1)));
        catalogoConfig.add(pieza("Leche deslactosada 1 L", "Cremas y lácteos", "INV-C11", 1030, 2300, 5, "CNM",
                pres("Pieza", "1", 3100, 0.9, 3), pres("Caja 12 pzas", "12", 35500, 0.1, 1)));
        catalogoConfig.add(pieza("Crema ácida 450 ml", "Cremas y lácteos", "INV-C12", 460, 2900, 5, "CNM",
                pres("Pieza", "1", 4200, 1, 2)));
        catalogoConfig.add(pieza("Crema ácida 900 ml", "Cremas y lácteos", "INV-C13", 920, 5400, 3, "CNM",
                pres("Pieza", "1", 7800, 1, 2)));
        catalogoConfig.add(pieza("Yogurt natural 1 kg", "Cremas y lácteos", "INV-C14", 1000, 3100, 3, "CN",
                pres("Pieza", "1", 4500, 1, 2)));
        catalogoConfig.add(pieza("Yogurt griego natural 900 g", "Cremas y lácteos", "INV-C15", 900, 4800, 1.2, "C",
                pres("Pieza", "1", 6800, 1, 1))
                .conAviso(new AvisoSinVenta(2, AvisoSinVenta.Unidad.HORAS, false, false, true)));
        catalogoConfig.add(pieza("Queso crema 190 g", "Quesos", "INV-Q20", 190, 3300, 3, "CNM", pres("Pieza", "1", 4800, 1, 2)));
        catalogoConfig.add(pieza("Mantequilla 90 g", "Cremas y lácteos", "INV-C16", 90, 1900, 3, "CNM",
                pres("Pieza", "1", 2800, 0.85, 3), pres("Paquete 4 pzas", "4", 10500, 0.15, 1)));
        catalogoConfig.add(pieza("Queso fresco pieza 500 g", "Quesos", "INV-Q21", 500, 4300, 3, "NM",
                pres("Pieza", "1", 6200, 1, 2)).conMerma(15));
        catalogoConfig.add(pieza("Huevo blanco 18 pzas", "Abarrotes", "INV-A10", 1080, 4700, 6, "CNM",
                pres("Paquete", "1", 6500, 0.9, 2), pres("Caja 10 paquetes", "10", 62000, 0.1, 1)));
        catalogoConfig.add(pieza("Tostadas de maíz 300 g", "Abarrotes", "INV-A11", 300, 2400, 2, "CNM", pres("Pieza", "1", 3500, 1, 2)));
        catalogoConfig.add(pieza("Frijoles refritos 430 g", "Abarrotes", "INV-A12", 430, 2200, 2, "CNM", pres("Pieza", "1", 3200, 1, 3)));
        catalogoConfig.add(pieza("Salsa picante 370 ml", "Abarrotes", "INV-A13", 380, 1500, 2, "CNM", pres("Pieza", "1", 2200, 1, 2)));
        catalogoConfig.add(pieza("Chiles jalapeños 220 g", "Abarrotes", "INV-A14", 220, 1600, 1.5, "CNM", pres("Pieza", "1", 2400, 1, 2)));
        catalogoConfig.add(pieza("Bolillo", "Abarrotes", "INV-A15", 70, 180, 4, "CN",
                pres("Pieza", "1", 300, 0.8, 10), pres("Bolsa 10 pzas", "10", 2800, 0.2, 1)));
        catalogoConfig.add(pieza("Tortillas de harina 10 pzas", "Abarrotes", "INV-A16", 400, 2600, 2, "CNM",
                pres("Paquete", "1", 3800, 1, 2)));
        catalogoConfig.add(pieza("Café de olla 250 g", "Abarrotes", "INV-A17", 250, 4500, 1, "CN", pres("Pieza", "1", 6500, 1, 1)));
        catalogoConfig.add(pieza("Refresco de cola 600 ml", "Bebidas", "INV-B01", 620, 1300, 5, "CN",
                pres("Pieza", "1", 2000, 0.85, 3), pres("Paquete 6 pzas", "6", 11000, 0.15, 1)));
        catalogoConfig.add(pieza("Agua natural 1 L", "Bebidas", "INV-B02", 1000, 900, 4, "CN",
                pres("Pieza", "1", 1500, 0.85, 3), pres("Paquete 12 pzas", "12", 15000, 0.15, 1)));
        catalogoConfig.add(pieza("Jugo de naranja 1 L", "Bebidas", "INV-B03", 1040, 2600, 2, "CN", pres("Pieza", "1", 3800, 1, 2)));
        // --- Temporada ---
        catalogoConfig.add(pieza("Crema de nuez para nogada 500 g", "Temporada", "INV-T01", 500, 9800, 1.5, "CNM",
                pres("Pieza", "1", 14500, 1, 2))
                .deTemporada(new Temporada(LocalDate.of(anio, 8, 1), LocalDate.of(anio, 9, 30), 1, Temporada.Unidad.ANIOS))
                .conAviso(new AvisoSinVenta(5, AvisoSinVenta.Unidad.DIAS, true, false, false)));
        catalogoConfig.add(pieza("Tamales de rajas con queso", "Temporada", "INV-T02", 150, 1300, 3, "CNM",
                pres("Pieza", "1", 2200, 1, 6))
                .deTemporada(new Temporada(LocalDate.of(anio, 9, 1), LocalDate.of(anio, 9, 16), null, null)));
        catalogoConfig.add(pieza("Pan de muerto relleno de nata", "Temporada", "INV-T03", 450, 5200, 2, "CN",
                pres("Pieza", "1", 8500, 1, 2))
                .deTemporada(new Temporada(LocalDate.of(anio, 10, 20), LocalDate.of(anio, 11, 2), 1, Temporada.Unidad.ANIOS)));
        catalogoConfig.add(pieza("Rompope artesanal 1 L", "Temporada", "INV-T04", 1100, 11000, 1.5, "CNM",
                pres("Pieza", "1", 18000, 1, 2))
                .deTemporada(new Temporada(LocalDate.of(anio, 12, 1), LocalDate.of(anio + 1, 1, 6), 1, Temporada.Unidad.ANIOS)));
        // Se da de alta y luego se elimina (lo descontinuó el proveedor).
        catalogoConfig.add(granel("Queso de bola holandés", "Quesos", "112", "INV-Q12", 20, 38000, 0, "", Pesada.FINO, 52000));
    }

    private static Pres pres(String nombre, String factor, long precio, double participacion, int maxPiezas) {
        return new Pres(nombre, false, new BigDecimal(factor), precio, participacion, maxPiezas);
    }

    private static Prod granel(String nombre, String categoria, String plu, String inventario, int merma, long costo,
                               double popularidad, String sucursales, Pesada pesada, long precioKilo, Pres... extras) {
        double resto = 1;
        for (Pres p : extras) {
            resto -= p.participacion();
        }
        List<Pres> lista = new ArrayList<>();
        lista.add(new Pres("Kilo", true, BigDecimal.ONE, precioKilo, resto, 1));
        lista.addAll(List.of(extras));
        return new Prod(nombre, categoria, plu, inventario, Unidad.KG, 1000, merma, Disponibilidad.REGULAR, null, null,
                costo, popularidad, sucursales, pesada, lista);
    }

    private static Prod pieza(String nombre, String categoria, String inventario, int gramaje, long costo,
                              double popularidad, String sucursales, Pres... presentaciones) {
        return new Prod(nombre, categoria, null, inventario, Unidad.PZA, gramaje, 0, Disponibilidad.REGULAR, null, null,
                costo, popularidad, sucursales, Pesada.PIEZA, List.of(presentaciones));
    }

    // ---------------------------------------------------------------------
    // Sucursales, cajas y personal
    // ---------------------------------------------------------------------

    private void crearSucursalesYCajas() {
        // La primera sucursal es la de esta terminal (la caja la toma como su catálogo).
        sucursales.add(new Suc("CENTRO", "Cremería Centro", "Av. Juárez 120, Col. Centro", "442 212 3456", 'C', 48,
                EnumSet.of(MONDAY, THURSDAY), 7, Map.of()));
        sucursales.add(new Suc("NORTE", "Cremería Norte", "Blvd. Bernardo Quintana 845, Col. Arboledas", "442 245 7788",
                'N', 32, EnumSet.of(TUESDAY, FRIDAY), 15, Map.of("Bebidas", 1.4)));
        sucursales.add(new Suc("MERCADO", "Cremería Mercado Hidalgo", "Mercado Hidalgo, locales 32 y 33", "442 214 0921",
                'M', 36, EnumSet.of(MONDAY, THURSDAY), 10, Map.of("Quesos", 1.3, "Carnes frías", 1.2)));
        for (Suc s : sucursales) {
            adm.sucursales().guardar(null, s.codigo, s.nombre, s.direccion, s.telefono, admin);
            avanzar(2);
        }
        adm.sucursales().guardar(null, "SUR", "Cremería Plaza Sur", "Plaza Sur, local 14", null, admin);
        avanzar(1);
        for (Sucursal s : adm.sucursales().listar()) {
            for (Suc suc : sucursales) {
                if (suc.codigo.equals(s.codigo())) {
                    suc.sucursal = s;
                }
            }
            if (s.codigo().equals("SUR")) {
                sur = s;
            }
        }
        // Todavía no abre: queda deshabilitada.
        adm.sucursales().cambiarActivo(sur, false, admin);

        // Cajas: esta terminal es la caja 1 del Centro; las demás son las otras terminales.
        Suc centro = suc("CENTRO");
        db.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE dispositivo SET sucursal_id = ? WHERE id = ?")) {
                ps.setString(1, centro.sucursal.id());
                ps.setString(2, cajaLocal.id());
                ps.executeUpdate();
            }
            return null;
        });
        centro.cajas.add(cajaLocal);
        centro.cajas.add(nuevaCaja(centro));
        suc("NORTE").cajas.add(nuevaCaja(suc("NORTE")));
        suc("NORTE").cajas.add(nuevaCaja(suc("NORTE")));
        suc("MERCADO").cajas.add(nuevaCaja(suc("MERCADO")));
        for (Suc s : sucursales) {
            s.catalogo = new CatalogoService(db, s.sucursal.id());
        }
    }

    private Caja nuevaCaja(Suc s) {
        String id = db.enTransaccion(c -> {
            String nuevo;
            do {
                nuevo = Ids.nuevo();
            } while (contar(c, "SELECT COUNT(*) FROM dispositivo WHERE nombre = '"
                    + DispositivoRepository.nombreCaja(nuevo) + "'") > 0);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dispositivo (id, nombre, sucursal_id, creado_en) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, nuevo);
                ps.setString(2, DispositivoRepository.nombreCaja(nuevo));
                ps.setString(3, s.sucursal.id());
                ps.setString(4, Tiempo.ahora());
                ps.executeUpdate();
            }
            return nuevo;
        });
        return caja(id, false);
    }

    private void crearPersonal() {
        List<Empleado> personal = List.of(
                new Empleado("Laura Méndez Castro", "lmendez", Rol.ADMINISTRADOR, null, null),
                new Empleado("María Fernanda López Ruiz", "mlopez", Rol.SUPERVISOR, "CENTRO", null),
                new Empleado("José Luis Hernández Soto", "jhernandez", Rol.CAJERO, "CENTRO",
                        semana(dias(MONDAY, SATURDAY), "07:00", "14:30", "10:30", "11:00")),
                new Empleado("Ana Karen Ramírez Díaz", "aramirez", Rol.CAJERO, "CENTRO",
                        semana(dias(MONDAY, SATURDAY), "14:30", "21:00", "17:30", "18:00")),
                new Empleado("Luis Ángel Torres Vega", "ltorres", Rol.CAJERO, "CENTRO", null),
                new Empleado("Roberto Sánchez Medina", "rsanchez", Rol.SUPERVISOR, "NORTE", horarioRoberto()),
                new Empleado("Daniela Cruz Ortega", "dcruz", Rol.CAJERO, "NORTE",
                        semana(dias(MONDAY, SATURDAY), "07:30", "14:00", "10:00", "10:30")),
                new Empleado("Miguel Ángel Flores Ríos", "mflores", Rol.CAJERO, "NORTE",
                        semana(dias(MONDAY, SATURDAY), "14:00", "20:30", "17:00", "17:30")),
                new Empleado("Jorge Castillo Peña", "jcastillo", Rol.CAJERO, "NORTE",
                        semana(EnumSet.of(SUNDAY), "08:00", "15:00", "11:30", "12:00")),
                new Empleado("Patricia Gómez Aguilar", "pgomez", Rol.SUPERVISOR, "MERCADO",
                        semana(dias(MONDAY, SATURDAY), "06:30", "15:30", null, null)),
                new Empleado("Carlos Jiménez Navarro", "cjimenez", Rol.CAJERO, "MERCADO",
                        semana(dias(MONDAY, FRIDAY), "06:30", "15:30", "11:00", "11:30")),
                new Empleado("Guadalupe Morales Luna", "gmorales", Rol.CAJERO, "MERCADO",
                        semana(EnumSet.of(SATURDAY, SUNDAY), "06:30", "15:30", "11:00", "11:30")));
        for (Empleado e : personal) {
            String sucursalId = e.sucursal() == null ? almacenId : suc(e.sucursal()).sucursal.id();
            adm.usuarios().crear(e.nombre(), e.usuario(), PASSWORD.toCharArray(), e.rol(), sucursalId, admin);
            Usuario u = db.con(c -> new UsuarioRepository().buscarPorUsuario(c, e.usuario())).orElseThrow();
            usuarios.put(e.usuario(), u);
            avanzar(1);
            if (e.horario() != null) {
                cajaLocal.auth().accesos().guardarHorario(u, e.horario(), admin);
                conHorario.add(u.id());
                avanzar(1);
            }
        }
        encargadaAlmacen = usuarios.get("lmendez");
        suc("CENTRO").supervisor = usuarios.get("mlopez");
        suc("NORTE").supervisor = usuarios.get("rsanchez");
        suc("MERCADO").supervisor = usuarios.get("pgomez");
    }

    private static Set<DayOfWeek> dias(DayOfWeek primero, DayOfWeek ultimo) {
        return EnumSet.range(primero, ultimo);
    }

    private static List<HorarioDia> semana(Set<DayOfWeek> laborables, String entrada, String salida,
                                           String descansoInicio, String descansoFin) {
        List<HorarioDia> lista = new ArrayList<>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            lista.add(laborables.contains(dia)
                    ? new HorarioDia(dia, true, LocalTime.parse(entrada), LocalTime.parse(salida), hora(descansoInicio),
                    hora(descansoFin))
                    : HorarioDia.descanso(dia));
        }
        return lista;
    }

    /** Supervisor del Norte: entre semana con comida; el sábado atiende la caja 2 hasta las 15:00. */
    private static List<HorarioDia> horarioRoberto() {
        List<HorarioDia> lista = new ArrayList<>(semana(dias(MONDAY, FRIDAY), "09:00", "18:00", "14:00", "15:00"));
        lista.set(SATURDAY.ordinal(), new HorarioDia(SATURDAY, true, LocalTime.of(9, 0), LocalTime.of(15, 0), null, null));
        return lista;
    }

    private static LocalTime hora(String texto) {
        return texto == null ? null : LocalTime.parse(texto);
    }

    /** Quién trabaja en cada caja ese día. */
    private List<Plan> planes(Suc s, LocalDate dia) {
        DayOfWeek d = dia.getDayOfWeek();
        List<Plan> lista = new ArrayList<>();
        switch (s.codigo) {
            case "CENTRO" -> {
                Caja c1 = s.cajas.get(0);
                Caja c2 = s.cajas.get(1);
                if (d == SUNDAY) {
                    lista.add(plan(s, c1, "ltorres", "08:00", "15:00", 1));
                } else if (d == MONDAY) {
                    lista.add(plan(s, c1, "jhernandez", "07:00", "14:30", 0.45));
                    lista.add(plan(s, c1, "aramirez", "14:30", "21:00", 0.55));
                } else {
                    lista.add(plan(s, c1, "jhernandez", "07:00", "14:30", 0.36));
                    lista.add(plan(s, c1, "aramirez", "14:30", "21:00", 0.40));
                    lista.add(plan(s, c2, "ltorres", "09:00", "17:00", 0.24));
                }
            }
            case "NORTE" -> {
                Caja n1 = s.cajas.get(0);
                Caja n2 = s.cajas.get(1);
                if (d == SUNDAY) {
                    // Desde que Jorge se dio de baja, el Norte no abre en domingo.
                    if (dia.isBefore(bajaJorge)) {
                        lista.add(plan(s, n1, "jcastillo", "08:00", "15:00", 0.8));
                    }
                } else if (d == SATURDAY) {
                    lista.add(plan(s, n1, "dcruz", "07:30", "14:00", 0.36));
                    lista.add(plan(s, n1, "mflores", "14:00", "20:30", 0.40));
                    lista.add(plan(s, n2, "rsanchez", "10:00", "15:00", 0.24));
                } else {
                    lista.add(plan(s, n1, "dcruz", "07:30", "14:00", 0.45));
                    lista.add(plan(s, n1, "mflores", "14:00", "20:30", 0.55));
                }
            }
            default -> lista.add(plan(s, s.cajas.getFirst(), d == SATURDAY || d == SUNDAY ? "gmorales" : "cjimenez",
                    "06:30", "15:30", 1));
        }
        return lista;
    }

    private Plan plan(Suc s, Caja caja, String usuario, String inicio, String fin, double participacion) {
        return new Plan(s, caja, usuarios.get(usuario), LocalTime.parse(inicio), LocalTime.parse(fin), participacion);
    }

    // ---------------------------------------------------------------------
    // Catálogo
    // ---------------------------------------------------------------------

    private void crearProductos() {
        ProductoRepository repo = new ProductoRepository();
        String[][] categorias = {
                {"Quesos", "#f59e0b"}, {"Cremas y lácteos", "#3b82f6"}, {"Carnes frías", "#ef4444"},
                {"Abarrotes", "#10b981"}, {"Bebidas", "#8b5cf6"}, {"Temporada", "#ec4899"}};
        db.enTransaccion(c -> {
            for (int i = 0; i < categorias.length; i++) {
                repo.insertarCategoria(c, Ids.nuevo(), categorias[i][0], categorias[i][1], i + 1);
            }
            return null;
        });
        int consecutivo = 1;
        for (Prod p : catalogoConfig) {
            List<Presentacion> presentaciones = new ArrayList<>();
            for (Pres pr : p.presentaciones()) {
                String codigo = pr.granel() ? null
                        : CodigoBarras.completarEan13("7501234%05d".formatted(consecutivo++));
                presentaciones.add(new Presentacion(null, null, pr.nombre(), pr.granel(), pr.factor(), codigo,
                        pr == p.presentaciones().getFirst(), true));
            }
            adm.productos().guardar(new ProductoAdminService.Datos(null, p.nombre(), p.categoria(), p.clave(),
                    p.codigoInventario(), p.unidad(), p.sujetoMerma(), p.disponibilidad(), p.temporada(),
                    presentaciones, List.of(), BigDecimal.valueOf(p.gramaje()), BigDecimal.valueOf(p.merma()),
                    p.aviso()), admin);
            avanzar(1);
        }
        refrescarProductos();
        for (ProductoCatalogo pc : productos.values()) {
            Prod p = catalogoConfig.stream().filter(x -> x.nombre().equals(pc.nombre())).findFirst().orElseThrow();
            idDe.put(p, pc.id());
            prodPorId.put(pc.id(), p);
            Map<Pres, String> ids = new HashMap<>();
            for (Presentacion presentacion : pc.presentaciones()) {
                Pres pr = p.presentaciones().stream().filter(x -> x.nombre().equals(presentacion.nombre()))
                        .findFirst().orElseThrow();
                ids.put(pr, presentacion.id());
                presPorId.put(presentacion.id(), pr);
            }
            idPresentacion.put(p, ids);
        }
    }

    private void refrescarProductos() {
        Map<String, ProductoCatalogo> mapa = new HashMap<>();
        for (ProductoCatalogo pc : adm.productos().listar()) {
            mapa.put(pc.id(), pc);
        }
        productos = mapa;
    }

    private ProductoCatalogo pc(Prod p) {
        return productos.get(idDe.get(p));
    }

    private boolean vigente(Prod p) {
        ProductoCatalogo pc = pc(p);
        return pc != null && pc.activo();
    }

    private Prod prod(String nombre) {
        return catalogoConfig.stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private List<Prod> asignados(Suc s) {
        return catalogoConfig.stream().filter(p -> p.sucursales().indexOf(s.letra) >= 0).toList();
    }

    /**
     * Qué tanto se vende un producto en la sucursal. Algunos casi no se venden en cierta sucursal
     * para que aparezcan los avisos de "producto sin venta" (el propio del producto o los días de la sucursal).
     */
    private double peso(Suc s, Prod p) {
        double factor = s.factorCategoria.getOrDefault(p.categoria(), 1.0);
        if (s.codigo.equals("NORTE") && (p.nombre().startsWith("Queso de cabra") || p.nombre().startsWith("Café de olla"))
                || s.codigo.equals("MERCADO") && p.nombre().startsWith("Jocoque")) {
            factor = 0.02;
        }
        return p.popularidad() * factor;
    }

    /** Demanda diaria esperada de cada producto por sucursal (unidad base), para mínimos, máximos y surtidos. */
    private void estimarDemanda() {
        Random mc = new Random(1);
        int muestras = 20_000;
        for (Suc s : sucursales) {
            List<Prod> lista = asignados(s);
            double[] pesos = lista.stream().mapToDouble(p -> peso(s, p)).toArray();
            Map<Prod, Double> base = new HashMap<>();
            double valor = 0;
            for (int i = 0; i < muestras; i++) {
                Prod p = lista.get(elegir(pesos, mc));
                Pres pr = presentacion(p, mc);
                BigDecimal q = cantidad(p, pr, mc);
                base.merge(p, q.multiply(pr.factor()).doubleValue(), Double::sum);
                valor += precio(p, pr, s, desde) * q.doubleValue();
            }
            double lineasDia = s.ventasDia * LINEAS_PROMEDIO;
            for (Prod p : lista) {
                s.demanda.put(p, base.getOrDefault(p, 0.0) / muestras * lineasDia);
            }
            s.ticketEstimado = valor / muestras * LINEAS_PROMEDIO;
        }
    }

    private double demanda(Suc s, Prod p) {
        return s.demanda.getOrDefault(p, 0.0);
    }

    private BigDecimal minimo(Suc s, Prod p) {
        return redondear(p, Math.max(p.unidad() == Unidad.KG ? 0.5 : 2, 1.5 * demanda(s, p)));
    }

    private BigDecimal maximo(Suc s, Prod p) {
        // Se surte dos veces por semana: el máximo alcanza para 8 días y casi nunca se llega al mínimo.
        return redondear(p, Math.max(p.unidad() == Unidad.KG ? 2 : 6, 8 * demanda(s, p)));
    }

    /** Hacia arriba: medios kilos, o piezas en múltiplos de su caja/paquete. */
    private static BigDecimal redondear(Prod p, double base) {
        if (p.unidad() == Unidad.KG) {
            return BigDecimal.valueOf(Math.ceil(base * 2) / 2).setScale(3, RoundingMode.HALF_UP);
        }
        int m = p.multiplo();
        return BigDecimal.valueOf((long) Math.ceil(base / m - 1e-9) * m);
    }

    // ---------------------------------------------------------------------
    // Configuración del administrador
    // ---------------------------------------------------------------------

    private void configurarAlertasMetasYComisiones() {
        for (Suc s : sucursales) {
            adm.alertas().guardarDiasSinVenta(s.sucursal, s.diasSinVenta, admin);
            avanzar(1);
        }
        // Meta del primer mes con la venta esperada: el Centro la rebasa, al Norte le falta.
        Map<String, Double> factor = Map.of("CENTRO", 0.95, "NORTE", 1.08, "MERCADO", 1.0);
        YearMonth mes = YearMonth.from(desde);
        for (Suc s : sucursales) {
            double esperado = s.ventasDia * s.ticketEstimado * mes.lengthOfMonth();
            guardarMeta(s, mes, Math.round(esperado * factor.get(s.codigo) / 100_000) * 100_000);
        }

        Prod oaxaca = prod("Queso Oaxaca");
        Prod jamon = prod("Jamón de pierna");
        Prod crema = prod("Crema ácida 450 ml");
        Prod leche = prod("Leche entera 1 L");
        Prod panela = prod("Queso panela");
        Prod rompope = prod("Rompope artesanal 1 L");
        regla("Venta diaria de $4,000", ComisionService.Tipo.META_TOTAL, null, null, null, 400_000, 4000,
                ComisionService.Periodo.DIARIO, null, null, true);
        regla("Meta semanal Centro", ComisionService.Tipo.META_TOTAL, null, null, null, 2_200_000, 20_000,
                ComisionService.Periodo.SEMANAL, null, suc("CENTRO"), true);
        regla("Meta mensual de $80,000", ComisionService.Tipo.META_TOTAL, null, null, null, 8_000_000, 50_000,
                ComisionService.Periodo.MENSUAL, null, null, true);
        regla("Queso Oaxaca: 12 kg netos a la semana", ComisionService.Tipo.META_CANTIDAD, ComisionService.Medida.GRAMOS,
                oaxaca, new BigDecimal("12000"), 0, 12_000, ComisionService.Periodo.SEMANAL, null, null, true);
        regla("Jamón de pierna por kilo neto", ComisionService.Tipo.POR_CANTIDAD, ComisionService.Medida.GRAMOS,
                jamon, new BigDecimal("1000"), 0, 1_000, ComisionService.Periodo.MENSUAL, null, null, true);
        regla("Crema 450 ml: por cada 10 piezas", ComisionService.Tipo.POR_CANTIDAD, ComisionService.Medida.UNIDADES,
                crema, new BigDecimal("10"), 0, 300, ComisionService.Periodo.SEMANAL, null, null, true);
        regla("Leche entera: 60 piezas al mes (José Luis)", ComisionService.Tipo.META_CANTIDAD,
                ComisionService.Medida.UNIDADES, leche, new BigDecimal("60"), 0, 10_000, ComisionService.Periodo.MENSUAL,
                usuarios.get("jhernandez"), null, true);
        regla("Panela en el Mercado: por cada 2 kg", ComisionService.Tipo.POR_CANTIDAD, ComisionService.Medida.GRAMOS,
                panela, new BigDecimal("2000"), 0, 500, ComisionService.Periodo.DIARIO, null, suc("MERCADO"), true);
        regla("Rompope navideño: 24 botellas", ComisionService.Tipo.META_CANTIDAD, ComisionService.Medida.UNIDADES,
                rompope, new BigDecimal("24"), 0, 8_000, ComisionService.Periodo.MENSUAL, null, null, false);
    }

    private void regla(String nombre, ComisionService.Tipo tipo, ComisionService.Medida medida, Prod producto,
                       BigDecimal cantidad, long importeMeta, long comision, ComisionService.Periodo periodo,
                       Usuario usuario, Suc suc, boolean activa) {
        adm.comisiones().guardar(new ComisionService.Regla(null, null, nombre, tipo,
                medida == null ? ComisionService.Medida.UNIDADES : medida, producto == null ? null : idDe.get(producto),
                null, producto == null ? null : producto.unidad(), cantidad, importeMeta, comision, periodo,
                usuario == null ? null : usuario.id(), null, suc == null ? null : suc.sucursal.id(), null, activa), admin);
        avanzar(1);
    }

    private void guardarMeta(Suc s, YearMonth mes, long centavos) {
        indicadores.guardarMeta(admin, s.sucursal.id(), mes.getYear(), mes.getMonthValue(), centavos);
        avanzar(1);
    }

    /** Meta del mes: lo que vendió en promedio por día el mes pasado, con un crecimiento distinto por sucursal. */
    private void metaDelMes(YearMonth mes) {
        Map<String, Double> crecimiento = Map.of("CENTRO", 1.02, "NORTE", 1.12, "MERCADO", 1.05);
        YearMonth anterior = mes.minusMonths(1);
        for (Suc s : sucursales) {
            double[] venta = db.con(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT COALESCE(SUM(total_centavos), 0), COUNT(DISTINCT substr(fecha, 1, 10))
                        FROM ventas WHERE sucursal_id = ? AND estado = 'COMPLETADA' AND fecha >= ? AND fecha < ?""")) {
                    ps.setString(1, s.sucursal.id());
                    ps.setString(2, Tiempo.formatear(anterior.atDay(1).atStartOfDay(ZONA).toInstant()));
                    ps.setString(3, Tiempo.formatear(mes.atDay(1).atStartOfDay(ZONA).toInstant()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return new double[]{rs.getLong(1), rs.getInt(2)};
                    }
                }
            });
            double porDia = venta[1] == 0 ? s.ventasDia * s.ticketEstimado : venta[0] / venta[1];
            guardarMeta(s, mes, Math.round(porDia * mes.lengthOfMonth() * crecimiento.get(s.codigo) / 100_000) * 100_000);
        }
    }

    // ---------------------------------------------------------------------
    // Inventario inicial
    // ---------------------------------------------------------------------

    private void inventarioInicial() {
        ir(desde.minusDays(2), 8, 0);
        for (Prod p : catalogoConfig) {
            ProductoCatalogo pc = pc(p);
            if (p.sucursales().isEmpty()) {
                continue;
            }
            double total = sucursales.stream().mapToDouble(s -> demanda(s, p)).sum();
            double cantidad = p.nombre().startsWith("Rompope") ? 48
                    : p.nombre().startsWith("Pan de muerto") ? 0 : Math.max(10, total * 35);
            if (cantidad > 0) {
                BigDecimal q = redondearEntrada(p, cantidad);
                adm.almacen().reabastecer(pc, q, costo(p, reloj.dia()), nota(p), encargadaAlmacen);
                avanzar(2);
            }
        }
        // El proveedor descontinuó el queso de bola: se elimina antes de surtirlo.
        adm.productos().eliminar(pc(prod("Queso de bola holandés")), admin);
        refrescarProductos();

        ir(desde.minusDays(1), 7, 0);
        for (Suc s : sucursales) {
            surtir(s, desde.minusDays(1), SurtidoService.FormaPago.EFECTIVO, "Surtido de apertura", true);
        }
        // Mínimos y máximos de los productos de línea de cada sucursal.
        ir(desde.minusDays(1), 12, 0);
        for (Suc s : sucursales) {
            List<LimitesService.Cambio> cambios = new ArrayList<>();
            for (Prod p : asignados(s)) {
                if (p.disponibilidad() == Disponibilidad.REGULAR && vigente(p)) {
                    cambios.add(new LimitesService.Cambio(pc(p), minimo(s, p), maximo(s, p)));
                }
            }
            adm.limites().guardar(s.sucursal, cambios, admin);
            avanzar(3);
        }
    }

    private BigDecimal redondearEntrada(Prod p, double cantidad) {
        if (p.unidad() == Unidad.KG) {
            return BigDecimal.valueOf(Math.ceil(cantidad / 5) * 5).setScale(3, RoundingMode.HALF_UP);
        }
        int m = Math.max(12, p.multiplo());
        return BigDecimal.valueOf((long) Math.ceil(cantidad / m) * m);
    }

    private String nota(Prod p) {
        String proveedor = switch (p.categoria()) {
            case "Quesos" -> r.nextBoolean() ? "Quesería La Esperanza" : "Lácteos Los Altos";
            case "Cremas y lácteos" -> r.nextBoolean() ? "Lácteos Los Altos" : "Cremería El Sauz";
            case "Carnes frías" -> r.nextBoolean() ? "Embutidos del Norte" : "Carnes Frías San Rafael";
            case "Bebidas" -> "Distribuidora de Bebidas del Bajío";
            case "Temporada" -> "Productos Artesanales Doña Chuy";
            default -> "Abarrotes La Central";
        };
        return "Factura F-" + (10_000 + r.nextInt(90_000)) + " · " + proveedor;
    }

    // ---------------------------------------------------------------------
    // Un día de operación
    // ---------------------------------------------------------------------

    private void simularDia(LocalDate dia) {
        DayOfWeek d = dia.getDayOfWeek();
        boolean ultimo = dia.equals(hoy);
        long transcurridos = ChronoUnit.DAYS.between(desde, dia);
        long total = Math.max(1, ChronoUnit.DAYS.between(desde, hoy));
        if (dia.getDayOfMonth() == 1 || transcurridos % 7 == 0) {
            log.info("Simulando {}", dia);
        }

        if (!ir(dia, 6, 0)) {
            return;
        }
        adm.productos().revisarTemporadas();
        refrescarProductos();
        if (dia.getDayOfMonth() == 1 && !dia.equals(desde)) {
            metaDelMes(YearMonth.from(dia));
        }
        eventosDelDia(dia, transcurridos, total);

        if (d == MONDAY && ir(dia, 6, 10)) {
            reabastecerAlmacen(dia);
        }
        if (!ultimo && ir(dia, 6, 30)) {
            atenderOrdenes(dia);
        }
        if (ultimo && ir(dia, 6, 45)) {
            metaDelMes(YearMonth.from(dia).plusMonths(1)); // Deja capturada la meta del mes que viene.
        }
        if (ir(dia, 7, 0)) {
            for (Suc s : sucursales) {
                if (s.diasSurtido.contains(d)) {
                    surtir(s, dia, forma(s, dia), d == MONDAY || d == TUESDAY ? "Surtido de inicio de semana"
                            : "Surtido de fin de semana", false);
                }
            }
        }
        if (ir(dia, 8, 15)) {
            for (Suc s : sucursales) {
                if (r.nextDouble() < 0.3) {
                    mermaSucursal(s);
                }
            }
            if (d == FRIDAY) {
                mermaAlmacen();
            }
        }
        if (ir(dia, 10, 0)) {
            abonos(dia);
        }
        for (Suc s : sucursales) {
            s.catalogo.recargar();
            s.precios = db.con(c -> lotes.preciosDeSucursal(c, s.sucursal.id()));
            double ruido = 0.85 + 0.3 * r.nextDouble();
            double quincena = dia.getDayOfMonth() >= 14 && dia.getDayOfMonth() <= 16
                    || dia.getDayOfMonth() >= 29 || dia.getDayOfMonth() <= 2 ? 1.1 : 1.0;
            double tendencia = 1 + 0.08 * transcurridos / total;
            for (Plan p : planes(s, dia)) {
                simularTurno(p, dia, factorDia(d) * ruido * quincena * tendencia);
            }
        }
    }

    private static double factorDia(DayOfWeek d) {
        return switch (d) {
            case MONDAY, TUESDAY -> 0.9;
            case WEDNESDAY -> 0.95;
            case THURSDAY -> 1.0;
            case FRIDAY -> 1.15;
            case SATURDAY -> 1.3;
            case SUNDAY -> 0.8;
        };
    }

    private SurtidoService.FormaPago forma(Suc s, LocalDate dia) {
        return switch (s.codigo) {
            case "NORTE" -> SurtidoService.FormaPago.CREDITO;
            case "MERCADO" -> dia.getDayOfWeek().getValue() <= WEDNESDAY.getValue()
                    ? SurtidoService.FormaPago.EFECTIVO : SurtidoService.FormaPago.CREDITO;
            default -> SurtidoService.FormaPago.EFECTIVO;
        };
    }

    /** Cambios del administrador a lo largo del periodo, para que queden en el historial. */
    private void eventosDelDia(LocalDate dia, long transcurridos, long total) {
        if (dia.equals(bajaJorge) && ir(dia, 6, 5)) {
            adm.usuarios().cambiarActivo(usuarios.get("jcastillo"), false, admin);
        }
        if (transcurridos == 10 && ir(dia, 6, 5)) {
            Suc norte = suc("NORTE");
            adm.sucursales().guardar(norte.sucursal.id(), norte.codigo, norte.nombre, norte.direccion,
                    "442 245 7790", admin);
        }
        if (transcurridos == total / 3 && ir(dia, 6, 6)) {
            // Se ajusta la merma del Oaxaca: se pierde más al deshebrarlo.
            Prod oaxaca = prod("Queso Oaxaca");
            ProductoCatalogo pc = pc(oaxaca);
            adm.productos().guardar(new ProductoAdminService.Datos(pc.id(), pc.nombre(), pc.categoria(), pc.clave(),
                    pc.codigoInventario(), pc.unidad(), true, pc.disponibilidad(), pc.temporada(), pc.presentaciones(),
                    List.of(), pc.gramajeGramos(), new BigDecimal("35"), pc.avisoSinVenta()), admin);
            refrescarProductos();
        }
        if (transcurridos == total / 2 && ir(dia, 6, 7)) {
            ComisionService.Regla regla = adm.comisiones().reglas().stream()
                    .filter(x -> x.nombre().startsWith("Venta diaria")).findFirst().orElseThrow();
            adm.comisiones().guardar(new ComisionService.Regla(regla.id(), regla.folio(), regla.nombre(), regla.tipo(),
                    regla.medida(), null, null, null, null, regla.importeMetaCentavos(), 5000, regla.periodo(), null,
                    null, null, null, true), admin);
        }
        if (transcurridos == total * 2 / 5 && ir(dia, 8, 40)) {
            // Falla del refrigerador de la vitrina del Norte.
            Suc norte = suc("NORTE");
            cargarExistencias(norte);
            for (String nombre : List.of("Queso panela", "Crema de rancho a granel", "Jamón de pavo")) {
                Prod p = prod(nombre);
                BigDecimal ex = norte.existencia.getOrDefault(idDe.get(p), BigDecimal.ZERO);
                BigDecimal q = ex.multiply(new BigDecimal("0.4")).setScale(3, RoundingMode.DOWN);
                if (q.compareTo(new BigDecimal("0.100")) >= 0) {
                    adm.almacen().registrarMerma(pc(p), norte.sucursal, q,
                            "Falla del refrigerador de la vitrina: se perdió la cadena de frío", admin);
                    avanzar(1);
                }
            }
        }
        if (transcurridos == total * 2 / 3 && ir(dia, 6, 8)) {
            // Se terminó la edición especial.
            adm.productos().cambiarActivo(pc(prod("Queso añejo al vino tinto")), false, admin);
            refrescarProductos();
        }
    }

    // ---------------------------------------------------------------------
    // Almacén, surtidos, órdenes, crédito y merma
    // ---------------------------------------------------------------------

    private void reabastecerAlmacen(LocalDate dia) {
        for (Prod p : catalogoConfig) {
            if (!vigente(p) || p.sucursales().isEmpty()) {
                continue;
            }
            double porDia = sucursales.stream().mapToDouble(s -> demanda(s, p)).sum();
            BigDecimal existencia = adm.almacen().existencia(idDe.get(p), almacenId);
            if (existencia.doubleValue() < 14 * porDia) {
                adm.almacen().reabastecer(pc(p), redondearEntrada(p, Math.max(10, 21 * porDia)), costo(p, dia), nota(p),
                        r.nextDouble() < 0.7 ? encargadaAlmacen : admin);
                avanzar(1);
            }
        }
    }

    /** Surte hasta el máximo lo que esté abajo del 85 %. */
    private void surtir(Suc s, LocalDate dia, SurtidoService.FormaPago forma, String notas, boolean apertura) {
        cargarExistencias(s);
        List<SurtidoService.Linea> lineas = new ArrayList<>();
        for (Prod p : asignados(s)) {
            if (!vigente(p)) {
                continue;
            }
            double maximo = maximo(s, p).doubleValue();
            double existencia = s.existencia.getOrDefault(idDe.get(p), BigDecimal.ZERO).doubleValue();
            if (!apertura && existencia >= 0.85 * maximo) {
                continue;
            }
            BigDecimal q = limitarAlAlmacen(p, redondear(p, maximo - existencia));
            if (q.signum() > 0) {
                lineas.add(new SurtidoService.Linea(pc(p), q, precios(p, s, dia)));
            }
        }
        if (!lineas.isEmpty()) {
            adm.surtidos().registrar(s.sucursal, forma, lineas, notas, admin, null);
            avanzar(3);
        }
    }

    private BigDecimal limitarAlAlmacen(Prod p, BigDecimal cantidad) {
        BigDecimal disponible = adm.almacen().existencia(idDe.get(p), almacenId);
        if (p.unidad() == Unidad.PZA) {
            disponible = disponible.setScale(0, RoundingMode.DOWN);
        }
        return cantidad.min(disponible).max(BigDecimal.ZERO);
    }

    private Map<String, Long> precios(Prod p, Suc s, LocalDate dia) {
        Map<String, Long> precios = new HashMap<>();
        for (Presentacion presentacion : pc(p).presentaciones()) {
            if (presentacion.activo()) {
                precios.put(presentacion.id(), precio(p, presPorId.get(presentacion.id()), s, dia));
            }
        }
        return precios;
    }

    /** Precio de lista: el Mercado da los quesos 3 % más baratos y a media quincena subieron los lácteos. */
    private long precio(Prod p, Pres pr, Suc s, LocalDate dia) {
        double f = 1;
        if (s.codigo.equals("MERCADO") && p.categoria().equals("Quesos")) {
            f *= 0.97;
        }
        if (!dia.isBefore(fechaAumentoPrecios)
                && (p.categoria().equals("Quesos") || p.categoria().equals("Cremas y lácteos"))) {
            f *= 1.06;
        }
        long bruto = Math.round(pr.precio() * f);
        long paso = bruto >= 5000 ? 100 : 50;
        return Math.max(paso, Math.round((double) bruto / paso) * paso);
    }

    private long costo(Prod p, LocalDate dia) {
        double f = 1 + (r.nextDouble() - 0.5) * 0.06;
        if (!dia.isBefore(fechaAumentoCostos)) {
            f *= 1.05;
        }
        return Math.round(p.costo() * f / 10) * 10;
    }

    private void atenderOrdenes(LocalDate dia) {
        for (OrdenRepository.Orden o : adm.ordenes().pendientes()) {
            Suc s = sucursales.stream().filter(x -> x.sucursal.id().equals(o.sucursalId())).findFirst().orElseThrow();
            Prod p = prodPorId.get(o.productoId());
            avanzar(2);
            if (!vigente(p)) {
                adm.ordenes().rechazar(o, "El producto ya no está a la venta (fuera de temporada o deshabilitado).", admin);
                continue;
            }
            BigDecimal q = limitarAlAlmacen(p, redondear(p, o.cantidadSugerida().doubleValue()));
            if (q.signum() <= 0) {
                adm.ordenes().rechazar(o, "El almacén no tiene existencia; se surte con la próxima entrada.", admin);
                continue;
            }
            if (r.nextDouble() < 0.12) {
                adm.ordenes().rechazar(o, r.nextBoolean() ? "Se incluye en el surtido programado de la semana."
                        : "Conteo físico: la sucursal todavía tiene existencia en bodega.", admin);
                continue;
            }
            adm.surtidos().registrar(s.sucursal, forma(s, dia),
                    List.of(new SurtidoService.Linea(pc(p), q, precios(p, s, dia))),
                    "Surtido por orden de reabastecimiento " + o.folio(), admin, o.id());
        }
    }

    private void abonos(LocalDate dia) {
        for (Suc s : sucursales) {
            boolean toca = s.codigo.equals("NORTE") && dia.getDayOfWeek() == FRIDAY
                    || s.codigo.equals("MERCADO") && dia.getDayOfWeek() == MONDAY
                    && dia.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) % 2 == 0;
            if (!toca) {
                continue;
            }
            long saldo = adm.credito().saldos(List.of(s.sucursal)).getFirst().saldo();
            long abono = (long) (saldo * (s.codigo.equals("NORTE") ? 0.6 : 0.9)) / 10_000 * 10_000;
            if (abono > 0) {
                adm.credito().abonar(s.sucursal, abono, switch (r.nextInt(3)) {
                    case 0 -> "Depósito bancario";
                    case 1 -> "Transferencia SPEI";
                    default -> "Pago en efectivo en el almacén";
                }, admin);
                avanzar(5);
            }
        }
    }

    private void mermaSucursal(Suc s) {
        cargarExistencias(s);
        List<Prod> candidatos = asignados(s).stream().filter(p -> p.sujetoMerma() && vigente(p)).toList();
        Prod p = candidatos.get(r.nextInt(candidatos.size()));
        BigDecimal ex = s.existencia.getOrDefault(idDe.get(p), BigDecimal.ZERO);
        BigDecimal q = p.unidad() == Unidad.KG
                ? BigDecimal.valueOf(0.05 + r.nextDouble() * 0.35).setScale(3, RoundingMode.HALF_UP)
                : BigDecimal.ONE;
        if (ex.compareTo(q.multiply(BigDecimal.valueOf(3))) < 0) {
            return;
        }
        String[] motivos = switch (p.categoria()) {
            case "Carnes frías" -> new String[]{"Merma por rebanado", "Producto caducado",
                    "Cambió de color, se retiró de la vitrina"};
            case "Cremas y lácteos" -> new String[]{"Se agrió la crema", "Producto caducado"};
            default -> new String[]{"Producto caducado", "Moho en la orilla del queso", "Merma por corte y rebanado",
                    "Se resecó en la vitrina"};
        };
        adm.almacen().registrarMerma(pc(p), s.sucursal, q, motivos[r.nextInt(motivos.length)], admin);
        avanzar(1);
    }

    private void mermaAlmacen() {
        Sucursal almacen = adm.sucursales().almacen().orElseThrow();
        List<Prod> candidatos = catalogoConfig.stream().filter(p -> p.sujetoMerma() && vigente(p)).toList();
        for (int i = 0; i < 1 + r.nextInt(2); i++) {
            Prod p = candidatos.get(r.nextInt(candidatos.size()));
            BigDecimal q = p.unidad() == Unidad.KG
                    ? BigDecimal.valueOf(0.5 + r.nextDouble() * 2.5).setScale(3, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(1 + r.nextInt(3));
            if (adm.almacen().existencia(idDe.get(p), almacenId).compareTo(q.multiply(BigDecimal.TEN)) < 0) {
                continue;
            }
            String[] motivos = {"Revisión semanal: producto caducado", "Empaque dañado al descargar",
                    "Moho detectado en la revisión de la cámara fría"};
            adm.almacen().registrarMerma(pc(p), almacen, q, motivos[r.nextInt(motivos.length)], encargadaAlmacen);
            avanzar(2);
        }
    }

    private void cargarExistencias(Suc s) {
        Map<String, BigDecimal> mapa = db.con(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT producto_id, existencia FROM v_existencias WHERE sucursal_id = ?")) {
                ps.setString(1, s.sucursal.id());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, BigDecimal> m = new HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString(1), BigDecimal.valueOf(rs.getDouble(2)).setScale(3, RoundingMode.HALF_UP));
                    }
                    return m;
                }
            }
        });
        s.existencia.clear();
        s.existencia.putAll(mapa);
    }

    // ---------------------------------------------------------------------
    // Turnos de caja
    // ---------------------------------------------------------------------

    private void simularTurno(Plan p, LocalDate dia, double factor) {
        Suc s = p.suc();
        Caja caja = p.caja();
        Usuario u = p.empleado();
        boolean ultimo = dia.equals(hoy);
        boolean horario = conHorario.contains(u.id());
        // Hoy: Miguel llega tarde y nadie le ha dado acceso; Daniela y Luis Ángel no hicieron su corte.
        boolean quedaBloqueado = ultimo && u.usuario().equals("mflores");
        boolean sinCorte = ultimo && (u.usuario().equals("dcruz") || u.usuario().equals("ltorres") && !caja.local());
        // Guadalupe salió antes el último domingo: su acceso queda pendiente hasta su siguiente turno.
        boolean saleAntesForzado = u.usuario().equals("gmorales") && dia.getDayOfWeek() == SUNDAY
                && dia.isBefore(hoy) && !dia.plusDays(7).isBefore(hoy);
        boolean llegaTarde = horario && (quedaBloqueado || r.nextDouble() < 0.03);
        boolean saleAntes = horario && !llegaTarde && (saleAntesForzado || r.nextDouble() < 0.012);

        Instant entrada = a(dia, p.inicio());
        Instant login = llegaTarde ? entrada.plus(Duration.ofMinutes(8 + r.nextInt(18)))
                : entrada.minus(Duration.ofMinutes(3 + r.nextInt(10)));
        autorizarPendientes(s, u, login.minus(Duration.ofMinutes(6)));
        if (!ir(login)) {
            return;
        }
        ResultadoLogin resultado = caja.auth().iniciarSesion(u.usuario(), PASSWORD.toCharArray(), ModoConexion.OFFLINE);
        if (resultado instanceof ResultadoLogin.Rechazado rechazo) {
            if (rechazo.bloqueoId() == null) {
                throw new IllegalStateException(u.usuario() + ": " + rechazo.mensaje());
            }
            if (quedaBloqueado) {
                return;
            }
            login = login.plus(Duration.ofMinutes(5 + r.nextInt(10)));
            if (!ir(login)) {
                return;
            }
            autorizar(s, u, rechazo.bloqueoId(), caja);
            login = login.plus(Duration.ofMinutes(1));
            if (!ir(login)) {
                return;
            }
            resultado = caja.auth().iniciarSesion(u.usuario(), PASSWORD.toCharArray(), ModoConexion.OFFLINE);
            if (!(resultado instanceof ResultadoLogin.Exitoso)) {
                throw new IllegalStateException(u.usuario() + " no pudo entrar con el permiso autorizado.");
            }
        }
        Sesion sesion = ((ResultadoLogin.Exitoso) resultado).sesion();

        Instant apertura = max(login.plus(Duration.ofMinutes(1 + r.nextInt(3))), entrada.plus(Duration.ofMinutes(r.nextInt(3))));
        Instant fin = a(dia, p.fin());
        Instant salida = saleAntes ? fin.minus(Duration.ofMinutes(25 + r.nextInt(45)))
                : fin.plus(Duration.ofMinutes(r.nextInt(4)));
        Instant cierre = saleAntes ? salida.minus(Duration.ofMinutes(3)) : fin.minus(Duration.ofMinutes(2 + r.nextInt(3)));
        if (!ir(apertura)) {
            return;
        }
        Turno turno;
        try {
            turno = caja.caja().abrir(sesion, r.nextBoolean() ? 50_000 : 100_000);
        } catch (IllegalStateException e) {
            return; // La caja se quedó abierta sin corte.
        }
        if (r.nextDouble() < 0.08) {
            avanzar(2);
            caja.caja().registrarMovimiento(turno, CajaService.TipoMovimiento.ENTRADA, (2 + r.nextInt(4)) * 10_000,
                    "Cambio en monedas para el fondo", u, null);
        }

        double planeado = Duration.between(entrada, fin).toMinutes();
        double real = Math.max(10, Duration.between(apertura, cierre).toMinutes());
        int ventas = poisson(s.ventasDia * p.participacion() * factor * real / planeado);
        List<Instant> tiempos = new ArrayList<>();
        long ventana = Math.max(1, Duration.between(apertura, cierre).toSeconds() - 7 * 60);
        for (int i = 0; i < ventas; i++) {
            tiempos.add(apertura.plusSeconds(3 * 60 + (long) (r.nextDouble() * ventana)));
        }
        tiempos.sort(null);
        Instant gasto = r.nextDouble() < 0.06 && !tiempos.isEmpty() ? tiempos.get(r.nextInt(tiempos.size())) : null;

        cargarExistencias(s);
        int hechas = 0;
        boolean enCurso = false;
        for (Instant t : tiempos) {
            if (!ir(t)) {
                enCurso = true;
                break;
            }
            Ticket ticket = vender(s, caja, sesion, turno);
            if (ticket == null) {
                continue;
            }
            hechas++;
            if (r.nextDouble() < 0.012 && ir(t.plus(Duration.ofMinutes(1 + r.nextInt(3))))) {
                caja.ventas().cancelar(ticket.ventaId(), turno, u, autorizadorDe(s, u), MOTIVOS_CANCELACION[
                        r.nextInt(MOTIVOS_CANCELACION.length)]);
                cargarExistencias(s);
            }
            if (hechas % 10 == 0) {
                retiroParcial(s, caja, turno, u);
            }
            if (t.equals(gasto)) {
                gasto(s, caja, turno, u);
            }
        }
        if (enCurso || cierre.isAfter(hasta)) {
            // La simulación termina a media jornada: la caja sigue abierta, salvo esta terminal.
            if (caja.local()) {
                ir(hasta);
                corte(s, caja, turno, u, true);
            }
            return;
        }
        if (!sinCorte) {
            ir(cierre);
            corte(s, caja, turno, u, false);
        }
        if (ir(salida)) {
            caja.auth().cerrarSesion(sesion, "CIERRE_USUARIO");
        }
    }

    private static final String[] MOTIVOS_CANCELACION = {
            "El cliente ya no quiso el producto", "Error en el peso capturado", "Cobro duplicado",
            "Se capturó el producto equivocado", "El cliente no traía suficiente dinero"};

    private Ticket vender(Suc s, Caja caja, Sesion sesion, Turno turno) {
        int lineas = elegir(new double[]{35, 30, 20, 10, 5}, r) + 1;
        List<Prod> lista = asignados(s).stream().filter(this::vigente).toList();
        double[] pesos = lista.stream().mapToDouble(p -> {
            BigDecimal ex = s.existencia.getOrDefault(idDe.get(p), BigDecimal.ZERO);
            return ex.signum() > 0 ? peso(s, p) : 0;
        }).toArray();
        if (Arrays.stream(pesos).sum() <= 0) {
            return null;
        }
        List<VentaService.Renglon> renglones = new ArrayList<>();
        Set<Prod> usados = new HashSet<>();
        for (int intento = 0; intento < lineas * 2 && renglones.size() < lineas; intento++) {
            Prod p = lista.get(elegir(pesos, r));
            if (!usados.add(p)) {
                continue;
            }
            Pres pr = presentacion(p, r);
            Producto articulo = s.catalogo.porId(idPresentacion.get(p).get(pr)).orElse(null);
            if (articulo == null) {
                continue;
            }
            BigDecimal q = cantidad(p, pr, r);
            BigDecimal base = q.multiply(pr.factor()).setScale(3, RoundingMode.HALF_UP);
            BigDecimal ex = s.existencia.getOrDefault(idDe.get(p), BigDecimal.ZERO);
            if (ex.compareTo(base) < 0) {
                if (!pr.granel() || ex.compareTo(new BigDecimal("0.100")) < 0) {
                    continue;
                }
                q = ex.setScale(3, RoundingMode.DOWN); // Se lleva lo que quedaba.
                base = q;
            }
            renglones.add(new VentaService.Renglon(articulo, q));
            s.existencia.put(idDe.get(p), ex.subtract(base));
        }
        if (renglones.isEmpty()) {
            return null;
        }
        long total = cotizar(s, renglones);
        Ticket ticket = caja.ventas().registrar(renglones, pagos(total), sesion, turno);
        if (ticket.totalCentavos() != total) {
            throw new IllegalStateException("El total calculado no coincide con el de la venta " + ticket.folio());
        }
        return ticket;
    }

    /** Mismo cálculo que la venta: cada renglón toma de los lotes más antiguos a su precio. */
    private long cotizar(Suc s, List<VentaService.Renglon> renglones) {
        return db.con(c -> {
            Map<String, List<LoteRepository.Lote>> porProducto = new HashMap<>();
            Map<String, BigDecimal> consumido = new HashMap<>();
            long suma = 0;
            for (VentaService.Renglon ren : renglones) {
                Producto p = ren.producto();
                List<LoteRepository.Lote> lista = porProducto.get(p.productoId());
                if (lista == null) {
                    lista = lotes.deProducto(c, p.productoId(), s.sucursal.id());
                    porProducto.put(p.productoId(), lista);
                }
                PreciosLote.Tramos tramos = PreciosLote.de(lista, s.precios, p.id());
                suma += Tarifa.cotizar(tramos.tramos(), tramos.extra(), p.factor(), consumido,
                        p.unidad().normalizar(ren.cantidad())).totalCentavos();
            }
            return suma;
        });
    }

    private List<Pago> pagos(long total) {
        double u = r.nextDouble();
        if (u < 0.70) {
            return List.of(new Pago(MetodoPago.EFECTIVO, efectivoRecibido(total), null));
        }
        if (u < 0.88) {
            return List.of(new Pago(MetodoPago.TARJETA, total,
                    r.nextDouble() < 0.7 ? "%06d".formatted(r.nextInt(1_000_000)) : null));
        }
        if (u < 0.97 || total < 4000) {
            return List.of(new Pago(MetodoPago.TRANSFERENCIA, total,
                    r.nextDouble() < 0.8 ? "SPEI " + (1_000_000 + r.nextInt(9_000_000)) : null));
        }
        long tarjeta = total / 2 / 1000 * 1000;
        long resto = total - tarjeta;
        return List.of(new Pago(MetodoPago.EFECTIVO, (resto + 999) / 1000 * 1000, null),
                new Pago(MetodoPago.TARJETA, tarjeta, null));
    }

    /** Lo que entrega el cliente: justo, o el billete que le alcanza. */
    private long efectivoRecibido(long total) {
        long[] denominaciones = {50, 1000, 5000, 10_000, 20_000, 50_000};
        int i = elegir(new double[]{25, 15, 20, 20, 10, 10}, r);
        long den = denominaciones[i];
        return (total + den - 1) / den * den;
    }

    private void retiroParcial(Suc s, Caja caja, Turno turno, Usuario u) {
        long efectivo = caja.caja().resumen(turno).efectivoEsperado();
        if (efectivo > 300_000) {
            long monto = (efectivo - 100_000) / 50_000 * 50_000;
            avanzar(1);
            caja.caja().registrarMovimiento(turno, CajaService.TipoMovimiento.RETIRO, monto,
                    "Retiro parcial a caja fuerte", u, autorizadorDe(s, u));
        }
    }

    private void gasto(Suc s, Caja caja, Turno turno, Usuario u) {
        Object[][] gastos = {{"Pago de garrafones de agua", 9_000L}, {"Compra de bolsas para despacho", 15_000L},
                {"Pago de hielo", 6_000L}, {"Artículos de limpieza", 12_000L}, {"Propina al repartidor", 3_000L}};
        Object[] g = gastos[r.nextInt(gastos.length)];
        long monto = (Long) g[1];
        if (caja.caja().resumen(turno).efectivoEsperado() >= monto) {
            avanzar(1);
            caja.caja().registrarMovimiento(turno, CajaService.TipoMovimiento.RETIRO, monto, (String) g[0], u,
                    autorizadorDe(s, u));
        }
    }

    private void corte(Suc s, Caja caja, Turno turno, Usuario u, boolean alCierreDeLaSimulacion) {
        ResumenTurno resumen = caja.caja().resumen(turno);
        long esperado = resumen.efectivoEsperado();
        double x = r.nextDouble();
        long diferencia = alCierreDeLaSimulacion || x < 0.78 ? 0
                : x < 0.9 ? -(1 + r.nextInt(20)) * 250L : (1 + r.nextInt(8)) * 250L;
        long contado = Math.max(0, esperado + diferencia);
        diferencia = contado - esperado;
        boolean porSupervisor = !alCierreDeLaSimulacion && r.nextDouble() < 0.1 && s.supervisor != null;
        String notas = diferencia < 0 ? "Faltante; el cajero lo repone en su siguiente turno"
                : diferencia > 0 ? "Sobrante; se deja en el sobre del fondo"
                : porSupervisor ? "Corte hecho por el supervisor" : r.nextDouble() < 0.2 ? "Sin novedad" : null;
        caja.caja().cerrar(turno, contado, notas, porSupervisor ? s.supervisor : u);
    }

    private Usuario autorizadorDe(Suc s, Usuario u) {
        return s.supervisor == null || s.supervisor.id().equals(u.id()) ? admin : s.supervisor;
    }

    /** Permisos pendientes de días anteriores (p. ej. salió antes): se los da su supervisor antes de entrar. */
    private void autorizarPendientes(Suc s, Usuario u, Instant cuando) {
        for (AccesoService.Bloqueo b : cajaLocal.auth().accesos().pendientes()) {
            if (b.usuarioId().equals(u.id()) && ir(max(cuando, b.fecha().plus(Duration.ofMinutes(1))))) {
                autorizar(s, u, b.id(), s.cajas.getFirst());
            }
        }
    }

    private void autorizar(Suc s, Usuario u, String bloqueoId, Caja donde) {
        Usuario quien = autorizadorDe(s, u);
        (quien == admin ? cajaLocal : donde).auth().accesos().autorizar(bloqueoId, quien);
    }

    // ---------------------------------------------------------------------
    // Cierre
    // ---------------------------------------------------------------------

    /** Una venta en espera en esta terminal, para probar recuperarla. */
    private void ventaEnEspera() {
        Suc centro = suc("CENTRO");
        centro.catalogo.recargar();
        List<LineaVenta> lineas = new ArrayList<>();
        centro.catalogo.porId(idPresentacion.get(prod("Queso Oaxaca")).get(prod("Queso Oaxaca").presentaciones().getFirst()))
                .ifPresent(p -> lineas.add(new LineaVenta(p, new BigDecimal("0.750"))));
        centro.catalogo.porId(idPresentacion.get(prod("Leche entera 1 L")).get(prod("Leche entera 1 L").presentaciones().getFirst()))
                .ifPresent(p -> lineas.add(new LineaVenta(p, new BigDecimal("2"))));
        if (!lineas.isEmpty()) {
            ir(hasta);
            new VentaEsperaService(db).ponerEnEspera(usuarios.get("ltorres").id(), "El cliente fue por más efectivo",
                    lineas);
        }
    }

    private void asegurarCajaLocalLibre() {
        if (cajaLocal.caja().turnoAbierto().isPresent()) {
            throw new IllegalStateException("La caja de esta terminal quedó abierta.");
        }
    }

    /** Las contraseñas se generaron con costo bajo para simular rápido; quedan con el costo normal. */
    private void endurecerContrasenas() {
        for (Usuario u : usuarios.values()) {
            String hash = hasherFinal.hash(PASSWORD.toCharArray());
            db.con(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE usuarios SET password_hash = ? WHERE id = ?")) {
                    ps.setString(1, hash);
                    ps.setString(2, u.id());
                    return ps.executeUpdate();
                }
            });
        }
    }

    private Resumen resumen() {
        return db.con(c -> new Resumen(
                contar(c, "SELECT COUNT(*) FROM sucursales WHERE es_almacen = 0 AND eliminado_en IS NULL"),
                contar(c, "SELECT COUNT(*) FROM usuarios WHERE eliminado_en IS NULL"),
                contar(c, "SELECT COUNT(*) FROM productos WHERE eliminado_en IS NULL"),
                contar(c, "SELECT COUNT(*) FROM ventas"),
                contar(c, "SELECT COUNT(*) FROM ventas WHERE estado = 'CANCELADA'"),
                contar(c, "SELECT COUNT(*) FROM turnos_caja"),
                contar(c, "SELECT COUNT(*) FROM turnos_caja WHERE estado = 'ABIERTO'"),
                contar(c, "SELECT COUNT(*) FROM surtidos"),
                contar(c, "SELECT COUNT(*) FROM ordenes_reabastecimiento"),
                contar(c, "SELECT COUNT(*) FROM ordenes_reabastecimiento WHERE estado = 'PENDIENTE'"),
                contar(c, "SELECT COUNT(*) FROM mermas"),
                contar(c, "SELECT COUNT(*) FROM bloqueos_acceso WHERE estado = 'PENDIENTE'"),
                contar(c, "SELECT COUNT(*) FROM bitacora")));
    }

    // ---------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------

    private Suc suc(String codigo) {
        return sucursales.stream().filter(s -> s.codigo.equals(codigo)).findFirst().orElseThrow();
    }

    private Pres presentacion(Prod p, Random rnd) {
        double[] pesos = p.presentaciones().stream().mapToDouble(Pres::participacion).toArray();
        return p.presentaciones().get(elegir(pesos, rnd));
    }

    /** Cantidad que pide el cliente: pesadas típicas con la variación de la báscula, o piezas. */
    private static BigDecimal cantidad(Prod p, Pres pr, Random rnd) {
        if (!pr.granel()) {
            int k = 1 + (int) Math.floor(Math.pow(rnd.nextDouble(), 2.2) * pr.maxPiezas());
            return BigDecimal.valueOf(Math.min(k, pr.maxPiezas()));
        }
        double[][] opciones = switch (p.pesada()) {
            case QUESO -> new double[][]{{0.25, 25}, {0.5, 30}, {0.75, 10}, {1.0, 25}, {1.5, 5}, {2.0, 5}};
            case CARNE -> new double[][]{{0.2, 15}, {0.25, 30}, {0.5, 35}, {0.75, 10}, {1.0, 10}};
            case FINO -> new double[][]{{0.1, 30}, {0.15, 25}, {0.2, 25}, {0.25, 15}, {0.3, 5}};
            case CREMA, PIEZA -> new double[][]{{0.25, 25}, {0.5, 40}, {1.0, 30}, {1.5, 5}};
        };
        double[] pesos = new double[opciones.length];
        for (int i = 0; i < opciones.length; i++) {
            pesos[i] = opciones[i][1];
        }
        double kilos = opciones[elegir(pesos, rnd)][0];
        kilos *= 1 + (rnd.nextDouble() - 0.5) * 0.12;
        return BigDecimal.valueOf(Math.max(0.05, kilos)).setScale(3, RoundingMode.HALF_UP);
    }

    private static int elegir(double[] pesos, Random rnd) {
        double total = 0;
        for (double p : pesos) {
            total += p;
        }
        double x = rnd.nextDouble() * total;
        for (int i = 0; i < pesos.length; i++) {
            x -= pesos[i];
            if (x < 0) {
                return i;
            }
        }
        for (int i = pesos.length - 1; i >= 0; i--) {
            if (pesos[i] > 0) {
                return i;
            }
        }
        return pesos.length - 1;
    }

    private int poisson(double lambda) {
        double limite = Math.exp(-lambda);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > limite);
        return k - 1;
    }

    private static Instant a(LocalDate dia, LocalTime hora) {
        return dia.atTime(hora).atZone(ZONA).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** Mueve el reloj; devuelve false (sin moverlo) si el momento todavía no llega. */
    private boolean ir(Instant instante) {
        if (instante.isAfter(hasta)) {
            return false;
        }
        reloj.ir(instante);
        return true;
    }

    private boolean ir(LocalDate dia, int hora, int minuto) {
        return ir(a(dia, LocalTime.of(hora, minuto)));
    }

    /** Avanza unos minutos (entre capturas del mismo momento), sin pasar del final. */
    private void avanzar(int minutos) {
        Instant siguiente = reloj.instant().plus(Duration.ofMinutes(minutos));
        reloj.ir(siguiente.isAfter(hasta) ? hasta : siguiente);
    }

    private static int contar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String texto(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Reloj que avanza a mano: todo lo que se registre queda con la fecha simulada. */
    private static final class RelojSimulado extends Clock {
        private Instant ahora;

        RelojSimulado(Instant inicio) {
            this.ahora = inicio;
        }

        void ir(Instant instante) {
            ahora = instante;
        }

        LocalDate dia() {
            return ahora.atZone(ZONA).toLocalDate();
        }

        @Override
        public ZoneId getZone() {
            return ZONA;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
