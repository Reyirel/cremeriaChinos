-- =====================================================================
-- V2: Catálogo de productos, inventario, turnos de caja y ventas
--
--   * Importes en centavos (INTEGER) para no tener errores de redondeo.
--   * Cantidades en REAL con 3 decimales (kilos: 0.250 = 250 g).
--   * El inventario es un historial de movimientos (entradas/salidas):
--     así varios equipos pueden vender sin conexión y al sincronizar solo
--     se suman movimientos, sin pisarse la existencia unos a otros.
--   * Las filas hijas (detalle y pagos de venta) se suben junto con su
--     venta, por eso solo la tabla padre se encola en sync_outbox.
-- =====================================================================

CREATE TABLE categorias (
    id              TEXT PRIMARY KEY,
    nombre          TEXT NOT NULL UNIQUE COLLATE NOCASE,
    color           TEXT,
    orden           INTEGER NOT NULL DEFAULT 0,
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT
);

CREATE TABLE productos (
    id               TEXT PRIMARY KEY,
    codigo_barras    TEXT UNIQUE,
    -- Clave corta / PLU: se usa para teclear rápido y en etiquetas de báscula.
    clave            TEXT UNIQUE COLLATE NOCASE,
    nombre           TEXT NOT NULL,
    descripcion      TEXT,
    categoria_id     TEXT REFERENCES categorias (id),
    unidad           TEXT NOT NULL CHECK (unidad IN ('PZA', 'KG')),
    precio_centavos  INTEGER NOT NULL CHECK (precio_centavos >= 0),
    costo_centavos   INTEGER NOT NULL DEFAULT 0 CHECK (costo_centavos >= 0),
    inventario_minimo REAL NOT NULL DEFAULT 0,
    activo           INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en        TEXT NOT NULL,
    actualizado_en   TEXT NOT NULL,
    eliminado_en     TEXT,
    sincronizado_en  TEXT
);

CREATE INDEX idx_productos_nombre ON productos (nombre COLLATE NOCASE);
CREATE INDEX idx_productos_categoria ON productos (categoria_id);

CREATE TABLE movimientos_inventario (
    id              TEXT PRIMARY KEY,
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    sucursal_id     TEXT REFERENCES sucursales (id),
    tipo            TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'VENTA', 'CANCELACION_VENTA', 'AJUSTE', 'MERMA')),
    -- Positivo = entra mercancía, negativo = sale.
    cantidad        REAL NOT NULL,
    referencia_id   TEXT,
    usuario_id      TEXT REFERENCES usuarios (id),
    fecha           TEXT NOT NULL,
    notas           TEXT,
    sincronizado_en TEXT
);

CREATE INDEX idx_mov_inv_producto ON movimientos_inventario (producto_id, sucursal_id);
CREATE INDEX idx_mov_inv_referencia ON movimientos_inventario (referencia_id);

CREATE VIEW v_existencias AS
SELECT producto_id, sucursal_id, ROUND(SUM(cantidad), 3) AS existencia
FROM movimientos_inventario
GROUP BY producto_id, sucursal_id;

-- ---------------------------------------------------------------------
-- Caja
-- ---------------------------------------------------------------------

CREATE TABLE turnos_caja (
    id                          TEXT PRIMARY KEY,
    sucursal_id                 TEXT REFERENCES sucursales (id),
    dispositivo_id              TEXT NOT NULL REFERENCES dispositivo (id),
    usuario_id                  TEXT NOT NULL REFERENCES usuarios (id),
    abierto_en                  TEXT NOT NULL,
    fondo_inicial_centavos      INTEGER NOT NULL CHECK (fondo_inicial_centavos >= 0),
    cerrado_en                  TEXT,
    efectivo_esperado_centavos  INTEGER,
    efectivo_contado_centavos   INTEGER,
    diferencia_centavos         INTEGER,
    notas_cierre                TEXT,
    estado                      TEXT NOT NULL DEFAULT 'ABIERTO' CHECK (estado IN ('ABIERTO', 'CERRADO')),
    sincronizado_en             TEXT
);

-- Solo puede haber un turno abierto por terminal.
CREATE UNIQUE INDEX ux_turno_abierto_dispositivo ON turnos_caja (dispositivo_id) WHERE estado = 'ABIERTO';

CREATE TABLE movimientos_caja (
    id               TEXT PRIMARY KEY,
    turno_id         TEXT NOT NULL REFERENCES turnos_caja (id),
    tipo             TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'RETIRO')),
    monto_centavos   INTEGER NOT NULL CHECK (monto_centavos > 0),
    concepto         TEXT NOT NULL,
    usuario_id       TEXT NOT NULL REFERENCES usuarios (id),
    autorizado_por   TEXT REFERENCES usuarios (id),
    fecha            TEXT NOT NULL,
    sincronizado_en  TEXT
);

CREATE INDEX idx_mov_caja_turno ON movimientos_caja (turno_id);

-- Contadores locales para folios consecutivos por terminal.
CREATE TABLE contadores (
    clave TEXT PRIMARY KEY,
    valor INTEGER NOT NULL
);

CREATE TABLE ventas (
    id                   TEXT PRIMARY KEY,
    folio                TEXT NOT NULL UNIQUE,
    turno_id             TEXT NOT NULL REFERENCES turnos_caja (id),
    sucursal_id          TEXT REFERENCES sucursales (id),
    dispositivo_id       TEXT NOT NULL REFERENCES dispositivo (id),
    usuario_id           TEXT NOT NULL REFERENCES usuarios (id),
    fecha                TEXT NOT NULL,
    articulos            REAL NOT NULL,
    total_centavos       INTEGER NOT NULL CHECK (total_centavos >= 0),
    pagado_centavos      INTEGER NOT NULL,
    cambio_centavos      INTEGER NOT NULL DEFAULT 0,
    estado               TEXT NOT NULL DEFAULT 'COMPLETADA' CHECK (estado IN ('COMPLETADA', 'CANCELADA')),
    cancelada_en         TEXT,
    cancelada_por        TEXT REFERENCES usuarios (id),
    autorizada_por       TEXT REFERENCES usuarios (id),
    motivo_cancelacion   TEXT,
    sincronizado_en      TEXT
);

CREATE INDEX idx_ventas_turno ON ventas (turno_id);
CREATE INDEX idx_ventas_fecha ON ventas (fecha);

CREATE TABLE venta_detalle (
    id                        TEXT PRIMARY KEY,
    venta_id                  TEXT NOT NULL REFERENCES ventas (id),
    renglon                   INTEGER NOT NULL,
    producto_id               TEXT NOT NULL REFERENCES productos (id),
    -- Se guarda copia del nombre y precio: el ticket no cambia si después cambia el catálogo.
    descripcion               TEXT NOT NULL,
    unidad                    TEXT NOT NULL,
    cantidad                  REAL NOT NULL CHECK (cantidad > 0),
    precio_unitario_centavos  INTEGER NOT NULL,
    importe_centavos          INTEGER NOT NULL
);

CREATE INDEX idx_venta_detalle_venta ON venta_detalle (venta_id);

CREATE TABLE venta_pagos (
    id                  TEXT PRIMARY KEY,
    venta_id            TEXT NOT NULL REFERENCES ventas (id),
    metodo              TEXT NOT NULL CHECK (metodo IN ('EFECTIVO', 'TARJETA', 'TRANSFERENCIA')),
    -- Monto aplicado a la venta (en efectivo ya descontado el cambio).
    monto_centavos      INTEGER NOT NULL CHECK (monto_centavos >= 0),
    recibido_centavos   INTEGER NOT NULL,
    referencia          TEXT
);

CREATE INDEX idx_venta_pagos_venta ON venta_pagos (venta_id);

-- Ventas en espera y autoguardado de la venta en curso (solo locales, no se sincronizan).
CREATE TABLE ventas_espera (
    id          TEXT PRIMARY KEY,
    tipo        TEXT NOT NULL CHECK (tipo IN ('ESPERA', 'AUTOGUARDADO')),
    usuario_id  TEXT NOT NULL REFERENCES usuarios (id),
    nota        TEXT,
    creado_en   TEXT NOT NULL
);

CREATE TABLE ventas_espera_detalle (
    id               TEXT PRIMARY KEY,
    venta_espera_id  TEXT NOT NULL REFERENCES ventas_espera (id) ON DELETE CASCADE,
    renglon          INTEGER NOT NULL,
    producto_id      TEXT NOT NULL REFERENCES productos (id),
    cantidad         REAL NOT NULL
);

-- ---------------------------------------------------------------------
-- Triggers de sincronización
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_categorias_ins AFTER INSERT ON categorias
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'categorias', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'categorias' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_categorias_upd AFTER UPDATE OF nombre, color, orden, activo, eliminado_en ON categorias
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'categorias', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'categorias' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_productos_ins AFTER INSERT ON productos
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'productos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'productos' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_productos_upd
AFTER UPDATE OF codigo_barras, clave, nombre, descripcion, categoria_id, unidad, precio_centavos,
                costo_centavos, inventario_minimo, activo, eliminado_en ON productos
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'productos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'productos' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_mov_inv_ins AFTER INSERT ON movimientos_inventario
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('movimientos_inventario', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_turnos_ins AFTER INSERT ON turnos_caja
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'turnos_caja', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'turnos_caja' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_turnos_upd AFTER UPDATE OF estado, cerrado_en, efectivo_contado_centavos ON turnos_caja
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'turnos_caja', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'turnos_caja' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_mov_caja_ins AFTER INSERT ON movimientos_caja
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('movimientos_caja', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_ventas_ins AFTER INSERT ON ventas
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'ventas', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'ventas' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_ventas_upd AFTER UPDATE OF estado, cancelada_en ON ventas
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'ventas', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'ventas' AND registro_id = NEW.id AND enviado_en IS NULL);
END;
