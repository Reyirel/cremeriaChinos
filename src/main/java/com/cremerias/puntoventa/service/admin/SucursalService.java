package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Alta, edición, habilitación y borrado (lógico) de sucursales. */
public class SucursalService {

    private final Database database;
    private final String dispositivoId;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public SucursalService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    /** Sucursales (sin el almacén), incluidas las deshabilitadas. */
    public List<Sucursal> listar() {
        return database.con(c -> consultar(c, "WHERE eliminado_en IS NULL AND es_almacen = 0"));
    }

    public List<Sucursal> activas() {
        return database.con(c -> consultar(c, "WHERE eliminado_en IS NULL AND es_almacen = 0 AND activo = 1"));
    }

    public Optional<Sucursal> almacen() {
        return database.con(c -> consultar(c, "WHERE es_almacen = 1")).stream().findFirst();
    }

    /** Todas las ubicaciones con inventario: almacén primero y luego sucursales activas. */
    public List<Sucursal> ubicaciones() {
        List<Sucursal> lista = new ArrayList<>();
        almacen().ifPresent(lista::add);
        lista.addAll(activas());
        return lista;
    }

    private static List<Sucursal> consultar(Connection c, String filtro) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, codigo, nombre, direccion, telefono, activo, es_almacen FROM sucursales
                """ + filtro + " ORDER BY nombre COLLATE NOCASE");
             ResultSet rs = ps.executeQuery()) {
            List<Sucursal> lista = new ArrayList<>();
            while (rs.next()) {
                lista.add(new Sucursal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)));
            }
            return lista;
        }
    }

    /** Crea (id nulo) o edita una sucursal. Devuelve el folio. */
    public String guardar(String id, String codigo, String nombre, String direccion, String telefono, Usuario quien) {
        String cod = codigo == null ? "" : codigo.strip().toUpperCase();
        String nom = nombre == null ? "" : nombre.strip();
        if (cod.isEmpty() || nom.isEmpty()) {
            throw new IllegalArgumentException("El código y el nombre son obligatorios.");
        }
        if (!cod.matches("[A-Z0-9]{2,12}")) {
            throw new IllegalArgumentException("El código debe tener de 2 a 12 letras o números, sin espacios.");
        }
        return database.enTransaccion(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT nombre FROM sucursales WHERE codigo = ? COLLATE NOCASE AND id <> ?")) {
                ps.setString(1, cod);
                ps.setString(2, id == null ? "" : id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalArgumentException("El código " + cod + " ya lo usa " + rs.getString(1) + ".");
                    }
                }
            }
            String ahora = Tiempo.ahora();
            if (id == null) {
                String nuevo = Ids.nuevo();
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO sucursales (id, codigo, nombre, direccion, telefono, creado_en, actualizado_en)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, nuevo);
                    ps.setString(2, cod);
                    ps.setString(3, nom);
                    ps.setString(4, vacio(direccion));
                    ps.setString(5, vacio(telefono));
                    ps.setString(6, ahora);
                    ps.setString(7, ahora);
                    ps.executeUpdate();
                }
                return bitacora.registrar(c, AccionBitacora.SUCURSAL_CREADA, quien, dispositivoId, "sucursales", nuevo,
                        "Sucursal " + nom + " (" + cod + ")");
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE sucursales SET codigo = ?, nombre = ?, direccion = ?, telefono = ?, actualizado_en = ?
                    WHERE id = ? AND es_almacen = 0""")) {
                ps.setString(1, cod);
                ps.setString(2, nom);
                ps.setString(3, vacio(direccion));
                ps.setString(4, vacio(telefono));
                ps.setString(5, ahora);
                ps.setString(6, id);
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.SUCURSAL_EDITADA, quien, dispositivoId, "sucursales", id,
                    "Sucursal " + nom + " (" + cod + ")");
        });
    }

    public String cambiarActivo(Sucursal sucursal, boolean activo, Usuario quien) {
        return database.enTransaccion(c -> {
            if (!activo) {
                validarSinCajasAbiertas(c, sucursal);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sucursales SET activo = ?, actualizado_en = ? WHERE id = ?")) {
                ps.setBoolean(1, activo);
                ps.setString(2, Tiempo.ahora());
                ps.setString(3, sucursal.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, activo ? AccionBitacora.SUCURSAL_HABILITADA : AccionBitacora.SUCURSAL_DESHABILITADA,
                    quien, dispositivoId, "sucursales", sucursal.id(), "Sucursal " + sucursal.nombre());
        });
    }

    /** Borrado lógico: deja de aparecer, pero sus ventas y movimientos se conservan. */
    public String eliminar(Sucursal sucursal, Usuario quien) {
        return database.enTransaccion(c -> {
            validarSinCajasAbiertas(c, sucursal);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM usuarios WHERE sucursal_id = ? AND eliminado_en IS NULL")) {
                ps.setString(1, sucursal.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        throw new IllegalStateException("La sucursal tiene " + rs.getInt(1)
                                + " usuario(s). Muévelos a otra sucursal o elimínalos primero.");
                    }
                }
            }
            String ahora = Tiempo.ahora();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE sucursales SET eliminado_en = ?, activo = 0, actualizado_en = ? WHERE id = ? AND es_almacen = 0")) {
                ps.setString(1, ahora);
                ps.setString(2, ahora);
                ps.setString(3, sucursal.id());
                ps.executeUpdate();
            }
            return bitacora.registrar(c, AccionBitacora.SUCURSAL_ELIMINADA, quien, dispositivoId, "sucursales",
                    sucursal.id(), "Sucursal " + sucursal.nombre() + " (" + sucursal.codigo() + ")");
        });
    }

    private static void validarSinCajasAbiertas(Connection c, Sucursal sucursal) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM turnos_caja WHERE sucursal_id = ? AND estado = 'ABIERTO'")) {
            ps.setString(1, sucursal.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    throw new IllegalStateException("La sucursal tiene cajas abiertas. Haz su corte primero.");
                }
            }
        }
    }

    private static String vacio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }
}
