package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.ConfigLocalRepository;

import java.util.Optional;

/** Preferencias de esta terminal guardadas en la base local. */
public class PreferenciasService {

    private final Database database;
    private final ConfigLocalRepository config = new ConfigLocalRepository();

    public PreferenciasService(Database database) {
        this.database = database;
    }

    public Optional<String> ultimoUsuario() {
        return database.con(c -> config.obtener(c, ConfigLocalRepository.ULTIMO_USUARIO));
    }

    public void recordarUsuario(String usuario) {
        database.con(c -> {
            if (usuario == null) {
                config.eliminar(c, ConfigLocalRepository.ULTIMO_USUARIO);
            } else {
                config.guardar(c, ConfigLocalRepository.ULTIMO_USUARIO, usuario);
            }
            return null;
        });
    }

    public boolean temaOscuro() {
        return database.con(c -> config.obtener(c, ConfigLocalRepository.TEMA)).map("oscuro"::equals).orElse(false);
    }

    public void guardarTemaOscuro(boolean oscuro) {
        database.con(c -> {
            config.guardar(c, ConfigLocalRepository.TEMA, oscuro ? "oscuro" : "claro");
            return null;
        });
    }
}
