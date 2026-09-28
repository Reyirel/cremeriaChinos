package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Alta, edición, contraseña, habilitación y borrado (lógico) de usuarios. */
public class UsuarioService {

    public record Fila(Usuario usuario, String sucursal, boolean tieneHorario, boolean accesoBloqueado) {
    }

    private final Database database;
    private final PasswordHasher hasher;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();
    private final UsuarioRepository usuarios = new UsuarioRepository();

    public UsuarioService(Database database, PasswordHasher hasher, String dispositivoId) {
        this.database = database;
        this.hasher = hasher;
        this.dispositivoId = dispositivoId;
    }

    public List<Fila> listar() {
        return database.con(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT u.id, COALESCE(s.nombre, 'Sin sucursal') AS sucursal,
                           EXISTS (SELECT 1 FROM horarios h WHERE h.usuario_id = u.id) AS con_horario,
                           EXISTS (SELECT 1 FROM bloqueos_acceso b WHERE b.usuario_id = u.id AND b.estado = 'PENDIENTE') AS bloqueado
                    FROM usuarios u LEFT JOIN sucursales s ON s.id = u.sucursal_id
                    WHERE u.eliminado_en IS NULL
                    ORDER BY CASE u.rol WHEN 'ADMINISTRADOR' THEN 0 WHEN 'SUPERVISOR' THEN 1 ELSE 2 END,
                             u.nombre_completo COLLATE NOCASE""");
                 ResultSet rs = ps.executeQuery()) {
                List<String[]> filas = new ArrayList<>();
                while (rs.next()) {
                    filas.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
                }
                List<Fila> lista = new ArrayList<>();
                for (String[] f : filas) {
                    lista.add(new Fila(usuarios.porId(c, f[0]).orElseThrow(), f[1], "1".equals(f[2]), "1".equals(f[3])));
                }
                return lista;
            }
        });
    }

    /** Crea un usuario. Devuelve el folio. */
    public String crear(String nombre, String usuario, char[] password, Rol rol, String sucursalId, Usuario quien) {
        try {
            validar(nombre, usuario, rol, sucursalId);
            validarPassword(password);
            String hash = hasher.hash(password);
            return database.enTransaccion(c -> {
                validarUsuarioLibre(c, usuario, null);
                String id = Ids.nuevo();
                usuarios.insertar(c, new Usuario(id, sucursalId, nombre.strip(), usuario.strip(), hash, rol, true,
                        false, 0, null, null));
                return bitacora.registrar(c, AccionBitacora.USUARIO_CREADO, quien, dispositivoId, "usuarios", id,
                        nombre.strip() + " (" + usuario.strip() + ") como " + rol.nombre());
            });
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public String editar(Usuario original, String nombre, String usuario, Rol rol, String sucursalId, Usuario quien) {
        validar(nombre, usuario, rol, sucursalId);
        if (original.id().equals(quien.id()) && rol != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("No puedes quitarte a ti mismo el rol de administrador.");
        }
        return database.enTransaccion(c -> {
            validarUsuarioLibre(c, usuario, original.id());
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE usuarios SET nombre_completo = ?, usuario = ?, rol = ?, sucursal_id = ?, actualizado_en = ?
                    WHERE id = ?""")) {
                ps.setString(1, nombre.strip());
                ps.setString(2, usuario.strip());
                ps.setString(3, rol.name());
                ps.setString(4, sucursalId);
                ps.setString(5, Tiempo.ahora());
                ps.setString(6, original.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.USUARIO_EDITADO, quien, dispositivoId, "usuarios",
                    original.id(), nombre.strip() + " (" + usuario.strip() + "), " + rol.nombre());
        });
    }

    public String cambiarPassword(Usuario usuario, char[] password, Usuario quien) {
        try {
            validarPassword(password);
            String hash = hasher.hash(password);
            return database.enTransaccion(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE usuarios SET password_hash = ?, debe_cambiar_password = 0, intentos_fallidos = 0,
                                            bloqueado_hasta = NULL, actualizado_en = ?
                        WHERE id = ?""")) {
                    ps.setString(1, hash);
                    ps.setString(2, Tiempo.ahora());
                    ps.setString(3, usuario.id());
                    ps.executeUpdate();
                }
                return bitacora.registrar(c, AccionBitacora.USUARIO_PASSWORD, quien, dispositivoId, "usuarios",
                        usuario.id(), "Contraseña de " + usuario.nombreCompleto());
            });
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public String cambiarActivo(Usuario usuario, boolean activo, Usuario quien) {
        if (usuario.id().equals(quien.id())) {
            throw new IllegalStateException("No puedes deshabilitar tu propio usuario.");
        }
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE usuarios SET activo = ?, actualizado_en = ? WHERE id = ?")) {
                ps.setBoolean(1, activo);
                ps.setString(2, Tiempo.ahora());
                ps.setString(3, usuario.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, activo ? AccionBitacora.USUARIO_HABILITADO : AccionBitacora.USUARIO_DESHABILITADO,
                    quien, dispositivoId, "usuarios", usuario.id(), usuario.nombreCompleto());
        });
    }

    public String eliminar(Usuario usuario, Usuario quien) {
        if (usuario.id().equals(quien.id())) {
            throw new IllegalStateException("No puedes eliminar tu propio usuario.");
        }
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM turnos_caja WHERE usuario_id = ? AND estado = 'ABIERTO'")) {
                ps.setString(1, usuario.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("El usuario tiene una caja abierta. Haz su corte primero.");
                    }
                }
            }
            String ahora = Tiempo.ahora();
            // Se libera el nombre de usuario para que pueda volver a usarse; el id conserva el historial.
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE usuarios SET eliminado_en = ?, activo = 0, usuario = usuario || '#' || substr(id, 1, 8),
                                        actualizado_en = ?
                    WHERE id = ?""")) {
                ps.setString(1, ahora);
                ps.setString(2, ahora);
                ps.setString(3, usuario.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.USUARIO_ELIMINADO, quien, dispositivoId, "usuarios",
                    usuario.id(), usuario.nombreCompleto() + " (" + usuario.usuario() + ")");
        });
    }

    private static void validar(String nombre, String usuario, Rol rol, String sucursalId) {
        if (nombre == null || nombre.isBlank()) {
            throw new IllegalArgumentException("Escribe el nombre completo.");
        }
        if (usuario == null || !usuario.strip().matches("[A-Za-z0-9._-]{3,30}")) {
            throw new IllegalArgumentException("El usuario debe tener de 3 a 30 letras o números, sin espacios.");
        }
        if (rol == null) {
            throw new IllegalArgumentException("Elige el rol.");
        }
        if (sucursalId == null) {
            throw new IllegalArgumentException("Elige la sucursal.");
        }
    }

    private static void validarPassword(char[] password) {
        if (password == null || password.length < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
        }
    }

    private static void validarUsuarioLibre(Connection c, String usuario, String excluir) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT nombre_completo FROM usuarios WHERE usuario = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, usuario.strip());
            ps.setString(2, excluir == null ? "" : excluir);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("El usuario \"" + usuario.strip() + "\" ya existe ("
                            + rs.getString(1) + ").");
                }
            }
        }
    }
}
