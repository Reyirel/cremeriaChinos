package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatosPruebaTest {

    @TempDir
    Path carpeta;

    @Test
    void generaOperacionCoherenteSobreUnaBaseLimpia() {
        Database db = new Database(carpeta.resolve("prueba.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        db.enTransaccion(c -> {
            String almacen;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM sucursales WHERE es_almacen = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                almacen = rs.getString(1);
            }
            new UsuarioRepository().insertar(c, new Usuario(Ids.nuevo(), almacen, "Administrador General", "admin",
                    hasher.hash("admin123".toCharArray()), Rol.ADMINISTRADOR, true, false, 0, null, null));
            return null;
        });
        String terminal = db.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, null));

        Instant ahora = Instant.now();
        DatosPrueba.Resumen resumen = new DatosPrueba(db, LocalDate.now().minusDays(12), ahora, 7, hasher).generar();

        assertEquals(4, resumen.sucursales(), "Centro, Norte y Mercado, más Plaza Sur deshabilitada");
        assertTrue(resumen.ventas() > 300, "ventas: " + resumen.ventas());
        assertTrue(resumen.surtidos() > 6);
        assertTrue(resumen.mermas() > 0);
        db.con(c -> {
            // Ningún producto queda con existencia negativa y nada queda fechado en el futuro.
            assertEquals(0, contar(c, "SELECT COUNT(*) FROM v_existencias WHERE existencia < -0.0005"));
            assertEquals(0, contar(c, "SELECT COUNT(*) FROM v_existencias_lote WHERE existencia < -0.0005"));
            assertEquals(0, contar(c, "SELECT COUNT(*) FROM bitacora WHERE fecha > '" + Tiempo.formatear(ahora) + "'"));
            assertEquals(0, contar(c, "SELECT COUNT(*) FROM ventas WHERE fecha > '" + Tiempo.formatear(ahora) + "'"));
            // Los cortes cuadran y el catálogo tiene de todo.
            assertEquals(0, contar(c, """
                    SELECT COUNT(*) FROM turnos_caja
                    WHERE estado = 'CERRADO' AND diferencia_centavos <> efectivo_contado_centavos - efectivo_esperado_centavos"""));
            assertEquals(0, contar(c, "SELECT COUNT(*) FROM turnos_caja WHERE estado = 'ABIERTO' AND dispositivo_id = '"
                    + terminal + "'"));
            assertTrue(contar(c, "SELECT COUNT(*) FROM productos WHERE disponibilidad = 'TEMPORADA'") >= 4);
            assertTrue(contar(c, "SELECT COUNT(*) FROM productos WHERE disponibilidad = 'EDICION_ESPECIAL'") >= 2);
            assertTrue(contar(c, "SELECT COUNT(*) FROM productos WHERE sujeto_merma = 1 AND unidad = 'PZA'") >= 1);
            assertTrue(contar(c, "SELECT COUNT(*) FROM presentaciones WHERE tipo = 'UNIDAD' AND factor < 1") >= 1);
            assertTrue(contar(c, "SELECT COUNT(DISTINCT usuario_id) FROM horarios") >= 8);
            assertTrue(contar(c, "SELECT COUNT(*) FROM reglas_comision") >= 9);
            assertTrue(contar(c, "SELECT COUNT(*) FROM metas_sucursal") >= 3);
            assertTrue(contar(c, "SELECT COUNT(*) FROM limites_sucursal") > 0);
            assertTrue(contar(c, "SELECT COUNT(*) FROM movimientos_credito WHERE tipo = 'CARGO'") > 0);
            return null;
        });
        // Esta terminal sigue siendo la misma aunque ahora haya más cajas registradas.
        assertEquals(terminal, db.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, null)));

        Administracion admin = new Administracion(db, hasher, Clock.systemDefaultZone(), terminal);
        assertFalse(admin.comisiones().calcular(LocalDate.now().minusDays(12), LocalDate.now()).isEmpty());
        // Un cajero sin horario entra con la contraseña de prueba.
        AuthService auth = new AuthService(db, hasher, Clock.systemDefaultZone(), terminal);
        assertInstanceOf(ResultadoLogin.Exitoso.class,
                auth.iniciarSesion("ltorres", DatosPrueba.PASSWORD.toCharArray(), ModoConexion.OFFLINE));
    }

    /**
     * Carga los datos de prueba en una base existente (solo con administrador y almacén), respaldándola antes:
     * {@code ./mvnw test -Dtest=DatosPruebaTest#cargarEnBase -DdatosPrueba.bd=$HOME/.cremerias-pos/cremerias.db}
     * Opcional: {@code -DdatosPrueba.desde=2026-08-01} (por defecto, el día 1 del mes pasado).
     */
    @Test
    @EnabledIfSystemProperty(named = "datosPrueba.bd", matches = ".+")
    void cargarEnBase() throws Exception {
        Path archivo = Path.of(System.getProperty("datosPrueba.bd"));
        assertTrue(Files.exists(archivo), "No existe " + archivo);
        Database db = new Database(archivo);
        Path respaldos = archivo.resolveSibling("respaldos");
        Files.createDirectories(respaldos);
        Path respaldo = respaldos.resolve("cremerias_antes_de_datos_prueba_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".db");
        db.con(c -> {
            try (var st = c.createStatement()) {
                st.execute("VACUUM INTO '" + respaldo.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        new Migraciones(db).aplicar();
        String desde = System.getProperty("datosPrueba.desde");
        LocalDate inicio = desde == null || desde.isBlank()
                ? LocalDate.now().minusMonths(1).withDayOfMonth(1) : LocalDate.parse(desde);
        // Dos minutos atrás, para que nada quede con fecha posterior a cuando se abra la app.
        DatosPrueba.Resumen resumen = new DatosPrueba(db, inicio, Instant.now().minusSeconds(120), 20260929,
                new PasswordHasher()).generar();
        System.out.println("Respaldo previo: " + respaldo);
        System.out.println("Datos de prueba cargados: " + resumen);
    }

    private static int contar(java.sql.Connection c, String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
