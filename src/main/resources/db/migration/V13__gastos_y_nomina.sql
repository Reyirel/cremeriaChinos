-- =====================================================================
-- V13: Gastos y nómina semanal por sucursal
--
--   * gastos: cada renglón de gasto (fijo o variable) de una sucursal, con su categoría.
--     Se puede dar de baja (eliminado_en) para corregir una captura equivocada.
--   * nomina_semanal: salario y comida de cada empleado por semana (lunes de inicio).
--     Una sola captura por sucursal/empleado/semana: volver a capturar esa combinación
--     corrige la anterior (no hay baja, igual que metas_sucursal).
-- =====================================================================

CREATE TABLE gastos (
    id              TEXT PRIMARY KEY,
    folio           TEXT NOT NULL UNIQUE,
    sucursal_id     TEXT NOT NULL REFERENCES sucursales (id),
    fecha           TEXT NOT NULL,
    categoria       TEXT NOT NULL CHECK (categoria IN ('RENTA', 'ELECTRICIDAD', 'INTERNET', 'SEGURO_LOCAL',
                     'IMSS_INFONAVIT', 'IMPUESTO_NOMINA', 'TRANSPORTE', 'PUBLICIDAD', 'INDIRECTOS')),
    tipo            TEXT NOT NULL CHECK (tipo IN ('FIJO', 'VARIABLE')),
    concepto        TEXT NOT NULL,
    monto_centavos  INTEGER NOT NULL CHECK (monto_centavos > 0),
    nota            TEXT,
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT
);

CREATE INDEX idx_gastos_sucursal_fecha ON gastos (sucursal_id, fecha);

CREATE TRIGGER trg_gastos_ins AFTER INSERT ON gastos
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('gastos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_gastos_upd AFTER UPDATE ON gastos
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'gastos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'gastos' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TABLE nomina_semanal (
    id               TEXT PRIMARY KEY,
    sucursal_id      TEXT NOT NULL REFERENCES sucursales (id),
    usuario_id       TEXT NOT NULL REFERENCES usuarios (id),
    semana_inicio    TEXT NOT NULL,
    salario_centavos INTEGER NOT NULL CHECK (salario_centavos >= 0),
    comida_centavos  INTEGER NOT NULL DEFAULT 0 CHECK (comida_centavos >= 0),
    creado_en        TEXT NOT NULL,
    actualizado_en   TEXT NOT NULL,
    sincronizado_en  TEXT,
    UNIQUE (sucursal_id, usuario_id, semana_inicio)
);

CREATE INDEX idx_nomina_semanal_sucursal ON nomina_semanal (sucursal_id, semana_inicio);

CREATE TRIGGER trg_nomina_semanal_ins AFTER INSERT ON nomina_semanal
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('nomina_semanal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_nomina_semanal_upd AFTER UPDATE ON nomina_semanal
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'nomina_semanal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'nomina_semanal' AND registro_id = NEW.id AND enviado_en IS NULL);
END;
