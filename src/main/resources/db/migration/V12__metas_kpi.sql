-- =====================================================================
-- V12: Metas de KPI por sucursal y ponderaciones del tablero de Indicadores
--
--   * meta_kilos, meta_articulos_ticket y meta_utilidad_pct amplían la meta
--     mensual de metas_sucursal (V10) para el resto de los KPIs del módulo
--     Indicadores. Son opcionales: una sucursal puede tener solo la meta
--     de venta capturada.
--   * meta_kilos se guarda en gramos, igual que productos.gramaje_gramos
--     y merma_gramos, aunque el nombre de la columna hable de kilos.
--   * Las ponderaciones de cada KPI (venta/kilos/artículos por ticket/
--     utilidad) son un solo valor global, no por sucursal, así que viven
--     en configuracion_general como las demás banderas globales.
-- =====================================================================

ALTER TABLE metas_sucursal ADD COLUMN meta_kilos REAL CHECK (meta_kilos IS NULL OR meta_kilos >= 0);
ALTER TABLE metas_sucursal ADD COLUMN meta_articulos_ticket REAL CHECK (meta_articulos_ticket IS NULL OR meta_articulos_ticket >= 0);
ALTER TABLE metas_sucursal ADD COLUMN meta_utilidad_pct REAL CHECK (meta_utilidad_pct IS NULL OR meta_utilidad_pct BETWEEN 0 AND 100);

INSERT INTO configuracion_general (clave, valor, actualizado_en) VALUES
    ('kpi.ponderacion.venta', '30', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    ('kpi.ponderacion.kilos', '34', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    ('kpi.ponderacion.articulos_ticket', '20', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    ('kpi.ponderacion.utilidad', '16', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
