package com.cremerias.puntoventa.db;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Acceso a la base de datos local SQLite.
 *
 * <p>Configurada para no perder datos aunque se vaya la luz o el internet:
 * modo WAL, {@code synchronous=FULL} y llaves foráneas activas.
 */
public class Database {

    private final Path archivo;
    private final SQLiteConfig config;
    private final SQLiteConfig configInmediata;

    public Database(Path archivo) {
        this.archivo = archivo;
        this.config = configuracion();
        this.configInmediata = configuracion();
        configInmediata.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    }

    private static SQLiteConfig configuracion() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(5_000);
        return config;
    }

    public Path archivo() {
        return archivo;
    }

    public Connection abrir() throws SQLException {
        return config.createConnection("jdbc:sqlite:" + archivo.toAbsolutePath());
    }

    /** Ejecuta una operación de solo lectura (o de una sola sentencia). */
    public <T> T con(SqlFunction<T> operacion) {
        try (Connection conexion = abrir()) {
            return operacion.aplicar(conexion);
        } catch (SQLException e) {
            throw new DatabaseException("Error en la base de datos local", e);
        }
    }

    /** Ejecuta varias sentencias de forma atómica: o se guardan todas o ninguna. */
    public <T> T enTransaccion(SqlFunction<T> operacion) {
        return transaccion(config, operacion);
    }

    /**
     * Como {@link #enTransaccion}, pero toma el bloqueo de escritura desde el inicio: nadie más
     * escribe mientras dura (lo usa la sincronización para saber qué filas generó ella misma).
     */
    public <T> T enTransaccionInmediata(SqlFunction<T> operacion) {
        return transaccion(configInmediata, operacion);
    }

    private <T> T transaccion(SQLiteConfig configuracion, SqlFunction<T> operacion) {
        try (Connection conexion = configuracion.createConnection("jdbc:sqlite:" + archivo.toAbsolutePath())) {
            conexion.setAutoCommit(false);
            try {
                T resultado = operacion.aplicar(conexion);
                conexion.commit();
                return resultado;
            } catch (SQLException | RuntimeException e) {
                conexion.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Error en la base de datos local", e);
        }
    }
}
