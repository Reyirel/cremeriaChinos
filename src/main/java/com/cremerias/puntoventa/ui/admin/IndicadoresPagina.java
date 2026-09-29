package com.cremerias.puntoventa.ui.admin;

import com.cremerias.puntoventa.ui.componentes.IndicadoresVista;
import javafx.scene.Node;

/**
 * Indicadores de desempeño, venta, costo, utilidad y avance de meta de las sucursales.
 * El administrador (Superadministrador) puede elegir cualquier sucursal.
 */
public class IndicadoresPagina extends Pagina {

    private IndicadoresVista vista;

    public IndicadoresPagina(AdminContexto a) {
        super(a);
    }

    @Override
    protected Node construir() {
        vista = new IndicadoresVista(a.ctx().indicadores(), a.usuario(), () -> a.admin().sucursales().activas(),
                a.ctx()::nombreSucursal, a.dialogos(), a.avisos());
        return vista.nodo();
    }

    @Override
    public void alMostrar() {
        vista.actualizar();
    }
}
