-- =====================================================================
-- Tablas del punto de venta en la nube
--
-- Mismo modelo y mismos nombres que la base local de las cajas (SQLite,
-- migraciones V1–V11 en src/main/resources/db/migration), con tipos de
-- Postgres:
--   * ids uuid generados en el equipo que crea el registro (así se puede
--     trabajar sin conexión); si no se manda, lo genera el servidor.
--   * Fechas timestamptz (UTC). Importes en centavos (bigint).
--     Cantidades numeric(14,3) en la unidad base del producto: piezas, o
--     kilos con 3 decimales (0.250 = 250 g).
--   * Valores fijos como text + check (más fácil de ampliar que un enum).
--   * No se borra nada: activo / eliminado_en / estado (borrado lógico).
--   * Llaves foráneas diferibles: un lote de cambios que llega en una sola
--     transacción no tiene que venir en orden de dependencias.
--   * sincronizado_en: hora del servidor en que llegó la última versión
--     de la fila (ver privado.marcar_sincronizado).
--
-- Diferencias con la base local:
--   * El hash de la contraseña vive aparte, en usuarios_credenciales,
--     para que solo el administrador y las cajas lo puedan leer.
--   * usuarios.auth_user_id y dispositivo.auth_user_id ligan cada persona
--     o caja con su cuenta de Supabase Auth.
--   * No se suben las columnas obsoletas de productos (precio, costo,
--     mínimo y código de barras: viven en lotes, límites y
--     presentaciones), ni lo que solo existe en cada terminal: intentos
--     fallidos de acceso, config_local, contadores, sync_outbox y ventas
--     en espera.
--   * Las órdenes de reabastecimiento no tienen el índice único de "una
--     pendiente por producto y sucursal": dos cajas de la misma sucursal
--     pueden generarla sin conexión al mismo tiempo.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Sucursales, cajas y usuarios
-- ---------------------------------------------------------------------

create table public.sucursales (
    id              uuid primary key default gen_random_uuid(),
    codigo          text not null,
    nombre          text not null,
    direccion       text,
    telefono        text,
    -- El almacén central es una sucursal especial que surte a las demás.
    es_almacen      boolean not null default false,
    activo          boolean not null default true,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now()
);

create unique index ux_sucursales_codigo on public.sucursales (lower(codigo));
create unique index ux_sucursales_un_almacen on public.sucursales (es_almacen) where es_almacen;

-- Cada caja (terminal de escritorio). En la base local es una sola fila
-- con la identidad del equipo; aquí están todas.
create table public.dispositivo (
    id              uuid primary key default gen_random_uuid(),
    nombre          text not null,
    sucursal_id     uuid references public.sucursales (id) deferrable initially deferred,
    -- Cuenta de Supabase Auth con la que la caja sincroniza.
    auth_user_id    uuid unique references auth.users (id) on delete set null,
    activo          boolean not null default true,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now()
);

create index idx_dispositivo_sucursal on public.dispositivo (sucursal_id);

create table public.usuarios (
    id                    uuid primary key default gen_random_uuid(),
    sucursal_id           uuid references public.sucursales (id) deferrable initially deferred,
    nombre_completo       text not null,
    usuario               text not null,
    rol                   text not null check (rol in ('ADMINISTRADOR', 'SUPERVISOR', 'CAJERO')),
    activo                boolean not null default true,
    debe_cambiar_password boolean not null default false,
    ultimo_acceso         timestamptz,
    -- Cuenta de Supabase Auth con la que la persona entra a web/móvil.
    auth_user_id          uuid unique references auth.users (id) on delete set null,
    creado_en             timestamptz not null default now(),
    actualizado_en        timestamptz not null default now(),
    eliminado_en          timestamptz,
    sincronizado_en       timestamptz not null default now()
);

create unique index ux_usuarios_usuario on public.usuarios (lower(usuario));
create index idx_usuarios_sucursal on public.usuarios (sucursal_id);

-- Hash BCrypt con el que la caja valida el acceso sin conexión.
create table public.usuarios_credenciales (
    usuario_id      uuid primary key references public.usuarios (id) on delete cascade deferrable initially deferred,
    password_hash   text not null,
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now()
);

create table public.sesiones (
    id              uuid primary key default gen_random_uuid(),
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    dispositivo_id  uuid not null references public.dispositivo (id) deferrable initially deferred,
    inicio          timestamptz not null,
    fin             timestamptz,
    motivo_cierre   text,
    modo            text not null check (modo in ('ONLINE', 'OFFLINE')),
    sincronizado_en timestamptz not null default now()
);

create index idx_sesiones_usuario on public.sesiones (usuario_id);
create index idx_sesiones_dispositivo on public.sesiones (dispositivo_id, inicio);

-- Intentos de acceso (exitosos y fallidos) para auditoría.
create table public.bitacora_accesos (
    id              uuid primary key default gen_random_uuid(),
    usuario_texto   text not null,
    usuario_id      uuid references public.usuarios (id) deferrable initially deferred,
    dispositivo_id  uuid references public.dispositivo (id) deferrable initially deferred,
    exitoso         boolean not null,
    motivo          text,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_bitacora_accesos_fecha on public.bitacora_accesos (fecha);
create index idx_bitacora_accesos_dispositivo on public.bitacora_accesos (dispositivo_id);

-- ---------------------------------------------------------------------
-- Catálogo
-- ---------------------------------------------------------------------

create table public.categorias (
    id              uuid primary key default gen_random_uuid(),
    nombre          text not null,
    color           text,
    orden           integer not null default 0,
    activo          boolean not null default true,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now()
);

create unique index ux_categorias_nombre on public.categorias (lower(nombre));

create table public.productos (
    id                       uuid primary key default gen_random_uuid(),
    -- Clave corta / PLU: para teclear rápido y en etiquetas de báscula.
    clave                    text,
    -- Control interno del almacén.
    codigo_inventario        text,
    nombre                   text not null,
    descripcion              text,
    categoria_id             uuid references public.categorias (id) deferrable initially deferred,
    unidad                   text not null check (unidad in ('PZA', 'KG')),
    sujeto_merma             boolean not null default false,
    disponibilidad           text not null default 'REGULAR'
                             check (disponibilidad in ('REGULAR', 'EDICION_ESPECIAL', 'TEMPORADA')),
    -- Peso de una unidad base (en productos por kilo, 1000 g) y cuánto de
    -- ese peso se pierde si el producto es sujeto a merma.
    gramaje_gramos           numeric(12, 3) check (gramaje_gramos is null or gramaje_gramos > 0),
    merma_gramos             numeric(12, 3) not null default 0 check (merma_gramos >= 0),
    -- Aviso opcional cuando el producto no se vende en cierto plazo.
    aviso_plazo              integer check (aviso_plazo is null or aviso_plazo > 0),
    aviso_unidad             text check (aviso_unidad is null or aviso_unidad in ('MINUTOS', 'HORAS', 'DIAS', 'SEMANAS')),
    aviso_admin              boolean not null default false,
    aviso_supervisor         boolean not null default false,
    aviso_caja               boolean not null default false,
    -- Ventana de venta de un producto de temporada y si se repite sola.
    temporada_desde          date,
    temporada_fin            date,
    temporada_repetir_cada   integer check (temporada_repetir_cada is null or temporada_repetir_cada > 0),
    temporada_repetir_unidad text check (temporada_repetir_unidad is null
                                         or temporada_repetir_unidad in ('DIAS', 'SEMANAS', 'MESES', 'ANIOS')),
    activo                   boolean not null default true,
    creado_en                timestamptz not null default now(),
    actualizado_en           timestamptz not null default now(),
    eliminado_en             timestamptz,
    sincronizado_en          timestamptz not null default now()
);

create unique index ux_productos_clave on public.productos (lower(clave));
create unique index ux_productos_codigo_inventario on public.productos (lower(codigo_inventario));
create index idx_productos_nombre on public.productos (lower(nombre));
create index idx_productos_categoria on public.productos (categoria_id);

create table public.presentaciones (
    id              uuid primary key default gen_random_uuid(),
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    nombre          text not null,
    -- GRANEL: se vende por peso (solo productos por kilo). UNIDAD: por piezas/cajas.
    tipo            text not null check (tipo in ('GRANEL', 'UNIDAD')),
    -- Cuántas unidades base contiene (1 caja = 12 piezas; 1 pieza de queso = 0.400 kg).
    factor          numeric(14, 3) not null check (factor > 0),
    codigo_barras   text unique,
    es_principal    boolean not null default false,
    activo          boolean not null default true,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now()
);

create index idx_presentaciones_producto on public.presentaciones (producto_id);

-- ---------------------------------------------------------------------
-- Inventario: lotes (PEPS, precio por lote) y movimientos
-- ---------------------------------------------------------------------

create table public.lotes (
    id                      uuid primary key default gen_random_uuid(),
    folio                   text,
    producto_id             uuid not null references public.productos (id) deferrable initially deferred,
    sucursal_id             uuid not null references public.sucursales (id) deferrable initially deferred,
    origen                  text not null check (origen in ('ENTRADA', 'SURTIDO', 'INICIAL', 'MIGRACION')),
    referencia_id           uuid,
    cantidad_inicial        numeric(14, 3) not null,
    -- Costo por unidad base (por pieza o por kilo).
    costo_unitario_centavos bigint not null default 0 check (costo_unitario_centavos >= 0),
    fecha_ingreso           timestamptz not null,
    usuario_id              uuid references public.usuarios (id) deferrable initially deferred,
    creado_en               timestamptz not null default now(),
    sincronizado_en         timestamptz not null default now()
);

create index idx_lotes_producto_sucursal on public.lotes (producto_id, sucursal_id, fecha_ingreso);
create index idx_lotes_sucursal on public.lotes (sucursal_id);

-- Precio de venta de cada presentación en un lote de sucursal.
create table public.lote_precios (
    id              uuid primary key default gen_random_uuid(),
    lote_id         uuid not null references public.lotes (id) on delete cascade deferrable initially deferred,
    presentacion_id uuid not null references public.presentaciones (id) deferrable initially deferred,
    precio_centavos bigint not null check (precio_centavos >= 0),
    sincronizado_en timestamptz not null default now(),
    unique (lote_id, presentacion_id)
);

create table public.movimientos_inventario (
    id              uuid primary key default gen_random_uuid(),
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    sucursal_id     uuid references public.sucursales (id) deferrable initially deferred,
    lote_id         uuid references public.lotes (id) deferrable initially deferred,
    tipo            text not null check (tipo in ('ENTRADA', 'VENTA', 'CANCELACION_VENTA', 'AJUSTE', 'MERMA',
                                                  'SURTIDO_SALIDA', 'SURTIDO_ENTRADA')),
    -- Positivo = entra mercancía, negativo = sale.
    cantidad        numeric(14, 3) not null,
    referencia_id   uuid,
    usuario_id      uuid references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    notas           text,
    sincronizado_en timestamptz not null default now()
);

create index idx_mov_inv_producto on public.movimientos_inventario (producto_id, sucursal_id);
create index idx_mov_inv_sucursal_fecha on public.movimientos_inventario (sucursal_id, fecha);
create index idx_mov_inv_referencia on public.movimientos_inventario (referencia_id);
create index idx_mov_inv_lote on public.movimientos_inventario (lote_id);

-- ---------------------------------------------------------------------
-- Caja: turnos, entradas/retiros y ventas
-- ---------------------------------------------------------------------

create table public.turnos_caja (
    id                         uuid primary key default gen_random_uuid(),
    sucursal_id                uuid references public.sucursales (id) deferrable initially deferred,
    dispositivo_id             uuid not null references public.dispositivo (id) deferrable initially deferred,
    usuario_id                 uuid not null references public.usuarios (id) deferrable initially deferred,
    abierto_en                 timestamptz not null,
    fondo_inicial_centavos     bigint not null check (fondo_inicial_centavos >= 0),
    cerrado_en                 timestamptz,
    efectivo_esperado_centavos bigint,
    efectivo_contado_centavos  bigint,
    diferencia_centavos        bigint,
    notas_cierre               text,
    estado                     text not null default 'ABIERTO' check (estado in ('ABIERTO', 'CERRADO')),
    -- Quién hizo el corte (el cajero o un supervisor).
    cerrado_por                uuid references public.usuarios (id) deferrable initially deferred,
    sincronizado_en            timestamptz not null default now()
);

-- Solo puede haber un turno abierto por caja.
create unique index ux_turno_abierto_dispositivo on public.turnos_caja (dispositivo_id) where estado = 'ABIERTO';
create index idx_turnos_sucursal_estado on public.turnos_caja (sucursal_id, estado);
create index idx_turnos_sucursal_cierre on public.turnos_caja (sucursal_id, cerrado_en);

create table public.movimientos_caja (
    id              uuid primary key default gen_random_uuid(),
    turno_id        uuid not null references public.turnos_caja (id) deferrable initially deferred,
    tipo            text not null check (tipo in ('ENTRADA', 'RETIRO')),
    monto_centavos  bigint not null check (monto_centavos > 0),
    concepto        text not null,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    autorizado_por  uuid references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_mov_caja_turno on public.movimientos_caja (turno_id);

create table public.ventas (
    id                 uuid primary key default gen_random_uuid(),
    -- PREFIJO-TERMINAL-consecutivo (ej. CENTRO-04E2-000001): único entre cajas.
    folio              text not null unique,
    turno_id           uuid not null references public.turnos_caja (id) deferrable initially deferred,
    sucursal_id        uuid references public.sucursales (id) deferrable initially deferred,
    dispositivo_id     uuid not null references public.dispositivo (id) deferrable initially deferred,
    usuario_id         uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha              timestamptz not null,
    articulos          numeric(14, 3) not null,
    total_centavos     bigint not null check (total_centavos >= 0),
    pagado_centavos    bigint not null,
    cambio_centavos    bigint not null default 0,
    estado             text not null default 'COMPLETADA' check (estado in ('COMPLETADA', 'CANCELADA')),
    cancelada_en       timestamptz,
    cancelada_por      uuid references public.usuarios (id) deferrable initially deferred,
    autorizada_por     uuid references public.usuarios (id) deferrable initially deferred,
    motivo_cancelacion text,
    sincronizado_en    timestamptz not null default now()
);

create index idx_ventas_turno on public.ventas (turno_id);
create index idx_ventas_sucursal_fecha on public.ventas (sucursal_id, fecha);
create index idx_ventas_fecha on public.ventas (fecha);

create table public.venta_detalle (
    id                       uuid primary key default gen_random_uuid(),
    venta_id                 uuid not null references public.ventas (id) on delete cascade deferrable initially deferred,
    renglon                  integer not null,
    producto_id              uuid not null references public.productos (id) deferrable initially deferred,
    presentacion_id          uuid references public.presentaciones (id) deferrable initially deferred,
    -- Copia del nombre y precio: el ticket no cambia si después cambia el catálogo.
    descripcion              text not null,
    unidad                   text not null,
    cantidad                 numeric(14, 3) not null check (cantidad > 0),
    -- Cantidad en unidad base del producto (cantidad × factor de la presentación).
    cantidad_base            numeric(14, 3),
    precio_unitario_centavos bigint not null,
    importe_centavos         bigint not null,
    sincronizado_en          timestamptz not null default now()
);

create index idx_venta_detalle_venta on public.venta_detalle (venta_id);
create index idx_venta_detalle_producto on public.venta_detalle (producto_id);

create table public.venta_pagos (
    id                uuid primary key default gen_random_uuid(),
    venta_id          uuid not null references public.ventas (id) on delete cascade deferrable initially deferred,
    metodo            text not null check (metodo in ('EFECTIVO', 'TARJETA', 'TRANSFERENCIA')),
    -- Monto aplicado a la venta (en efectivo ya descontado el cambio).
    monto_centavos    bigint not null check (monto_centavos >= 0),
    recibido_centavos bigint not null,
    referencia        text,
    sincronizado_en   timestamptz not null default now()
);

create index idx_venta_pagos_venta on public.venta_pagos (venta_id);

-- ---------------------------------------------------------------------
-- Almacén: mínimos/máximos, órdenes, surtidos, crédito y merma
-- ---------------------------------------------------------------------

create table public.limites_sucursal (
    id              uuid primary key default gen_random_uuid(),
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    minimo          numeric(14, 3) not null check (minimo >= 0),
    maximo          numeric(14, 3) not null check (maximo >= 0),
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now(),
    unique (sucursal_id, producto_id),
    check (maximo >= minimo)
);

create table public.ordenes_reabastecimiento (
    id                uuid primary key default gen_random_uuid(),
    folio             text not null unique,
    sucursal_id       uuid not null references public.sucursales (id) deferrable initially deferred,
    producto_id       uuid not null references public.productos (id) deferrable initially deferred,
    existencia        numeric(14, 3) not null,
    minimo            numeric(14, 3) not null,
    maximo            numeric(14, 3) not null,
    cantidad_sugerida numeric(14, 3) not null,
    estado            text not null default 'PENDIENTE' check (estado in ('PENDIENTE', 'APROBADA', 'RECHAZADA')),
    creado_en         timestamptz not null default now(),
    resuelto_por      uuid references public.usuarios (id) deferrable initially deferred,
    resuelto_en       timestamptz,
    motivo_rechazo    text,
    surtido_id        uuid,
    sincronizado_en   timestamptz not null default now()
);

create index idx_ordenes_sucursal_estado on public.ordenes_reabastecimiento (sucursal_id, producto_id, estado);

create table public.surtidos (
    id                   uuid primary key default gen_random_uuid(),
    folio                text not null unique,
    sucursal_id          uuid not null references public.sucursales (id) deferrable initially deferred,
    forma_pago           text not null check (forma_pago in ('EFECTIVO', 'CREDITO')),
    total_costo_centavos bigint not null,
    total_venta_centavos bigint not null,
    usuario_id           uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha                timestamptz not null,
    notas                text,
    sincronizado_en      timestamptz not null default now()
);

create index idx_surtidos_sucursal on public.surtidos (sucursal_id, fecha);

create table public.surtido_detalle (
    id                   uuid primary key default gen_random_uuid(),
    surtido_id           uuid not null references public.surtidos (id) on delete cascade deferrable initially deferred,
    renglon              integer not null,
    producto_id          uuid not null references public.productos (id) deferrable initially deferred,
    cantidad             numeric(14, 3) not null check (cantidad > 0),
    costo_total_centavos bigint not null,
    valor_venta_centavos bigint not null,
    lote_destino_id      uuid not null references public.lotes (id) deferrable initially deferred,
    sincronizado_en      timestamptz not null default now()
);

create index idx_surtido_detalle_surtido on public.surtido_detalle (surtido_id);

-- Crédito de cada sucursal con el almacén: cargos por surtidos a crédito y abonos.
create table public.movimientos_credito (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    tipo            text not null check (tipo in ('CARGO', 'ABONO')),
    monto_centavos  bigint not null check (monto_centavos > 0),
    surtido_id      uuid references public.surtidos (id) deferrable initially deferred,
    nota            text,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_credito_sucursal on public.movimientos_credito (sucursal_id, fecha);

create table public.mermas (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    cantidad        numeric(14, 3) not null check (cantidad > 0),
    costo_centavos  bigint not null,
    motivo          text not null,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_mermas_sucursal on public.mermas (sucursal_id, fecha);

-- ---------------------------------------------------------------------
-- Comisiones, metas, horarios y bloqueos de acceso
-- ---------------------------------------------------------------------

create table public.reglas_comision (
    id                    uuid primary key default gen_random_uuid(),
    folio                 text not null,
    nombre                text not null,
    -- META_CANTIDAD: al vender la cantidad meta del producto en el periodo.
    -- META_TOTAL:    al vender el importe meta en el periodo.
    -- POR_CANTIDAD:  por cada "cantidad meta" vendida del producto.
    tipo                  text not null check (tipo in ('META_CANTIDAD', 'META_TOTAL', 'POR_CANTIDAD')),
    producto_id           uuid references public.productos (id) deferrable initially deferred,
    cantidad_meta         numeric(14, 3),
    -- La meta de producto se mide en gramos o en piezas.
    medida                text not null default 'UNIDADES' check (medida in ('GRAMOS', 'UNIDADES')),
    importe_meta_centavos bigint,
    comision_centavos     bigint not null check (comision_centavos > 0),
    periodo               text not null check (periodo in ('DIARIO', 'SEMANAL', 'MENSUAL')),
    usuario_id            uuid references public.usuarios (id) deferrable initially deferred,
    sucursal_id           uuid references public.sucursales (id) deferrable initially deferred,
    activo                boolean not null default true,
    creado_en             timestamptz not null default now(),
    actualizado_en        timestamptz not null default now(),
    eliminado_en          timestamptz,
    sincronizado_en       timestamptz not null default now()
);

-- Meta mensual de venta por sucursal (módulo Indicadores).
create table public.metas_sucursal (
    id              uuid primary key default gen_random_uuid(),
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    anio            smallint not null check (anio between 2000 and 2100),
    mes             smallint not null check (mes between 1 and 12),
    meta_centavos   bigint not null check (meta_centavos >= 0),
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now(),
    unique (sucursal_id, anio, mes)
);

create table public.horarios (
    id              uuid primary key default gen_random_uuid(),
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    dia_semana      smallint not null check (dia_semana between 1 and 7), -- 1 = lunes
    labora          boolean not null default true,
    entrada         time,
    salida          time,
    descanso_inicio time,
    descanso_fin    time,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now(),
    unique (usuario_id, dia_semana)
);

-- Bloqueo por llegar tarde, salir antes, etc. Lo desbloquea el
-- administrador o un supervisor de la sucursal.
create table public.bloqueos_acceso (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    motivo          text not null,
    detalle         text,
    fecha           timestamptz not null,
    dispositivo_id  uuid,
    estado          text not null default 'PENDIENTE' check (estado in ('PENDIENTE', 'AUTORIZADO')),
    autorizado_por  uuid references public.usuarios (id) deferrable initially deferred,
    autorizado_en   timestamptz,
    usado_en        timestamptz,
    sincronizado_en timestamptz not null default now()
);

create index idx_bloqueos_usuario on public.bloqueos_acceso (usuario_id, estado);

-- ---------------------------------------------------------------------
-- Configuración, alertas y bitácora
-- ---------------------------------------------------------------------

create table public.configuracion_general (
    clave           text primary key,
    valor           text not null,
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now()
);

insert into public.configuracion_general (clave, valor) values ('alerta.dias_sin_venta', '30');

-- Días sin venta para el aviso de "producto sin venta", por sucursal.
create table public.alertas_sucursal (
    sucursal_id     uuid primary key references public.sucursales (id) deferrable initially deferred,
    dias_sin_venta  integer not null check (dias_sin_venta between 1 and 365),
    actualizado_en  timestamptz not null default now(),
    sincronizado_en timestamptz not null default now()
);

-- Toda acción tiene un folio único (PREFIJO-TERMINAL-n).
create table public.bitacora (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    fecha           timestamptz not null,
    usuario_id      uuid references public.usuarios (id) deferrable initially deferred,
    sucursal_id     uuid references public.sucursales (id) deferrable initially deferred,
    dispositivo_id  uuid,
    accion          text not null,
    entidad         text not null,
    entidad_id      text,
    descripcion     text not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_bitacora_fecha on public.bitacora (fecha);
create index idx_bitacora_sucursal_fecha on public.bitacora (sucursal_id, fecha);
create index idx_bitacora_entidad on public.bitacora (entidad_id);

-- ---------------------------------------------------------------------
-- Fechas automáticas en todas las tablas
-- ---------------------------------------------------------------------

do $$
declare
    t record;
begin
    for t in select c.relname
             from pg_class c
             join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public' and c.relkind = 'r'
    loop
        execute format('create trigger marcar_sincronizado before insert or update on public.%I '
                       'for each row execute function privado.marcar_sincronizado()', t.relname);

        if exists (select 1 from information_schema.columns
                   where table_schema = 'public' and table_name = t.relname and column_name = 'actualizado_en') then
            execute format('create trigger tocar_actualizado before update on public.%I '
                           'for each row execute function privado.tocar_actualizado()', t.relname);
        end if;

        -- Índice para que las cajas bajen solo lo que cambió.
        execute format('create index %I on public.%I (sincronizado_en)',
                       'idx_' || t.relname || '_sincronizado', t.relname);
    end loop;
end;
$$;
