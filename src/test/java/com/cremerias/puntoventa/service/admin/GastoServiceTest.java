package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.CategoriaGasto;
import com.cremerias.puntoventa.model.Gasto;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.TipoGasto;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.repository.DispositivoRepository;
import com.cremerias.puntoventa.security.PasswordHasher;
import com.cremerias.puntoventa.service.AuthService;
import com.cremerias.puntoventa.service.DatosDemo;
import com.cremerias.puntoventa.service.DatosIniciales;
import com.cremerias.puntoventa.service.ResultadoLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alta, edición y baja de gastos, y su restricción a solo el administrador. */
class GastoServiceTest {

    @TempDir
    Path carpeta;

    private GastoService gastos;
    private Usuario admin;
    private Usuario supervisor;
    private String matriz;

    @BeforeEach
    void preparar() {
        Database db = new Database(carpeta.resolve("gastos.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        matriz = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(matriz);
        String dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, matriz));
        AuthService auth = new AuthService(db, hasher, Clock.systemUTC(), dispositivo);
        gastos = new GastoService(db, dispositivo);
        admin = ((ResultadoLogin.Exitoso) auth.iniciarSesion("admin", "admin123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
        supervisor = ((ResultadoLogin.Exitoso) auth.iniciarSesion("supervisor", "super123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
    }

    private Gasto nuevoGasto() {
        return new Gasto(null, null, matriz, hoy(), CategoriaGasto.RENTA, TipoGasto.FIJO, "Renta de octubre", 500000,
                null, null, null);
    }

    private static Instant hoy() {
        return java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    @Test
    void soloElAdministradorPuedeVerCapturarYEliminarGastos() {
        assertThrows(IllegalStateException.class, () -> gastos.listar(supervisor, matriz, YearMonth.now()));
        assertThrows(IllegalStateException.class, () -> gastos.guardar(nuevoGasto(), supervisor));
        String folio = gastos.guardar(nuevoGasto(), admin);
        Gasto creado = gastos.listar(admin, matriz, YearMonth.now()).getFirst();
        assertThrows(IllegalStateException.class, () -> gastos.eliminar(creado, supervisor));
        assertTrue(folio.startsWith("GAS-"));
    }

    @Test
    void creaYListaUnGastoDelMes() {
        gastos.guardar(nuevoGasto(), admin);
        var lista = gastos.listar(admin, matriz, YearMonth.now());
        assertEquals(1, lista.size());
        Gasto g = lista.getFirst();
        assertEquals("Renta de octubre", g.concepto());
        assertEquals(500000L, g.montoCentavos());
        assertEquals(CategoriaGasto.RENTA, g.categoria());
        assertEquals(TipoGasto.FIJO, g.tipo());
        assertEquals(admin.id(), g.usuarioId());
    }

    @Test
    void editarUnGastoConservaElFolioYElRegistrador() {
        String folioCreacion = gastos.guardar(nuevoGasto(), admin);
        Gasto creado = gastos.listar(admin, matriz, YearMonth.now()).getFirst();
        Gasto editado = new Gasto(creado.id(), creado.folio(), creado.sucursalId(), creado.fecha(), creado.categoria(),
                TipoGasto.VARIABLE, "Renta de octubre (ajustada)", 550000, "Ajuste", creado.usuarioId(), creado.usuario());
        gastos.guardar(editado, admin);
        Gasto actualizado = gastos.listar(admin, matriz, YearMonth.now()).getFirst();
        assertEquals(folioCreacion, actualizado.folio());
        assertEquals(550000L, actualizado.montoCentavos());
        assertEquals(TipoGasto.VARIABLE, actualizado.tipo());
        assertEquals("Renta de octubre (ajustada)", actualizado.concepto());
    }

    @Test
    void eliminarUnGastoYaNoLoMuestraEnElListado() {
        gastos.guardar(nuevoGasto(), admin);
        Gasto creado = gastos.listar(admin, matriz, YearMonth.now()).getFirst();
        gastos.eliminar(creado, admin);
        assertTrue(gastos.listar(admin, matriz, YearMonth.now()).isEmpty());
    }

    @Test
    void elMontoDebeSerMayorACero() {
        Gasto invalido = new Gasto(null, null, matriz, hoy(), CategoriaGasto.RENTA, TipoGasto.FIJO,
                "Renta", 0, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> gastos.guardar(invalido, admin));
    }
}
