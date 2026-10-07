package com.cremerias.puntoventa.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;

/**
 * Proyecto de Supabase que trae la aplicación ({@code nube.properties}): con él una computadora
 * nueva se conecta sola la primera vez que entra un administrador (ver {@link ConexionCaja}). La
 * clave es la pública: por sí sola no da acceso a nada.
 */
public record ProyectoNube(URI url, String clavePublica) {

    private static final Logger log = LoggerFactory.getLogger(ProyectoNube.class);
    private static final String RECURSO = "/com/cremerias/puntoventa/nube.properties";

    /** El proyecto incluido en la aplicación, si lo trae completo. */
    public static Optional<ProyectoNube> incluido() {
        try (InputStream entrada = ProyectoNube.class.getResourceAsStream(RECURSO)) {
            if (entrada == null) {
                return Optional.empty();
            }
            return leer(new InputStreamReader(entrada, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("No se pudo leer {}: las cajas nuevas no se conectarán solas a la nube", RECURSO, e);
            return Optional.empty();
        }
    }

    static Optional<ProyectoNube> leer(Reader lector) throws IOException {
        Properties p = new Properties();
        p.load(lector);
        String url = p.getProperty("url", "").strip();
        String clave = p.getProperty("clave_publica", "").strip();
        if (url.isEmpty() || clave.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProyectoNube(URI.create(url.replaceAll("/+$", "")), clave));
    }
}
