package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.util.Cantidades;
import com.cremerias.puntoventa.util.Dinero;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Inventario del almacén: existencias por lote, reabastecimiento y merma. */
public class AlmacenService {

    public record Existencia(ProductoCatalogo producto, BigDecimal existencia, int lotesConExistencia,
                             long costoActualCentavos, long valorCentavos) {
    }

    /** Parte de una salida surtida de un lote (primero el más antiguo). */
    public record SalidaLote(LoteRepository.Lote lote, BigDecimal cantidad, long costoCentavos) {
    }

    private final Database database;
    private final String dispositivoId;
    private final String almacenId;
    private final LoteRepository lotes = new LoteRepository();
    private final ProductoRepository productos = new ProductoRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();

    public AlmacenService(Database database, String dispositivoId, String almacenId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
        this.almacenId = almacenId;
    }

    public String almacenId() {
        return almacenId;
    }

    public List<Existencia> existencias() {
        return database.con(c -> {
            Map<String, List<LoteRepository.Lote>> porProducto = new HashMap<>();
            for (LoteRepository.Lote l : lotes.deSucursal(c, almacenId)) {
                porProducto.computeIfAbsent(l.productoId(), k -> new ArrayList<>()).add(l);
            }
            List<Existencia> lista = new ArrayList<>();
            for (ProductoCatalogo p : productos.listar(c)) {
                BigDecimal existencia = BigDecimal.ZERO;
                int conExistencia = 0;
                long costoActual = 0;
                long valor = 0;
                for (LoteRepository.Lote l : porProducto.getOrDefault(p.id(), List.of())) {
                    existencia = existencia.add(l.existencia());
                    if (l.existencia().signum() > 0) {
                        if (conExistencia == 0) {
                            costoActual = l.costoUnitarioCentavos();
                        }
                        conExistencia++;
                        valor += Dinero.importe(l.costoUnitarioCentavos(), l.existencia());
                    }
                }
                lista.add(new Existencia(p, existencia, conExistencia, costoActual, valor));
            }
            return lista;
        });
    }

    public List<LoteRepository.Lote> lotes(String productoId, String ubicacionId) {
        return database.con(c -> lotes.deProducto(c, productoId, ubicacionId));
    }

    public BigDecimal existencia(String productoId, String ubicacionId) {
        return database.con(c -> lotes.existencia(c, productoId, ubicacionId));
    }

    /** Entrada de mercancía al almacén: crea un lote nuevo con su costo. Devuelve el folio. */
    public String reabastecer(ProductoCatalogo producto, BigDecimal cantidad, long costoUnitario, String notas,
                              Usuario quien) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero.");
        }
        if (costoUnitario < 0) {
            throw new IllegalArgumentException("El costo no puede ser negativo.");
        }
        return database.enTransaccion(c -> {
            String folio = bitacora.siguienteFolio(c, AccionBitacora.ENTRADA_ALMACEN.prefijo(), dispositivoId);
            String lote = lotes.insertar(c, folio, producto.id(), almacenId, "ENTRADA", null, cantidad, costoUnitario,
                    quien.id());
            inventario.registrar(c, producto.id(), almacenId, lote, "ENTRADA", cantidad, lote, quien.id(), notas);
            return bitacora.registrar(c, AccionBitacora.ENTRADA_ALMACEN, quien, dispositivoId, "lotes", lote,
                    producto.nombre() + ": " + Cantidades.formatear(producto.unidad(), cantidad) + " a "
                            + Dinero.formatear(costoUnitario) + " por " + (producto.unidad().esGranel() ? "kg" : "pieza")
                            + " = " + Dinero.formatear(Dinero.importe(costoUnitario, cantidad))
                            + (notas == null || notas.isBlank() ? "" : " · " + notas.strip()), folio);
        });
    }

    /** Merma (solo productos sujetos a merma) en el almacén o una sucursal. Devuelve el folio. */
    public String registrarMerma(ProductoCatalogo producto, Sucursal ubicacion, BigDecimal cantidad, String motivo,
                                 Usuario quien) {
        if (!producto.sujetoMerma()) {
            throw new IllegalStateException(producto.nombre() + " no está marcado como sujeto a merma.");
        }
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new IllegalArgumentException("La cantidad debe ser mayor a cero.");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new IllegalArgumentException("Escribe el motivo de la merma.");
        }
        return database.enTransaccion(c -> {
            List<SalidaLote> salidas = salidaPeps(c, producto, ubicacion.id(), cantidad);
            long costo = salidas.stream().mapToLong(SalidaLote::costoCentavos).sum();
            String folio = bitacora.siguienteFolio(c, AccionBitacora.MERMA.prefijo(), dispositivoId);
            String id = Ids.nuevo();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO mermas (id, folio, producto_id, sucursal_id, cantidad, costo_centavos, motivo, usuario_id, fecha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, id);
                ps.setString(2, folio);
                ps.setString(3, producto.id());
                ps.setString(4, ubicacion.id());
                ps.setBigDecimal(5, cantidad);
                ps.setLong(6, costo);
                ps.setString(7, motivo.strip());
                ps.setString(8, quien.id());
                ps.setString(9, Tiempo.ahora());
                ps.executeUpdate();
            }
            for (SalidaLote s : salidas) {
                inventario.registrar(c, producto.id(), ubicacion.id(), s.lote().id(), "MERMA", s.cantidad().negate(), id,
                        quien.id(), motivo.strip());
            }
            return bitacora.registrar(c, AccionBitacora.MERMA, quien, dispositivoId, "mermas", id,
                    producto.nombre() + " en " + ubicacion.nombre() + ": " + Cantidades.formatear(producto.unidad(), cantidad)
                            + " (costo " + Dinero.formatear(costo) + ") · " + motivo.strip(), folio);
        });
    }

    /**
     * Toma la cantidad de los lotes más antiguos primero. Falla si no hay suficiente existencia.
     * Se usa en la merma y al surtir sucursales.
     */
    List<SalidaLote> salidaPeps(Connection c, ProductoCatalogo producto, String ubicacionId, BigDecimal cantidad)
            throws SQLException {
        List<SalidaLote> salidas = new ArrayList<>();
        BigDecimal restante = cantidad;
        BigDecimal disponible = BigDecimal.ZERO;
        for (LoteRepository.Lote lote : lotes.deProducto(c, producto.id(), ubicacionId)) {
            if (lote.existencia().signum() <= 0) {
                continue;
            }
            disponible = disponible.add(lote.existencia());
            if (restante.signum() <= 0) {
                continue;
            }
            BigDecimal toma = lote.existencia().min(restante);
            salidas.add(new SalidaLote(lote, toma, Dinero.importe(lote.costoUnitarioCentavos(), toma)));
            restante = restante.subtract(toma);
        }
        if (restante.signum() > 0) {
            throw new IllegalStateException("No hay suficiente " + producto.nombre() + ": hay "
                    + Cantidades.formatear(producto.unidad(), disponible.setScale(3, RoundingMode.HALF_UP)) + ".");
        }
        return salidas;
    }
}
