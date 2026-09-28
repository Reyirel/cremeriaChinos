-- =====================================================================
-- V8: Código de inventario (control interno del almacén) del producto,
--     aparte de la clave/PLU y del código de barras de cada presentación.
--     Permite buscar el producto con la clave que ya usan internamente.
-- =====================================================================

-- SQLite no permite agregar una columna UNIQUE con ALTER TABLE: se aplica con un índice aparte.
ALTER TABLE productos ADD COLUMN codigo_inventario TEXT COLLATE NOCASE;
CREATE UNIQUE INDEX idx_productos_codigo_inventario ON productos (codigo_inventario);
