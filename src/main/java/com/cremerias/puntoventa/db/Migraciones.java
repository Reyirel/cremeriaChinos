package com.cremerias.puntoventa.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aplica los cambios de esquema versionados que están en {@code /db/migration}.
 *
 * <p>Para cambiar la base de datos se agrega un archivo nuevo (ej. {@code V2__productos.sql})
 * y se registra en {@link #MIGRACIONES}. Nunca se modifica una migración ya aplicada:
 * se valida su checksum para detectarlo.
 */
public class Migraciones {

    private static final Logger log = LoggerFactory.getLogger(Migraciones.class);

    static final List<String> MIGRACIONES = List.of(
            "V1__esquema_inicial.sql",
            "V2__catalogo_y_caja.sql",
            "V3__cortes_supervisor.sql",
            "V4__nombre_cajas.sql",
            "V5__almacen_administracion.sql",
            "V6__gramaje_productos.sql",
            "V7__limites_y_alertas_por_sucursal.sql",
            "V8__codigo_inventario_productos.sql",
            "V9__aviso_sin_venta_por_producto.sql",
            "V10__metas_sucursal.sql",
            "V11__temporada_productos.sql",
            "V12__metas_kpi.sql",
            "V13__gastos_y_nomina.sql",
            "V14__marca_productos.sql",
            "V15__movimientos_credito_y_precios.sql",
            "V16__reglas_y_escalas_de_precio.sql",
            "V17__compras_y_proveedores.sql",
            "V18__ajustes_y_transformaciones.sql",
            "V19__devoluciones.sql",
            "V20__recepcion_y_cancelacion_de_surtidos.sql"
    );

    /**
     * Checksums de versiones anteriores de una migración que se corrigió sin cambiar su efecto en
     * las bases donde ya se aplicó (p. ej. un {@code INSERT} que pasó a {@code INSERT OR IGNORE}).
     * Las bases con el checksum viejo se dan por buenas en lugar de rechazarse.
     */
    private static final Map<Integer, Set<String>> CHECKSUMS_ANTERIORES = Map.of(
            12, Set.of("03ea8b80f32b8512c89540f45c11a0c7e3aad357ab30e3b4520e5d6bfeabf2ee"),
            17, Set.of("5520bcd09a079120b2941ca625f7e2bbc92727e642a4425c7399fa180b3be328"));

    private final Database database;

    public Migraciones(Database database) {
        this.database = database;
    }

    public void aplicar() {
        aplicarHasta(Integer.MAX_VALUE);
    }

    /** Aplica las migraciones hasta la versión indicada (inclusive). Visible para probar la migración de bases viejas. */
    void aplicarHasta(int versionMaxima) {
        database.con(conexion -> {
            crearTablaControl(conexion);
            for (String archivo : MIGRACIONES) {
                if (version(archivo) <= versionMaxima) {
                    aplicarSiFalta(conexion, archivo);
                }
            }
            return null;
        });
    }

    private void crearTablaControl(Connection conexion) throws SQLException {
        try (Statement st = conexion.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migraciones (
                        version    INTEGER PRIMARY KEY,
                        archivo    TEXT NOT NULL,
                        checksum   TEXT NOT NULL,
                        aplicada_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
                    )""");
        }
    }

    private void aplicarSiFalta(Connection conexion, String archivo) throws SQLException {
        int version = version(archivo);
        String sql = leer(archivo);
        String checksum = checksum(sql);

        try (PreparedStatement ps = conexion.prepareStatement(
                "SELECT checksum FROM schema_migraciones WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String aplicado = rs.getString(1);
                    if (!aplicado.equals(checksum)
                            && !CHECKSUMS_ANTERIORES.getOrDefault(version, Set.of()).contains(aplicado)) {
                        throw new IllegalStateException("La migración " + archivo
                                + " cambió después de aplicarse. Crea una migración nueva en lugar de editarla.");
                    }
                    return;
                }
            }
        }

        log.info("Aplicando migración {}", archivo);
        conexion.setAutoCommit(false);
        try (Statement st = conexion.createStatement()) {
            for (String sentencia : dividirSentencias(sql)) {
                st.execute(sentencia);
            }
            try (PreparedStatement ps = conexion.prepareStatement(
                    "INSERT INTO schema_migraciones (version, archivo, checksum) VALUES (?, ?, ?)")) {
                ps.setInt(1, version);
                ps.setString(2, archivo);
                ps.setString(3, checksum);
                ps.executeUpdate();
            }
            conexion.commit();
        } catch (SQLException | RuntimeException e) {
            conexion.rollback();
            throw e;
        } finally {
            conexion.setAutoCommit(true);
        }
    }

    static int version(String archivo) {
        return Integer.parseInt(archivo.substring(1, archivo.indexOf("__")));
    }

    /** Divide el script por ';' respetando los bloques BEGIN ... END de los triggers. */
    static List<String> dividirSentencias(String sql) {
        List<String> sentencias = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean enTrigger = false;

        for (String linea : sql.split("\\R")) {
            String limpia = linea.strip();
            if (limpia.isEmpty() || limpia.startsWith("--")) {
                continue;
            }
            if (actual.isEmpty() && limpia.toUpperCase().startsWith("CREATE TRIGGER")) {
                enTrigger = true;
            }
            actual.append(linea).append('\n');

            boolean fin = enTrigger ? limpia.equalsIgnoreCase("END;") : limpia.endsWith(";");
            if (fin) {
                sentencias.add(actual.toString().strip());
                actual.setLength(0);
                enTrigger = false;
            }
        }
        if (!actual.toString().isBlank()) {
            sentencias.add(actual.toString().strip());
        }
        return sentencias;
    }

    private static String leer(String archivo) {
        try (InputStream in = Migraciones.class.getResourceAsStream("/db/migration/" + archivo)) {
            if (in == null) {
                throw new IllegalStateException("No se encontró la migración " + archivo);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String checksum(String sql) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sql.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
