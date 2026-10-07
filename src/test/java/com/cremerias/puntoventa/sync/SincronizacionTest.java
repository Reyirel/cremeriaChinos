package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.DatosDemo;
import com.cremerias.puntoventa.service.DatosIniciales;
import com.cremerias.puntoventa.service.ResultadoLogin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SincronizacionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path carpeta;

    private Database database;
    private NubeFalsa nube;

    @BeforeEach
    void preparar() {
        database = new Database(carpeta.resolve("caja.db"));
        new Migraciones(database).aplicar();
        nube = new NubeFalsa();
    }

    private void conDatosDeEjemplo() {
        String sucursal = new DatosIniciales(database, new PasswordHasher(4)).sembrarSiVacia();
        new DatosDemo(database).cargarSiVacio(sucursal);
        database.con(c -> new DispositivoRepository().obtenerOCrear(c, sucursal));
    }

    // ------------------------------------------------------------------
    // Subida
    // ------------------------------------------------------------------

    @Test
    void subeTodoLoEncoladoConSusFilasHijasYLoMarcaComoEnviado() throws Exception {
        conDatosDeEjemplo();
        int encolados = contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL");
        assertTrue(encolados > 0);

        int subidos = new Subida(database).subir(nube);

        assertEquals(encolados, subidos);
        assertEquals(0, contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL OR enviado_en = 'ENVIANDO'"));
        List<JsonNode> enviadas = nube.filasSubidas();
        // Los precios de cada lote viajan con su lote aunque no estén en la cola.
        assertEquals(contar("SELECT COUNT(*) FROM lote_precios"),
                enviadas.stream().filter(f -> f.path("tabla").asText().equals("lote_precios")).count());
        // El hash de la contraseña viaja con el usuario (en la nube va a usuarios_credenciales).
        assertTrue(enviadas.stream().filter(f -> f.path("tabla").asText().equals("usuarios"))
                .allMatch(f -> f.path("fila").path("password_hash").asText().startsWith("$2a$")));
    }

    @Test
    void siLaNubeRechazaUnCambioLosDemasSiSuben() throws Exception {
        conDatosDeEjemplo();
        String rechazado = consultar("SELECT id FROM usuarios WHERE usuario = 'supervisor'");
        nube.rechazar = f -> f.path("fila").path("id").asText().equals(rechazado);
        int encolados = contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL");

        int subidos = new Subida(database).subir(nube);

        assertEquals(encolados - 1, subidos);
        assertEquals(1, contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL AND intentos = 1"
                + " AND ultimo_error = 'rechazado' AND registro_id = '" + rechazado + "'"));
        // Se reintenta en la siguiente vuelta, no en la misma.
        nube.rechazar = f -> false;
        assertEquals(1, new Subida(database).subir(nube));
        assertEquals(0, contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL"));
    }

    @Test
    void sinConexionTodoRegresaALaColaSinContarComoIntento() {
        conDatosDeEjemplo();
        int encolados = contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL");
        nube.sinConexion = true;

        assertThrows(IOException.class, () -> new Subida(database).subir(nube));

        assertEquals(encolados, contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL AND intentos = 0"));
    }

    @Test
    void unCambioHechoMientrasSeSubeNoSePierde() throws Exception {
        conDatosDeEjemplo();
        String producto = consultar("SELECT id FROM productos LIMIT 1");
        // Mientras la nube recibe el lote, alguien cambia el producto en la caja.
        nube.alRecibir = () -> database.con(c -> c.createStatement().executeUpdate(
                "UPDATE productos SET nombre = 'Cambiado', actualizado_en = '2030-01-01T00:00:00.000Z' WHERE id = '"
                        + producto + "'"));

        new Subida(database).subir(nube);

        // El cambio se volvió a encolar y subió con el nombre nuevo.
        assertTrue(nube.filasSubidas().stream().anyMatch(f -> f.path("fila").path("id").asText().equals(producto)
                && f.path("fila").path("nombre").asText().equals("Cambiado")));
        assertEquals(0, contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL"));
    }

    // ------------------------------------------------------------------
    // Bajada
    // ------------------------------------------------------------------

    @Test
    void bajaLoNuevoEnFormatoLocalSinVolverAEncolarlo() throws Exception {
        conDatosDeEjemplo();
        new Subida(database).subir(nube);
        String sucursal = consultar("SELECT id FROM sucursales WHERE es_almacen = 0 LIMIT 1");
        String usuario = UUID.randomUUID().toString();
        String hash = new PasswordHasher(4).hash("nube123".toCharArray());
        nube.fila("usuarios", "id", usuario, "2026-10-07T05:11:03.715558+00:00",
                "sucursal_id", sucursal, "nombre_completo", "Desde la nube", "usuario", "nube",
                "rol", "ADMINISTRADOR", "activo", true, "debe_cambiar_password", false,
                "auth_user_id", UUID.randomUUID().toString(),
                "creado_en", "2026-10-07T05:11:03.7+00:00", "actualizado_en", "2026-10-07T05:11:03+00:00");
        nube.fila("usuarios_credenciales", "usuario_id", usuario, "2026-10-07T05:11:03.715558+00:00",
                "password_hash", hash);
        nube.fila("horarios", "id", UUID.randomUUID().toString(), "2026-10-07T05:11:04+00:00",
                "usuario_id", usuario, "dia_semana", 1, "labora", true, "entrada", "07:30:00", "salida", "15:00:00",
                "creado_en", "2026-10-07T05:11:03+00:00", "actualizado_en", "2026-10-07T05:11:03+00:00");

        int bajadas = new Bajada(database).bajar(nube);

        assertEquals(3, bajadas);
        assertEquals("Desde la nube|1|0|2026-10-07T05:11:03.700Z|2026-10-07T05:11:03.715Z", consultar(
                "SELECT nombre_completo || '|' || activo || '|' || debe_cambiar_password || '|' || creado_en || '|' || sincronizado_en"
                        + " FROM usuarios WHERE id = '" + usuario + "'"));
        assertEquals("07:30|15:00", consultar("SELECT entrada || '|' || salida FROM horarios WHERE usuario_id = '" + usuario + "'"));
        assertEquals(0, contar("SELECT COUNT(*) FROM sync_outbox WHERE enviado_en IS NULL"));
        assertEquals("2026-10-07T05:11:03.715558+00:00|" + usuario,
                consultar("SELECT valor FROM config_local WHERE clave = 'nube.bajada.usuarios'"));
        // Con la contraseña que llegó de la nube ya puede entrar en la caja sin internet.
        AuthService auth = new AuthService(database, new PasswordHasher(4), Clock.systemUTC(),
                consultar("SELECT id FROM dispositivo ORDER BY rowid LIMIT 1"));
        assertInstanceOf(ResultadoLogin.Exitoso.class, auth.iniciarSesion("nube", "nube123".toCharArray(), ModoConexion.ONLINE));
    }

    @Test
    void noPisaUnCambioLocalQueTodaviaNoSube() throws Exception {
        conDatosDeEjemplo();
        new Subida(database).subir(nube);
        String producto = consultar("SELECT id FROM productos LIMIT 1");
        database.con(c -> c.createStatement().executeUpdate(
                "UPDATE productos SET nombre = 'Cambio local' WHERE id = '" + producto + "'"));
        nube.fila("productos", "id", producto, "2026-10-07T05:11:03+00:00", "nombre", "Versión de la nube",
                "unidad", "PZA", "activo", true);

        new Bajada(database).bajar(nube);

        assertEquals("Cambio local", consultar("SELECT nombre FROM productos WHERE id = '" + producto + "'"));
    }

    @Test
    void siUnaTablaTraeVariasPaginasLosHijosLleganDespuesDeSusPadres() throws Exception {
        conDatosDeEjemplo();
        new Subida(database).subir(nube);
        String producto = consultar("SELECT id FROM productos LIMIT 1");
        int categorias = Bajada.PAGINA * 2 + 7;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < categorias; i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            // Muchas con la misma hora del servidor, como cuando llegan en un solo lote.
            nube.fila("categorias", "id", id, "2026-01-07T05:00:00+00:00", "nombre", "Categoría " + i,
                    "orden", i, "activo", true);
        }
        // Un producto que usa la última categoría (que llega en la última página de categorías).
        nube.fila("productos", "id", producto, "2026-01-07T05:00:01+00:00", "nombre", "Con categoría nueva",
                "unidad", "PZA", "activo", true,
                "categoria_id", nube.tablas.get("categorias").getLast().path("id").asText());

        new Bajada(database).bajar(nube);

        assertEquals(categorias, contar("SELECT COUNT(*) FROM categorias WHERE nombre LIKE 'Categoría %'"));
        assertEquals("Con categoría nueva", consultar("SELECT nombre FROM productos WHERE id = '" + producto + "'"));
        // La siguiente vuelta no vuelve a bajar lo mismo: una sola llamada y nada nuevo.
        nube.llamadasBajada = 0;
        assertEquals(0, new Bajada(database).bajar(nube));
        assertEquals(1, nube.llamadasBajada);
        // Con la misma hora del servidor, el orden sigue por id: el cursor queda en el mayor.
        assertEquals(ids.stream().max(String::compareTo).orElseThrow(), consultar("SELECT substr(valor, instr(valor, '|') + 1) FROM config_local"
                + " WHERE clave = 'nube.bajada.categorias'"));
    }

    // ------------------------------------------------------------------
    // Caja nueva
    // ------------------------------------------------------------------

    @Test
    void unaCajaNuevaTomaLosDatosDeLaNubeEnVezDeLosDeEjemplo() throws Exception {
        String almacen = UUID.randomUUID().toString();
        String centro = UUID.randomUUID().toString();
        String admin = UUID.randomUUID().toString();
        String otraCaja = UUID.randomUUID().toString();
        String ts = "2026-10-07T05:00:00+00:00";
        nube.fila("sucursales", "id", almacen, ts, "codigo", "ALMACEN", "nombre", "Almacén de la nube",
                "es_almacen", true, "activo", true, "creado_en", "2026-01-01T00:00:00+00:00",
                "actualizado_en", ts);
        nube.fila("sucursales", "id", centro, ts, "codigo", "CENTRO", "nombre", "Centro", "es_almacen", false,
                "activo", true, "creado_en", "2026-01-02T00:00:00+00:00", "actualizado_en", ts);
        nube.fila("dispositivo", "id", otraCaja, ts, "nombre", "Caja OTRA", "sucursal_id", centro,
                "creado_en", ts, "activo", true);
        nube.fila("usuarios", "id", admin, ts, "sucursal_id", almacen, "nombre_completo", "Admin nube",
                "usuario", "jefa", "rol", "ADMINISTRADOR", "activo", true, "debe_cambiar_password", false,
                "creado_en", ts, "actualizado_en", ts);
        nube.fila("usuarios_credenciales", "usuario_id", admin, ts,
                "password_hash", new PasswordHasher(4).hash("jefa123".toCharArray()));

        assertTrue(CajaNueva.prepararDesdeNube(database, nube));

        // El almacén que crea la migración V5 se cambió por el de la nube.
        assertEquals("Almacén de la nube", consultar("SELECT nombre FROM sucursales WHERE es_almacen = 1"));
        assertEquals(2, contar("SELECT COUNT(*) FROM sucursales"));
        // Esta terminal es la primera caja (no la que bajó) y quedó en la sucursal de la nube.
        String estaCaja = consultar("SELECT id FROM dispositivo ORDER BY rowid LIMIT 1");
        assertTrue(!estaCaja.equals(otraCaja));
        assertEquals(centro, consultar("SELECT sucursal_id FROM dispositivo WHERE id = '" + estaCaja + "'"));
        // Lo único por subir es esta caja.
        assertEquals("dispositivo", consultar("SELECT group_concat(tabla) FROM sync_outbox WHERE enviado_en IS NULL"));
        // No se crean los usuarios de ejemplo y se puede entrar con el de la nube.
        assertNull(new DatosIniciales(database, new PasswordHasher(4)).sembrarSiVacia());
        AuthService auth = new AuthService(database, new PasswordHasher(4), Clock.systemUTC(), estaCaja);
        assertInstanceOf(ResultadoLogin.Exitoso.class, auth.iniciarSesion("jefa", "jefa123".toCharArray(), ModoConexion.ONLINE));
        // Una caja con datos propios no se toca.
        assertTrue(!CajaNueva.prepararDesdeNube(database, nube));
    }

    // ------------------------------------------------------------------

    private int contar(String sql) {
        return database.con(c -> {
            try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private String consultar(String sql) {
        return database.con(c -> {
            try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    /** Supabase en memoria: guarda lo que se sube y sirve bajar_cambios con la misma paginación. */
    private static final class NubeFalsa implements Nube {

        final List<ObjectNode> subidas = new ArrayList<>();
        final Map<String, List<ObjectNode>> tablas = new HashMap<>();
        Predicate<JsonNode> rechazar = f -> false;
        boolean sinConexion;
        Runnable alRecibir;
        int llamadasBajada;

        void fila(String tabla, String llave, String id, String sincronizadoEn, Object... campos) {
            ObjectNode fila = JSON.createObjectNode();
            fila.put(llave, id);
            fila.put("sincronizado_en", sincronizadoEn);
            for (int i = 0; i < campos.length; i += 2) {
                fila.set((String) campos[i], JSON.valueToTree(campos[i + 1]));
            }
            tablas.computeIfAbsent(tabla, t -> new ArrayList<>()).add(fila);
        }

        List<JsonNode> filasSubidas() {
            List<JsonNode> filas = new ArrayList<>();
            subidas.forEach(p -> p.get("p_cambios").forEach(filas::add));
            return filas;
        }

        @Override
        public JsonNode rpc(String funcion, ObjectNode parametros) throws IOException, ErrorNube {
            if (sinConexion) {
                throw new IOException("Sin red");
            }
            return switch (funcion) {
                case "subir_cambios" -> {
                    if (alRecibir != null) {
                        alRecibir.run();
                        alRecibir = null;
                    }
                    for (JsonNode cambio : parametros.get("p_cambios")) {
                        if (rechazar.test(cambio)) {
                            throw new ErrorNube(409, "rechazado");
                        }
                    }
                    subidas.add(parametros);
                    yield IntNode.valueOf(parametros.get("p_cambios").size());
                }
                case "bajar_cambios" -> bajar(parametros);
                default -> throw new ErrorNube(404, "No existe " + funcion);
            };
        }

        private JsonNode bajar(ObjectNode parametros) {
            llamadasBajada++;
            int limite = parametros.get("p_limite").asInt();
            ObjectNode respuesta = JSON.createObjectNode();
            parametros.get("p_cursores").fields().forEachRemaining(entrada -> {
                String tabla = entrada.getKey();
                String llave = TablasNube.llave(tabla);
                JsonNode cursor = entrada.getValue();
                Comparator<JsonNode> orden = Comparator
                        .comparing((JsonNode f) -> OffsetDateTime.parse(f.path("sincronizado_en").asText()))
                        .thenComparing(f -> f.path(llave).asText());
                ArrayNode filas = JSON.createArrayNode();
                tablas.getOrDefault(tabla, List.of()).stream()
                        .filter(f -> !cursor.has("desde") || despues(f, llave, cursor))
                        .sorted(orden)
                        .limit(limite)
                        .forEach(filas::add);
                respuesta.set(tabla, filas);
            });
            return respuesta;
        }

        private static boolean despues(JsonNode fila, String llave, JsonNode cursor) {
            int comparacion = OffsetDateTime.parse(fila.path("sincronizado_en").asText())
                    .compareTo(OffsetDateTime.parse(cursor.path("desde").asText()));
            return comparacion > 0 || comparacion == 0
                    && fila.path(llave).asText().compareTo(cursor.path("llave").asText("")) > 0;
        }

        @Override
        public ArrayNode consultar(String consulta) {
            throw new UnsupportedOperationException();
        }
    }
}
