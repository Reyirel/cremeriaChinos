package com.cremerias.puntoventa.repository;

import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Registro de acciones con folio. Siempre se usa dentro de la misma transacción que la acción. */
public class BitacoraRepository {

    public record Registro(String id, String folio, Instant fecha, String usuario, String sucursal,
                           AccionBitacora accion, String entidad, String entidadId, String descripcion) {
    }

    private final ContadorRepository contadores = new ContadorRepository();

    /** Folio consecutivo por tipo de acción y terminal: SUR-1987-000012. */
    public String siguienteFolio(Connection c, String prefijo, String dispositivoId) throws SQLException {
        long n = contadores.siguiente(c, "folio:" + prefijo + ":" + dispositivoId);
        String terminal = dispositivoId.replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT);
        return "%s-%s-%06d".formatted(prefijo, terminal, n);
    }

    /** Registra la acción y devuelve su folio. */
    public String registrar(Connection c, AccionBitacora accion, Usuario usuario, String dispositivoId,
                            String entidad, String entidadId, String descripcion) throws SQLException {
        return registrar(c, accion, usuario, dispositivoId, entidad, entidadId, descripcion,
                siguienteFolio(c, accion.prefijo(), dispositivoId));
    }

    /** Registra la acción con un folio ya generado (cuando el documento se creó antes con ese folio). */
    public String registrar(Connection c, AccionBitacora accion, Usuario usuario, String dispositivoId,
                            String entidad, String entidadId, String descripcion, String folio) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bitacora (id, folio, fecha, usuario_id, sucursal_id, dispositivo_id, accion, entidad,
                                      entidad_id, descripcion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Ids.nuevo());
            ps.setString(2, folio);
            ps.setString(3, Tiempo.ahora());
            ps.setString(4, usuario == null ? null : usuario.id());
            ps.setString(5, usuario == null ? null : usuario.sucursalId());
            ps.setString(6, dispositivoId);
            ps.setString(7, accion.name());
            ps.setString(8, entidad);
            ps.setString(9, entidadId);
            ps.setString(10, descripcion);
            ps.executeUpdate();
        }
        return folio;
    }

    public List<Registro> buscar(Connection c, Instant desde, Instant hasta, String texto, String prefijo, int limite)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT b.id, b.folio, b.fecha, COALESCE(u.nombre_completo, 'Sistema') AS usuario,
                       COALESCE(s.nombre, '') AS sucursal, b.accion, b.entidad, b.entidad_id, b.descripcion
                FROM bitacora b
                LEFT JOIN usuarios u ON u.id = b.usuario_id
                LEFT JOIN sucursales s ON s.id = b.sucursal_id
                WHERE b.fecha >= ? AND b.fecha < ?""");
        List<String> parametros = new ArrayList<>(List.of(Tiempo.formatear(desde), Tiempo.formatear(hasta)));
        if (prefijo != null) {
            sql.append(" AND b.folio LIKE ?");
            parametros.add(prefijo + "-%");
        }
        if (texto != null && !texto.isBlank()) {
            sql.append(" AND (b.folio LIKE ? OR b.descripcion LIKE ? OR b.entidad_id LIKE ? OR u.nombre_completo LIKE ?)");
            String patron = "%" + texto.strip() + "%";
            for (int i = 0; i < 4; i++) {
                parametros.add(patron);
            }
        }
        sql.append(" ORDER BY b.fecha DESC LIMIT ").append(limite);
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < parametros.size(); i++) {
                ps.setString(i + 1, parametros.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Registro> lista = new ArrayList<>();
                while (rs.next()) {
                    lista.add(new Registro(rs.getString("id"), rs.getString("folio"), Tiempo.leer(rs.getString("fecha")),
                            rs.getString("usuario"), rs.getString("sucursal"), AccionBitacora.valueOf(rs.getString("accion")),
                            rs.getString("entidad"), rs.getString("entidad_id"), rs.getString("descripcion")));
                }
                return lista;
            }
        }
    }
}
