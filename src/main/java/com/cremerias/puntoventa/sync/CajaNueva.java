package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Primera vez que se abre una caja configurada para la nube: en lugar de crear los datos de
 * ejemplo, toma todo de Supabase (sucursales, usuarios, catálogo, existencias…).
 */
public final class CajaNueva {

    private static final Logger log = LoggerFactory.getLogger(CajaNueva.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private CajaNueva() {
    }

    /**
     * Si en esta caja nunca ha entrado nadie (no hay usuarios), la llena con lo que hay en la nube.
     *
     * @return {@code true} si la caja quedó con los datos de la nube; {@code false} si ya tenía
     *         datos propios o la nube está vacía.
     */
    public static boolean prepararDesdeNube(Database database, Nube nube) throws IOException, ErrorNube {
        if (database.con(c -> contarUsuarios(c) > 0)) {
            return false;
        }
        // Nadie ha entrado nunca: lo que hay lo creó la instalación (el almacén de la migración V5,
        // con un id propio) o un intento anterior que no terminó. Lo bueno es lo de la nube.
        return tomarDeLaNube(database, nube);
    }

    /**
     * Cambia lo que tiene esta caja por lo de la nube aunque ya tenga datos propios: se usa al
     * conectar una computadora que trabajaba sin nube (antes se guarda una copia con
     * {@link #guardarCopiaSiTieneDatos}).
     */
    public static boolean reemplazarConLaNube(Database database, Nube nube) throws IOException, ErrorNube {
        return tomarDeLaNube(database, nube);
    }

    /**
     * Si esta caja ya recibió algo de la nube (la bajada guarda hasta dónde llegó en cada tabla).
     * Una caja con {@code supabase.properties} que nunca recibió nada no está conectada de verdad: su
     * cuenta no puede ver la nube (p. ej. se creó a mano) o sus datos son propios.
     */
    public static boolean recibioDatosDeLaNube(Database database) {
        return database.con(c -> {
            try (var ps = c.prepareStatement("SELECT 1 FROM config_local WHERE clave LIKE ? LIMIT 1")) {
                ps.setString(1, Bajada.PREFIJO_CURSOR + "%");
                try (var rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /**
     * Copia la base local en {@code carpeta} si alguien ya la usó (hay usuarios), para no perder
     * lo que tenía antes de cambiarla por lo de la nube.
     *
     * @return la copia, o vacío si la base no tenía datos propios
     */
    public static Optional<Path> guardarCopiaSiTieneDatos(Database database, Path carpeta) throws IOException {
        if (!database.con(c -> contarUsuarios(c) > 0)) {
            return Optional.empty();
        }
        Files.createDirectories(carpeta);
        Path copia = carpeta.resolve("cremerias_antes_de_conectar_" + FECHA.format(LocalDateTime.now()) + ".db");
        database.con(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + copia.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        log.info("Copia de la base local antes de tomar los datos de la nube: {}", copia);
        return Optional.of(copia);
    }

    private static boolean tomarDeLaNube(Database database, Nube nube) throws IOException, ErrorNube {
        database.enTransaccion(c -> {
            List<String> tablas = new ArrayList<>(List.of("ventas_espera_detalle", "ventas_espera"));
            tablas.addAll(TablasNube.ORDEN.reversed().stream().filter(TablasNube::seSincroniza).toList());
            try (Statement st = c.createStatement()) {
                // Esta terminal (la primera caja de la base) se queda con su id: puede que ya esté
                // registrada en la nube con él. Las demás cajas llegan de la nube.
                st.executeUpdate("UPDATE dispositivo SET sucursal_id = NULL");
                for (String tabla : tablas) {
                    st.executeUpdate("dispositivo".equals(tabla)
                            ? "DELETE FROM dispositivo WHERE rowid <> (SELECT MIN(rowid) FROM dispositivo)"
                            : "DELETE FROM " + tabla);
                }
                st.executeUpdate("DELETE FROM sync_outbox");
                st.executeUpdate("DELETE FROM config_local WHERE clave LIKE '" + Bajada.PREFIJO_CURSOR + "%'");
            }
            // Si la base no tenía caja, esta terminal se da de alta antes que las que bajen.
            new DispositivoRepository().obtenerOCrear(c, null);
            return null;
        });

        int filas = new Bajada(database).bajar(nube);
        int usuarios = database.enTransaccion(c -> {
            try (var ps = c.prepareStatement("""
                    UPDATE dispositivo
                    SET sucursal_id = (SELECT id FROM sucursales
                                       WHERE eliminado_en IS NULL AND es_almacen = 0
                                       ORDER BY creado_en LIMIT 1)
                    WHERE rowid = (SELECT MIN(rowid) FROM dispositivo) AND sucursal_id IS NULL""")) {
                ps.executeUpdate();
            }
            return contarUsuarios(c);
        });
        log.info("Caja nueva: se bajaron {} registros de la nube ({} usuarios)", filas, usuarios);
        return usuarios > 0;
    }

    private static int contarUsuarios(Connection c) throws SQLException {
        try (var ps = c.prepareStatement("SELECT COUNT(*) FROM usuarios"); var rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
