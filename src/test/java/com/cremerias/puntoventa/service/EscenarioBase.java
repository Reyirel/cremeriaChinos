package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.Producto;
import com.cremerias.puntoventa.model.ProductoCatalogo;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Sucursal;
import com.cremerias.puntoventa.model.Ticket;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.admin.Administracion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;

/**
 * Base aislada para las pruebas de los módulos nuevos: base SQLite temporal con el catálogo de
 * ejemplo (almacén central y sucursal MATRIZ), los tres usuarios de ejemplo y los servicios.
 */
abstract class EscenarioBase {

    @TempDir
    Path carpeta;

    Database db;
    String dispositivo;
    AuthService auth;
    Administracion admin;
    CatalogoService catalogo;
    CajaService caja;
    VentaService ventas;
    Sucursal matriz;
    Sucursal almacen;
    Usuario administrador;
    Sesion cajero;
    Usuario supervisor;

    @BeforeEach
    void prepararEscenario() {
        db = new Database(carpeta.resolve("escenario.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        String matrizId = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(matrizId);
        dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, matrizId));
        auth = new AuthService(db, hasher, Clock.systemDefaultZone(), dispositivo);
        admin = new Administracion(db, hasher, Clock.systemDefaultZone(), dispositivo);
        catalogo = new CatalogoService(db, matrizId);
        catalogo.recargar();
        caja = new CajaService(db, dispositivo);
        ventas = new VentaService(db, dispositivo);
        matriz = admin.sucursales().listar().stream().filter(s -> s.id().equals(matrizId)).findFirst().orElseThrow();
        almacen = admin.sucursales().almacen().orElseThrow();
        administrador = entrar("admin", "admin123").usuario();
        cajero = entrar("cajero", "cajero123");
        supervisor = ((ResultadoAutorizacion.Autorizado) auth.autorizar("supervisor", "super123".toCharArray(),
                EnumSet.of(Rol.SUPERVISOR, Rol.ADMINISTRADOR), "prueba")).autorizador();
    }

    Sesion entrar(String usuario, String clave) {
        return ((ResultadoLogin.Exitoso) auth.iniciarSesion(usuario, clave.toCharArray(), ModoConexion.OFFLINE)).sesion();
    }

    ProductoCatalogo catalogoAdmin(String nombre) {
        return admin.productos().listar().stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    /** Artículo de la caja por su nombre exacto («Leche entera 1 L · Caja 12 pzas»). */
    Producto articulo(String nombre) {
        catalogo.recargar();
        return catalogo.buscar(nombre, 20).stream().filter(p -> p.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    Ticket vender(Turno turno, Producto producto, String cantidad) {
        return vender(turno, List.of(new VentaService.Renglon(producto, new BigDecimal(cantidad))));
    }

    Ticket vender(Turno turno, List<VentaService.Renglon> renglones) {
        return ventas.registrar(renglones, List.of(new Pago(MetodoPago.EFECTIVO, 10_000_000, null)), cajero, turno);
    }

    BigDecimal existencia(String productoId, String sucursalId) {
        return admin.almacen().existencia(productoId, sucursalId);
    }

    String consultar(String sql) {
        return db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery(sql);
            return rs.next() ? rs.getString(1) : null;
        });
    }

    long numero(String sql) {
        return db.con(c -> {
            ResultSet rs = c.createStatement().executeQuery(sql);
            rs.next();
            return rs.getLong(1);
        });
    }

    static void mismo(String esperado, BigDecimal real) {
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal(esperado).compareTo(real),
                () -> "Se esperaba " + esperado + " y quedó " + real);
    }
}
