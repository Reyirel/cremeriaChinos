-- =====================================================================
-- Metas de KPI por sucursal y ponderaciones del tablero de Indicadores
-- (equivalente a V12__metas_kpi.sql en la base local)
-- =====================================================================

alter table public.metas_sucursal add column meta_kilos numeric(12, 3);
alter table public.metas_sucursal add column meta_articulos_ticket numeric(6, 2);
alter table public.metas_sucursal add column meta_utilidad_pct numeric(5, 2);

alter table public.metas_sucursal
    add constraint metas_sucursal_meta_kilos_check check (meta_kilos is null or meta_kilos >= 0),
    add constraint metas_sucursal_meta_articulos_ticket_check
        check (meta_articulos_ticket is null or meta_articulos_ticket >= 0),
    add constraint metas_sucursal_meta_utilidad_pct_check
        check (meta_utilidad_pct is null or meta_utilidad_pct between 0 and 100);

insert into public.configuracion_general (clave, valor) values
    ('kpi.ponderacion.venta', '30'),
    ('kpi.ponderacion.kilos', '34'),
    ('kpi.ponderacion.articulos_ticket', '20'),
    ('kpi.ponderacion.utilidad', '16');
