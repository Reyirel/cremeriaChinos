-- =====================================================================
-- V6: Gramaje de cada producto y merma del producto
--
--   * gramaje_gramos: peso de una unidad base (una pieza; en productos por
--     kilo es 1000 g por kilo). Sirve para dar comisiones por gramaje.
--   * merma_gramos: cuánto de ese gramaje se pierde (solo si el producto es
--     sujeto a merma). Gramaje neto = gramaje − merma.
--   * reglas_comision.medida: la meta de producto se mide en gramos o en piezas.
-- =====================================================================

ALTER TABLE productos ADD COLUMN gramaje_gramos REAL CHECK (gramaje_gramos IS NULL OR gramaje_gramos > 0);
ALTER TABLE productos ADD COLUMN merma_gramos REAL NOT NULL DEFAULT 0 CHECK (merma_gramos >= 0);

-- Los productos por kilo pesan 1000 g por kilo; los de pieza se capturan al editarlos.
UPDATE productos SET gramaje_gramos = 1000 WHERE unidad = 'KG';

ALTER TABLE reglas_comision ADD COLUMN medida TEXT NOT NULL DEFAULT 'UNIDADES'
    CHECK (medida IN ('GRAMOS', 'UNIDADES'));
