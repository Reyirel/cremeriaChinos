package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.SyncOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sincroniza una base de la caja contra el Supabase de verdad (no corre con las demás pruebas).
 * Usar con una COPIA de la base:
 *
 * <pre>
 * ./mvnw test -Dtest=SincronizacionNubeTest -Dnube.bd=/ruta/copia.db -Dnube.config=~/.cremerias-pos/supabase.properties
 * </pre>
 *
 * {@code cajaNuevaTomaLosDatosDeLaNube} solo baja (en una base temporal) y no registra nada en la nube.
 */
class SincronizacionNubeTest {

    @TempDir
    Path carpeta;

    @Test
    @EnabledIfSystemProperty(named = "nube.config", matches = ".+")
    void cajaNuevaTomaLosDatosDeLaNube() throws Exception {
        Database database = new Database(carpeta.resolve("nueva.db"));
        new Migraciones(database).aplicar();
        ClienteSupabase nube = new ClienteSupabase(ConfigNube.cargar(Path.of(System.getProperty("nube.config")))
                .orElseThrow(() -> new IllegalStateException("Falta o está incompleto nube.config")));

        assertTrue(CajaNueva.prepararDesdeNube(database, nube));

        String resumen = database.con(c -> {
            try (var rs = c.createStatement().executeQuery("""
                    SELECT (SELECT group_concat(nombre) FROM sucursales WHERE eliminado_en IS NULL),
                           (SELECT group_concat(usuario || ':' || rol || ':' || (password_hash LIKE '$2a$%')) FROM usuarios),
                           (SELECT COUNT(*) FROM dispositivo), (SELECT COUNT(*) FROM sync_outbox)""")) {
                rs.next();
                return "sucursales=" + rs.getString(1) + " usuarios=" + rs.getString(2)
                        + " cajas=" + rs.getInt(3) + " por subir=" + rs.getInt(4);
            }
        });
        System.out.println("Caja nueva: " + resumen);
    }

    @Test
    @EnabledIfSystemProperty(named = "nube.bd", matches = ".+")
    void sincronizarConLaNube() throws Exception {
        Database database = new Database(Path.of(System.getProperty("nube.bd")));
        new Migraciones(database).aplicar();
        ClienteSupabase nube = new ClienteSupabase(ConfigNube.cargar(Path.of(System.getProperty("nube.config")))
                .orElseThrow(() -> new IllegalStateException("Falta o está incompleto nube.config")));
        SyncOutboxRepository outbox = new SyncOutboxRepository();
        String caja = database.con(c -> new DispositivoRepository().obtenerOCrear(c, null));
        database.con(outbox::reiniciarEnvios);

        new RegistroCaja(database, caja).asegurar(nube, nube.config().correo());
        long inicio = System.nanoTime();
        int subidos = new Subida(database).subir(nube);
        long subida = System.nanoTime();
        int bajados = new Bajada(database).bajar(nube);
        long fin = System.nanoTime();
        int segundaVuelta = new Bajada(database).bajar(nube);

        System.out.printf("Caja %s: %d subidos en %.1f s, %d bajados en %.1f s, segunda vuelta %d; pendientes %d, rechazados %d%n",
                caja, subidos, (subida - inicio) / 1e9, bajados, (fin - subida) / 1e9, segundaVuelta,
                database.con(outbox::contarPendientes), database.con(outbox::contarRechazados));
        assertEquals(0, (int) database.con(outbox::contarPendientes));
    }
}
