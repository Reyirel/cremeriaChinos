package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.AppContext;
import com.cremerias.puntoventa.model.Usuario;
import com.cremerias.puntoventa.service.admin.Administracion;
import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.componentes.Avisos;
import com.cremerias.puntoventa.ui.componentes.Dialogos;

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

    public static String mensaje(Throwable e) {
        Throwable causa = e;
        while (causa.getCause() != null && (causa.getMessage() == null || causa.getMessage().startsWith("Error en la base"))) {
            causa = causa.getCause();
        }
        String m = causa.getMessage();
        if (m == null) {
            return "Ocurrió un error inesperado.";
        }
        if (m.contains("UNIQUE constraint failed")) {
            return "Ese dato ya existe (debe ser único).";
        }
        return m;
    }
}
