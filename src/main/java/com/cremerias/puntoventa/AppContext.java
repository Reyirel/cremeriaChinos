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
import com.cremerias.puntoventa.sync.Sincronizador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private AppContext(Database database, AuthService auth, PreferenciasService preferencias,
                       Sincronizador sincronizador, CatalogoService catalogo, CajaService caja,
                       Administracion administracion) {
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
        AppPaths.crearDirectorios();
        Database database = new Database(AppPaths.baseDeDatos());
        log.info("Base de datos local: {}", database.archivo());

        Respaldos respaldos = new Respaldos(database, AppPaths.respaldos());
        if (Files.exists(database.archivo())) {
            respaldos.respaldarSiCorresponde();
        }
        new Migraciones(database).aplicar();

        PasswordHasher hasher = new PasswordHasher();
        new DatosIniciales(database, hasher).sembrarSiVacia();

        String sucursalId = database.con(AppContext::sucursalPrincipal).orElse(null);
        // Esta terminal es una caja de sucursal (no el almacén).
        new DatosDemo(database).cargarSiVacio(sucursalId);

        String dispositivoId = database.enTransaccion(c -> {
            String sucursal = sucursalPrincipal(c).orElse(null);
            String id = new DispositivoRepository().obtenerOCrear(c, sucursal);
            int huerfanas = new SesionRepository().cerrarHuerfanas(c, id);
            if (huerfanas > 0) {
                log.warn("Se cerraron {} sesiones que quedaron abiertas (cierre inesperado)", huerfanas);
            }
            return id;
        });

        AuthService auth = new AuthService(database, hasher, Clock.systemDefaultZone(), dispositivoId);
        Sincronizador sincronizador = new Sincronizador(database);
        sincronizador.iniciar();
        CatalogoService catalogo = new CatalogoService(database, sucursalId);
        catalogo.recargar();
        Clock reloj = Clock.systemDefaultZone();
        return new AppContext(database, auth, new PreferenciasService(database), sincronizador, catalogo,
                new CajaService(database, dispositivoId), new Administracion(database, hasher, reloj, dispositivoId));
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
