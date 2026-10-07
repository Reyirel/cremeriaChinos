package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.repository.SyncOutboxRepository;
import com.cremerias.puntoventa.repository.SyncOutboxRepository.Pendiente;
import com.cremerias.puntoventa.util.Tiempo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sube a Supabase los cambios locales de {@code sync_outbox}.
 *
 * <p>Se manda el estado actual de cada fila (con sus filas hijas: detalle y pagos de una venta,
 * precios de un lote, detalle de un surtido) a {@code subir_cambios}, que aplica el lote completo en
 * una transacción. Si la nube rechaza un lote por sus datos, se parte en mitades hasta aislar el
 * cambio que falla; los demás sí se suben.
 */
class Subida {

    private static final Logger log = LoggerFactory.getLogger(Subida.class);
    static final int LOTE = 200;

    private final Database database;
    private final SyncOutboxRepository outbox = new SyncOutboxRepository();
    private final ObjectMapper json = new ObjectMapper();

    /** Un cambio de la cola con las filas que se mandan por él ({@code {tabla, fila}}). */
    record Cambio(long outboxId, List<ObjectNode> filas) {
    }

    /** Lo que se tomó de la cola: los cambios por mandar y hasta qué id se llegó (0 = la cola está vacía). */
    private record Reclamo(List<Cambio> cambios, long hastaId) {
    }

    Subida(Database database) {
        this.database = database;
    }

    /** @return cuántos cambios se subieron. Los rechazados quedan en la cola con su error. */
    int subir(Nube nube) throws IOException, ErrorNube {
        int subidos = 0;
        long ultimo = 0;
        while (true) {
            long desde = ultimo;
            Reclamo reclamo = database.enTransaccionInmediata(c -> reclamar(c, desde));
            if (reclamo.hastaId() == 0) {
                return subidos;
            }
            ultimo = reclamo.hastaId();
            List<Cambio> lote = reclamo.cambios();
            if (lote.isEmpty()) {
                continue;
            }
            try {
                subidos += enviar(nube, lote);
            } catch (IOException | ErrorNube | RuntimeException e) {
                database.enTransaccion(c -> {
                    outbox.devolver(c, ids(lote));
                    return null;
                });
                throw e;
            }
        }
    }

    private Reclamo reclamar(Connection c, long despuesDe) throws SQLException {
        List<Cambio> cambios = new ArrayList<>();
        List<Long> sinNadaQueSubir = new ArrayList<>();
        List<Pendiente> pendientes = outbox.reclamar(c, despuesDe, LOTE);
        for (Pendiente p : pendientes) {
            if (!TablasNube.seSincroniza(p.tabla())) {
                outbox.marcarError(c, p.id(), "La tabla " + p.tabla() + " no se sincroniza con la nube");
                continue;
            }
            List<ObjectNode> filas = leer(c, p.tabla(), TablasNube.llave(p.tabla()), p.registroId());
            if (filas.isEmpty()) {
                sinNadaQueSubir.add(p.id());
                continue;
            }
            for (TablasNube.Hija hija : TablasNube.HIJAS.getOrDefault(p.tabla(), List.of())) {
                filas.addAll(leer(c, hija.tabla(), hija.columnaPadre(), p.registroId()));
            }
            cambios.add(new Cambio(p.id(), filas));
        }
        // El registro ya no existe en la base local: no hay nada que mandar.
        outbox.marcarEnviados(c, sinNadaQueSubir, Tiempo.ahora());
        return new Reclamo(cambios, pendientes.isEmpty() ? 0 : pendientes.getLast().id());
    }

    /** Filas de {@code tabla} con {@code columna = valor}, como {@code {tabla, fila}}. */
    private List<ObjectNode> leer(Connection c, String tabla, String columna, String valor) throws SQLException {
        List<ObjectNode> filas = new ArrayList<>();
        // tabla y columna vienen de TablasNube, no de datos.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + tabla + " WHERE " + columna + " = ? ORDER BY rowid")) {
            ps.setString(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    ObjectNode fila = json.createObjectNode();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        poner(fila, meta.getColumnName(i), rs.getObject(i));
                    }
                    filas.add(json.createObjectNode().put("tabla", tabla).set("fila", fila));
                }
            }
        }
        return filas;
    }

    private static void poner(ObjectNode fila, String columna, Object valor) {
        switch (valor) {
            case null -> fila.putNull(columna);
            case Integer n -> fila.put(columna, n);
            case Long n -> fila.put(columna, n);
            case Double n -> fila.put(columna, n);
            case Float n -> fila.put(columna, n);
            default -> fila.put(columna, valor.toString());
        }
    }

    /** Manda el lote; si la nube lo rechaza por sus datos, lo parte para subir lo que sí entra. */
    private int enviar(Nube nube, List<Cambio> lote) throws IOException, ErrorNube {
        ArrayNode cambios = json.createArrayNode();
        lote.forEach(cambio -> cambios.addAll(cambio.filas()));
        try {
            nube.rpc("subir_cambios", json.createObjectNode().set("p_cambios", cambios));
        } catch (ErrorNube e) {
            if (!e.esDeLosDatos()) {
                throw e;
            }
            if (lote.size() == 1) {
                Cambio rechazado = lote.getFirst();
                log.warn("La nube rechazó el cambio {} de {}: {}", rechazado.outboxId(),
                        rechazado.filas().getFirst().path("tabla").asText(), e.getMessage());
                database.enTransaccion(c -> {
                    outbox.marcarError(c, rechazado.outboxId(), e.getMessage());
                    return null;
                });
                return 0;
            }
            int mitad = lote.size() / 2;
            return enviar(nube, lote.subList(0, mitad)) + enviar(nube, lote.subList(mitad, lote.size()));
        }
        database.enTransaccion(c -> {
            outbox.marcarEnviados(c, ids(lote), Tiempo.ahora());
            return null;
        });
        return lote.size();
    }

    private static List<Long> ids(List<Cambio> lote) {
        return lote.stream().map(Cambio::outboxId).toList();
    }
}
