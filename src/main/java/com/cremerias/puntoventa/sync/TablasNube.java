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
            "presentaciones", "reglas_precio", "escalas_precio", "proveedores", "lotes", "lote_precios",
            "turnos_caja", "movimientos_caja", "ventas", "venta_detalle", "venta_pagos", "movimientos_inventario",
            "surtidos", "surtido_detalle", "movimientos_credito", "mermas", "compras", "compra_detalle",
            "devoluciones_proveedor", "devolucion_proveedor_detalle", "ajustes_inventario", "transformaciones",
            "devoluciones_venta", "devolucion_venta_detalle", "devoluciones_sucursal", "devolucion_sucursal_detalle",
            "recepciones_surtido", "recepcion_surtido_detalle", "aclaraciones_surtido", "cancelaciones_surtido",
            "limites_sucursal", "ordenes_reabastecimiento", "reglas_comision",
            "metas_sucursal", "gastos", "nomina_semanal", "horarios", "bloqueos_acceso", "configuracion_general",
            "alertas_sucursal", "bitacora", "sesiones", "bitacora_accesos");

    static final String CREDENCIALES = "usuarios_credenciales";

    /**
     * Filas hijas que en la caja no se encolan: se suben junto con su padre
     * (tabla padre → tabla hija y columna que apunta al padre).
     */
    static final Map<String, List<Hija>> HIJAS = Map.of(
            "ventas", List.of(new Hija("venta_detalle", "venta_id"), new Hija("venta_pagos", "venta_id")),
            "lotes", List.of(new Hija("lote_precios", "lote_id")),
            "surtidos", List.of(new Hija("surtido_detalle", "surtido_id")),
            "compras", List.of(new Hija("compra_detalle", "compra_id")),
            "devoluciones_proveedor", List.of(new Hija("devolucion_proveedor_detalle", "devolucion_id")),
            "devoluciones_venta", List.of(new Hija("devolucion_venta_detalle", "devolucion_id")),
            "devoluciones_sucursal", List.of(new Hija("devolucion_sucursal_detalle", "devolucion_id")),
            "recepciones_surtido", List.of(new Hija("recepcion_surtido_detalle", "recepcion_id")));

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
