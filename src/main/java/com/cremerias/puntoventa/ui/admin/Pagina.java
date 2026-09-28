package com.cremerias.puntoventa.ui.admin;

import javafx.scene.Node;

/** Sección del panel del administrador. La vista se construye una vez y se actualiza al mostrarse. */
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
}
