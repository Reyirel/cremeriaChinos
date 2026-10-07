package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.ConfigLocalRepository;
import com.cremerias.puntoventa.repository.SyncOutboxRepository;
import com.cremerias.puntoventa.util.Tiempo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Baja de Supabase lo que cambió y lo guarda en la base local.
 *
 * <ul>
 *   <li>Por cada tabla se recuerda la última fila bajada ({@code sincronizado_en} del servidor y
 *       su llave) en {@code config_local} y se pide lo que vino después.</li>
 *   <li>Normalmente basta una llamada a {@code bajar_cambios} con todas las tablas. Si una tabla
 *       trae más de una página, de ahí en adelante se baja tabla por tabla, en orden, para que los
 *       padres siempre lleguen antes que los hijos (la base local revisa las llaves foráneas).</li>
 *   <li>Un registro con cambios locales que todavía no suben no se toca: primero se sube lo de la
 *       caja y la siguiente vuelta trae la versión de la nube.</li>
 *   <li>Lo que encolan los triggers al guardar lo bajado se quita en la misma transacción: vino de
 *       la nube, no hay que regresarlo.</li>
 * </ul>
 */
class Bajada {

    private static final Logger log = LoggerFactory.getLogger(Bajada.class);
    static final int PAGINA = 500;
    /**
     * Una transacción del servidor puede terminar después de otra que empezó más tarde (y quedar
     * con una hora anterior). En Supabase una petición de la API no dura más de 8 s, así que si lo
     * último que se bajó es reciente se repasan los últimos 15 s.
     */
    private static final Duration MARGEN = Duration.ofSeconds(15);
    private static final Duration RECIENTE = Duration.ofMinutes(2);
    static final String PREFIJO_CURSOR = "nube.bajada.";

    private static final Pattern FECHA_HORA =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:\\d{2}|Z)");
    private static final Pattern HORA = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");

    private final Database database;
    private final SyncOutboxRepository outbox = new SyncOutboxRepository();
    private final ConfigLocalRepository config = new ConfigLocalRepository();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, List<Columna>> columnas = new HashMap<>();

    /** Columna de la base local (PRAGMA table_info). */
    private record Columna(String nombre, String tipo, boolean obligatoria, boolean conDefault) {
    }

    /** Última fila bajada de una tabla. */
    record Cursor(String desde, String llave) {
    }

    Bajada(Database database) {
        this.database = database;
    }

    /** @return cuántas filas se guardaron. */
    int bajar(Nube nube) throws IOException, ErrorNube {
        Map<String, Cursor> guardados = database.con(this::leerCursores);
        ObjectNode todas = json.createObjectNode();
        for (String tabla : TablasNube.ORDEN) {
            todas.set(tabla, desde(guardados.get(tabla)));
        }
        JsonNode respuesta = pedir(nube, todas);

        int total = 0;
        for (int i = 0; i < TablasNube.ORDEN.size(); i++) {
            String tabla = TablasNube.ORDEN.get(i);
            JsonNode filas = respuesta.path(tabla);
            total += guardar(tabla, filas);
            if (filas.size() >= PAGINA) {
                // Esta tabla trae más: se termina y las siguientes se piden una por una, en orden.
                total += bajarCompleta(nube, tabla, ultimoCursor(tabla, filas));
                for (String siguiente : TablasNube.ORDEN.subList(i + 1, TablasNube.ORDEN.size())) {
                    total += bajarCompleta(nube, siguiente, desde(guardados.get(siguiente)));
                }
                break;
            }
        }
        return total;
    }

    private int bajarCompleta(Nube nube, String tabla, ObjectNode cursor) throws IOException, ErrorNube {
        int total = 0;
        while (true) {
            JsonNode filas = pedir(nube, json.createObjectNode().set(tabla, cursor)).path(tabla);
            total += guardar(tabla, filas);
            if (filas.size() < PAGINA) {
                return total;
            }
            cursor = ultimoCursor(tabla, filas);
        }
    }

    private JsonNode pedir(Nube nube, ObjectNode cursores) throws IOException, ErrorNube {
        ObjectNode parametros = json.createObjectNode();
        parametros.set("p_cursores", cursores);
        parametros.put("p_limite", PAGINA);
        return nube.rpc("bajar_cambios", parametros);
    }

    private int guardar(String tabla, JsonNode filas) throws ErrorNube {
        if (filas.isEmpty()) {
            return 0;
        }
        Resultado resultado = database.enTransaccionInmediata(c -> guardar(c, tabla, filas));
        if (resultado.error() != null) {
            // Se guardó hasta antes de la fila que falló; la siguiente vuelta se reintenta desde ahí.
            throw new ErrorNube(0, "No se pudo guardar en la caja un cambio de " + tabla + ": " + resultado.error());
        }
        return resultado.guardadas();
    }

    /** Filas guardadas y, si se detuvo, por qué (se guardó hasta antes de esa fila). */
    record Resultado(int guardadas, String error) {
    }

    /** Guarda las filas en la base local. Visible para pruebas. */
    Resultado guardar(Connection c, String tabla, JsonNode filas) throws SQLException {
        boolean credenciales = TablasNube.CREDENCIALES.equals(tabla);
        String tablaLocal = credenciales ? "usuarios" : tabla;
        String llave = TablasNube.llave(tabla);
        long ultimoEncolado = outbox.ultimoId(c);
        Set<String> sinSubir = outbox.registrosSinSubir(c, tablaLocal);

        int guardadas = 0;
        String error = null;
        JsonNode ultima = null;
        for (JsonNode fila : filas) {
            String id = fila.path(llave).asText();
            if (!sinSubir.contains(id)) {
                Savepoint punto = c.setSavepoint();
                try {
                    if (credenciales) {
                        guardarCredencial(c, id, fila.path("password_hash").asText(null));
                    } else {
                        guardarFila(c, tabla, llave, fila);
                    }
                    c.releaseSavepoint(punto);
                    guardadas++;
                } catch (SQLException e) {
                    c.rollback(punto);
                    if (!esDuplicado(e)) {
                        error = e.getMessage();
                        break;
                    }
                    // Choca con otro registro local por un dato único (p. ej. dos órdenes pendientes
                    // del mismo producto): no se puede resolver solo, se deja la versión local.
                    log.warn("No se guardó {} {} de la nube: {}", tabla, id, e.getMessage());
                }
            }
            ultima = fila;
        }
        outbox.borrarPosteriores(c, ultimoEncolado);
        if (ultima != null) {
            config.guardar(c, PREFIJO_CURSOR + tabla,
                    ultima.path("sincronizado_en").asText() + "|" + ultima.path(llave).asText());
        }
        return new Resultado(guardadas, error);
    }

    private void guardarFila(Connection c, String tabla, String llave, JsonNode fila) throws SQLException {
        List<Columna> locales = columnas(c, tabla);
        Map<String, Object> valores = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = fila.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> campo = it.next();
            if (locales.stream().anyMatch(col -> col.nombre().equals(campo.getKey()))) {
                valores.put(campo.getKey(), aLocal(tabla, campo.getValue()));
            }
        }
        List<String> desdeNube = new ArrayList<>(valores.keySet());
        desdeNube.remove(llave);
        // Columnas que solo existen en la caja y no pueden quedar vacías al dar de alta (p. ej. el
        // precio obsoleto de productos, o el hash de un usuario cuya contraseña llega aparte).
        for (Columna col : locales) {
            if (col.obligatoria() && !col.conDefault() && !valores.containsKey(col.nombre())) {
                valores.put(col.nombre(), col.tipo().contains("INT") || col.tipo().contains("REAL") ? 0 : "");
            }
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tabla).append(" (")
                .append(String.join(", ", valores.keySet())).append(") VALUES (")
                .append("?, ".repeat(valores.size() - 1)).append("?) ON CONFLICT (").append(llave).append(") DO ");
        if (desdeNube.isEmpty()) {
            sql.append("NOTHING");
        } else {
            sql.append("UPDATE SET ");
            sql.append(String.join(", ", desdeNube.stream().map(col -> col + " = excluded." + col).toList()));
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object valor : valores.values()) {
                ps.setObject(i++, valor);
            }
            ps.executeUpdate();
        }
    }

    private static void guardarCredencial(Connection c, String usuarioId, String hash) throws SQLException {
        if (hash == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE usuarios SET password_hash = ? WHERE id = ? AND password_hash IS NOT ?")) {
            ps.setString(1, hash);
            ps.setString(2, usuarioId);
            ps.setString(3, hash);
            ps.executeUpdate();
        }
    }

    /** Convierte un valor de la nube al formato de la base local. */
    static Object aLocal(String tabla, JsonNode valor) {
        if (valor == null || valor.isNull() || valor.isContainerNode()) {
            return null;
        }
        if (valor.isBoolean()) {
            return valor.booleanValue() ? 1 : 0;
        }
        if (valor.isIntegralNumber()) {
            return valor.longValue();
        }
        if (valor.isNumber()) {
            return valor.doubleValue();
        }
        String texto = valor.asText();
        if (FECHA_HORA.matcher(texto).matches()) {
            // La caja guarda y compara fechas como texto, siempre en UTC con milisegundos.
            return Tiempo.formatear(OffsetDateTime.parse(texto).toInstant());
        }
        if ("horarios".equals(tabla) && HORA.matcher(texto).matches()) {
            return texto.substring(0, 5);
        }
        return texto;
    }

    private List<Columna> columnas(Connection c, String tabla) throws SQLException {
        List<Columna> lista = columnas.get(tabla);
        if (lista == null) {
            lista = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, type, \"notnull\", dflt_value FROM pragma_table_info(?)")) {
                ps.setString(1, tabla);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lista.add(new Columna(rs.getString(1), rs.getString(2).toUpperCase(java.util.Locale.ROOT),
                                rs.getInt(3) == 1, rs.getString(4) != null));
                    }
                }
            }
            if (lista.isEmpty()) {
                throw new SQLException("La tabla " + tabla + " no existe en la base local");
            }
            columnas.put(tabla, lista);
        }
        return lista;
    }

    private static boolean esDuplicado(SQLException e) {
        String mensaje = String.valueOf(e.getMessage());
        return mensaje.contains("UNIQUE constraint failed");
    }

    private Map<String, Cursor> leerCursores(Connection c) throws SQLException {
        Map<String, Cursor> cursores = new HashMap<>();
        for (String tabla : TablasNube.ORDEN) {
            Optional<String> valor = config.obtener(c, PREFIJO_CURSOR + tabla);
            valor.ifPresent(v -> {
                int separador = v.lastIndexOf('|');
                cursores.put(tabla, new Cursor(v.substring(0, separador), v.substring(separador + 1)));
            });
        }
        return cursores;
    }

    /** Desde lo último que se bajó (o desde el principio). */
    private ObjectNode desde(Cursor cursor) {
        ObjectNode nodo = json.createObjectNode();
        if (cursor == null) {
            return nodo;
        }
        OffsetDateTime ultima = OffsetDateTime.parse(cursor.desde());
        if (ultima.toInstant().isAfter(Instant.now().minus(RECIENTE))) {
            nodo.put("desde", ultima.minus(MARGEN).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            nodo.put("llave", "");
        } else {
            nodo.put("desde", cursor.desde());
            nodo.put("llave", cursor.llave());
        }
        return nodo;
    }

    private ObjectNode ultimoCursor(String tabla, JsonNode filas) {
        JsonNode ultima = filas.get(filas.size() - 1);
        return json.createObjectNode()
                .put("desde", ultima.path("sincronizado_en").asText())
                .put("llave", ultima.path(TablasNube.llave(tabla)).asText());
    }
}
