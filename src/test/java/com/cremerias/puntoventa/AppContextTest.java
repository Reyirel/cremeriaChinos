package com.cremerias.puntoventa;

import com.cremerias.puntoventa.config.AppPaths;
import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.repository.ConfigLocalRepository;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.DatosDemo;
import com.cremerias.puntoventa.service.DatosIniciales;
import com.cremerias.puntoventa.sync.ConfigNube;
import com.cremerias.puntoventa.sync.EstadoNube;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cómo arranca una caja según su conexión con la nube (sin red: Supabase no se alcanza). */
class AppContextTest {

    private static final String HOME = "cremerias.home";

    @TempDir
    Path carpeta;

    private String homeAnterior;

    @BeforeEach
    void usarCarpetaTemporal() {
        homeAnterior = System.getProperty(HOME);
        System.setProperty(HOME, carpeta.toString());
    }

    @AfterEach
    void restaurar() {
        if (homeAnterior == null) {
            System.clearProperty(HOME);
        } else {
            System.setProperty(HOME, homeAnterior);
        }
    }

    @Test
    void unaComputadoraNuevaNoCreaLosUsuariosDeEjemplo() {
        try (AppContext contexto = AppContext.iniciar()) {
            assertTrue(contexto.porConectarALaNube());
            assertEquals(0, contar(contexto.database(), "SELECT COUNT(*) FROM usuarios"));
            assertEquals(0, contar(contexto.database(), "SELECT COUNT(*) FROM productos"));
        }
    }

    @Test
    void unaCajaConCuentaHechaAManoQueNuncaRecibioNadaSeVuelveAConectar() throws Exception {
        // Como la computadora que se conectó con una cuenta creada en el panel: no veía la nube,
        // creó los datos de ejemplo y su registro falla porque su sucursal no existe en la nube.
        Database database = baseConDatosDeEjemplo();
        cuentaHechaAMano().guardar(AppPaths.configNube());
        int encolados = contar(database, "SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL");

        try (AppContext contexto = AppContext.iniciar()) {
            // No sincroniza con esa cuenta: se conecta cuando entre un administrador de la nube.
            assertTrue(contexto.porConectarALaNube());
            assertEquals(EstadoNube.SOLO_LOCAL, contexto.sincronizador().estadoProperty().get());
            // No se tocó nada todavía: los datos se cambian por los de la nube al conectarse.
            assertEquals(encolados, contar(contexto.database(), "SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL"));
        }
    }

    @Test
    void unaCajaQueYaRecibioDatosDeLaNubeSigueConectada() throws Exception {
        Database database = baseConDatosDeEjemplo();
        database.enTransaccion(c -> {
            new ConfigLocalRepository().guardar(c, "nube.bajada.usuarios", "2026-10-07T05:00:00+00:00|x");
            return null;
        });
        cuentaHechaAMano().guardar(AppPaths.configNube());

        try (AppContext contexto = AppContext.iniciar()) {
            assertFalse(contexto.porConectarALaNube());
            assertEquals(EstadoNube.SIN_CONEXION, contexto.sincronizador().estadoProperty().get());
        }
    }

    private static Database baseConDatosDeEjemplo() {
        AppPaths.crearDirectorios();
        Database database = new Database(AppPaths.baseDeDatos());
        new Migraciones(database).aplicar();
        String sucursal = new DatosIniciales(database, new PasswordHasher(4)).sembrarSiVacia();
        new DatosDemo(database).cargarSiVacio(sucursal);
        database.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, sucursal));
        return database;
    }

    private static ConfigNube cuentaHechaAMano() {
        return new ConfigNube(URI.create("http://127.0.0.1:1"), "sb_publishable_abc",
                "caja-dev@cremerias.local", "secreta");
    }

    private static int contar(Database database, String sql) {
        return database.con(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
                return rs.getInt(1);
            }
        });
    }
}
