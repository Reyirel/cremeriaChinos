package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.OrdenRepository;

import java.util.List;

/**
 * Órdenes de reabastecimiento que generan las sucursales al llegar a su mínimo.
 * Se aprueban surtiéndolas ({@link SurtidoService#registrar}) o se rechazan.
 */
public class OrdenService {

    private final Database database;
    private final String dispositivoId;
    private final OrdenRepository ordenes = new OrdenRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public OrdenService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public List<OrdenRepository.Orden> pendientes() {
        return database.con(c -> ordenes.listar(c, "PENDIENTE", 500));
    }

    public List<OrdenRepository.Orden> atendidas() {
        return database.con(c -> ordenes.listar(c, null, 300));
    }

    public int contarPendientes() {
        return database.con(ordenes::contarPendientes);
    }

    public String rechazar(OrdenRepository.Orden orden, String motivo, Usuario quien) {
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("Escribe el motivo del rechazo.");
        }
        return database.enTransaccion(c -> {
            ordenes.resolver(c, orden.id(), "RECHAZADA", quien.id(), motivo.strip(), null);
            return bitacora.registrar(c, AccionBitacora.ORDEN_RECHAZADA, quien, dispositivoId,
                    "ordenes_reabastecimiento", orden.id(), "Orden " + orden.folio() + " de " + orden.sucursal()
                            + " (" + orden.producto() + "): " + motivo.strip());
        });
    }
}
