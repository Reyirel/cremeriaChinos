-- =====================================================================
-- V7: Mínimos/máximos solo de productos asignados a cada sucursal,
--     y aviso de "producto sin venta" con días por sucursal
--
--   * Un producto se considera de la sucursal cuando ya se le ha surtido
--     ahí (tiene un lote) o ya tiene mínimo/máximo capturado. No todas las
--     sucursales venden los mismos productos.
--   * "alerta.dias_sin_venta" en configuracion_general era un solo valor
--     para todas las sucursales; ahora cada sucursal tiene el suyo, definido
--     por el administrador, en alertas_sucursal.
-- =====================================================================

CREATE TABLE alertas_sucursal (
    sucursal_id     TEXT PRIMARY KEY REFERENCES sucursales (id),
    dias_sin_venta  INTEGER NOT NULL CHECK (dias_sin_venta BETWEEN 1 AND 365),
    actualizado_en  TEXT NOT NULL,
    sincronizado_en TEXT
);

-- Arranca con el valor global que ya tenía cada sucursal, para no cambiar
-- el comportamiento el día de la migración.
INSERT INTO alertas_sucursal (sucursal_id, dias_sin_venta, actualizado_en)
SELECT s.id,
       CAST(COALESCE((SELECT valor FROM configuracion_general WHERE clave = 'alerta.dias_sin_venta'), '30') AS INTEGER),
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM sucursales s
WHERE s.es_almacen = 0;

CREATE TRIGGER trg_alertas_sucursal_ins AFTER INSERT ON alertas_sucursal
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('alertas_sucursal', NEW.sucursal_id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_alertas_sucursal_upd AFTER UPDATE ON alertas_sucursal
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'alertas_sucursal', NEW.sucursal_id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'alertas_sucursal' AND registro_id = NEW.sucursal_id AND enviado_en IS NULL);
END;
