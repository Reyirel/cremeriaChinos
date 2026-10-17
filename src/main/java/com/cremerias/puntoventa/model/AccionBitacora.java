package com.cremerias.puntoventa.model;

/**
 * Acciones que quedan registradas en la bitácora. Cada una recibe un folio con su prefijo:
 * PREFIJO-TERMINAL-consecutivo (ej. SUR-1987-000012).
 */
public enum AccionBitacora {

    SUCURSAL_CREADA("SUC", "Sucursal creada"),
    SUCURSAL_EDITADA("SUC", "Sucursal editada"),
    SUCURSAL_HABILITADA("SUC", "Sucursal habilitada"),
    SUCURSAL_DESHABILITADA("SUC", "Sucursal deshabilitada"),
    SUCURSAL_ELIMINADA("SUC", "Sucursal eliminada"),

    USUARIO_CREADO("USR", "Usuario creado"),
    USUARIO_EDITADO("USR", "Usuario editado"),
    USUARIO_PASSWORD("USR", "Contraseña cambiada"),
    USUARIO_HABILITADO("USR", "Usuario habilitado"),
    USUARIO_DESHABILITADO("USR", "Usuario deshabilitado"),
    USUARIO_ELIMINADO("USR", "Usuario eliminado"),
    HORARIO_ACTUALIZADO("HOR", "Horario actualizado"),

    PRODUCTO_CREADO("PRD", "Producto creado"),
    PRODUCTO_EDITADO("PRD", "Producto editado"),
    PRODUCTO_HABILITADO("PRD", "Producto habilitado"),
    PRODUCTO_DESHABILITADO("PRD", "Producto deshabilitado"),
    PRODUCTO_ELIMINADO("PRD", "Producto eliminado"),

    ENTRADA_ALMACEN("ENT", "Reabastecimiento del almacén"),
    COMPRA("CMP", "Compra a proveedor"),
    PROVEEDOR_CREADO("PRV", "Proveedor creado"),
    PROVEEDOR_EDITADO("PRV", "Proveedor editado"),
    DEVOLUCION_PROVEEDOR("DVP", "Devolución a proveedor"),
    AJUSTE_INVENTARIO("AJU", "Ajuste de inventario"),
    TRANSFORMACION("TRA", "Transformación de producto"),
    REGLA_PRECIO_GUARDADA("PRE", "Regla de precio guardada"),
    REGLA_PRECIO_DESACTIVADA("PRE", "Regla de precio desactivada"),
    ESCALA_PRECIO_GUARDADA("ESC", "Escala de mayoreo guardada"),
    ESCALA_PRECIO_DESACTIVADA("ESC", "Escala de mayoreo desactivada"),
    MERMA("MER", "Merma registrada"),
    SURTIDO("SUR", "Surtido a sucursal"),
    RECEPCION_SURTIDO("REC", "Recepción de surtido"),
    ACLARACION_SURTIDO("ACL", "Diferencia de surtido aclarada"),
    CANCELACION_SURTIDO("CSU", "Surtido cancelado"),
    DEVOLUCION_SUCURSAL("DVS", "Devolución de sucursal a bodega"),
    ORDEN_GENERADA("ORD", "Orden de reabastecimiento generada"),
    ORDEN_RECHAZADA("ORD", "Orden de reabastecimiento rechazada"),
    LIMITES_ACTUALIZADOS("LIM", "Mínimos y máximos actualizados"),
    ABONO_CREDITO("ABN", "Abono a crédito"),
    NOTA_CREDITO("NCR", "Nota de crédito"),
    META_MENSUAL_ACTUALIZADA("MET", "Meta mensual actualizada"),

    REGLA_COMISION_CREADA("COM", "Regla de comisión creada"),
    REGLA_COMISION_EDITADA("COM", "Regla de comisión editada"),
    REGLA_COMISION_ELIMINADA("COM", "Regla de comisión eliminada"),

    GASTO_CREADO("GAS", "Gasto creado"),
    GASTO_EDITADO("GAS", "Gasto editado"),
    GASTO_ELIMINADO("GAS", "Gasto eliminado"),

    NOMINA_CAPTURADA("NOM", "Nómina semanal capturada"),
    NOMINA_EDITADA("NOM", "Nómina semanal editada"),

    CORTE_CAJA("CRT", "Corte de caja"),
    VENTA_CANCELADA("CAN", "Venta cancelada"),
    DEVOLUCION_VENTA("DEV", "Devolución de cliente"),
    MOVIMIENTO_CAJA("MOV", "Entrada/retiro de efectivo"),

    ACCESO_BLOQUEADO("ACC", "Acceso bloqueado por horario"),
    ACCESO_AUTORIZADO("ACC", "Acceso autorizado"),

    CONFIGURACION("CFG", "Configuración cambiada");

    private final String prefijo;
    private final String descripcion;

    AccionBitacora(String prefijo, String descripcion) {
        this.prefijo = prefijo;
        this.descripcion = descripcion;
    }

    public String prefijo() {
        return prefijo;
    }

    public String descripcion() {
        return descripcion;
    }
}
