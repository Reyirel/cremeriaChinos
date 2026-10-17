-- =====================================================================
-- V18: Ajustes de inventario y transformaciones de producto
--
--   * ajustes_inventario: corrección positiva o negativa de la existencia
--     de un producto en una ubicación (almacén o sucursal) con su motivo,
--     documento de referencia y valor al costo. Los lotes afectados quedan en
--     movimientos_inventario (tipo AJUSTE, referencia = ajuste).
--   * transformaciones: se consume un producto de origen (p. ej. queso
--     entero) para producir otro con existencia propia (p. ej. paquetes de
--     500 g). equivalencia = unidades base del origen por unidad base del
--     destino; merma_origen = consumido − producido × equivalencia. El costo
--     del origen consumido (PEPS) pasa completo al lote del destino.
-- =====================================================================

CREATE TABLE ajustes_inventario (
    id              TEXT PRIMARY KEY,
    folio           TEXT NOT NULL UNIQUE,
    sucursal_id     TEXT NOT NULL REFERENCES sucursales (id),
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    motivo          TEXT NOT NULL CHECK (motivo IN ('SOBRANTE', 'FALTANTE', 'CADUCIDAD', 'DANO', 'CORRECCION',
                                                    'CORTESIA', 'DEVOLUCION', 'OTRO')),
    cantidad        REAL NOT NULL CHECK (cantidad <> 0),
    costo_centavos  INTEGER NOT NULL,
    documento       TEXT,
    nota            TEXT,
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    fecha           TEXT NOT NULL,
    sincronizado_en TEXT
);

CREATE INDEX idx_ajustes_sucursal_fecha ON ajustes_inventario (sucursal_id, fecha);
CREATE INDEX idx_ajustes_documento ON ajustes_inventario (documento);

CREATE TABLE transformaciones (
    id                              TEXT PRIMARY KEY,
    folio                           TEXT NOT NULL UNIQUE,
    sucursal_id                     TEXT NOT NULL REFERENCES sucursales (id),
    producto_origen_id              TEXT NOT NULL REFERENCES productos (id),
    cantidad_origen                 REAL NOT NULL CHECK (cantidad_origen > 0),
    producto_destino_id             TEXT NOT NULL REFERENCES productos (id),
    cantidad_destino                REAL NOT NULL CHECK (cantidad_destino > 0),
    equivalencia                    REAL NOT NULL CHECK (equivalencia > 0),
    merma_origen                    REAL NOT NULL DEFAULT 0 CHECK (merma_origen >= 0),
    costo_origen_centavos           INTEGER NOT NULL CHECK (costo_origen_centavos >= 0),
    costo_unitario_destino_centavos INTEGER NOT NULL CHECK (costo_unitario_destino_centavos >= 0),
    lote_destino_id                 TEXT NOT NULL REFERENCES lotes (id),
    nota                            TEXT,
    usuario_id                      TEXT NOT NULL REFERENCES usuarios (id),
    fecha                           TEXT NOT NULL,
    sincronizado_en                 TEXT,
    CHECK (producto_origen_id <> producto_destino_id)
);

CREATE INDEX idx_transformaciones_sucursal_fecha ON transformaciones (sucursal_id, fecha);

CREATE TRIGGER trg_ajustes_inventario_ins AFTER INSERT ON ajustes_inventario
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('ajustes_inventario', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_transformaciones_ins AFTER INSERT ON transformaciones
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('transformaciones', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
