package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.BitacoraRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Consulta del historial de acciones con folio. */
public class BitacoraService {

    private final Database database;
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public BitacoraService(Database database) {
        this.database = database;
    }

    public List<BitacoraRepository.Registro> buscar(LocalDate desde, LocalDate hasta, String texto, String prefijo) {
        ZoneId zona = ZoneId.systemDefault();
        return database.con(c -> bitacora.buscar(c, desde.atStartOfDay(zona).toInstant(),
                hasta.plusDays(1).atStartOfDay(zona).toInstant(), texto, prefijo, 2000));
    }
}
