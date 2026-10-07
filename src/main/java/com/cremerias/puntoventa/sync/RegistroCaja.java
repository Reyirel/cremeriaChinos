package com.cremerias.puntoventa.sync;

import com.cremerias.puntoventa.config.AppPaths;
import com.cremerias.puntoventa.db.Database;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Liga esta caja con su cuenta de la nube. La caja se registra con el mismo id que tiene en su base
 * local; si la cuenta ya es de otra caja o de una persona, no se sincroniza nada.
 */
class RegistroCaja {

    private static final Logger log = LoggerFactory.getLogger(RegistroCaja.class);

    private final Database database;
    private final String dispositivoId;
    private final ObjectMapper json = new ObjectMapper();
    private boolean registrada;

    RegistroCaja(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    void asegurar(Nube nube, String cuenta) throws IOException, ErrorNube {
        if (registrada) {
            return;
        }
        for (JsonNode perfil : nube.rpc("mi_perfil", json.createObjectNode())) {
            String tipo = perfil.path("tipo").asText();
            if ("CAJA".equals(tipo) && dispositivoId.equals(perfil.path("id").asText())) {
                registrada = true;
                return;
            }
            throw new ErrorNube(0, "La cuenta " + cuenta + " ya está ligada a "
                    + ("CAJA".equals(tipo) ? "otra caja (" + perfil.path("nombre").asText() + ")" : "una persona")
                    + ": cada caja necesita su propia cuenta.");
        }
        revisarQueSeaLaMismaEmpresa(nube);
        ObjectNode caja = database.con(c -> {
            try (var ps = c.prepareStatement("""
                    SELECT d.nombre,
                           COALESCE(d.sucursal_id, (SELECT id FROM sucursales WHERE es_almacen = 1 LIMIT 1))
                    FROM dispositivo d WHERE d.id = ?""")) {
                ps.setString(1, dispositivoId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new java.sql.SQLException("No existe la caja " + dispositivoId + " en la base local");
                    }
                    return json.createObjectNode()
                            .put("p_id", dispositivoId)
                            .put("p_nombre", rs.getString(1))
                            .put("p_sucursal_id", rs.getString(2));
                }
            }
        });
        nube.rpc("registrar_caja", caja);
        log.info("{} registrada en la nube con la cuenta {}", caja.path("p_nombre").asText(), cuenta);
        registrada = true;
    }

    /**
     * Una instalación que ya tenía datos propios (otro almacén central, usuarios y catálogo de
     * ejemplo) no se mezcla con la nube: chocaría con su almacén y subiría datos que no son de ahí.
     */
    private void revisarQueSeaLaMismaEmpresa(Nube nube) throws IOException, ErrorNube {
        ObjectNode parametros = json.createObjectNode();
        parametros.set("p_cursores", json.createObjectNode().set("sucursales", json.createObjectNode()));
        parametros.put("p_limite", Bajada.PAGINA);
        String almacenNube = null;
        for (JsonNode sucursal : nube.rpc("bajar_cambios", parametros).path("sucursales")) {
            if (sucursal.path("es_almacen").asBoolean()) {
                almacenNube = sucursal.path("id").asText();
            }
        }
        String almacenLocal = database.con(c -> {
            try (var ps = c.prepareStatement("SELECT id FROM sucursales WHERE es_almacen = 1 LIMIT 1");
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
        if (almacenNube != null && almacenLocal != null && !almacenNube.equals(almacenLocal)) {
            throw new ErrorNube(0, "Esta caja tiene datos propios que no son los de la nube (otro almacén central),"
                    + " así que no se sincroniza. Para conectarla: cierra la app, borra " + AppPaths.configNube()
                    + " y vuelve a abrirla. Al entrar un administrador de la nube, la caja se conecta y toma todo de"
                    + " la nube (lo que tenía queda en una copia en " + AppPaths.reserva() + ").");
        }
    }
}
