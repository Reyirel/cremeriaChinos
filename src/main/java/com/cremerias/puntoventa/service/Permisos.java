package com.cremerias.puntoventa.service;

import com.cremerias.puntoventa.model.Rol;
import com.cremerias.puntoventa.model.Usuario;

/**
 * Permisos que se revisan en los servicios, no solo en la pantalla: si una vista llama mal a una
 * operación, el servicio la rechaza. Los mensajes van directo al usuario.
 */
public final class Permisos {

    private Permisos() {
    }

    /** Solo el administrador (trabaja en el almacén central y ve todas las sucursales). */
    public static void exigirAdministrador(Usuario quien, String accion) {
        if (quien == null || quien.rol() != Rol.ADMINISTRADOR) {
            throw new IllegalStateException("Solo el administrador puede " + accion + ".");
        }
    }

    /** Administrador o supervisor; el supervisor solo en su sucursal. */
    public static void exigirSupervisorOAdministrador(Usuario quien, String sucursalId, String accion) {
        if (quien == null || quien.rol() == Rol.CAJERO) {
            throw new IllegalStateException("Solo un supervisor o el administrador puede " + accion + ".");
        }
        exigirAlcance(quien, sucursalId, accion);
    }

    /** El administrador trabaja en cualquier sucursal; los demás, solo en la suya. */
    public static void exigirAlcance(Usuario quien, String sucursalId, String accion) {
        if (quien == null) {
            throw new IllegalStateException("Inicia sesión para " + accion + ".");
        }
        if (quien.rol() != Rol.ADMINISTRADOR && (quien.sucursalId() == null || !quien.sucursalId().equals(sucursalId))) {
            throw new IllegalStateException("Solo puedes " + accion + " en tu sucursal.");
        }
    }

    /**
     * Quien autoriza una operación delicada (cancelación, devolución, retiro): supervisor de la
     * misma sucursal o administrador. Un cajero nunca autoriza.
     */
    public static void exigirAutorizador(Usuario autorizador, String sucursalId, String accion) {
        if (autorizador == null || autorizador.rol() == Rol.CAJERO) {
            throw new IllegalStateException("Para " + accion + " necesita autorizar un supervisor o el administrador.");
        }
        if (autorizador.rol() == Rol.SUPERVISOR && sucursalId != null && !sucursalId.equals(autorizador.sucursalId())) {
            throw new IllegalStateException("Solo un supervisor de esta sucursal puede autorizar " + accion + ".");
        }
    }

    /** Texto obligatorio (motivo de una cancelación, devolución, ajuste…). */
    public static String exigirTexto(String texto, String mensaje) {
        if (texto == null || texto.isBlank()) {
            throw new IllegalArgumentException(mensaje);
        }
        return texto.strip();
    }
}
