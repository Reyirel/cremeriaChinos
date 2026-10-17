-- =====================================================================
-- V20: Recepción de surtidos, aclaración de diferencias y cancelación
--
--   * El surtido sigue entrando a la sucursal al registrarse (proceso
--     actual). La sucursal confirma lo que recibió renglón por renglón; si
--     llegó menos, la diferencia sale del lote de la sucursal (movimiento
--     DIFERENCIA_SURTIDO) y queda pendiente de aclarar.
--   * aclaraciones_surtido: el administrador decide qué pasó con una
--     diferencia (regresa al almacén o se registra como pérdida) y si se
--     acredita al saldo de la sucursal.
--   * cancelaciones_surtido: solo mientras la sucursal no haya vendido nada
--     de lo surtido ni confirmado la recepción; la mercancía vuelve a los
--     lotes de origen y el cargo a crédito se cancela con una nota de crédito.
--   * Un solo documento de cada tipo por surtido en esta caja (en la nube no
--     se exige, como las órdenes de reabasto: dos equipos sin conexión
--     podrían registrarlo a la vez).
--   * surtidos.requiere_recepcion: solo los surtidos registrados con esta
--     versión esperan la confirmación de la sucursal. Los anteriores ya
--     entraron a la sucursal con el proceso de antes y se consideran
--     recibidos (no aparecen como pendientes ni se pueden cancelar).
-- =====================================================================

ALTER TABLE surtidos ADD COLUMN requiere_recepcion INTEGER NOT NULL DEFAULT 0 CHECK (requiere_recepcion IN (0, 1));

CREATE TABLE recepciones_surtido (
    id              TEXT PRIMARY KEY,
    folio           TEXT NOT NULL UNIQUE,
    surtido_id      TEXT NOT NULL REFERENCES surtidos (id),
    sucursal_id     TEXT NOT NULL REFERENCES sucursales (id),
    con_diferencia  INTEGER NOT NULL CHECK (con_diferencia IN (0, 1)),
    notas           TEXT,
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    fecha           TEXT NOT NULL,
    sincronizado_en TEXT
);

CREATE UNIQUE INDEX ux_recepcion_por_surtido ON recepciones_surtido (surtido_id);

CREATE TABLE recepcion_surtido_detalle (
    id                 TEXT PRIMARY KEY,
    recepcion_id       TEXT NOT NULL REFERENCES recepciones_surtido (id),
    surtido_detalle_id TEXT NOT NULL REFERENCES surtido_detalle (id),
    producto_id        TEXT NOT NULL REFERENCES productos (id),
    lote_id            TEXT NOT NULL REFERENCES lotes (id),
    cantidad_enviada   REAL NOT NULL CHECK (cantidad_enviada > 0),
    cantidad_recibida  REAL NOT NULL CHECK (cantidad_recibida >= 0),
    diferencia         REAL NOT NULL CHECK (diferencia >= 0)
);

CREATE INDEX idx_recepcion_surtido_detalle ON recepcion_surtido_detalle (recepcion_id);

CREATE TABLE aclaraciones_surtido (
    id                    TEXT PRIMARY KEY,
    folio                 TEXT NOT NULL UNIQUE,
    recepcion_detalle_id  TEXT NOT NULL REFERENCES recepcion_surtido_detalle (id),
    decision              TEXT NOT NULL CHECK (decision IN ('REINGRESO_ALMACEN', 'PERDIDA')),
    cantidad              REAL NOT NULL CHECK (cantidad > 0),
    nota_credito_centavos INTEGER NOT NULL DEFAULT 0 CHECK (nota_credito_centavos >= 0),
    nota                  TEXT,
    usuario_id            TEXT NOT NULL REFERENCES usuarios (id),
    fecha                 TEXT NOT NULL,
    sincronizado_en       TEXT
);

CREATE UNIQUE INDEX ux_aclaracion_por_renglon ON aclaraciones_surtido (recepcion_detalle_id);

CREATE TABLE cancelaciones_surtido (
    id              TEXT PRIMARY KEY,
    folio           TEXT NOT NULL UNIQUE,
    surtido_id      TEXT NOT NULL REFERENCES surtidos (id),
    motivo          TEXT NOT NULL,
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    fecha           TEXT NOT NULL,
    sincronizado_en TEXT
);

CREATE UNIQUE INDEX ux_cancelacion_por_surtido ON cancelaciones_surtido (surtido_id);

CREATE TRIGGER trg_recepciones_surtido_ins AFTER INSERT ON recepciones_surtido
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('recepciones_surtido', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_aclaraciones_surtido_ins AFTER INSERT ON aclaraciones_surtido
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('aclaraciones_surtido', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

CREATE TRIGGER trg_cancelaciones_surtido_ins AFTER INSERT ON cancelaciones_surtido
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('cancelaciones_surtido', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
