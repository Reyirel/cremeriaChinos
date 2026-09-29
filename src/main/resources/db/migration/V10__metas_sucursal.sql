-- =====================================================================
-- V10: Meta mensual de venta por sucursal (módulo Indicadores)
--
--   * Una meta por sucursal/año/mes, capturada por el administrador.
--   * El módulo Indicadores calcula venta, costo y utilidad directo de
--     ventas/lotes/movimientos_inventario; aquí solo vive la meta, que
--     no se puede derivar de las operaciones.
-- =====================================================================

CREATE TABLE metas_sucursal (
    id              TEXT PRIMARY KEY,
    sucursal_id     TEXT NOT NULL REFERENCES sucursales (id),
    anio            INTEGER NOT NULL CHECK (anio BETWEEN 2000 AND 2100),
    mes             INTEGER NOT NULL CHECK (mes BETWEEN 1 AND 12),
    meta_centavos   INTEGER NOT NULL CHECK (meta_centavos >= 0),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    sincronizado_en TEXT,
    UNIQUE (sucursal_id, anio, mes)
);

CREATE TRIGGER trg_metas_sucursal_ins AFTER INSERT ON metas_sucursal
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('metas_sucursal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_metas_sucursal_upd AFTER UPDATE ON metas_sucursal
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'metas_sucursal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'metas_sucursal' AND registro_id = NEW.id AND enviado_en IS NULL);
END;
