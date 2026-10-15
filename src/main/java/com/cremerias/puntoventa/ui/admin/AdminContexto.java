package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Cargador;
import com.cremerias.puntoventa.ui.componentes.Dialogo;
import com.cremerias.puntoventa.ui.componentes.Dialogos;
import com.cremerias.puntoventa.ui.componentes.Errores;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lo que necesitan las páginas del panel del administrador. */
public record AdminContexto(Navegador navegador, AppContext ctx, Administracion admin, Usuario usuario,
                            Dialogos dialogos, Avisos avisos, Runnable refrescarContadores, Consumer<String> irA) {

    /**
     * Ejecuta una acción que devuelve un folio y avisa el resultado.
     * @return true si se hizo
     */
    public boolean ejecutar(String exito, Supplier<String> accion) {
        try {
            String folio = accion.get();
            avisos.exito(exito + (folio == null ? "" : " · Folio " + folio));
            refrescarContadores.run();
            return true;
        } catch (RuntimeException e) {
            avisos.error(mensaje(e));
            return false;
        }
    }

    /**
     * Guarda lo capturado en un diálogo sin bloquear la pantalla: el diálogo queda ocupado (sin
     * doble envío); si falla, el error aparece en el mismo diálogo con los datos intactos.
     */
    public void guardar(Dialogo dialogo, String exito, Callable<String> accion, Runnable despues) {
        dialogo.enviar(accion, folio -> {
            dialogo.cerrar();
            avisos.exito(exito + (folio == null ? "" : " · Folio " + folio));
            refrescarContadores.run();
            despues.run();
        });
    }

    /** Acción confirmada (deshabilitar, eliminar...) en segundo plano; avisa cómo terminó. */
    public void enFondo(String exito, Callable<String> accion, Runnable despues) {
        Cargador.ejecutar(accion, folio -> {
            avisos.exito(exito + (folio == null ? "" : " · Folio " + folio));
            refrescarContadores.run();
            despues.run();
        }, e -> avisos.error(mensaje(e)));
    }

    public static String mensaje(Throwable e) {
        return Errores.mensaje(e);
    }
}
