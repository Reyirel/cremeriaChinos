package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Primera vez que se abre una caja configurada para la nube: en lugar de crear los datos de
 * ejemplo, toma todo de Supabase (sucursales, usuarios, catálogo, existencias…).
 */
public final class CajaNueva {

    private static final Logger log = LoggerFactory.getLogger(CajaNueva.class);

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
        database.enTransaccion(c -> {
            // Nadie ha entrado nunca: lo que hay lo creó la instalación (el almacén de la migración
            // V5, con un id propio) o un intento anterior que no terminó. Lo bueno es lo de la nube.
            List<String> tablas = new ArrayList<>(List.of("ventas_espera_detalle", "ventas_espera"));
            tablas.addAll(TablasNube.ORDEN.reversed().stream().filter(TablasNube::seSincroniza).toList());
            try (Statement st = c.createStatement()) {
                for (String tabla : tablas) {
                    st.executeUpdate("DELETE FROM " + tabla);
                }
                st.executeUpdate("DELETE FROM sync_outbox");
                st.executeUpdate("DELETE FROM config_local WHERE clave LIKE '" + Bajada.PREFIJO_CURSOR + "%'");
            }
            // Esta terminal es la primera caja de la base: se da de alta antes que las que bajen.
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
