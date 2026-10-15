package com.cremerias.puntoventa.ui.admin;

import javafx.scene.Node;
import javafx.scene.control.TextField;

/**
 * Sección del panel del administrador. La vista se construye una vez (conserva filtros,
 * selección y posición al volver) y se actualiza al mostrarse.
 */
public abstract class Pagina {

    protected final AdminContexto a;
    private Node vista;

    protected Pagina(AdminContexto a) {
        this.a = a;
    }

    public Node vista() {
        if (vista == null) {
            vista = construir();
        }
        return vista;
    }

    protected abstract Node construir();

    /** Se llama cada vez que se muestra la página (para recargar datos). */
    public void alMostrar() {
    }

    /** Nombre de la página para mensajes ("Mínimos y máximos"). */
    public String titulo() {
        return "esta página";
    }

    /** Hay cambios sin guardar: el menú pregunta antes de salir. */
    public boolean cambiosPendientes() {
        return false;
    }

    /** Se descartan los cambios sin guardar (el usuario decidió salir). */
    public void descartarCambios() {
    }

    /** Campo de búsqueda principal de la página (Ctrl+F), si tiene. */
    public TextField busqueda() {
        return null;
    }
}
