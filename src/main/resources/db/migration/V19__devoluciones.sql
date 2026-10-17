-- =====================================================================
-- V19: Devoluciones de clientes (total, parcial y cambio) y de sucursales
--      a la bodega
--
--   * devoluciones_venta: sobre un ticket de la sucursal. El reembolso en
--     efectivo sale del turno abierto (turno_id) y se resta del efectivo
--     esperado del corte; con otro método no toca el cajón. Un CAMBIO queda
--     ligado a la venta de la mercancía nueva (venta_cambio_id).
--   * devolucion_venta_detalle: renglón del ticket, cantidad (en unidades de
--     la presentación vendida y en base), importe devuelto proporcional a lo
--     cobrado y estado del producto. Solo lo VENDIBLE regresa a los mismos
--     lotes de la venta (movimiento DEVOLUCION_CLIENTE); lo dañado o caducado
--     queda fuera del inventario vendible, valuado al costo.
--   * devoluciones_sucursal: mercancía que una sucursal regresa a la bodega;
--     lo vendible entra al almacén en un lote nuevo con el costo de origen.
--     Puede generar una nota de crédito al saldo de la sucursal.
-- =====================================================================

CREATE TABLE devoluciones_venta (
    id                 TEXT PRIMARY KEY,
    folio              TEXT NOT NULL UNIQUE,
    venta_id           TEXT NOT NULL REFERENCES ventas (id),
    sucursal_id        TEXT NOT NULL REFERENCES sucursales (id),
    turno_id           TEXT REFERENCES turnos_caja (id),
    tipo               TEXT NOT NULL CHECK (tipo IN ('DEVOLUCION', 'CAMBIO')),
    metodo_reembolso   TEXT NOT NULL CHECK (metodo_reembolso IN ('EFECTIVO', 'TARJETA', 'TRANSFERENCIA', 'SIN_REEMBOLSO')),
    reembolso_centavos INTEGER NOT NULL CHECK (reembolso_centavos >= 0),
    motivo             TEXT NOT NULL,
    venta_cambio_id    TEXT REFERENCES ventas (id),
    usuario_id         TEXT NOT NULL REFERENCES usuarios (id),
    autorizado_por     TEXT REFERENCES usuarios (id),
    fecha              TEXT NOT NULL,
    sincronizado_en    TEXT
);

CREATE INDEX idx_devoluciones_venta_venta ON devoluciones_venta (venta_id);
CREATE INDEX idx_devoluciones_venta_turno ON devoluciones_venta (turno_id);
CREATE INDEX idx_devoluciones_venta_fecha ON devoluciones_venta (sucursal_id, fecha);

CREATE TABLE devolucion_venta_detalle (
    id               TEXT PRIMARY KEY,
    devolucion_id    TEXT NOT NULL REFERENCES devoluciones_venta (id),
    venta_detalle_id TEXT NOT NULL REFERENCES venta_detalle (id),
    producto_id      TEXT NOT NULL REFERENCES productos (id),
    cantidad         REAL NOT NULL CHECK (cantidad > 0),
    cantidad_base    REAL NOT NULL CHECK (cantidad_base > 0),
    importe_centavos INTEGER NOT NULL CHECK (importe_centavos >= 0),
    estado_producto  TEXT NOT NULL CHECK (estado_producto IN ('VENDIBLE', 'DANADO', 'CADUCADO')),
    costo_centavos   INTEGER NOT NULL CHECK (costo_centavos >= 0)
);

CREATE INDEX idx_devolucion_venta_detalle_devolucion ON devolucion_venta_detalle (devolucion_id);
CREATE INDEX idx_devolucion_venta_detalle_linea ON devolucion_venta_detalle (venta_detalle_id);

CREATE TABLE devoluciones_sucursal (
    id                    TEXT PRIMARY KEY,
    folio                 TEXT NOT NULL UNIQUE,
    sucursal_id           TEXT NOT NULL REFERENCES sucursales (id),
    almacen_id            TEXT NOT NULL REFERENCES sucursales (id),
    motivo                TEXT NOT NULL,
    total_costo_centavos  INTEGER NOT NULL CHECK (total_costo_centavos >= 0),
    nota_credito_centavos INTEGER NOT NULL DEFAULT 0 CHECK (nota_credito_centavos >= 0),
    usuario_id            TEXT NOT NULL REFERENCES usuarios (id),
    fecha                 TEXT NOT NULL,
    sincronizado_en       TEXT
);

CREATE INDEX idx_devoluciones_sucursal_fecha ON devoluciones_sucursal (sucursal_id, fecha);

CREATE TABLE devolucion_sucursal_detalle (
    id              TEXT PRIMARY KEY,
    devolucion_id   TEXT NOT NULL REFERENCES devoluciones_sucursal (id),
    renglon         INTEGER NOT NULL,
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    cantidad        REAL NOT NULL CHECK (cantidad > 0),
    estado_producto TEXT NOT NULL CHECK (estado_producto IN ('VENDIBLE', 'DANADO', 'CADUCADO')),
    costo_centavos  INTEGER NOT NULL CHECK (costo_centavos >= 0),
    lote_destino_id TEXT REFERENCES lotes (id)
);

CREATE INDEX idx_devolucion_sucursal_detalle ON devolucion_sucursal_detalle (devolucion_id);

CREATE TRIGGER trg_devoluciones_venta_ins AFTER INSERT ON devoluciones_venta
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('devoluciones_venta', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;

-- Un cambio se liga a su venta nueva después de cobrarla.
CREATE TRIGGER trg_devoluciones_venta_upd AFTER UPDATE ON devoluciones_venta
WHEN NEW.sincronizado_en IS OLD.sincronizado_en
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'devoluciones_venta', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'devoluciones_venta' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_devoluciones_sucursal_ins AFTER INSERT ON devoluciones_sucursal
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('devoluciones_sucursal', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
