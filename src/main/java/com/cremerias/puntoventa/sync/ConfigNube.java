package com.cremerias.puntoventa.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Properties;

/**
 * Conexión de esta caja con Supabase, leída de {@code supabase.properties} en la carpeta de datos:
 *
 * <pre>
 * url=https://xxxx.supabase.co
 * clave_publica=sb_publishable_...
 * correo=caja-1234@...
 * contrasena=...
 * </pre>
 *
 * La cuenta es la de la caja (la crea el administrador en Supabase), no la de una persona. La clave
 * es la pública: la secreta nunca va en una caja.
 */
public record ConfigNube(URI url, String clavePublica, String correo, String contrasena) {

    private static final Logger log = LoggerFactory.getLogger(ConfigNube.class);

    public static Optional<ConfigNube> cargar(Path archivo) {
        if (!Files.exists(archivo)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader lector = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            p.load(lector);
        } catch (IOException e) {
            log.warn("No se pudo leer {}: la caja trabajará solo en local", archivo, e);
            return Optional.empty();
        }
        String url = valor(p, "url");
        String clave = valor(p, "clave_publica");
        String correo = valor(p, "correo");
        String contrasena = valor(p, "contrasena");
        if (url == null || clave == null || correo == null || contrasena == null) {
            log.warn("{} incompleto (url, clave_publica, correo y contrasena): la caja trabajará solo en local", archivo);
            return Optional.empty();
        }
        return Optional.of(new ConfigNube(URI.create(url.replaceAll("/+$", "")), clave, correo, contrasena));
    }

    /**
     * Guarda la conexión (lo hace la app al conectar la caja). Lleva la contraseña de la cuenta de
     * la caja: donde se puede, solo la lee el usuario de la computadora.
     */
    public void guardar(Path archivo) throws IOException {
        Properties p = new Properties();
        p.setProperty("url", url.toString());
        p.setProperty("clave_publica", clavePublica);
        p.setProperty("correo", correo);
        p.setProperty("contrasena", contrasena);
        Files.createDirectories(archivo.toAbsolutePath().getParent());
        Path temporal = archivo.resolveSibling(archivo.getFileName() + ".tmp");
        try (Writer escritor = Files.newBufferedWriter(temporal, StandardCharsets.UTF_8)) {
            p.store(escritor, "Conexion de esta caja con Supabase (la creo la app al conectarla)");
        }
        try {
            Files.setPosixFilePermissions(temporal, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // En Windows la carpeta del usuario ya es privada.
        }
        Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String valor(Properties p, String clave) {
        String v = p.getProperty(clave);
        return v == null || v.isBlank() ? null : v.strip();
    }

    @Override
    public String toString() {
        // Sin la contraseña, por si termina en un log.
        return "ConfigNube[" + url + ", " + correo + "]";
    }
}
