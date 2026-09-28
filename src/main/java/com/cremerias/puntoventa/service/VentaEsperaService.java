package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.LineaVenta;
import com.cremerias.puntoventa.model.VentaEspera;
import com.cremerias.puntoventa.repository.VentaEsperaRepository;
import com.cremerias.puntoventa.repository.VentaEsperaRepository.Renglon;

import java.util.List;
import java.util.Optional;

/**
 * Ventas en espera (el cliente olvidó algo y se atiende al siguiente) y autoguardado
 * de la venta en curso para recuperarla si la aplicación se cierra de golpe.
 */
public class VentaEsperaService {

    private final Database database;
    private final VentaEsperaRepository repo = new VentaEsperaRepository();

    public VentaEsperaService(Database database) {
        this.database = database;
    }

    public void ponerEnEspera(String usuarioId, String nota, List<LineaVenta> lineas) {
        database.enTransaccion(c -> {
            repo.guardar(c, "ESPERA", usuarioId, nota, renglones(lineas), total(lineas));
            repo.eliminarAutoguardado(c, usuarioId);
            return null;
        });
    }

    public List<VentaEspera> listar() {
        return database.con(repo::listarEspera);
    }

    public int contar() {
        return database.con(repo::contarEspera);
    }

    /** Devuelve los renglones y elimina la venta de la lista de espera. */
    public List<Renglon> recuperar(String id) {
        return database.enTransaccion(c -> {
            List<Renglon> renglones = repo.renglones(c, id);
            repo.eliminar(c, id);
            return renglones;
        });
    }

    public void descartar(String id) {
        database.con(c -> {
            repo.eliminar(c, id);
            return null;
        });
    }

    public void autoguardar(String usuarioId, List<LineaVenta> lineas) {
        database.enTransaccion(c -> {
            repo.eliminarAutoguardado(c, usuarioId);
            if (!lineas.isEmpty()) {
                repo.guardar(c, "AUTOGUARDADO", usuarioId, null, renglones(lineas), total(lineas));
            }
            return null;
        });
    }

    public Optional<List<Renglon>> autoguardado(String usuarioId) {
        return database.con(c -> {
            Optional<String> id = repo.autoguardado(c, usuarioId);
            return id.isPresent() ? Optional.of(repo.renglones(c, id.get())) : Optional.empty();
        });
    }

    private static List<Renglon> renglones(List<LineaVenta> lineas) {
        return lineas.stream()
                .map(l -> new Renglon(l.producto().productoId(), l.producto().id(), l.getCantidad()))
                .toList();
    }

    private static long total(List<LineaVenta> lineas) {
        return lineas.stream().mapToLong(LineaVenta::getImporte).sum();
    }
}
