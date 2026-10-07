package com.cremerias.puntoventa;

import com.cremerias.puntoventa.config.AppPaths;
import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.db.Respaldos;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.SesionRepository;
import com.cremerias.puntoventa.repository.SucursalRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.CajaService;
import com.cremerias.puntoventa.service.CatalogoService;
import com.cremerias.puntoventa.service.DatosDemo;
import com.cremerias.puntoventa.service.IndicadoresService;
import com.cremerias.puntoventa.service.InventarioSucursalService;
import com.cremerias.puntoventa.service.VentaEsperaService;
import com.cremerias.puntoventa.service.VentaService;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.service.DatosIniciales;
import com.cremerias.puntoventa.service.PreferenciasService;
import com.cremerias.puntoventa.service.SesionActual;
import com.cremerias.puntoventa.service.SinVentaService;
import com.cremerias.puntoventa.sync.CajaNueva;
import com.cremerias.puntoventa.sync.ClienteSupabase;
import com.cremerias.puntoventa.sync.ConexionCaja;
import com.cremerias.puntoventa.sync.ConfigNube;
import com.cremerias.puntoventa.sync.ErrorNube;
import com.cremerias.puntoventa.sync.ProyectoNube;
import com.cremerias.puntoventa.sync.Sincronizador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Optional;

/** Arma los servicios de la aplicación (inyección de dependencias manual). */
public final class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    private final Database database;
    private final AuthService auth;
    private final PreferenciasService preferencias;
    private final SesionActual sesionActual = new SesionActual();
    private final Sincronizador sincronizador;
    private final CatalogoService catalogo;
    private final CajaService caja;
    private final VentaService ventas;
    private final VentaEsperaService ventasEspera;
    private final InventarioSucursalService inventarioSucursal;
    private final SinVentaService sinVenta;
    private final IndicadoresService indicadores;
    private final Administracion administracion;
    /** Proyecto con el que esta computadora se puede conectar a la nube; nulo si ya está conectada. */
    private final ProyectoNube porConectar;

    private AppContext(Database database, AuthService auth, PreferenciasService preferencias,
                       Sincronizador sincronizador, CatalogoService catalogo, CajaService caja,
                       Administracion administracion, ProyectoNube porConectar) {
        this.porConectar = porConectar;
        this.database = database;
        this.auth = auth;
        this.preferencias = preferencias;
        this.sincronizador = sincronizador;
        this.catalogo = catalogo;
        this.caja = caja;
        this.ventas = new VentaService(database, caja.dispositivoId());
        this.ventasEspera = new VentaEsperaService(database);
        this.inventarioSucursal = new InventarioSucursalService(database);
        this.sinVenta = new SinVentaService(database, Clock.systemDefaultZone());
        this.indicadores = new IndicadoresService(database, caja.dispositivoId());
        this.administracion = administracion;
    }

    public static AppContext iniciar() {
        return iniciar(false);
    }

    /** @param reemplazarConLaNube la caja se acaba de conectar: lo que tenga se cambia por lo de la nube */
    private static AppContext iniciar(boolean reemplazarConLaNube) {
        AppPaths.crearDirectorios();
        Database database = new Database(AppPaths.baseDeDatos());
        log.info("Base de datos local: {}", database.archivo());

        Respaldos respaldos = new Respaldos(database, AppPaths.respaldos());
        if (Files.exists(database.archivo())) {
            respaldos.respaldarSiCorresponde();
        }
        new Migraciones(database).aplicar();

        ClienteSupabase nube = ConfigNube.cargar(AppPaths.configNube()).map(ClienteSupabase::new).orElse(null);
        ProyectoNube porConectar = null;
        if (nube == null) {
            porConectar = ProyectoNube.incluido().orElse(null);
            log.info(porConectar == null
                    ? "Sin conexión con la nube configurada ({}): la caja trabaja solo en local"
                    : "Caja sin conectar a la nube ({}): se conecta cuando entre un administrador", AppPaths.configNube());
        } else {
            log.info("Caja conectada a la nube: {}", nube.config());
            try {
                // Caja recién instalada (o recién conectada): toma los datos de la nube en vez de
                // crear los de ejemplo.
                if (reemplazarConLaNube) {
                    CajaNueva.reemplazarConLaNube(database, nube);
                } else {
                    CajaNueva.prepararDesdeNube(database, nube);
                }
            } catch (IOException | ErrorNube e) {
                throw new IllegalStateException("Esta caja está configurada para la nube, pero no pudo bajar los datos"
                        + " de Supabase (" + e.getMessage() + "). Revisa la conexión a internet y "
                        + AppPaths.configNube() + ".", e);
            }
            Optional<ProyectoNube> proyecto = ProyectoNube.incluido();
            if (proyecto.isPresent() && !CajaNueva.recibioDatosDeLaNube(database)) {
                // Con esta cuenta la caja nunca ha recibido nada (se creó a mano y no puede ver la
                // nube, o la caja tiene datos propios): así no sincronizaría nunca. Queda como una
                // caja sin conectar: al entrar un administrador de la nube se conecta con su propia
                // cuenta y sus datos se cambian por los de la nube.
                log.warn("La caja nunca ha recibido datos de la nube con la cuenta {}: se vuelve a conectar cuando"
                        + " entre un administrador", nube.config().correo());
                porConectar = proyecto.get();
                nube = null;
            }
        }

        PasswordHasher hasher = new PasswordHasher();
        // Los usuarios y el catálogo de ejemplo solo se crean en una caja sin nube: en una caja de la
        // nube se subirían (usuarios con contraseñas conocidas y productos que no existen). Una caja
        // que se puede conectar espera a que entre un administrador de la nube, que trae los de verdad.
        // Devuelve la sucursal creada solo si la base es nueva (no tenía usuarios).
        String sucursalNueva = nube == null && porConectar == null
                ? new DatosIniciales(database, hasher).sembrarSiVacia()
                : null;

        // El catálogo de ejemplo se carga solo en una base nueva: si se limpió y quedó sin
        // productos a propósito, no debe volver a aparecer al crear la primera sucursal.
        if (sucursalNueva != null) {
            new DatosDemo(database).cargarSiVacio(sucursalNueva);
        }
        String dispositivoId = database.enTransaccion(c -> {
            // Esta terminal es una caja de sucursal (no el almacén).
            String sucursal = sucursalPrincipal(c).orElse(null);
            String id = new DispositivoRepository().obtenerOCrear(c, sucursal);
            int huerfanas = new SesionRepository().cerrarHuerfanas(c, id);
            if (huerfanas > 0) {
                log.warn("Se cerraron {} sesiones que quedaron abiertas (cierre inesperado)", huerfanas);
            }
            return id;
        });

        AuthService auth = new AuthService(database, hasher, Clock.systemDefaultZone(), dispositivoId);
        Sincronizador sincronizador = new Sincronizador(database, dispositivoId, nube, porConectar != null);
        sincronizador.iniciar();
        // Sin sucursal todavía: la caja carga el catálogo de la sucursal de quien entra.
        CatalogoService catalogo = new CatalogoService(database, null);
        Clock reloj = Clock.systemDefaultZone();
        Administracion administracion = new Administracion(database, hasher, reloj, dispositivoId);
        try {
            administracion.productos().revisarTemporadas();
        } catch (RuntimeException e) {
            // Una revisión fallida no debe impedir que la app arranque.
            log.warn("No se pudieron revisar las temporadas de productos", e);
        }
        return new AppContext(database, auth, new PreferenciasService(database), sincronizador, catalogo,
                new CajaService(database, dispositivoId), administracion, porConectar);
    }

    /** Esta computadora todavía no está conectada a la nube, pero se puede conectar sola. */
    public boolean porConectarALaNube() {
        return porConectar != null;
    }

    /**
     * Conecta esta computadora a la nube con un administrador: Supabase crea y registra la cuenta de
     * la caja, se guarda {@code supabase.properties} y los datos locales se cambian por los de la
     * nube (los que hubiera quedan antes en una copia en {@link AppPaths#reserva()}).
     *
     * <p>Si el usuario o la contraseña no sirven, no cambia nada. Si funciona, cierra este contexto
     * y devuelve el nuevo.
     */
    public AppContext conectarALaNube(String usuario, char[] contrasena) throws IOException, ErrorNube {
        if (porConectar == null) {
            throw new IllegalStateException("Esta caja ya está conectada a la nube o no trae el proyecto de Supabase");
        }
        String cajaId = caja.dispositivoId();
        String nombre = database.con(c -> {
            try (var ps = c.prepareStatement("SELECT nombre FROM dispositivo WHERE id = ?")) {
                ps.setString(1, cajaId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : "Caja";
                }
            }
        });
        ConfigNube config = ConexionCaja.conectar(porConectar, usuario, contrasena, cajaId, nombre);
        CajaNueva.guardarCopiaSiTieneDatos(database, AppPaths.reserva());
        close();
        config.guardar(AppPaths.configNube());
        log.info("Caja {} conectada a la nube con la cuenta {}", nombre, config.correo());
        return iniciar(true);
    }

    private static Optional<String> sucursalPrincipal(java.sql.Connection c) throws java.sql.SQLException {
        try (var ps = c.prepareStatement("SELECT id FROM sucursales WHERE eliminado_en IS NULL AND es_almacen = 0 ORDER BY creado_en LIMIT 1");
             var rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        }
    }

    public Database database() {
        return database;
    }

    public AuthService auth() {
        return auth;
    }

    public PreferenciasService preferencias() {
        return preferencias;
    }

    public SesionActual sesionActual() {
        return sesionActual;
    }

    public Sincronizador sincronizador() {
        return sincronizador;
    }

    public CatalogoService catalogo() {
        return catalogo;
    }

    public CajaService caja() {
        return caja;
    }

    public VentaService ventas() {
        return ventas;
    }

    public VentaEsperaService ventasEspera() {
        return ventasEspera;
    }

    public InventarioSucursalService inventarioSucursal() {
        return inventarioSucursal;
    }

    /** Productos que llevan su plazo sin venderse (avisos para ofertarlos). */
    public SinVentaService sinVenta() {
        return sinVenta;
    }

    /** Venta, costo, utilidad y avance de meta por sucursal (módulo Indicadores). */
    public IndicadoresService indicadores() {
        return indicadores;
    }

    public Administracion admin() {
        return administracion;
    }

    public String nombreSucursal(String sucursalId) {
        return database.con(c -> new SucursalRepository().nombre(c, sucursalId)).orElse("Sin sucursal");
    }

    @Override
    public void close() {
        sesionActual.obtener().ifPresent(s -> auth.cerrarSesion(s, "CIERRE_APLICACION"));
        sincronizador.close();
    }
}
