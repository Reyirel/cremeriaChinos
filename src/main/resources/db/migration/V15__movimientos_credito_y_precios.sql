-- =====================================================================
-- V15: Tipos de movimiento nuevos, concepto del crédito y columnas de precio
--
--   * movimientos_inventario se reconstruye (como en V5) para ampliar los
--     tipos: compras, devoluciones (de cliente, de sucursal a bodega y a
--     proveedor), transformaciones, diferencias de entrega y cancelación de
--     surtidos. Se conservan todas las filas, los índices, el trigger de la
--     cola de sincronización y las vistas de existencias.
--   * movimientos_credito.concepto distingue un pago de una nota de crédito
--     (devolución, diferencia de entrega, cancelación, ajuste). Una nota de
--     crédito es un ABONO: el saldo sigue siendo cargos − abonos.
--   * Precio interno de bodega a sucursal: se guarda en el surtido aparte del
--     costo (que no cambia) y del valor a precio al público.
--   * venta_detalle guarda el precio de lista, el nivel (menudeo, mayoreo,
--     súper mayoreo) y la escala aplicada, para auditar el precio cobrado.
--   * presentaciones.merma_pct: merma de un derivado (p. ej. queso
--     rebanado) que se descuenta del producto original al venderlo.
--   * sucursales.canal_precio: lista de precios con la que se le surte
--     (cremerías o ruta de la Sierra).
-- =====================================================================

DROP VIEW v_existencias;
DROP VIEW v_existencias_lote;
DROP TRIGGER trg_mov_inv_ins;

CREATE TABLE movimientos_inventario_v15 (
    id              TEXT PRIMARY KEY,
    producto_id     TEXT NOT NULL REFERENCES productos (id),
    sucursal_id     TEXT REFERENCES sucursales (id),
    lote_id         TEXT REFERENCES lotes (id),
    tipo            TEXT NOT NULL CHECK (tipo IN ('ENTRADA', 'VENTA', 'CANCELACION_VENTA', 'AJUSTE', 'MERMA',
                                                  'SURTIDO_SALIDA', 'SURTIDO_ENTRADA', 'COMPRA',
                                                  'DEVOLUCION_CLIENTE', 'DEVOLUCION_SUCURSAL_SALIDA',
                                                  'DEVOLUCION_SUCURSAL_ENTRADA', 'DEVOLUCION_PROVEEDOR',
                                                  'TRANSFORMACION_SALIDA', 'TRANSFORMACION_ENTRADA',
                                                  'DIFERENCIA_SURTIDO', 'CANCELACION_SURTIDO')),
    cantidad        REAL NOT NULL,
    referencia_id   TEXT,
    usuario_id      TEXT REFERENCES usuarios (id),
    fecha           TEXT NOT NULL,
    notas           TEXT,
    sincronizado_en TEXT
);

INSERT INTO movimientos_inventario_v15 (id, producto_id, sucursal_id, lote_id, tipo, cantidad, referencia_id,
                                        usuario_id, fecha, notas, sincronizado_en)
SELECT id, producto_id, sucursal_id, lote_id, tipo, cantidad, referencia_id, usuario_id, fecha, notas, sincronizado_en
FROM movimientos_inventario;

DROP TABLE movimientos_inventario;
ALTER TABLE movimientos_inventario_v15 RENAME TO movimientos_inventario;

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

-- ---------------------------------------------------------------------
-- Crédito: concepto de cada movimiento y documento que lo origina
-- ---------------------------------------------------------------------

ALTER TABLE movimientos_credito ADD COLUMN concepto TEXT NOT NULL DEFAULT 'PAGO'
    CHECK (concepto IN ('SURTIDO', 'PAGO', 'DEVOLUCION', 'DIFERENCIA_ENTREGA', 'CANCELACION', 'AJUSTE'));
ALTER TABLE movimientos_credito ADD COLUMN referencia_id TEXT;

-- Los cargos que ya existían son surtidos a crédito (no hay trigger de cambio: no se reencolan).
UPDATE movimientos_credito SET concepto = 'SURTIDO', referencia_id = surtido_id WHERE tipo = 'CARGO';

CREATE INDEX idx_credito_referencia ON movimientos_credito (referencia_id);

-- ---------------------------------------------------------------------
-- Precios: interno de surtido, lista y nivel en la venta, canal de la sucursal
-- ---------------------------------------------------------------------

ALTER TABLE surtidos ADD COLUMN total_interno_centavos INTEGER CHECK (total_interno_centavos IS NULL OR total_interno_centavos >= 0);
ALTER TABLE surtidos ADD COLUMN canal TEXT CHECK (canal IS NULL OR canal IN ('BODEGA_SUCURSAL', 'RUTA_SIERRA'));
ALTER TABLE surtido_detalle ADD COLUMN importe_interno_centavos INTEGER
    CHECK (importe_interno_centavos IS NULL OR importe_interno_centavos >= 0);
ALTER TABLE surtido_detalle ADD COLUMN regla_precio_id TEXT;

ALTER TABLE venta_detalle ADD COLUMN precio_lista_centavos INTEGER;
ALTER TABLE venta_detalle ADD COLUMN nivel_precio TEXT
    CHECK (nivel_precio IS NULL OR nivel_precio IN ('MENUDEO', 'MAYOREO', 'SUPER_MAYOREO'));
ALTER TABLE venta_detalle ADD COLUMN escala_id TEXT;

ALTER TABLE sucursales ADD COLUMN canal_precio TEXT NOT NULL DEFAULT 'BODEGA_SUCURSAL'
    CHECK (canal_precio IN ('BODEGA_SUCURSAL', 'RUTA_SIERRA'));

ALTER TABLE presentaciones ADD COLUMN merma_pct REAL NOT NULL DEFAULT 0 CHECK (merma_pct >= 0 AND merma_pct < 100);
