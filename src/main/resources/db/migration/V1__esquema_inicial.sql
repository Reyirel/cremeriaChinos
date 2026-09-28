-- =====================================================================
-- V1: Esquema inicial (usuarios, sesiones y soporte offline)
--
-- Convenciones (pensadas para migrar después a Supabase/PostgreSQL):
--   * Llaves primarias TEXT con UUID generado en el equipo: permiten crear
--     registros sin conexión sin chocar con otros equipos.
--   * Fechas en UTC con formato ISO-8601 (ej. 2026-09-27T18:30:00.000Z).
--   * Borrado lógico con eliminado_en (nunca se borra físicamente algo
--     que ya pudo haberse sincronizado).
--   * sincronizado_en = última vez que el registro se envió a la nube.
--   * sync_outbox guarda qué registros tienen cambios pendientes de subir.
--     Se llena con triggers para que ningún cambio quede fuera.
-- =====================================================================

CREATE TABLE sucursales (
    id              TEXT PRIMARY KEY,
    codigo          TEXT NOT NULL UNIQUE COLLATE NOCASE,
    nombre          TEXT NOT NULL,
    direccion       TEXT,
    telefono        TEXT,
    activo          INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    creado_en       TEXT NOT NULL,
    actualizado_en  TEXT NOT NULL,
    eliminado_en    TEXT,
    sincronizado_en TEXT
);

-- Identidad de esta terminal (una sola fila). Se usa para saber qué equipo
-- generó cada sesión/venta cuando se sincronice con la nube.
CREATE TABLE dispositivo (
    id          TEXT PRIMARY KEY,
    nombre      TEXT NOT NULL,
    sucursal_id TEXT REFERENCES sucursales (id),
    creado_en   TEXT NOT NULL
);

CREATE TABLE usuarios (
    id                    TEXT PRIMARY KEY,
    sucursal_id           TEXT REFERENCES sucursales (id),
    nombre_completo       TEXT NOT NULL,
    usuario               TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash         TEXT NOT NULL,
    rol                   TEXT NOT NULL CHECK (rol IN ('ADMINISTRADOR', 'SUPERVISOR', 'CAJERO')),
    activo                INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0, 1)),
    debe_cambiar_password INTEGER NOT NULL DEFAULT 0 CHECK (debe_cambiar_password IN (0, 1)),
    -- Control de intentos: es local de cada terminal, no se sincroniza.
    intentos_fallidos     INTEGER NOT NULL DEFAULT 0,
    bloqueado_hasta       TEXT,
    ultimo_acceso         TEXT,
    creado_en             TEXT NOT NULL,
    actualizado_en        TEXT NOT NULL,
    eliminado_en          TEXT,
    sincronizado_en       TEXT
);

CREATE INDEX idx_usuarios_sucursal ON usuarios (sucursal_id);

CREATE TABLE sesiones (
    id              TEXT PRIMARY KEY,
    usuario_id      TEXT NOT NULL REFERENCES usuarios (id),
    dispositivo_id  TEXT NOT NULL REFERENCES dispositivo (id),
    inicio          TEXT NOT NULL,
    fin             TEXT,
    motivo_cierre   TEXT,
    modo            TEXT NOT NULL CHECK (modo IN ('ONLINE', 'OFFLINE')),
    sincronizado_en TEXT
);

CREATE INDEX idx_sesiones_usuario ON sesiones (usuario_id);
CREATE INDEX idx_sesiones_abiertas ON sesiones (fin) WHERE fin IS NULL;

-- Bitácora de intentos de acceso (exitosos y fallidos) para auditoría.
CREATE TABLE bitacora_accesos (
    id              TEXT PRIMARY KEY,
    usuario_texto   TEXT NOT NULL,
    usuario_id      TEXT REFERENCES usuarios (id),
    dispositivo_id  TEXT REFERENCES dispositivo (id),
    exitoso         INTEGER NOT NULL CHECK (exitoso IN (0, 1)),
    motivo          TEXT,
    fecha           TEXT NOT NULL,
    sincronizado_en TEXT
);

CREATE INDEX idx_bitacora_fecha ON bitacora_accesos (fecha);

-- Preferencias locales de la terminal (tema, último usuario, etc.).
CREATE TABLE config_local (
    clave TEXT PRIMARY KEY,
    valor TEXT
);

-- Cola de cambios pendientes de subir a la nube.
CREATE TABLE sync_outbox (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    tabla        TEXT NOT NULL,
    registro_id  TEXT NOT NULL,
    operacion    TEXT NOT NULL CHECK (operacion IN ('UPSERT', 'DELETE')),
    creado_en    TEXT NOT NULL,
    intentos     INTEGER NOT NULL DEFAULT 0,
    ultimo_error TEXT,
    enviado_en   TEXT
);

CREATE INDEX idx_outbox_pendientes ON sync_outbox (enviado_en, id);

-- ---------------------------------------------------------------------
-- Triggers: cualquier alta/cambio en tablas sincronizables se encola.
-- Si ya hay un pendiente para el mismo registro no se duplica, porque al
-- subir se envía el estado actual de la fila.
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_sucursales_ins AFTER INSERT ON sucursales
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'sucursales', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'sucursales' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_sucursales_upd
AFTER UPDATE OF codigo, nombre, direccion, telefono, activo, eliminado_en ON sucursales
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'sucursales', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'sucursales' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_usuarios_ins AFTER INSERT ON usuarios
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'usuarios', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'usuarios' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_usuarios_upd
AFTER UPDATE OF sucursal_id, nombre_completo, usuario, password_hash, rol, activo,
                debe_cambiar_password, eliminado_en ON usuarios
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'usuarios', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'usuarios' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_sesiones_ins AFTER INSERT ON sesiones
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'sesiones', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'sesiones' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_sesiones_upd AFTER UPDATE OF fin, motivo_cierre ON sesiones
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    SELECT 'sesiones', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (SELECT 1 FROM sync_outbox
                      WHERE tabla = 'sesiones' AND registro_id = NEW.id AND enviado_en IS NULL);
END;

CREATE TRIGGER trg_bitacora_ins AFTER INSERT ON bitacora_accesos
BEGIN
    INSERT INTO sync_outbox (tabla, registro_id, operacion, creado_en)
    VALUES ('bitacora_accesos', NEW.id, 'UPSERT', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
END;
