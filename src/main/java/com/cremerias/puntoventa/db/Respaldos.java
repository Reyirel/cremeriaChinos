package com.cremerias.puntoventa.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copias de seguridad automáticas de la base local, para no depender solo de la nube.
 * Se crea una al iniciar si la última tiene más de un día y se conservan las más recientes.
 */
public class Respaldos {

    private static final Logger log = LoggerFactory.getLogger(Respaldos.class);
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Duration FRECUENCIA = Duration.ofHours(24);
    private static final int MAXIMO = 15;

    private final Database database;
    private final Path carpeta;

    public Respaldos(Database database, Path carpeta) {
        this.database = database;
        this.carpeta = carpeta;
    }

    public void respaldarSiCorresponde() {
        try {
            List<Path> existentes = listar();
            if (!existentes.isEmpty()) {
                Instant ultimo = Files.getLastModifiedTime(existentes.getFirst()).toInstant();
                if (Duration.between(ultimo, Instant.now()).compareTo(FRECUENCIA) < 0) {
                    return;
                }
            }
            respaldar();
            depurar();
        } catch (Exception e) {
            // Un respaldo fallido no debe impedir que la caja opere.
            log.warn("No se pudo crear el respaldo automático", e);
        }
    }

    public Path respaldar() {
        Path destino = carpeta.resolve("cremerias_" + LocalDateTime.now().format(FORMATO) + ".db");
        database.con(conexion -> {
            try (Statement st = conexion.createStatement()) {
                st.execute("VACUUM INTO '" + destino.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        log.info("Respaldo creado en {}", destino);
        return destino;
    }

    private List<Path> listar() throws IOException {
        if (!Files.isDirectory(carpeta)) {
            return List.of();
        }
        try (Stream<Path> archivos = Files.list(carpeta)) {
            return archivos
                    .filter(p -> p.getFileName().toString().matches("cremerias_\\d{8}_\\d{6}\\.db"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
    }

    private void depurar() throws IOException {
        List<Path> existentes = listar();
        for (Path viejo : existentes.subList(Math.min(MAXIMO, existentes.size()), existentes.size())) {
            Files.deleteIfExists(viejo);
        }
    }
}
