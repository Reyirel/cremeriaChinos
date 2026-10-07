package com.cremerias.puntoventa.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Conecta esta computadora con la nube la primera vez, con el usuario y la contraseña de un
 * administrador: la función {@code vincular-caja} de Supabase crea la cuenta de la caja, la registra
 * con su id local y devuelve con qué datos sincronizarse. Así no hay que crear cuentas ni copiar
 * {@code supabase.properties} a mano.
 */
public final class ConexionCaja {

    static final String FUNCION = "/functions/v1/vincular-caja";
    private static final Duration ESPERA = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private ConexionCaja() {
    }

    /**
     * @return la conexión de esta caja, lista para guardarse en {@code supabase.properties}
     * @throws ErrorNube 401 usuario o contraseña incorrectos, 403 no es administrador, 429
     *                   demasiados intentos; el mensaje se puede mostrar tal cual
     * @throws IOException sin internet o sin respuesta de Supabase
     */
    public static ConfigNube conectar(ProyectoNube proyecto, String usuario, char[] contrasena, String cajaId,
                                      String nombreCaja) throws IOException, ErrorNube {
        ObjectNode cuerpo = JSON.createObjectNode()
                .put("usuario", usuario.strip())
                .put("contrasena", new String(contrasena))
                .put("caja_id", cajaId)
                .put("nombre", nombreCaja);
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(proyecto.url() + FUNCION))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo)))
                .header("apikey", proyecto.clavePublica())
                .header("Content-Type", "application/json")
                .timeout(ESPERA)
                .build();
        HttpResponse<String> respuesta;
        try {
            respuesta = HTTP.send(peticion, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Conexión con la nube interrumpida", e);
        }
        if (respuesta.statusCode() >= 300) {
            throw new ErrorNube(respuesta.statusCode(), ClienteSupabase.mensaje(respuesta.body()));
        }
        JsonNode cuenta = JSON.readTree(respuesta.body());
        String correo = cuenta.path("correo").asText("");
        String clave = cuenta.path("contrasena").asText("");
        if (correo.isBlank() || clave.isBlank()) {
            throw new ErrorNube(500, "Supabase no devolvió la cuenta de la caja");
        }
        return new ConfigNube(proyecto.url(), proyecto.clavePublica(), correo, clave);
    }
}
