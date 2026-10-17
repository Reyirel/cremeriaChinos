package com.cremerias.puntoventa.db;

import com.cremerias.puntoventa.service.admin.RecepcionSurtidoService;
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

    /**
     * Una caja instalada ya tiene movimientos y crédito: al reconstruir movimientos_inventario (V15)
     * no se pierde ninguna fila, la existencia es la misma, el trigger de la cola sigue encolando y los
     * cargos que ya existían quedan como concepto SURTIDO.
     */
    @Test
    void unaBaseConDatosConservaMovimientosYCreditoAlMigrar() {
        Database db = new Database(carpeta.resolve("vieja.db"));
        Migraciones migraciones = new Migraciones(db);
        migraciones.aplicarHasta(14);
        db.con(c -> {
            var st = c.createStatement();
            String ahora = "'2026-10-01T10:00:00.000Z'";
            st.executeUpdate("INSERT INTO sucursales (id, codigo, nombre, creado_en, actualizado_en) VALUES "
                    + "('s1', 'NORTE', 'Norte', " + ahora + ", " + ahora + ")");
            st.executeUpdate("INSERT INTO usuarios (id, sucursal_id, nombre_completo, usuario, password_hash, rol, "
                    + "creado_en, actualizado_en) VALUES ('u1', 's1', 'Admin', 'admin', 'x', 'ADMINISTRADOR', "
                    + ahora + ", " + ahora + ")");
            st.executeUpdate("INSERT INTO productos (id, nombre, unidad, precio_centavos, creado_en, actualizado_en) "
                    + "VALUES ('p1', 'Queso', 'KG', 0, " + ahora + ", " + ahora + ")");
            st.executeUpdate("INSERT INTO lotes (id, producto_id, sucursal_id, origen, cantidad_inicial, "
                    + "costo_unitario_centavos, fecha_ingreso, creado_en) VALUES ('l1', 'p1', 's1', 'ENTRADA', 10, "
                    + "10000, " + ahora + ", " + ahora + ")");
            st.executeUpdate("INSERT INTO movimientos_inventario (id, producto_id, sucursal_id, lote_id, tipo, cantidad, "
                    + "fecha) VALUES ('m1', 'p1', 's1', 'l1', 'ENTRADA', 10, " + ahora + ")");
            st.executeUpdate("INSERT INTO movimientos_inventario (id, producto_id, sucursal_id, lote_id, tipo, cantidad, "
                    + "fecha) VALUES ('m2', 'p1', 's1', 'l1', 'VENTA', -2.5, " + ahora + ")");
            st.executeUpdate("INSERT INTO movimientos_credito (id, folio, sucursal_id, tipo, monto_centavos, usuario_id, "
                    + "fecha) VALUES ('c1', 'SUR-1', 's1', 'CARGO', 50000, 'u1', " + ahora + ")");
            st.executeUpdate("INSERT INTO movimientos_credito (id, folio, sucursal_id, tipo, monto_centavos, usuario_id, "
                    + "fecha) VALUES ('c2', 'ABN-1', 's1', 'ABONO', 20000, 'u1', " + ahora + ")");
            st.executeUpdate("INSERT INTO surtidos (id, folio, sucursal_id, forma_pago, total_costo_centavos, "
                    + "total_venta_centavos, usuario_id, fecha) VALUES ('t1', 'SUR-1', 's1', 'CREDITO', 50000, 60000, 'u1', "
                    + ahora + ")");
            return null;
        });

        migraciones.aplicar();

        assertEquals("2", consultar(db, "SELECT COUNT(*) FROM movimientos_inventario"));
        assertEquals("7.5", consultar(db, "SELECT existencia FROM v_existencias WHERE producto_id = 'p1'"));
        assertEquals("7.5", consultar(db, "SELECT existencia FROM v_existencias_lote WHERE lote_id = 'l1'"));
        assertEquals("SURTIDO", consultar(db, "SELECT concepto FROM movimientos_credito WHERE id = 'c1'"));
        assertEquals("PAGO", consultar(db, "SELECT concepto FROM movimientos_credito WHERE id = 'c2'"));
        // Un movimiento de un tipo nuevo se acepta y se encola para la nube.
        db.con(c -> c.createStatement().executeUpdate("INSERT INTO movimientos_inventario (id, producto_id, sucursal_id, "
                + "lote_id, tipo, cantidad, fecha) VALUES ('m3', 'p1', 's1', 'l1', 'DEVOLUCION_CLIENTE', 1, "
                + "'2026-10-02T10:00:00.000Z')"));
        assertEquals("1", consultar(db, "SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'movimientos_inventario'"
                + " AND registro_id = 'm3'"));
        assertEquals("BODEGA_SUCURSAL", consultar(db, "SELECT canal_precio FROM sucursales WHERE id = 's1'"));
        // Lo surtido con el proceso de antes ya entró a la sucursal: no queda «por recibir» ni se puede cancelar.
        assertEquals("0", consultar(db, "SELECT requiere_recepcion FROM surtidos WHERE id = 't1'"));
        RecepcionSurtidoService recepciones = new RecepcionSurtidoService(db, "d1", null);
        assertEquals(RecepcionSurtidoService.Estado.RECIBIDO, recepciones.estado("t1"));
    }

    private static String consultar(Database db, String sql) {
        return db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery(sql);
            rs.next();
            return rs.getString(1);
        });
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
