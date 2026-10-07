package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.DatosIniciales;
import com.cremerias.puntoventa.service.ResultadoLogin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conectar una computadora a la nube sin configurar nada a mano. */
class ConexionCajaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TS = "2026-10-07T05:00:00+00:00";

    @TempDir
    Path carpeta;

    @Test
    void laAppTraeElProyectoDeSupabase() throws Exception {
        assertTrue(ProyectoNube.incluido().isPresent(), "Falta nube.properties con url y clave_publica");
        assertEquals(Optional.of(new ProyectoNube(URI.create("https://x.supabase.co"), "sb_publishable_abc")),
                ProyectoNube.leer(new StringReader("url=https://x.supabase.co/\nclave_publica=sb_publishable_abc\n")));
        assertTrue(ProyectoNube.leer(new StringReader("url=https://x.supabase.co\n")).isEmpty());
    }

    @Test
    void laConexionGuardadaSeVuelveALeer() throws Exception {
        Path archivo = carpeta.resolve("datos").resolve("supabase.properties");
        ConfigNube config = new ConfigNube(URI.create("https://x.supabase.co"), "sb_publishable_abc",
                "caja-1@cremerias.local", "c:l=a#ve!");

        config.guardar(archivo);

        assertEquals(Optional.of(config), ConfigNube.cargar(archivo));
    }

    @Test
    void sinInternetNoSeConectaNiCambiaNada() {
        ProyectoNube inalcanzable = new ProyectoNube(URI.create("http://127.0.0.1:1"), "sb_publishable_abc");

        assertThrows(IOException.class, () -> ConexionCaja.conectar(inalcanzable, "jefa", "jefa123".toCharArray(),
                UUID.randomUUID().toString(), "Caja"));
    }

    @Test
    void unaBaseSinUsuariosNoNecesitaCopia() throws Exception {
        assertTrue(CajaNueva.guardarCopiaSiTieneDatos(baseNueva("vacia.db"), carpeta.resolve("reserva")).isEmpty());
    }

    @Test
    void conectarUnaCajaConDatosPropiosLosCambiaPorLosDeLaNubeYConservaSuId() throws Exception {
        Database database = baseNueva("caja.db");
        PasswordHasher hasher = new PasswordHasher(4);
        // La computadora trabajó sin nube: tiene los usuarios de ejemplo (admin/admin123).
        String sucursalLocal = new DatosIniciales(database, hasher).sembrarSiVacia();
        String cajaId = database.enTransaccion(c -> new DispositivoRepository().obtenerOCrear(c, sucursalLocal));

        Path copia = CajaNueva.guardarCopiaSiTieneDatos(database, carpeta.resolve("reserva")).orElseThrow();

        // Lo que hay en la nube, con esta caja ya registrada por la función vincular-caja.
        NubeFija nube = new NubeFija();
        String almacen = UUID.randomUUID().toString();
        String centro = UUID.randomUUID().toString();
        String jefa = UUID.randomUUID().toString();
        nube.fila("sucursales").put("id", almacen).put("codigo", "ALMACEN").put("nombre", "Almacén de la nube")
                .put("es_almacen", true).put("activo", true).put("creado_en", "2026-01-01T00:00:00+00:00")
                .put("actualizado_en", TS);
        nube.fila("sucursales").put("id", centro).put("codigo", "CENTRO").put("nombre", "Centro")
                .put("es_almacen", false).put("activo", true).put("creado_en", "2026-01-02T00:00:00+00:00")
                .put("actualizado_en", TS);
        nube.fila("dispositivo").put("id", cajaId).put("nombre", "Caja de la oficina").put("sucursal_id", centro)
                .put("activo", true).put("creado_en", TS);
        nube.fila("usuarios").put("id", jefa).put("sucursal_id", almacen).put("nombre_completo", "Admin nube")
                .put("usuario", "jefa").put("rol", "ADMINISTRADOR").put("activo", true)
                .put("debe_cambiar_password", false).put("creado_en", TS).put("actualizado_en", TS);
        nube.fila("usuarios_credenciales").put("usuario_id", jefa)
                .put("password_hash", hasher.hash("jefa123".toCharArray()));

        assertTrue(CajaNueva.reemplazarConLaNube(database, nube));

        // Esta terminal sigue siendo la misma caja (la que se registró en la nube).
        assertEquals(cajaId, consultar(database, "SELECT id FROM dispositivo ORDER BY rowid LIMIT 1"));
        assertEquals(centro, consultar(database, "SELECT sucursal_id FROM dispositivo WHERE id = '" + cajaId + "'"));
        // Los datos de ejemplo se cambiaron por los de la nube y se entra con el usuario de la nube.
        assertEquals("jefa", consultar(database, "SELECT group_concat(usuario) FROM usuarios"));
        assertEquals("Almacén de la nube", consultar(database, "SELECT nombre FROM sucursales WHERE es_almacen = 1"));
        AuthService auth = new AuthService(database, hasher, Clock.systemUTC(), cajaId);
        assertInstanceOf(ResultadoLogin.Exitoso.class,
                auth.iniciarSesion("jefa", "jefa123".toCharArray(), ModoConexion.ONLINE));
        // Lo que tenía antes quedó en la copia.
        assertTrue(Files.size(copia) > 0);
        assertEquals("admin", consultar(new Database(copia), "SELECT usuario FROM usuarios WHERE usuario = 'admin'"));
    }

    private Database baseNueva(String nombre) {
        Database database = new Database(carpeta.resolve(nombre));
        new Migraciones(database).aplicar();
        return database;
    }

    private static String consultar(Database database, String sql) {
        return database.con(c -> {
            try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    /** Nube falsa: {@code bajar_cambios} devuelve todas las filas de cada tabla la primera vez. */
    private static final class NubeFija implements Nube {

        private final Map<String, ArrayNode> tablas = new HashMap<>();

        ObjectNode fila(String tabla) {
            ObjectNode fila = JSON.createObjectNode().put("sincronizado_en", TS);
            tablas.computeIfAbsent(tabla, t -> JSON.createArrayNode()).add(fila);
            return fila;
        }

        @Override
        public JsonNode rpc(String funcion, ObjectNode parametros) {
            ObjectNode resultado = JSON.createObjectNode();
            parametros.path("p_cursores").fields().forEachRemaining(cursor -> resultado.set(cursor.getKey(),
                    cursor.getValue().has("desde")
                            ? JSON.createArrayNode()
                            : tablas.getOrDefault(cursor.getKey(), JSON.createArrayNode())));
            return resultado;
        }

        @Override
        public ArrayNode consultar(String consulta) {
            return JSON.createArrayNode();
        }
    }
}
