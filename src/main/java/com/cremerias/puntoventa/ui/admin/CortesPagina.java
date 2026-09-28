package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.ui.Navegador;
import com.cremerias.puntoventa.ui.supervisor.CortesController;
import javafx.scene.Node;

/** Cortes de caja de todas las sucursales o de una en específico (misma vista que el supervisor). */
public class CortesPagina extends Pagina {

    private Navegador.Vista<CortesController> vista;

    public CortesPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        vista = a.navegador().cargarVista("supervisor/cortes-view.fxml");
        vista.controlador().setAlCambiar(a.refrescarContadores());
        return vista.nodo();
    }

    @Override
    public void alMostrar() {
        vista.controlador().actualizar();
    }
}
