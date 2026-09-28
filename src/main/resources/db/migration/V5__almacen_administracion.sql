-- =====================================================================
-- V5: Almacén central y panel del administrador
--
--   * El almacén es una "sucursal" especial (es_almacen = 1) que surte a las demás.
--   * Productos con presentaciones (pieza, caja, kilo...) y lotes.
--     Cada lote tiene su costo y, en sucursal, su precio por presentación:
--     se vende primero el lote más antiguo (PEPS) y cuando se agota se usa
--     el precio del siguiente.
--   * Mínimos/máximos por sucursal y órdenes de reabastecimiento automáticas.
--   * Surtidos del almacén a sucursales en efectivo o a crédito (con saldo y abonos).
--   * Horarios de empleados y bloqueos de acceso.
--   * Reglas de comisión.
--   * Bitácora: toda acción tiene un folio único.
--
-- Las cantidades se guardan en la unidad base del producto: piezas, o kilos
-- con 3 decimales (0.250 = 250 g); en pantalla los productos a granel se
-- capturan en gramos.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Almacén central
-- ---------------------------------------------------------------------

ALTER TABLE sucursales ADD COLUMN es_almacen INTEGER NOT NULL DEFAULT 0 CHECK (es_almacen IN (0, 1));

INSERT INTO sucursales (id, codigo, nombre, es_almacen, creado_en, actualizado_en)
VALUES (lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2)
            || '-a' || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
        'ALMACEN', 'Almacén central', 1,
        strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));

-- Los administradores trabajan en el almacén.
UPDATE usuarios SET sucursal_id = (SELECT id FROM sucursales WHERE es_almacen = 1)
WHERE rol = 'ADMINISTRADOR';

DROP TRIGGER trg_sucursales_upd;

CREATE TRIGGER trg_sucursales_upd AFTER UPDATE ON sucursales
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'sucursales', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'sucursales' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

-- ---------------------------------------------------------------------
-- Productos: merma, disponibilidad y presentaciones
-- (precio_centavos, costo_centavos, inventario_minimo y codigo_barras de
--  productos quedan obsoletos: ahora viven en lotes, límites y presentaciones)
-- ---------------------------------------------------------------------

ALTER TABLE productos ADD COLUMN sujeto_merma INTEGER NOT NULL DEFAULT 0 CHECK (sujeto_merma IN (0, 1));
ALTER TABLE productos ADD COLUMN disponibilidad TEXT NOT NULL DEFAULT 'REGULAR'
    CHECK (disponibilidad IN ('REGULAR', 'EDICION_ESPECIAL', 'TEMPORADA'));

DROP TRIGGER trg_productos_upd;

CREATE TRIGGER trg_productos_upd AFTER UPDATE ON productos
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'productos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'productos' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TABLE presentaciones (
    id              TEXT PRIMARY KEY,
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    nombre          TEXT NOT NULL,
    -- GRANEL: se vende por peso (solo productos por kilo). UNIDAD: se vende por piezas/cajas.
    tipo            TEXT NOT NULL CHECK (tipo IN ('GRANEL', 'UNIDAD')),
    -- Cuántas unidades base contiene (1 caja = 12 piezas; 1 pieza de queso = 0.400 kg).
    factor          REAL NOT NULL CHECK (factor > 0),
    codigo_barras   TEXT UNIQUE,
    es_principal    INTEGER NOT NULL DEFAULT 0 CHECK (es_principal IN (0, 1)),
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT
);

CREATE INDEX idx_presentaciones_producto ON presentaciones (producto_id);

INSERT INTO presentaciones (id, producto_id, nombre, tipo, factor, codigo_barras, es_principal, creado_en, actualizado_en)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2)
           || '-a' || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
       id,
       CASE unidad WHEN 'KG' THEN 'Kilo' ELSE 'Pieza' END,
       CASE unidad WHEN 'KG' THEN 'GRANEL' ELSE 'UNIDAD' END,
       1, codigo_barras, 1,
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM productos;

-- ---------------------------------------------------------------------
-- Lotes
-- ---------------------------------------------------------------------

CREATE TABLE lotes (
    id                       TEXT PRIMARY KEY,
    folio                    TEXT,
    producto_id              TEXT NOT NULL REFERENCES productos (id),
    sucursal_id              TEXT NOT NULL REFERENCES sucursales (id),
    origen                   TEXT NOT NULL CHECK (origen IN ('ENTRADA', 'SURTIDO', 'INICIAL', 'MIGRACION')),
    referencia_id            TEXT,
    cantidad_inicial         REAL NOT NULL,
    -- Costo por unidad base (por pieza o por kilo).
    costo_unitario_centavos  INTEGER NOT NULL DEFAULT 0 CHECK (costo_unitario_centavos >= 0),
    fecha_ingreso            TEXT NOT NULL,
    usuario_id               TEXT REFERENCES usuarios (id),
    creado_en                TEXT NOT NULL,
    sincronizado_en          TEXT
);

CREATE INDEX idx_lotes_producto_sucursal ON lotes (producto_id, sucursal_id, fecha_ingreso);

-- Precio de venta de cada presentación en un lote de sucursal (se sube junto con su lote).
CREATE TABLE lote_precios (
    id               TEXT PRIMARY KEY,
    lote_id          TEXT NOT NULL REFERENCES lotes (id),
    presentacion_id  TEXT NOT NULL REFERENCES presentaciones (id),
    precio_centavos  INTEGER NOT NULL CHECK (precio_centavos >= 0),
    UNIQUE (lote_id, presentacion_id)
);

-- Movimientos de inventario: se reconstruye la tabla para agregar el lote y los tipos de surtido.
DROP VIEW v_existencias;
DROP TRIGGER trg_mov_inv_ins;

CREATE TABLE movimientos_inventario_v5 (
    id              TEXT PRIMARY KEY,
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    sucursal_id     TEXT REFERENCES sucursales (id),
    lote_id         TEXT REFERENCES lotes (id),
    tipo            TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'VENTA', 'CANCELACION_VENTA', 'AJUSTE', 'MERMA',
                                                  'SURTIDO_SALIDA', 'SURTIDO_ENTRADA')),
    cantidad        REAL NOT NULL,
    referencia_id   TEXT,
    usuario_id      TEXT REFERENCES usuarios (id),
    fecha           TEXT NOT NULL,
    notas           TEXT,
    sincronizado_en TEXT
);

INSERT INTO movimientos_inventario_v5 (id, producto_id, sucursal_id, lote_id, tipo, cantidad, referencia_id,
                                       usuario_id, fecha, notas, sincronizado_en)
SELECT id, producto_id, sucursal_id, NULL, tipo, cantidad, referencia_id, usuario_id, fecha, notas, sincronizado_en
FROM movimientos_inventario;

DROP TABLE movimientos_inventario;
ALTER TABLE movimientos_inventario_v5 RENAME TO movimientos_inventario;

CREATE INDEX idx_mov_inv_producto ON movimientos_inventario (producto_id, sucursal_id);
CREATE INDEX idx_mov_inv_referencia ON movimientos_inventario (referencia_id);
CREATE INDEX idx_mov_inv_lote ON movimientos_inventario (lote_id);

CREATE TRIGGER trg_mov_inv_ins AFTER INSERT ON movimientos_inventario
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('movimientos_inventario', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE VIEW v_existencias AS
SELECT producto_id, sucursal_id, ROUND(SUM(cantidad), 3) AS existencia
FROM movimientos_inventario
GROUP BY producto_id, sucursal_id;

CREATE VIEW v_existencias_lote AS
SELECT lote_id, ROUND(SUM(cantidad), 3) AS existencia
FROM movimientos_inventario
WHERE lote_id IS NOT NULL
GROUP BY lote_id;

-- El inventario que ya existía se convierte en un lote por producto y sucursal,
-- con el costo y precio que tenía el producto.
INSERT INTO lotes (id, producto_id, sucursal_id, origen, cantidad_inicial, costo_unitario_centavos,
                   fecha_ingreso, creado_en)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2)
           || '-a' || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
       m.producto_id, m.sucursal_id, 'MIGRACION',
       SUM(CASE WHEN m.tipo = 'ENTRADA' THEN m.cantidad ELSE 0 END),
       MAX(p.costo_centavos), MIN(m.fecha), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM movimientos_inventario m
JOIN productos p ON p.id = m.producto_id
WHERE m.sucursal_id IS NOT NULL
GROUP BY m.producto_id, m.sucursal_id;

UPDATE movimientos_inventario
SET lote_id = (SELECT l.id FROM lotes l
               WHERE l.producto_id = movimientos_inventario.producto_id
                 AND l.sucursal_id = movimientos_inventario.sucursal_id
                 AND l.origen = 'MIGRACION');

INSERT INTO lote_precios (id, lote_id, presentacion_id, precio_centavos)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2)
           || '-a' || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6))),
       l.id, pr.id, p.precio_centavos
FROM lotes l
JOIN productos p ON p.id = l.producto_id
JOIN presentaciones pr ON pr.producto_id = p.id AND pr.es_principal = 1
WHERE l.origen = 'MIGRACION';

-- ---------------------------------------------------------------------
-- Ventas: presentación vendida y cantidad en unidad base
-- ---------------------------------------------------------------------

ALTER TABLE venta_detalle ADD COLUMN presentacion_id TEXT REFERENCES presentaciones (id);
ALTER TABLE venta_detalle ADD COLUMN cantidad_base REAL;

UPDATE venta_detalle
SET presentacion_id = (SELECT pr.id FROM presentaciones pr
                       WHERE pr.producto_id = venta_detalle.producto_id AND pr.es_principal = 1),
    cantidad_base = cantidad;

ALTER TABLE ventas_espera ADD COLUMN total_centavos INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ventas_espera_detalle ADD COLUMN presentacion_id TEXT REFERENCES presentaciones (id);

UPDATE ventas_espera_detalle
SET presentacion_id = (SELECT pr.id FROM presentaciones pr
                       WHERE pr.producto_id = ventas_espera_detalle.producto_id AND pr.es_principal = 1);

-- ---------------------------------------------------------------------
-- Mínimos y máximos por sucursal; órdenes de reabastecimiento
-- ---------------------------------------------------------------------

CREATE TABLE limites_sucursal (
    id              TEXT PRIMARY KEY,
    sucursal_id     TEXT NOT NULL REFERENCES sucursales (id),
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    minimo          REAL NOT NULL CHECK (minimo >= 0),
    maximo          REAL NOT NULL CHECK (maximo >= 0),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    sincronizado_en TEXT,
    UNIQUE (sucursal_id, producto_id),
    CHECK (maximo >= minimo)
);

CREATE TABLE ordenes_reabastecimiento (
    id                 TEXT PRIMARY KEY,
    folio              TEXT NOT NULL UNIQUE,
    sucursal_id        TEXT NOT NULL REFERENCES sucursales (id),
    producto_id        TEXT NOT NULL REFERENCES productos (id),
    existencia         REAL NOT NULL,
    minimo             REAL NOT NULL,
    maximo             REAL NOT NULL,
    cantidad_sugerida  REAL NOT NULL,
    estado             TEXT NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA')),
    creado_en          TEXT NOT NULL,
    resuelto_por       TEXT REFERENCES usuarios (id),
    resuelto_en        TEXT,
    motivo_rechazo     TEXT,
    surtido_id         TEXT,
    sincronizado_en    TEXT
);

-- Una sola orden pendiente por producto y sucursal.
CREATE UNIQUE INDEX ux_orden_pendiente ON ordenes_reabastecimiento (sucursal_id, producto_id)
    WHERE estado = 'PENDIENTE';

-- ---------------------------------------------------------------------
-- Surtidos del almacén a sucursales, crédito y merma
-- ---------------------------------------------------------------------

CREATE TABLE surtidos (
    id                    TEXT PRIMARY KEY,
    folio                 TEXT NOT NULL UNIQUE,
    sucursal_id           TEXT NOT NULL REFERENCES sucursales (id),
    forma_pago            TEXT NOT NULL CHECK (forma_pago IN ('EFECTIVO', 'CREDITO')),
    total_costo_centavos  INTEGER NOT NULL,
    total_venta_centavos  INTEGER NOT NULL,
    usuario_id            TEXT NOT NULL REFERENCES usuarios (id),
    fecha                 TEXT NOT NULL,
    notas                 TEXT,
    sincronizado_en       TEXT
);

CREATE INDEX idx_surtidos_sucursal ON surtidos (sucursal_id, fecha);

CREATE TABLE surtido_detalle (
    id                    TEXT PRIMARY KEY,
    surtido_id            TEXT NOT NULL REFERENCES surtidos (id),
    renglon               INTEGER NOT NULL,
    producto_id           TEXT NOT NULL REFERENCES productos (id),
    cantidad              REAL NOT NULL CHECK (cantidad > 0),
    costo_total_centavos  INTEGER NOT NULL,
    valor_venta_centavos  INTEGER NOT NULL,
    lote_destino_id       TEXT NOT NULL REFERENCES lotes (id)
);

CREATE TABLE movimientos_credito (
    id               TEXT PRIMARY KEY,
    folio            TEXT NOT NULL UNIQUE,
    sucursal_id      TEXT NOT NULL REFERENCES sucursales (id),
    tipo             TEXT NOT NULL CHECK (tipo IN ('CARGO', 'ABONO')),
    monto_centavos   INTEGER NOT NULL CHECK (monto_centavos > 0),
    surtido_id       TEXT REFERENCES surtidos (id),
    nota             TEXT,
    usuario_id       TEXT NOT NULL REFERENCES usuarios (id),
    fecha            TEXT NOT NULL,
    sincronizado_en  TEXT
);

CREATE INDEX idx_credito_sucursal ON movimientos_credito (sucursal_id, fecha);

CREATE TABLE mermas (
    id               TEXT PRIMARY KEY,
    folio            TEXT NOT NULL UNIQUE,
    producto_id      TEXT NOT NULL REFERENCES productos (id),
    sucursal_id      TEXT NOT NULL REFERENCES sucursales (id),
    cantidad         REAL NOT NULL CHECK (cantidad > 0),
    costo_centavos   INTEGER NOT NULL,
    motivo           TEXT NOT NULL,
    usuario_id       TEXT NOT NULL REFERENCES usuarios (id),
    fecha            TEXT NOT NULL,
    sincronizado_en  TEXT
);

-- ---------------------------------------------------------------------
-- Comisiones
-- ---------------------------------------------------------------------

CREATE TABLE reglas_comision (
    id                   TEXT PRIMARY KEY,
    folio                TEXT NOT NULL,
    nombre               TEXT NOT NULL,
    -- META_CANTIDAD: al vender la cantidad meta del producto en el periodo.
    -- META_TOTAL:    al vender el importe meta en el periodo.
    -- POR_CANTIDAD:  por cada "cantidad meta" vendida del producto.
    tipo                 TEXT NOT NULL CHECK (tipo IN ('META_CANTIDAD', 'META_TOTAL', 'POR_CANTIDAD')),
    producto_id          TEXT REFERENCES productos (id),
    cantidad_meta        REAL,
    importe_meta_centavos INTEGER,
    comision_centavos    INTEGER NOT NULL CHECK (comision_centavos > 0),
    periodo              TEXT NOT NULL CHECK (periodo IN ('DIARIO', 'SEMANAL', 'MENSUAL')),
    usuario_id           TEXT REFERENCES usuarios (id),
    sucursal_id          TEXT REFERENCES sucursales (id),
    activo               INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en            TEXT NOT NULL,
    actualizado_en       TEXT NOT NULL,
    eliminado_en         TEXT,
    sincronizado_en      TEXT
);

-- ---------------------------------------------------------------------
-- Horarios y bloqueos de acceso
-- ---------------------------------------------------------------------

CREATE TABLE horarios (
    id               TEXT PRIMARY KEY,
    usuario_id       TEXT NOT NULL REFERENCES usuarios (id),
    dia_semana       INTEGER NOT NULL CHECK (dia_semana BETWEEN 1 AND 7), -- 1 = lunes
    labora           INTEGER NOT NULL DEFAULT 1 CHECK (labora IN (0, 1)),
    entrada          TEXT,
    salida           TEXT,
    descanso_inicio  TEXT,
    descanso_fin     TEXT,
    creado_en        TEXT NOT NULL,
    actualizado_en   TEXT NOT NULL,
    sincronizado_en  TEXT,
    UNIQUE (usuario_id, dia_semana)
);

CREATE TABLE bloqueos_acceso (
    id               TEXT PRIMARY KEY,
    folio            TEXT NOT NULL UNIQUE,
    usuario_id       TEXT NOT NULL REFERENCES usuarios (id),
    motivo           TEXT NOT NULL,
    detalle          TEXT,
    fecha            TEXT NOT NULL,
    dispositivo_id   TEXT,
    estado           TEXT NOT NULL DEFAULT 'PENDIENTE' CHECK (estado IN ('PENDIENTE', 'AUTORIZADO')),
    autorizado_por   TEXT REFERENCES usuarios (id),
    autorizado_en    TEXT,
    usado_en         TEXT,
    sincronizado_en  TEXT
);

CREATE INDEX idx_bloqueos_usuario ON bloqueos_acceso (usuario_id, estado);

-- ---------------------------------------------------------------------
-- Configuración general y bitácora
-- ---------------------------------------------------------------------

CREATE TABLE configuracion_general (
    clave           TEXT PRIMARY KEY,
    valor           TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    sincronizado_en TEXT
);

INSERT INTO configuracion_general (clave, valor, actualizado_en)
VALUES ('alerta.dias_sin_venta', '30', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));

CREATE TABLE bitacora (
    id               TEXT PRIMARY KEY,
    folio            TEXT NOT NULL UNIQUE,
    fecha            TEXT NOT NULL,
    usuario_id       TEXT REFERENCES usuarios (id),
    sucursal_id      TEXT REFERENCES sucursales (id),
    dispositivo_id   TEXT,
    accion           TEXT NOT NULL,
    entidad          TEXT NOT NULL,
    entidad_id       TEXT,
    descripcion      TEXT NOT NULL,
    sincronizado_en  TEXT
);

CREATE INDEX idx_bitacora_general_fecha ON bitacora (fecha);
CREATE INDEX idx_bitacora_general_entidad ON bitacora (entidad_id);

-- ---------------------------------------------------------------------
-- Sincronización de las tablas nuevas
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_presentaciones_ins AFTER INSERT ON presentaciones
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('presentaciones', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_presentaciones_upd AFTER UPDATE ON presentaciones
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'presentaciones', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'presentaciones' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_lotes_ins AFTER INSERT ON lotes
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('lotes', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_limites_ins AFTER INSERT ON limites_sucursal
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('limites_sucursal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_limites_upd AFTER UPDATE ON limites_sucursal
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'limites_sucursal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'limites_sucursal' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_ordenes_ins AFTER INSERT ON ordenes_reabastecimiento
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('ordenes_reabastecimiento', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_ordenes_upd AFTER UPDATE ON ordenes_reabastecimiento
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'ordenes_reabastecimiento', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'ordenes_reabastecimiento' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_surtidos_ins AFTER INSERT ON surtidos
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('surtidos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_credito_ins AFTER INSERT ON movimientos_credito
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('movimientos_credito', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_mermas_ins AFTER INSERT ON mermas
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('mermas', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_reglas_ins AFTER INSERT ON reglas_comision
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('reglas_comision', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_reglas_upd AFTER UPDATE ON reglas_comision
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'reglas_comision', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'reglas_comision' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_horarios_ins AFTER INSERT ON horarios
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('horarios', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_horarios_upd AFTER UPDATE ON horarios
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'horarios', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'horarios' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_bloqueos_ins AFTER INSERT ON bloqueos_acceso
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('bloqueos_acceso', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_bloqueos_upd AFTER UPDATE ON bloqueos_acceso
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'bloqueos_acceso', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'bloqueos_acceso' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_config_general_upd AFTER UPDATE ON configuracion_general
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'configuracion_general', NEW.clave, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'configuracion_general' AND registro_id = NEW.clave AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_bitacora_general_ins AFTER INSERT ON bitacora
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('bitacora', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
