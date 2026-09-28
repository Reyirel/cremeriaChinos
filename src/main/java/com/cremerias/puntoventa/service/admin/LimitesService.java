package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.AccionBitacora;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.BitacoraRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.service.ReabastecimientoAutomatico;
import com.cremerias.puntoventa.util.Ids;
import com.cremerias.puntoventa.util.Tiempo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mínimos y máximos de cada producto en cada sucursal (los define el administrador). */
public class LimitesService {

    public record Fila(ProductoCatalogo producto, BigDecimal existencia, BigDecimal minimo, BigDecimal maximo) {
        public boolean enMinimo() {
            return maximo.signum() > 0 && existencia.compareTo(minimo) <= 0;
        }
    }

    public record Cambio(ProductoCatalogo producto, BigDecimal minimo, BigDecimal maximo) {
    }

    /** Resultado de guardar: folio y cuántas órdenes se generaron de inmediato. */
    public record Guardado(String folio, int ordenesGeneradas) {
    }

    private final Database database;
    private final String dispositivoId;
    private final ProductoRepository productos = new ProductoRepository();
    private final BitacoraRepository bitacora = new BitacoraRepository();
    private final ReabastecimientoAutomatico reabastecimiento = new ReabastecimientoAutomatico();

    public LimitesService(Database database, String dispositivoId) {
        this.database = database;
        this.dispositivoId = dispositivoId;
    }

    public List<Fila> listar(String sucursalId) {
        return database.con(c -> {
            Map<String, BigDecimal[]> limites = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT producto_id, minimo, maximo FROM limites_sucursal WHERE sucursal_id = ?")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        limites.put(rs.getString(1), new BigDecimal[]{decimal(rs.getDouble(2)), decimal(rs.getDouble(3))});
                    }
                }
            }
            Map<String, BigDecimal> existencias = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT producto_id, existencia FROM v_existencias WHERE sucursal_id = ?")) {
                ps.setString(1, sucursalId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existencias.put(rs.getString(1), decimal(rs.getDouble(2)));
                    }
                }
            }
            List<Fila> filas = new ArrayList<>();
            for (ProductoCatalogo p : productos.listar(c)) {
                if (!p.activo()) {
                    continue;
                }
                BigDecimal[] l = limites.getOrDefault(p.id(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                filas.add(new Fila(p, existencias.getOrDefault(p.id(), BigDecimal.ZERO), l[0], l[1]));
            }
            return filas;
        });
    }

    public Guardado guardar(Sucursal sucursal, List<Cambio> cambios, Usuario quien) {
        if (cambios.isEmpty()) {
            throw new IllegalArgumentException("No hay cambios que guardar.");
        }
        for (Cambio c : cambios) {
            if (c.minimo().signum() < 0 || c.maximo().signum() < 0) {
                throw new IllegalArgumentException(c.producto().nombre() + ": no se permiten valores negativos.");
            }
            if (c.maximo().compareTo(c.minimo()) < 0) {
                throw new IllegalArgumentException(c.producto().nombre() + ": el máximo debe ser mayor o igual al mínimo.");
            }
        }
        return database.enTransaccion(c -> {
            String ahora = Tiempo.ahora();
            for (Cambio cambio : cambios) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO limites_sucursal (id, sucursal_id, producto_id, minimo, maximo, creado_en, actualizado_en)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (sucursal_id, producto_id) DO UPDATE SET
                            minimo = excluded.minimo, maximo = excluded.maximo, actualizado_en = excluded.actualizado_en""")) {
                    ps.setString(1, Ids.nuevo());
                    ps.setString(2, sucursal.id());
                    ps.setString(3, cambio.producto().id());
                    ps.setBigDecimal(4, cambio.minimo());
                    ps.setBigDecimal(5, cambio.maximo());
                    ps.setString(6, ahora);
                    ps.setString(7, ahora);
                    ps.executeUpdate();
                }
            }
            String folio = bitacora.registrar(c, AccionBitacora.LIMITES_ACTUALIZADOS, quien, dispositivoId,
                    "limites_sucursal", sucursal.id(), sucursal.nombre() + ": " + cambios.size() + " producto(s)");
            int ordenes = reabastecimiento.revisarSucursal(c, sucursal.id(), quien, dispositivoId);
            return new Guardado(folio, ordenes);
        });
    }

    private static BigDecimal decimal(double valor) {
        return BigDecimal.valueOf(valor).setScale(3, RoundingMode.HALF_UP);
    }
}
