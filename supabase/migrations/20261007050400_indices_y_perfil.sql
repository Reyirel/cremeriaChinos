-- =====================================================================
-- Índices para consultas frecuentes y mi_perfil sin privilegios extra
--
--   * Índices en llaves foráneas que se consultan (ventas por cajero o por
--     caja, comisiones por producto, precios por presentación…). Las de
--     auditoría (autorizado_por, cancelada_por…) quedan sin índice: como
--     nunca se borran filas, no hacen falta para las llaves foráneas.
--   * mi_perfil no necesita saltarse el RLS: cada quien ya puede ver su
--     propio usuario o caja y su sucursal.
-- =====================================================================

create index idx_ventas_usuario_fecha on public.ventas (usuario_id, fecha);
create index idx_ventas_dispositivo on public.ventas (dispositivo_id);
create index idx_turnos_usuario on public.turnos_caja (usuario_id);
create index idx_mermas_producto on public.mermas (producto_id);
create index idx_lote_precios_presentacion on public.lote_precios (presentacion_id);
create index idx_limites_producto on public.limites_sucursal (producto_id);
create index idx_ordenes_producto on public.ordenes_reabastecimiento (producto_id);
create index idx_surtido_detalle_lote on public.surtido_detalle (lote_destino_id);
create index idx_reglas_comision_producto on public.reglas_comision (producto_id);
create index idx_reglas_comision_sucursal on public.reglas_comision (sucursal_id);
create index idx_reglas_comision_usuario on public.reglas_comision (usuario_id);

alter function public.mi_perfil() security invoker;
