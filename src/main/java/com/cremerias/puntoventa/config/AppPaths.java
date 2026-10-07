package com.cremerias.puntoventa.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ubicación de los datos locales de la aplicación.
 * Por defecto {@code ~/.cremerias-pos}; se puede cambiar con {@code -Dcremerias.home=/ruta}.
 */
public final class AppPaths {

    private static final String PROPIEDAD_HOME = "cremerias.home";

    private AppPaths() {
    }

    public static Path home() {
        String personalizado = System.getProperty(PROPIEDAD_HOME);
        if (personalizado != null && !personalizado.isBlank()) {
            return Path.of(personalizado);
        }
        return Path.of(System.getProperty("user.home"), ".cremerias-pos");
    }

    public static Path baseDeDatos() {
        return home().resolve("cremerias.db");
    }

    public static Path respaldos() {
        return home().resolve("respaldos");
    }

    public static Path logs() {
        return home().resolve("logs");
    }

    /** Copias que se guardan antes de un cambio grande (no se borran solas como los respaldos). */
    public static Path reserva() {
        return home().resolve("reserva");
    }

    /** Conexión de esta caja con Supabase; si no existe, la caja trabaja solo en local. */
    public static Path configNube() {
        return home().resolve("supabase.properties");
    }

    public static void crearDirectorios() {
        try {
            Files.createDirectories(home());
            Files.createDirectories(respaldos());
            Files.createDirectories(logs());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron crear los directorios de datos en " + home(), e);
        }
    }
}
