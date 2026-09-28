-- =====================================================================
-- V4: Nombre legible de cada caja ("Caja 1987"), usando el mismo código
-- de terminal que aparece en los folios (MATRIZ-1987-000001).
-- =====================================================================

UPDATE dispositivo
SET nombre = 'Caja ' || upper(substr(replace(id, '-', ''), 1, 4));
