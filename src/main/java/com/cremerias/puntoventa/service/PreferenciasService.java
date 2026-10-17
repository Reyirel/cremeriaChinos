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

    /** Sin animaciones de transición (para quien las encuentra molestas o en equipos lentos). */
    public boolean movimientoReducido() {
        return marcada(ConfigLocalRepository.MOVIMIENTO_REDUCIDO);
    }

    public void guardarMovimientoReducido(boolean reducido) {
        marcar(ConfigLocalRepository.MOVIMIENTO_REDUCIDO, reducido);
    }

    /** Menú lateral del panel solo con íconos. */
    public boolean menuCompacto() {
        return marcada(ConfigLocalRepository.MENU_COMPACTO);
    }

    public void guardarMenuCompacto(boolean compacto) {
        marcar(ConfigLocalRepository.MENU_COMPACTO, compacto);
    }

    /** Imprimir todo el ticket en negritas (sale más oscuro en térmicas con poco contraste). */
    public boolean ticketEnNegritas() {
        return marcada(ConfigLocalRepository.TICKET_NEGRITAS);
    }

    public void guardarTicketEnNegritas(boolean negritas) {
        marcar(ConfigLocalRepository.TICKET_NEGRITAS, negritas);
    }

    private boolean marcada(String clave) {
        return database.con(c -> config.obtener(c, clave)).map("1"::equals).orElse(false);
    }

    private void marcar(String clave, boolean valor) {
        database.con(c -> {
            config.guardar(c, clave, valor ? "1" : "0");
            return null;
        });
    }
}
