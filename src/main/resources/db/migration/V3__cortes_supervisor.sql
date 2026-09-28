-- =====================================================================
-- V3: Cortes de caja por sucursal (panel del supervisor)
--
--   * cerrado_por: quién hizo el corte (el cajero o un supervisor).
--   * La tabla dispositivo ahora se sincroniza: así cada terminal conoce
--     el nombre de las otras cajas de su sucursal.
-- =====================================================================

ALTER TABLE turnos_caja ADD COLUMN cerrado_por TEXT REFERENCES usuarios (id);

CREATE INDEX idx_turnos_sucursal_estado ON turnos_caja (sucursal_id, estado);
CREATE INDEX idx_turnos_sucursal_cierre ON turnos_caja (sucursal_id, cerrado_en);

ALTER TABLE dispositivo ADD COLUMN sincronizado_en TEXT;

CREATE TRIGGER trg_dispositivo_ins AFTER INSERT ON dispositivo
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'dispositivo', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'dispositivo' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_dispositivo_upd AFTER UPDATE OF nombre, sucursal_id ON dispositivo
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'dispositivo', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'dispositivo' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

-- El equipo que ya existía también debe subirse.
INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
SELECT 'dispositivo', id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now') FROM dispositivo;

-- El cierre ahora también registra quién lo hizo.
DROP TRIGGER trg_turnos_upd;

CREATE TRIGGER trg_turnos_upd AFTER UPDATE OF estado, cerrado_en, efectivo_contado_centavos, cerrado_por ON turnos_caja
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'turnos_caja', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'turnos_caja' AND registro_id = NEW.id AND enviado_en IS NULL);
END;
