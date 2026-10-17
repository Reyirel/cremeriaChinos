package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.SucursalRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crea la sucursal y los usuarios de arranque la primera vez que se abre el sistema.
 * Todos quedan marcados para cambiar su contraseña.
 */
public class DatosIniciales {

    private static final Logger log = LoggerFactory.getLogger(DatosIniciales.class);

    private final Database database;
    private final PasswordHasher hasher;
    private final UsuarioRepository usuarios = new UsuarioRepository();
    private final SucursalRepository sucursales = new SucursalRepository();

    public DatosIniciales(Database database, PasswordHasher hasher) {
        this.database = database;
        this.hasher = hasher;
    }

    /** @return el id de la sucursal creada, o {@code null} si ya había datos. */
    public String sembrarSiVacia() {
        return database.enTransaccion(c -> {
            if (usuarios.contar(c) > 0) {
                return null;
            }
            String sucursalId = Ids.nuevo();
            sucursales.insertar(c, sucursalId, "MATRIZ", "Cremería Matriz");
            // El administrador trabaja en el almacén central (creado por la migración V5).
            String almacenId;
            try (var ps = c.prepareStatement("SELECT id FROM sucursales WHERE es_almacen = 1 LIMIT 1");
                 var rs = ps.executeQuery()) {
                almacenId = rs.next() ? rs.getString(1) : sucursalId;
            }
            crear(c, almacenId, "Administrador General", "admin", "admin123", Rol.ADMINISTRADOR);
            crear(c, sucursalId, "Supervisor de Tienda", "supervisor", "super123", Rol.SUPERVISOR);
            crear(c, sucursalId, "Cajero de Mostrador", "cajero", "cajero123", Rol.CAJERO);
            log.info("Datos iniciales creados (sucursal MATRIZ y usuarios admin/supervisor/cajero)");
            return sucursalId;
        });
    }

    /**
     * Base limpia para la caja de prueba: solo el administrador (admin / admin123, sin pedir cambio de
     * contraseña) en el almacén central. Las sucursales, productos y usuarios los da de alta él.
     *
     * @return siempre {@code null}: no crea sucursal, así que no se carga el catálogo de ejemplo
     */
    public String sembrarSoloAdministrador() {
        return database.enTransaccion(c -> {
            if (usuarios.contar(c) > 0) {
                return null;
            }
            String almacenId;
            try (var ps = c.prepareStatement("SELECT id FROM sucursales WHERE es_almacen = 1 LIMIT 1");
                 var rs = ps.executeQuery()) {
                rs.next();
                almacenId = rs.getString(1);
            }
            usuarios.insertar(c, new Usuario(Ids.nuevo(), almacenId, "Administrador General", "admin",
                    hasher.hash("admin123".toCharArray()), Rol.ADMINISTRADOR, true, false, 0, null, null));
            log.info("Base de prueba creada: solo el usuario admin");
            return null;
        });
    }

    private void crear(java.sql.Connection c, String sucursalId, String nombre, String usuario, String password,
                       Rol rol) throws java.sql.SQLException {
        usuarios.insertar(c, new Usuario(Ids.nuevo(), sucursalId, nombre, usuario,
                hasher.hash(password.toCharArray()), rol, true, true, 0, null, null));
    }
}
