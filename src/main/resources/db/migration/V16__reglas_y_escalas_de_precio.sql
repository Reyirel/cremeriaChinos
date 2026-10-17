-- =====================================================================
-- V16: Reglas de precio por canal y escalas de mayoreo
--
--   * reglas_precio: porcentaje sobre el costo por canal comercial.
--       canal  PUBLICO          precio al cliente en la caja (solo sugiere al surtir)
--              BODEGA_SUCURSAL  precio interno de bodega a cremerías (lo que se carga al crédito)
--              RUTA_SIERRA      precio interno para la ruta de la Sierra
--       tipo   RECARGO_COSTO    precio = costo × (1 + porcentaje/100)
--              MARGEN_PRECIO    precio = costo ÷ (1 − porcentaje/100)  (margen sobre el precio)
--     Alcance opcional por categoría, producto, presentación y sucursal; la
--     regla más específica gana. Vigencia por fechas (YYYY-MM-DD, hasta
--     inclusive; sin fin = nula). Sin regla, el porcentaje es 0.
--   * escalas_precio: menudeo es el precio del lote; mayoreo y súper mayoreo
--     se definen por producto (opcionalmente por presentación y sucursal)
--     con una cantidad mínima en unidades base y un descuento (%) o un
--     precio fijo por unidad de la presentación.
-- =====================================================================

CREATE TABLE reglas_precio (
    id              TEXT PRIMARY KEY,
    folio           TEXT NOT NULL UNIQUE,
    canal           TEXT NOT NULL CHECK (canal IN ('PUBLICO', 'BODEGA_SUCURSAL', 'RUTA_SIERRA')),
    tipo            TEXT NOT NULL CHECK (tipo IN ('RECARGO_COSTO', 'MARGEN_PRECIO')),
    porcentaje      REAL NOT NULL CHECK (porcentaje >= 0 AND porcentaje <= 1000),
    categoria_id    TEXT REFERENCES categorias (id),
    producto_id     TEXT REFERENCES productos (id),
    presentacion_id TEXT REFERENCES presentaciones (id),
    sucursal_id     TEXT REFERENCES sucursales (id),
    vigente_desde   TEXT NOT NULL,
    vigente_hasta   TEXT,
    notas           TEXT,
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT,
    CHECK (tipo = 'RECARGO_COSTO' OR porcentaje < 100),
    CHECK (categoria_id IS NULL OR producto_id IS NULL),
    CHECK (presentacion_id IS NULL OR producto_id IS NOT NULL),
    CHECK (vigente_hasta IS NULL OR vigente_hasta >= vigente_desde)
);

CREATE INDEX idx_reglas_precio_canal ON reglas_precio (canal, activo);
CREATE INDEX idx_reglas_precio_producto ON reglas_precio (producto_id);

CREATE TABLE escalas_precio (
    id              TEXT PRIMARY KEY,
    folio           TEXT NOT NULL UNIQUE,
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    presentacion_id TEXT REFERENCES presentaciones (id),
    sucursal_id     TEXT REFERENCES sucursales (id),
    nivel           TEXT NOT NULL CHECK (nivel IN ('MAYOREO', 'SUPER_MAYOREO')),
    cantidad_minima REAL NOT NULL CHECK (cantidad_minima > 0),
    tipo            TEXT NOT NULL CHECK (tipo IN ('DESCUENTO_PCT', 'PRECIO_FIJO')),
    descuento_pct   REAL CHECK (descuento_pct IS NULL OR (descuento_pct > 0 AND descuento_pct < 100)),
    precio_centavos INTEGER CHECK (precio_centavos IS NULL OR precio_centavos > 0),
    vigente_desde   TEXT NOT NULL,
    vigente_hasta   TEXT,
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT,
    CHECK ((tipo = 'DESCUENTO_PCT' AND descuento_pct IS NOT NULL AND precio_centavos IS NULL)
        OR (tipo = 'PRECIO_FIJO' AND precio_centavos IS NOT NULL AND descuento_pct IS NULL
            AND presentacion_id IS NOT NULL)),
    CHECK (vigente_hasta IS NULL OR vigente_hasta >= vigente_desde)
);

CREATE INDEX idx_escalas_precio_producto ON escalas_precio (producto_id);

CREATE TRIGGER trg_reglas_precio_ins AFTER INSERT ON reglas_precio
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('reglas_precio', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_reglas_precio_upd AFTER UPDATE ON reglas_precio
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'reglas_precio', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'reglas_precio' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_escalas_precio_ins AFTER INSERT ON escalas_precio
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('escalas_precio', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_escalas_precio_upd AFTER UPDATE ON escalas_precio
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'escalas_precio', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'escalas_precio' AND registro_id = NEW.id AND enviado_en IS NULL);
END;
