package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.model.Disponibilidad;
import com.cremerias.puntoventa.model.Presentacion;
import com.cremerias.puntoventa.model.Unidad;
import com.cremerias.puntoventa.repository.InventarioRepository;
import com.cremerias.puntoventa.repository.LoteRepository;
import com.cremerias.puntoventa.repository.ProductoRepository;
import com.cremerias.puntoventa.util.CodigoBarras;
import com.cremerias.puntoventa.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Catálogo de ejemplo para probar el sistema en una instalación nueva: productos con sus
 * presentaciones, existencia en el almacén y un lote inicial con precios en la sucursal.
 * Solo se carga si no hay ningún producto registrado.
 */
public class DatosDemo {

    private static final Logger log = LoggerFactory.getLogger(DatosDemo.class);

    private final Database database;
    private final ProductoRepository productos = new ProductoRepository();
    private final LoteRepository lotes = new LoteRepository();
    private final InventarioRepository inventario = new InventarioRepository();
    private int consecutivo = 1;
    private String almacenId;
    private String sucursalId;

    public DatosDemo(Database database) {
        this.database = database;
    }

    public void cargarSiVacio(String sucursalId) {
        database.enTransaccion(c -> {
            if (productos.contar(c) > 0 || sucursalId == null) {
                return null;
            }
            this.sucursalId = sucursalId;
            this.almacenId = almacen(c);
            String quesos = categoria(c, "Quesos", "#f59e0b", 1);
            String cremas = categoria(c, "Cremas y lácteos", "#3b82f6", 2);
            String carnes = categoria(c, "Carnes frías", "#ef4444", 3);
            String abarrotes = categoria(c, "Abarrotes", "#10b981", 4);
            String bebidas = categoria(c, "Bebidas", "#8b5cf6", 5);

            // A granel (se pesan). La clave es el PLU de la báscula. Los quesos y carnes frías tienen merma.
            granel(c, "101", "Queso Oaxaca", quesos, 19000, 25, true);
            granel(c, "102", "Queso panela", quesos, 15000, 18, true);
            granel(c, "103", "Queso manchego", quesos, 22000, 12, true);
            granel(c, "104", "Queso cotija añejo", quesos, 24000, 8, true);
            granel(c, "105", "Queso asadero", quesos, 17000, 10, true);
            granel(c, "106", "Queso chihuahua", quesos, 21000, 15, true);
            granel(c, "107", "Requesón", quesos, 9000, 6, true);
            granel(c, "201", "Crema de rancho a granel", cremas, 8000, 20, false);
            granel(c, "202", "Mantequilla a granel", cremas, 18000, 5, false);
            granel(c, "301", "Jamón de pierna", carnes, 16000, 14, true);
            granel(c, "302", "Jamón de pavo", carnes, 14000, 10, true);
            granel(c, "303", "Chorizo de la casa", carnes, 14000, 9, true);
            granel(c, "304", "Salchicha de pavo a granel", carnes, 9500, 12, true);
            granel(c, "305", "Tocino ahumado", carnes, 19500, 0.4, true);

            // Por pieza, con código de barras. La leche también se vende por caja de 12.
            String leche = pieza(c, "Leche entera 1 L", cremas, 2800, 48, 1030);
            caja(c, leche, "Caja 12 pzas", 12, 32000);
            pieza(c, "Leche deslactosada 1 L", cremas, 3000, 36, 1030);
            pieza(c, "Crema ácida 450 ml", cremas, 4200, 20, 450);
            pieza(c, "Yogurt natural 1 kg", cremas, 4500, 15, 1000);
            pieza(c, "Queso crema 190 g", quesos, 4800, 18, 190);
            pieza(c, "Mantequilla 90 g", cremas, 2800, 30, 90);
            pieza(c, "Huevo blanco 18 pzas", abarrotes, 6500, 22, 1080);
            pieza(c, "Tostadas de maíz 300 g", abarrotes, 3500, 16, 300);
            pieza(c, "Frijoles refritos 430 g", abarrotes, 3200, 24, 430);
            pieza(c, "Salsa picante 370 ml", abarrotes, 2200, 30, 370);
            pieza(c, "Chiles jalapeños 220 g", abarrotes, 2400, 2, 220);
            pieza(c, "Bolillo", abarrotes, 300, 60, 70);
            pieza(c, "Refresco de cola 600 ml", bebidas, 2000, 40, 600);
            pieza(c, "Agua natural 1 L", bebidas, 1500, 50, 1000);
            pieza(c, "Jugo de naranja 1 L", bebidas, 3800, 0, 1000);
            log.info("Catálogo de ejemplo cargado");
            return null;
        });
    }

    private static String almacen(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM sucursales WHERE es_almacen = 1 LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String categoria(Connection c, String nombre, String color, int orden) throws SQLException {
        String id = Ids.nuevo();
        productos.insertarCategoria(c, id, nombre, color, orden);
        return id;
    }

    private void granel(Connection c, String plu, String nombre, String categoria, long precioKg, double kilos,
                        boolean merma) throws SQLException {
        String id = Ids.nuevo();
        productos.insertar(c, id, nombre, categoria, plu, null, Unidad.KG, merma, Disponibilidad.REGULAR,
                new BigDecimal("1000"), merma ? new BigDecimal("20") : BigDecimal.ZERO);
        String presentacion = Ids.nuevo();
        productos.insertarPresentacion(c, new Presentacion(presentacion, id, "Kilo", true, BigDecimal.ONE, null,
                true, true));
        existencias(c, id, presentacion, BigDecimal.valueOf(kilos), precioKg * 7 / 10, precioKg, BigDecimal.valueOf(50));
    }

    private String pieza(Connection c, String nombre, String categoria, long precio, int piezas, int gramos)
            throws SQLException {
        String id = Ids.nuevo();
        productos.insertar(c, id, nombre, categoria, null, null, Unidad.PZA, false, Disponibilidad.REGULAR,
                BigDecimal.valueOf(gramos), BigDecimal.ZERO);
        String presentacion = Ids.nuevo();
        String codigo = CodigoBarras.completarEan13("75010000%04d".formatted(consecutivo++));
        productos.insertarPresentacion(c, new Presentacion(presentacion, id, "Pieza", false, BigDecimal.ONE, codigo,
                true, true));
        existencias(c, id, presentacion, BigDecimal.valueOf(piezas), precio * 7 / 10, precio, BigDecimal.valueOf(200));
        return id;
    }

    private void caja(Connection c, String productoId, String nombre, int factor, long precio) throws SQLException {
        String presentacion = Ids.nuevo();
        String codigo = CodigoBarras.completarEan13("75010000%04d".formatted(consecutivo++));
        productos.insertarPresentacion(c, new Presentacion(presentacion, productoId, nombre, false,
                BigDecimal.valueOf(factor), codigo, false, true));
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM lotes WHERE producto_id = ? AND sucursal_id = ?")) {
            ps.setString(1, productoId);
            ps.setString(2, sucursalId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lotes.insertarPrecio(c, rs.getString(1), presentacion, precio);
                }
            }
        }
    }

    /** Existencia en el almacén y un lote inicial en la sucursal con su precio. */
    private void existencias(Connection c, String productoId, String presentacionId, BigDecimal enSucursal,
                             long costo, long precio, BigDecimal enAlmacen) throws SQLException {
        if (almacenId != null) {
            String loteAlmacen = lotes.insertar(c, null, productoId, almacenId, "INICIAL", null, enAlmacen, costo, null);
            inventario.registrar(c, productoId, almacenId, loteAlmacen, "ENTRADA", enAlmacen, null, null,
                    "Inventario inicial de ejemplo");
        }
        String lote = lotes.insertar(c, null, productoId, sucursalId, "INICIAL", null, enSucursal, costo, null);
        lotes.insertarPrecio(c, lote, presentacionId, precio);
        if (enSucursal.signum() > 0) {
            inventario.registrar(c, productoId, sucursalId, lote, "ENTRADA", enSucursal, null, null,
                    "Inventario inicial de ejemplo");
        }
    }
}
