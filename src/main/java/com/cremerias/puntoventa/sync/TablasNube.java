package com.cremerias.puntoventa.sync;

import java.util.List;
import java.util.Map;

/** Qué tablas se sincronizan, en qué orden y con qué llave. */
final class TablasNube {

    /**
     * Orden de bajada: los padres antes que los hijos, porque la base local revisa las llaves
     * foráneas al momento. {@code usuarios_credenciales} solo existe en la nube: en la caja el hash
     * vive en {@code usuarios.password_hash}.
     */
    static final List<String> ORDEN = List.of(
            "sucursales", "dispositivo", "usuarios", "usuarios_credenciales", "categorias", "productos",
            "presentaciones", "lotes", "lote_precios", "turnos_caja", "movimientos_caja", "ventas",
            "venta_detalle", "venta_pagos", "movimientos_inventario", "surtidos", "surtido_detalle",
            "movimientos_credito", "mermas", "limites_sucursal", "ordenes_reabastecimiento", "reglas_comision",
            "metas_sucursal", "horarios", "bloqueos_acceso", "configuracion_general", "alertas_sucursal",
            "bitacora", "sesiones", "bitacora_accesos");

    static final String CREDENCIALES = "usuarios_credenciales";

    /**
     * Filas hijas que en la caja no se encolan: se suben junto con su padre
     * (tabla padre → tabla hija y columna que apunta al padre).
     */
    static final Map<String, List<Hija>> HIJAS = Map.of(
            "ventas", List.of(new Hija("venta_detalle", "venta_id"), new Hija("venta_pagos", "venta_id")),
            "lotes", List.of(new Hija("lote_precios", "lote_id")),
            "surtidos", List.of(new Hija("surtido_detalle", "surtido_id")));

    record Hija(String tabla, String columnaPadre) {
    }

    private TablasNube() {
    }

    static String llave(String tabla) {
        return switch (tabla) {
            case "configuracion_general" -> "clave";
            case "alertas_sucursal" -> "sucursal_id";
            case CREDENCIALES -> "usuario_id";
            default -> "id";
        };
    }

    static boolean seSincroniza(String tabla) {
        return ORDEN.contains(tabla) && !CREDENCIALES.equals(tabla);
    }
}
