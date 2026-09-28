package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.CorteRealizado;
import com.cremerias.puntoventa.model.MetodoPago;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Pago;
import com.cremerias.puntoventa.model.ResumenTurno;
import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Sesion;
import com.cremerias.puntoventa.model.Turno;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.repository.SucursalRepository;
import com.cremerias.puntoventa.repository.UsuarioRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.util.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Un supervisor solo ve y cierra las cajas de su sucursal. */
class CortesSucursalTest {

    @TempDir
    Path carpeta;

    private Database db;
    private PasswordHasher hasher;
    private String matriz;
    private String norte;
    private AuthService auth;
    private CatalogoService catalogo;

    @BeforeEach
    void preparar() {
        db = new Database(carpeta.resolve("cortes.db"));
        new Migraciones(db).aplicar();
        hasher = new PasswordHasher(4);
        matriz = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(matriz);
        norte = Ids.nuevo();
        db.enTransaccion(c -> {
            new SucursalRepository().insertar(c, norte, "NORTE", "Cremería Norte");
            usuario(c, "cajeronorte", Rol.CAJERO, norte);
            usuario(c, "cajero2", Rol.CAJERO, matriz);
            return null;
        });
        String dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, matriz));
        auth = new AuthService(db, hasher, Clock.systemUTC(), dispositivo);
        catalogo = new CatalogoService(db, matriz);
        catalogo.recargar();
    }

    private void usuario(java.sql.Connection c, String nombre, Rol rol, String sucursal) throws java.sql.SQLException {
        new UsuarioRepository().insertar(c, new Usuario(Ids.nuevo(), sucursal, "Usuario " + nombre, nombre,
                hasher.hash("clave123".toCharArray()), rol, true, false, 0, null, null));
    }

    /** Simula otra terminal: registra el equipo y abre un turno ahí. */
    private CajaService cajaEnOtroEquipo(String nombreEquipo, String sucursal) {
        String id = Ids.nuevo();
        db.con(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO dispositivo (id, nombre, sucursal_id, creado_en) VALUES (?, ?, ?, '2026-01-01T00:00:00.000Z')")) {
                ps.setString(1, id);
                ps.setString(2, nombreEquipo);
                ps.setString(3, sucursal);
                ps.executeUpdate();
            }
            return null;
        });
        return new CajaService(db, id);
    }

    private Sesion entrar(String usuario, String clave, String dispositivo) {
        Sesion s = ((ResultadoLogin.Exitoso) auth.iniciarSesion(usuario, clave.toCharArray(), ModoConexion.OFFLINE)).sesion();
        return new Sesion(s.id(), s.usuario(), dispositivo, s.inicio(), s.modo());
    }

    @Test
    void elSupervisorSoloVeYCierraCajasDeSuSucursal() {
        CajaService caja1 = cajaEnOtroEquipo("Caja 1", matriz);
        CajaService caja2 = cajaEnOtroEquipo("Caja 2", matriz);
        CajaService cajaNorte = cajaEnOtroEquipo("Caja Norte", norte);

        Turno t1 = caja1.abrir(entrar("cajero", "cajero123", caja1.dispositivoId()), 0);
        Turno t2 = caja2.abrir(entrar("cajero2", "clave123", caja2.dispositivoId()), 0);
        Turno tNorte = cajaNorte.abrir(entrar("cajeronorte", "clave123", cajaNorte.dispositivoId()), 0);

        Sesion vendedor = entrar("cajero", "cajero123", caja1.dispositivoId());
        new VentaService(db, caja1.dispositivoId()).registrar(List.of(new VentaService.Renglon(catalogo.buscar("bolillo", 1).getFirst(),
                new BigDecimal("10"))), List.of(new Pago(MetodoPago.EFECTIVO, 3000, null)), vendedor, t1);

        Usuario supervisor = entrar("supervisor", "super123", caja1.dispositivoId()).usuario();
        List<ResumenTurno> abiertas = caja1.abiertosDeSucursal(supervisor.sucursalId());
        assertEquals(2, abiertas.size());
        assertTrue(abiertas.stream().noneMatch(r -> r.turno().id().equals(tNorte.id())));
        ResumenTurno r1 = abiertas.stream().filter(r -> r.turno().id().equals(t1.id())).findFirst().orElseThrow();
        assertEquals("Caja 1", r1.turno().dispositivoNombre());
        assertEquals(3000, r1.efectivoEsperado());

        // Cierra la caja de otro equipo de su sucursal.
        caja1.cerrar(t2, 0, null, supervisor);
        assertEquals(1, caja1.abiertosDeSucursal(matriz).size());

        // No puede cerrar la de otra sucursal.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> caja1.cerrar(tNorte, 0, null, supervisor));
        assertTrue(error.getMessage().contains("tu sucursal"));
        assertTrue(cajaNorte.turnoAbierto().isPresent());

        List<CorteRealizado> hoy = caja1.cortesDelDia(matriz, LocalDate.now());
        assertEquals(1, hoy.size());
        assertEquals("Supervisor de Tienda", hoy.getFirst().cerradoPor());
        assertTrue(caja1.cortesDelDia(matriz, LocalDate.now().minusDays(1)).isEmpty());
        assertTrue(caja1.cortesDelDia(norte, LocalDate.now()).isEmpty());
    }

    @Test
    void unCajeroNoPuedeCerrarLaCajaDeOtro() {
        CajaService caja1 = cajaEnOtroEquipo("Caja 1", matriz);
        Turno t1 = caja1.abrir(entrar("cajero", "cajero123", caja1.dispositivoId()), 0);
        Usuario otroCajero = entrar("cajero2", "clave123", caja1.dispositivoId()).usuario();
        assertThrows(IllegalStateException.class, () -> caja1.cerrar(t1, 0, null, otroCajero));

        Usuario admin = entrar("admin", "admin123", caja1.dispositivoId()).usuario();
        caja1.cerrar(t1, 0, null, admin);
        assertTrue(caja1.turnoAbierto().isEmpty());
        assertThrows(IllegalStateException.class, () -> caja1.cerrar(t1, 0, null, admin));
    }
}
