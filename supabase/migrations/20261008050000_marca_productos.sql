-- =====================================================================
-- Marca del producto, para el desglose de kilos por marca en Indicadores
-- (equivalente a V14__marca_productos.sql)
-- =====================================================================

alter table public.productos add column marca text;
