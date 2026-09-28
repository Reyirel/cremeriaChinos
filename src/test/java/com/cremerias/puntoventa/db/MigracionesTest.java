package com.cremerias.puntoventa.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigracionesTest {

    @TempDir
    Path carpeta;

    @Test
    void seAplicanUnaSolaVez() {
        Database db = new Database(carpeta.resolve("m.db"));
        new Migraciones(db).aplicar();
        assertDoesNotThrow(() -> new Migraciones(db).aplicar());
        int aplicadas = db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM schema_migraciones");
            rs.next();
            return rs.getInt(1);
        });
        assertEquals(Migraciones.MIGRACIONES.size(), aplicadas);
    }

    @Test
    void laBaseQuedaEnModoWal() {
        Database db = new Database(carpeta.resolve("w.db"));
        new Migraciones(db).aplicar();
        String modo = db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery("PRAGMA journal_mode");
            rs.next();
            return rs.getString(1);
        });
        assertEquals("wal", modo);
    }

    @Test
    void divideSentenciasRespetandoTriggers() {
        List<String> sentencias = Migraciones.dividirSentencias("""
                -- comentario
                CREATE TABLE a (x TEXT);
                CREATE TRIGGER t AFTER INSERT ON a
                BEGIN
                    INSERT INTO a VALUES ('1');
                    INSERT INTO a VALUES ('2');
                END;
                CREATE INDEX i ON a (x);
                """);
        assertEquals(3, sentencias.size());
        assertTrue(sentencias.get(1).endsWith("END;"));
    }
}
