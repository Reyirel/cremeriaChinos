package com.cremerias.puntoventa.service.admin;

import com.cremerias.puntoventa.db.Database;
import com.cremerias.puntoventa.db.Migraciones;
import com.cremerias.puntoventa.model.ModoConexion;
import com.cremerias.puntoventa.model.Nomina;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Captura semanal de nómina por empleado, y su restricción a solo el administrador. */
class NominaServiceTest {

    @TempDir
    Path carpeta;

    private NominaService nomina;
    private Usuario admin;
    private Usuario supervisor;
    private String matriz;

    @BeforeEach
    void preparar() {
        Database db = new Database(carpeta.resolve("nomina.db"));
        new Migraciones(db).aplicar();
        PasswordHasher hasher = new PasswordHasher(4);
        matriz = new DatosIniciales(db, hasher).sembrarSiVacia();
        new DatosDemo(db).cargarSiVacio(matriz);
        String dispositivo = db.con(c -> new DispositivoRepository().obtenerOCrear(c, matriz));
        AuthService auth = new AuthService(db, hasher, Clock.systemUTC(), dispositivo);
        nomina = new NominaService(db, dispositivo);
        admin = ((ResultadoLogin.Exitoso) auth.iniciarSesion("admin", "admin123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
        supervisor = ((ResultadoLogin.Exitoso) auth.iniciarSesion("supervisor", "super123".toCharArray(),
                ModoConexion.OFFLINE)).sesion().usuario();
    }

    @Test
    void semanasDelMesSoloDevuelveLunesDentroDelMes() {
        var semanas = NominaService.semanasDelMes(YearMonth.of(2026, 10));
        assertTrue(semanas.stream().allMatch(l -> l.getDayOfWeek() == DayOfWeek.MONDAY));
        assertTrue(semanas.stream().allMatch(l -> !l.isBefore(LocalDate.of(2026, 10, 1))
                && !l.isAfter(LocalDate.of(2026, 10, 31))));
        assertEquals(4, semanas.size()); // octubre 2026: lunes 5, 12, 19 y 26
    }

    @Test
    void soloElAdministradorPuedeVerYCapturarNomina() {
        LocalDate lunes = LocalDate.of(2026, 10, 5);
        assertThrows(IllegalStateException.class, () -> nomina.listar(supervisor, matriz, YearMonth.of(2026, 10)));
        assertThrows(IllegalStateException.class,
                () -> nomina.guardar(supervisor, matriz, supervisor.id(), lunes, 150000, 20000));
    }

    @Test
    void capturarLaMismaSemanaYEmpleadoActualizaEnVezDeDuplicar() {
        LocalDate lunes = LocalDate.of(2026, 10, 5);
        String folio1 = nomina.guardar(admin, matriz, supervisor.id(), lunes, 150000, 20000);
        assertTrue(folio1.startsWith("NOM-"));
        nomina.guardar(admin, matriz, supervisor.id(), lunes, 160000, 25000);

        var lista = nomina.listar(admin, matriz, YearMonth.of(2026, 10));
        assertEquals(1, lista.size());
        Nomina n = lista.getFirst();
        assertEquals(160000L, n.salarioCentavos());
        assertEquals(25000L, n.comidaCentavos());
        assertEquals(185000L, n.total());
    }

    @Test
    void capturadaDevuelveLoGuardadoParaPrecargarElDialogo() {
        LocalDate lunes = LocalDate.of(2026, 10, 5);
        assertTrue(nomina.capturada(admin, matriz, supervisor.id(), lunes).isEmpty());
        nomina.guardar(admin, matriz, supervisor.id(), lunes, 150000, 20000);
        Nomina n = nomina.capturada(admin, matriz, supervisor.id(), lunes).orElseThrow();
        assertEquals(150000L, n.salarioCentavos());
        assertEquals(20000L, n.comidaCentavos());
    }

    @Test
    void laSemanaDebeEmpezarEnLunes() {
        LocalDate martes = LocalDate.of(2026, 10, 6);
        assertThrows(IllegalArgumentException.class,
                () -> nomina.guardar(admin, matriz, supervisor.id(), martes, 150000, 20000));
    }
}
