package com.cremerias.puntoventa.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Habla con Supabase por HTTPS: inicia sesión con la cuenta de la caja (Auth) y usa la API de
 * datos (PostgREST) con esa sesión, así que aplican las reglas de acceso (RLS) de la caja.
 */
public class ClienteSupabase implements Nube {

    private static final Duration ESPERA = Duration.ofSeconds(60);

    private final ConfigNube config;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String token;
    private Instant venceToken = Instant.EPOCH;

    public ClienteSupabase(ConfigNube config) {
        this.config = config;
    }

    public ConfigNube config() {
        return config;
    }

    @Override
    public JsonNode rpc(String funcion, ObjectNode parametros) throws IOException, ErrorNube {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(uri("/rest/v1/rpc/" + funcion))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(parametros)))
                .header("Content-Type", "application/json");
        return leer(conSesion(peticion));
    }

    @Override
    public ArrayNode consultar(String consulta) throws IOException, ErrorNube {
        JsonNode respuesta = leer(conSesion(HttpRequest.newBuilder(uri("/rest/v1/" + consulta)).GET()));
        if (!(respuesta instanceof ArrayNode filas)) {
            throw new ErrorNube(500, "Respuesta inesperada al consultar " + consulta);
        }
        return filas;
    }

    /** Manda la petición con la sesión de la caja; si la sesión venció, entra de nuevo y reintenta una vez. */
    private String conSesion(HttpRequest.Builder peticion) throws IOException, ErrorNube {
        peticion.header("apikey", config.clavePublica()).header("Accept", "application/json").timeout(ESPERA);
        HttpResponse<String> respuesta = enviar(peticion.copy().header("Authorization", "Bearer " + token()).build());
        if (respuesta.statusCode() == 401) {
            token = null;
            respuesta = enviar(peticion.header("Authorization", "Bearer " + token()).build());
        }
        if (respuesta.statusCode() >= 300) {
            throw new ErrorNube(respuesta.statusCode(), mensaje(respuesta.body()));
        }
        return respuesta.body();
    }

    private synchronized String token() throws IOException, ErrorNube {
        if (token != null && Instant.now().isBefore(venceToken.minusSeconds(60))) {
            return token;
        }
        ObjectNode credenciales = json.createObjectNode()
                .put("email", config.correo())
                .put("password", config.contrasena());
        HttpRequest peticion = HttpRequest.newBuilder(uri("/auth/v1/token?grant_type=password"))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(credenciales)))
                .header("apikey", config.clavePublica())
                .header("Content-Type", "application/json")
                .timeout(ESPERA)
                .build();
        HttpResponse<String> respuesta = enviar(peticion);
        if (respuesta.statusCode() >= 300) {
            throw new ErrorNube(respuesta.statusCode(),
                    "No se pudo iniciar sesión con la cuenta de la caja: " + mensaje(respuesta.body()));
        }
        JsonNode sesion = json.readTree(respuesta.body());
        token = sesion.path("access_token").asText(null);
        venceToken = Instant.now().plusSeconds(sesion.path("expires_in").asLong(3600));
        if (token == null) {
            throw new ErrorNube(500, "Supabase no devolvió la sesión de la caja");
        }
        return token;
    }

    private HttpResponse<String> enviar(HttpRequest peticion) throws IOException {
        try {
            return http.send(peticion, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Sincronización interrumpida", e);
        }
    }

    private JsonNode leer(String cuerpo) throws IOException {
        return cuerpo == null || cuerpo.isBlank() ? json.nullNode() : json.readTree(cuerpo);
    }

    private URI uri(String ruta) {
        return URI.create(config.url() + ruta);
    }

    /** El texto del error que manda Supabase (PostgREST usa "message"; Auth, "msg"). */
    private String mensaje(String cuerpo) {
        try {
            JsonNode error = json.readTree(cuerpo);
            for (String campo : new String[]{"message", "msg", "error_description", "error"}) {
                if (error.hasNonNull(campo)) {
                    String detalle = error.path("details").asText("");
                    return error.get(campo).asText() + (detalle.isBlank() ? "" : " (" + detalle + ")");
                }
            }
        } catch (IOException | RuntimeException ignorado) {
            // No era JSON: se devuelve tal cual.
        }
        return cuerpo == null ? "" : cuerpo;
    }
}
