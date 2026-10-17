-- =====================================================================
-- V17: Proveedores, compras con varias líneas y bonificaciones,
--      devoluciones a proveedor
--
--   * Una compra es un documento (proveedor, nota, fecha) con varias líneas;
--     cada línea crea su lote en el almacén con su propio costo, en una sola
--     transacción. Los lotes usan origen ENTRADA con el folio de la compra.
--   * Bonificación: cantidad pagada y cantidad bonificada por separado. Con la
--     política PRORRATEAR el costo por unidad baja (importe ÷ unidades
--     recibidas); con COSTO_CERO lo bonificado va en un lote aparte a costo 0.
--     La política se elige en configuracion_general ('compras.bonificacion').
--   * Cantidades de la compra en unidades de la presentación comprada (cajas,
--     piezas, kilos); lo que entra al inventario, en unidades base.
-- =====================================================================

CREATE TABLE proveedores (
    id              TEXT PRIMARY KEY,
    nombre          TEXT NOT NULL,
    rfc             TEXT,
    telefono        TEXT,
    contacto        TEXT,
    notas           TEXT,
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT
);

CREATE UNIQUE INDEX ux_proveedores_nombre ON proveedores (nombre COLLATE NOCASE) WHERE eliminado_en IS NULL;

CREATE TABLE compras (
    id                    TEXT PRIMARY KEY,
    folio                 TEXT NOT NULL UNIQUE,
    proveedor_id          TEXT NOT NULL REFERENCES proveedores (id),
    sucursal_id           TEXT NOT NULL REFERENCES sucursales (id),
    documento             TEXT,
    fecha_documento       TEXT,
    politica_bonificacion TEXT NOT NULL CHECK (politica_bonificacion IN ('PRORRATEAR', 'COSTO_CERO')),
    total_centavos        INTEGER NOT NULL CHECK (total_centavos >= 0),
    notas                 TEXT,
    usuario_id            TEXT NOT NULL REFERENCES usuarios (id),
    fecha                 TEXT NOT NULL,
    sincronizado_en       TEXT
);

CREATE INDEX idx_compras_fecha ON compras (fecha);
CREATE INDEX idx_compras_proveedor ON compras (proveedor_id, fecha);

CREATE TABLE compra_detalle (
    id                      TEXT PRIMARY KEY,
    compra_id               TEXT NOT NULL REFERENCES compras (id),
    renglon                 INTEGER NOT NULL,
    producto_id             TEXT NOT NULL REFERENCES productos (id),
    presentacion_id         TEXT REFERENCES presentaciones (id),
    factor                  REAL NOT NULL CHECK (factor > 0),
    cantidad_pagada         REAL NOT NULL CHECK (cantidad_pagada > 0),
    cantidad_bonificada     REAL NOT NULL DEFAULT 0 CHECK (cantidad_bonificada >= 0),
    costo_unitario_centavos INTEGER NOT NULL CHECK (costo_unitario_centavos >= 0),
    importe_centavos        INTEGER NOT NULL CHECK (importe_centavos >= 0),
    cantidad_recibida_base  REAL NOT NULL CHECK (cantidad_recibida_base > 0),
    costo_base_centavos     INTEGER NOT NULL CHECK (costo_base_centavos >= 0),
    lote_id                 TEXT NOT NULL REFERENCES lotes (id),
    lote_bonificado_id      TEXT REFERENCES lotes (id)
);

CREATE INDEX idx_compra_detalle_compra ON compra_detalle (compra_id);

CREATE TABLE devoluciones_proveedor (
    id                   TEXT PRIMARY KEY,
    folio                TEXT NOT NULL UNIQUE,
    proveedor_id         TEXT NOT NULL REFERENCES proveedores (id),
    compra_id            TEXT REFERENCES compras (id),
    sucursal_id          TEXT NOT NULL REFERENCES sucursales (id),
    motivo               TEXT NOT NULL,
    tratamiento          TEXT NOT NULL CHECK (tratamiento IN ('POR_DEFINIR', 'NOTA_CREDITO', 'REEMBOLSO',
                                                              'REPOSICION', 'SIN_COMPENSACION')),
    evidencia            TEXT,
    total_costo_centavos INTEGER NOT NULL CHECK (total_costo_centavos >= 0),
    usuario_id           TEXT NOT NULL REFERENCES usuarios (id),
    fecha                TEXT NOT NULL,
    sincronizado_en      TEXT
);

CREATE INDEX idx_devoluciones_proveedor_fecha ON devoluciones_proveedor (fecha);

CREATE TABLE devolucion_proveedor_detalle (
    id             TEXT PRIMARY KEY,
    devolucion_id  TEXT NOT NULL REFERENCES devoluciones_proveedor (id),
    renglon        INTEGER NOT NULL,
    producto_id    TEXT NOT NULL REFERENCES productos (id),
    lote_id        TEXT NOT NULL REFERENCES lotes (id),
    cantidad       REAL NOT NULL CHECK (cantidad > 0),
    costo_centavos INTEGER NOT NULL CHECK (costo_centavos >= 0)
);

CREATE INDEX idx_devolucion_proveedor_detalle ON devolucion_proveedor_detalle (devolucion_id);

INSERT INTO configuracion_general (clave, valor, actualizado_en)
VALUES ('compras.bonificacion', 'PRORRATEAR', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));

CREATE TRIGGER trg_proveedores_ins AFTER INSERT ON proveedores
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('proveedores', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_proveedores_upd AFTER UPDATE ON proveedores
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'proveedores', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'proveedores' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_compras_ins AFTER INSERT ON compras
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('compras', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_devoluciones_proveedor_ins AFTER INSERT ON devoluciones_proveedor
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('devoluciones_proveedor', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
