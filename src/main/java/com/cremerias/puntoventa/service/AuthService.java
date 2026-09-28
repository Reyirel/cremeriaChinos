package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraAccesosRepository;
import com.cremerias.puntoventa.repository.SesionRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.ResultadoLogin.Motivo;
import com.cremerias.puntoventa.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Inicio y cierre de sesión contra la base local. Funciona igual con o sin internet.
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final int MAX_INTENTOS = 5;
    static final Duration DURACION_BLOQUEO = Duration.ofMinutes(5);
    private static final String MENSAJE_INVALIDO = "Usuario o contraseña incorrectos.";

    private final Database database;
    private final PasswordHasher hasher;
    private final Clock reloj;
    private final String dispositivoId;
    private final UsuarioRepository usuarios = new UsuarioRepository();
    private final SesionRepository sesiones = new SesionRepository();
    private final BitacoraAccesosRepository bitacora = new BitacoraAccesosRepository();
    /** Hash de relleno: si el usuario no existe igual se verifica uno para no revelar qué usuarios existen. */
    private final String hashSenuelo;

    private final AccesoService accesos;

    public AuthService(Database database, PasswordHasher hasher, Clock reloj, String dispositivoId) {
        this.database = database;
        this.hasher = hasher;
        this.reloj = reloj;
        this.dispositivoId = dispositivoId;
        this.hashSenuelo = hasher.hash("senuelo".toCharArray());
        this.accesos = new AccesoService(database, reloj, dispositivoId);
    }

    public AccesoService accesos() {
        return accesos;
    }

    public ResultadoLogin iniciarSesion(String nombreUsuario, char[] password, ModoConexion modo) {
        try {
            if (nombreUsuario == null || nombreUsuario.isBlank() || password == null || password.length == 0) {
                return new ResultadoLogin.Rechazado(Motivo.DATOS_INCOMPLETOS, "Escribe tu usuario y contraseña.");
            }
            String usuarioTexto = nombreUsuario.strip();
            return database.enTransaccion(c -> {
                Instant ahora = reloj.instant();
                Verificacion verificacion = verificar(c, usuarioTexto, password, ahora);
                if (verificacion instanceof Verificacion.Falla falla) {
                    return new ResultadoLogin.Rechazado(falla.motivo(), falla.mensaje());
                }
                Usuario usuario = ((Verificacion.Correcta) verificacion).usuario();
                Optional<AccesoService.Bloqueo> bloqueo = accesos.verificarEntrada(c, usuario);
                if (bloqueo.isPresent()) {
                    bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, false, "FUERA_DE_HORARIO");
                    return new ResultadoLogin.Rechazado(Motivo.FUERA_DE_HORARIO,
                            "Acceso bloqueado: " + bloqueo.get().motivo().descripcion().toLowerCase()
                                    + ". Un administrador o supervisor debe darte acceso (folio "
                                    + bloqueo.get().folio() + ").", bloqueo.get().id());
                }
                String sesionId = Ids.nuevo();
                usuarios.registrarAccesoExitoso(c, usuario.id(), ahora);
                sesiones.abrir(c, sesionId, usuario.id(), dispositivoId, ahora, modo);
                bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, true, null);
                log.info("Inicio de sesión: {} ({}) en modo {}", usuario.usuario(), usuario.rol(), modo);
                return new ResultadoLogin.Exitoso(new Sesion(sesionId, usuario, dispositivoId, ahora, modo));
            });
        } finally {
            limpiar(password);
        }
    }

    /**
     * Pide la contraseña de un usuario con alguno de los roles indicados para autorizar
     * una operación delicada (cancelar una venta, retirar efectivo...).
     */
    public ResultadoAutorizacion autorizar(String nombreUsuario, char[] password, Set<Rol> rolesPermitidos,
                                           String operacion) {
        try {
            if (nombreUsuario == null || nombreUsuario.isBlank() || password == null || password.length == 0) {
                return new ResultadoAutorizacion.Denegado("Escribe usuario y contraseña de quien autoriza.");
            }
            String usuarioTexto = nombreUsuario.strip();
            return database.enTransaccion(c -> {
                Verificacion verificacion = verificar(c, usuarioTexto, password, reloj.instant());
                if (verificacion instanceof Verificacion.Falla falla) {
                    return new ResultadoAutorizacion.Denegado(falla.mensaje());
                }
                Usuario usuario = ((Verificacion.Correcta) verificacion).usuario();
                if (!rolesPermitidos.contains(usuario.rol())) {
                    bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, false, "SIN_PERMISO:" + operacion);
                    return new ResultadoAutorizacion.Denegado("Este usuario no tiene permiso para autorizar.");
                }
                usuarios.registrarAccesoExitoso(c, usuario.id(), reloj.instant());
                bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, true, "AUTORIZACION:" + operacion);
                log.info("{} autorizó: {}", usuario.usuario(), operacion);
                return new ResultadoAutorizacion.Autorizado(usuario);
            });
        } finally {
            limpiar(password);
        }
    }

    private sealed interface Verificacion {
        record Correcta(Usuario usuario) implements Verificacion {
        }

        record Falla(Motivo motivo, String mensaje) implements Verificacion {
        }
    }

    /** Valida usuario y contraseña aplicando el bloqueo por intentos fallidos. */
    private Verificacion verificar(Connection c, String usuarioTexto, char[] password, Instant ahora)
            throws SQLException {
        Optional<Usuario> encontrado = usuarios.buscarPorUsuario(c, usuarioTexto);

        if (encontrado.isEmpty()) {
            hasher.verificar(password, hashSenuelo);
            bitacora.registrar(c, usuarioTexto, null, dispositivoId, false, "USUARIO_NO_EXISTE");
            return new Verificacion.Falla(Motivo.CREDENCIALES_INVALIDAS, MENSAJE_INVALIDO);
        }

        Usuario usuario = encontrado.get();
        if (usuario.bloqueado(ahora)) {
            bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, false, "BLOQUEADO");
            long minutos = Math.max(1, Duration.between(ahora, usuario.bloqueadoHasta()).toMinutes() + 1);
            return new Verificacion.Falla(Motivo.USUARIO_BLOQUEADO,
                    "Demasiados intentos fallidos. Intenta de nuevo en " + minutos
                            + (minutos == 1 ? " minuto." : " minutos."));
        }

        if (!hasher.verificar(password, usuario.passwordHash())) {
            int intentos = usuario.intentosFallidos() + 1;
            Instant bloqueo = intentos >= MAX_INTENTOS ? ahora.plus(DURACION_BLOQUEO) : null;
            usuarios.registrarIntentoFallido(c, usuario.id(), bloqueo != null ? 0 : intentos, bloqueo);
            bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, false, "PASSWORD_INCORRECTO");
            if (bloqueo != null) {
                log.warn("Usuario {} bloqueado por intentos fallidos", usuario.usuario());
                return new Verificacion.Falla(Motivo.USUARIO_BLOQUEADO,
                        "Demasiados intentos fallidos. La cuenta se bloqueó por "
                                + DURACION_BLOQUEO.toMinutes() + " minutos.");
            }
            int restantes = MAX_INTENTOS - intentos;
            String aviso = restantes <= 2
                    ? " Te " + (restantes == 1 ? "queda 1 intento." : "quedan " + restantes + " intentos.")
                    : "";
            return new Verificacion.Falla(Motivo.CREDENCIALES_INVALIDAS, MENSAJE_INVALIDO + aviso);
        }

        if (!usuario.activo()) {
            bitacora.registrar(c, usuarioTexto, usuario.id(), dispositivoId, false, "INACTIVO");
            return new Verificacion.Falla(Motivo.USUARIO_INACTIVO,
                    "Este usuario está desactivado. Consulta con el administrador.");
        }
        return new Verificacion.Correcta(usuario);
    }

    private static void limpiar(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    public void cerrarSesion(Sesion sesion, String motivo) {
        database.enTransaccion(c -> {
            sesiones.cerrar(c, sesion.id(), motivo);
            accesos.registrarSalida(c, sesion.usuario());
            return null;
        });
        log.info("Cierre de sesión: {} ({})", sesion.usuario().usuario(), motivo);
    }
}
