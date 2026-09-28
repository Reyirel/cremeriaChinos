package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    @TempDir
    Path carpeta;

    private Database database;
    private PasswordHasher hasher;
    private MutableClock reloj;
    private AuthService auth;

    @BeforeEach
    void preparar() {
        database = new Database(carpeta.resolve("prueba.db"));
        new Migraciones(database).aplicar();
        hasher = new PasswordHasher(4);
        new DatosIniciales(database, hasher).sembrarSiVacia();
        String dispositivo = database.con(c -> new DispositivoRepository().obtenerOCrear(c, null));
        reloj = new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        auth = new AuthService(database, hasher, reloj, dispositivo);
    }

    @Test
    void cadaRolIniciaSesionConSusCredenciales() {
        assertRol("admin", "admin123", Rol.ADMINISTRADOR);
        assertRol("SUPERVISOR", "super123", Rol.SUPERVISOR);
        assertRol(" cajero ", "cajero123", Rol.CAJERO);
    }

    @Test
    void passwordIncorrectoSeRechazaYQuedaEnBitacora() {
        var resultado = auth.iniciarSesion("cajero", "mala".toCharArray(), ModoConexion.OFFLINE);
        var rechazado = assertInstanceOf(ResultadoLogin.Rechazado.class, resultado);
        assertEquals(ResultadoLogin.Motivo.CREDENCIALES_INVALIDAS, rechazado.motivo());
        assertEquals(1, contar("SELECT COUNT(*) FROM bitacora_accesos WHERE exitoso = 0"));
    }

    @Test
    void usuarioInexistenteDaElMismoMensajeQuePasswordIncorrecto() {
        var inexistente = (ResultadoLogin.Rechazado) auth.iniciarSesion("nadie", "x".toCharArray(), ModoConexion.OFFLINE);
        var incorrecto = (ResultadoLogin.Rechazado) auth.iniciarSesion("admin", "x".toCharArray(), ModoConexion.OFFLINE);
        assertEquals(inexistente.mensaje(), incorrecto.mensaje());
    }

    @Test
    void seBloqueaTrasVariosIntentosYSeDesbloqueaConElTiempo() {
        for (int i = 0; i < AuthService.MAX_INTENTOS; i++) {
            auth.iniciarSesion("cajero", "mala".toCharArray(), ModoConexion.OFFLINE);
        }
        var bloqueado = auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.OFFLINE);
        assertEquals(ResultadoLogin.Motivo.USUARIO_BLOQUEADO, ((ResultadoLogin.Rechazado) bloqueado).motivo());

        reloj.avanzar(AuthService.DURACION_BLOQUEO.plusSeconds(1));
        assertInstanceOf(ResultadoLogin.Exitoso.class,
                auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.OFFLINE));
    }

    @Test
    void usuarioInactivoNoPuedeEntrar() {
        database.con(c -> c.createStatement().executeUpdate("UPDATE usuarios SET activo = 0 WHERE usuario = 'cajero'"));
        var resultado = auth.iniciarSesion("cajero", "cajero123".toCharArray(), ModoConexion.ONLINE);
        assertEquals(ResultadoLogin.Motivo.USUARIO_INACTIVO, ((ResultadoLogin.Rechazado) resultado).motivo());
    }

    @Test
    void elLoginExitosoAbreSesionYLaEncolaParaSincronizar() {
        var exito = (ResultadoLogin.Exitoso) auth.iniciarSesion("admin", "admin123".toCharArray(), ModoConexion.OFFLINE);
        assertEquals(1, contar("SELECT COUNT(*) FROM sesiones WHERE fin IS NULL"));
        assertEquals(1, contar("SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'sesiones' AND enviado_en IS NULL"));

        auth.cerrarSesion(exito.sesion(), "PRUEBA");
        assertEquals(0, contar("SELECT COUNT(*) FROM sesiones WHERE fin IS NULL"));
        // El cierre no duplica el pendiente: se sube el estado final de la sesión.
        assertEquals(1, contar("SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'sesiones' AND enviado_en IS NULL"));
    }

    @Test
    void losIntentosFallidosNoSeEncolanParaSincronizar() {
        int antes = contar("SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'usuarios'");
        database.con(c -> c.createStatement().executeUpdate("UPDATE sync_outbox SET enviado_en = 'x'"));
        auth.iniciarSesion("cajero", "mala".toCharArray(), ModoConexion.OFFLINE);
        assertEquals(antes, contar("SELECT COUNT(*) FROM sync_outbox WHERE tabla = 'usuarios'"));
    }

    @Test
    void losUsuariosInicialesDebenCambiarPassword() {
        assertEquals(3, contar("SELECT COUNT(*) FROM usuarios WHERE debe_cambiar_password = 1"));
        assertTrue(hasher.verificar("admin123".toCharArray(),
                database.con(c -> {
                    ResultSet rs = c.createStatement().executeQuery("SELECT password_hash FROM usuarios WHERE usuario = 'admin'");
                    rs.next();
                    return rs.getString(1);
                })));
    }

    private void assertRol(String usuario, String password, Rol esperado) {
        var resultado = auth.iniciarSesion(usuario, password.toCharArray(), ModoConexion.OFFLINE);
        var exito = assertInstanceOf(ResultadoLogin.Exitoso.class, resultado);
        assertEquals(esperado, exito.sesion().usuario().rol());
    }

    private int contar(String sql) {
        return database.con(c -> {
            ResultSet rs = c.createStatement().executeQuery(sql);
            rs.next();
            return rs.getInt(1);
        });
    }

    private static final class MutableClock extends Clock {
        private Instant ahora;

        MutableClock(Instant inicio) {
            this.ahora = inicio;
        }

        void avanzar(Duration d) {
            ahora = ahora.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
