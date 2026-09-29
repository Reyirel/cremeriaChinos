-- =====================================================================
-- V9: Aviso opcional por producto cuando no se vende en cierto tiempo
--
--   * aviso_plazo + aviso_unidad: plazo sin venta (ej. 15 MINUTOS, 2 HORAS,
--     3 DIAS, 1 SEMANAS). Nulo = el producto no tiene aviso propio y sigue
--     el de la sucursal (días, solo para el administrador).
--   * aviso_admin / aviso_supervisor / aviso_caja: a quién se le avisa.
--   * Cada sucursal lo mide con sus propias ventas, para que ahí lo oferten.
-- =====================================================================

ALTER TABLE productos ADD COLUMN aviso_plazo INTEGER CHECK (aviso_plazo IS NULL OR aviso_plazo > 0);
ALTER TABLE productos ADD COLUMN aviso_unidad TEXT
    CHECK (aviso_unidad IS NULL OR aviso_unidad IN ('MINUTOS', 'HORAS', 'DIAS', 'SEMANAS'));
ALTER TABLE productos ADD COLUMN aviso_admin INTEGER NOT NULL DEFAULT 0 CHECK (aviso_admin IN (0, 1));
ALTER TABLE productos ADD COLUMN aviso_supervisor INTEGER NOT NULL DEFAULT 0 CHECK (aviso_supervisor IN (0, 1));
ALTER TABLE productos ADD COLUMN aviso_caja INTEGER NOT NULL DEFAULT 0 CHECK (aviso_caja IN (0, 1));

-- Las cajas revisan la última venta de cada producto cada 30 segundos.
CREATE INDEX idx_venta_detalle_producto ON venta_detalle (producto_id);
