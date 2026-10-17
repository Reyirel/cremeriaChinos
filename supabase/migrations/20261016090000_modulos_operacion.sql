-- =====================================================================
-- Módulos de operación 2026 (equivalente a V15–V20 de la base local)
--
--   * Tipos de movimiento nuevos (compras, devoluciones, transformaciones,
--     diferencias y cancelaciones de surtido).
--   * Crédito: concepto del movimiento (pago o nota de crédito) y documento.
--   * Precios: reglas por canal, escalas de mayoreo, precio interno del
--     surtido, nivel de precio en la venta, canal de la sucursal, merma de
--     derivados.
--   * Compras, proveedores, devoluciones a proveedor.
--   * Ajustes de inventario y transformaciones.
--   * Devoluciones de clientes y de sucursales a la bodega.
--   * Recepción, aclaración y cancelación de surtidos.
--   * Corrige gastos y nomina_semanal: no tenían los triggers de
--     sincronizado_en/actualizado_en, así que sus cambios no bajaban a las
--     otras cajas.
--
-- ORDEN DE DESPLIEGUE: aplicar esta migración ANTES de instalar la versión de
-- la caja que trae V15–V20. Si la caja nueva sube un movimiento de un tipo que
-- la nube aún no acepta, la nube lo rechaza; queda en la cola y se reintenta
-- al abrir la app, pero no se pierde.
--
-- Como en el resto del esquema: ids uuid generados en el equipo, llaves
-- foráneas diferibles, tablas de movimientos solo alta, RLS por sucursal.
-- Los índices únicos de "un documento por surtido" de la caja no se exigen
-- aquí (dos equipos sin conexión podrían registrarlo a la vez).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Columnas nuevas en tablas existentes
-- ---------------------------------------------------------------------

alter table public.movimientos_inventario drop constraint movimientos_inventario_tipo_check;
alter table public.movimientos_inventario add constraint movimientos_inventario_tipo_check
    check (tipo in ('ENTRADA', 'VENTA', 'CANCELACION_VENTA', 'AJUSTE', 'MERMA', 'SURTIDO_SALIDA', 'SURTIDO_ENTRADA',
                    'COMPRA', 'DEVOLUCION_CLIENTE', 'DEVOLUCION_SUCURSAL_SALIDA', 'DEVOLUCION_SUCURSAL_ENTRADA',
                    'DEVOLUCION_PROVEEDOR', 'TRANSFORMACION_SALIDA', 'TRANSFORMACION_ENTRADA', 'DIFERENCIA_SURTIDO',
                    'CANCELACION_SURTIDO'));

alter table public.movimientos_credito add column concepto text not null default 'PAGO'
    check (concepto in ('SURTIDO', 'PAGO', 'DEVOLUCION', 'DIFERENCIA_ENTREGA', 'CANCELACION', 'AJUSTE'));
alter table public.movimientos_credito add column referencia_id uuid;
update public.movimientos_credito set concepto = 'SURTIDO', referencia_id = surtido_id where tipo = 'CARGO';
create index idx_credito_referencia on public.movimientos_credito (referencia_id);

alter table public.surtidos add column total_interno_centavos bigint
    check (total_interno_centavos is null or total_interno_centavos >= 0);
alter table public.surtidos add column canal text check (canal is null or canal in ('BODEGA_SUCURSAL', 'RUTA_SIERRA'));
-- Solo los surtidos de la versión nueva esperan confirmación; los anteriores cuentan como recibidos.
alter table public.surtidos add column requiere_recepcion boolean not null default false;
alter table public.surtido_detalle add column importe_interno_centavos bigint
    check (importe_interno_centavos is null or importe_interno_centavos >= 0);
alter table public.surtido_detalle add column regla_precio_id uuid;

alter table public.venta_detalle add column precio_lista_centavos bigint;
alter table public.venta_detalle add column nivel_precio text
    check (nivel_precio is null or nivel_precio in ('MENUDEO', 'MAYOREO', 'SUPER_MAYOREO'));
alter table public.venta_detalle add column escala_id uuid;

alter table public.sucursales add column canal_precio text not null default 'BODEGA_SUCURSAL'
    check (canal_precio in ('BODEGA_SUCURSAL', 'RUTA_SIERRA'));

alter table public.presentaciones add column merma_pct numeric(6, 3) not null default 0
    check (merma_pct >= 0 and merma_pct < 100);

insert into public.configuracion_general (clave, valor) values ('compras.bonificacion', 'PRORRATEAR')
on conflict (clave) do nothing;

-- ---------------------------------------------------------------------
-- Precios
-- ---------------------------------------------------------------------

create table public.reglas_precio (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    canal           text not null check (canal in ('PUBLICO', 'BODEGA_SUCURSAL', 'RUTA_SIERRA')),
    tipo            text not null check (tipo in ('RECARGO_COSTO', 'MARGEN_PRECIO')),
    porcentaje      numeric(8, 3) not null check (porcentaje >= 0 and porcentaje <= 1000),
    categoria_id    uuid references public.categorias (id) deferrable initially deferred,
    producto_id     uuid references public.productos (id) deferrable initially deferred,
    presentacion_id uuid references public.presentaciones (id) deferrable initially deferred,
    sucursal_id     uuid references public.sucursales (id) deferrable initially deferred,
    vigente_desde   date not null,
    vigente_hasta   date,
    notas           text,
    activo          boolean not null default true,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now(),
    check (tipo = 'RECARGO_COSTO' or porcentaje < 100),
    check (categoria_id is null or producto_id is null),
    check (presentacion_id is null or producto_id is not null),
    check (vigente_hasta is null or vigente_hasta >= vigente_desde)
);

create index idx_reglas_precio_canal on public.reglas_precio (canal, activo);

create table public.escalas_precio (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    presentacion_id uuid references public.presentaciones (id) deferrable initially deferred,
    sucursal_id     uuid references public.sucursales (id) deferrable initially deferred,
    nivel           text not null check (nivel in ('MAYOREO', 'SUPER_MAYOREO')),
    cantidad_minima numeric(14, 3) not null check (cantidad_minima > 0),
    tipo            text not null check (tipo in ('DESCUENTO_PCT', 'PRECIO_FIJO')),
    descuento_pct   numeric(6, 3) check (descuento_pct is null or (descuento_pct > 0 and descuento_pct < 100)),
    precio_centavos bigint check (precio_centavos is null or precio_centavos > 0),
    vigente_desde   date not null,
    vigente_hasta   date,
    activo          boolean not null default true,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now(),
    check ((tipo = 'DESCUENTO_PCT' and descuento_pct is not null and precio_centavos is null)
        or (tipo = 'PRECIO_FIJO' and precio_centavos is not null and descuento_pct is null
            and presentacion_id is not null)),
    check (vigente_hasta is null or vigente_hasta >= vigente_desde)
);

create index idx_escalas_precio_producto on public.escalas_precio (producto_id);

-- ---------------------------------------------------------------------
-- Compras y proveedores
-- ---------------------------------------------------------------------

create table public.proveedores (
    id              uuid primary key default gen_random_uuid(),
    nombre          text not null,
    rfc             text,
    telefono        text,
    contacto        text,
    notas           text,
    activo          boolean not null default true,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now()
);

create table public.compras (
    id                    uuid primary key default gen_random_uuid(),
    folio                 text not null unique,
    proveedor_id          uuid not null references public.proveedores (id) deferrable initially deferred,
    sucursal_id           uuid not null references public.sucursales (id) deferrable initially deferred,
    documento             text,
    fecha_documento       date,
    politica_bonificacion text not null check (politica_bonificacion in ('PRORRATEAR', 'COSTO_CERO')),
    total_centavos        bigint not null check (total_centavos >= 0),
    notas                 text,
    usuario_id            uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha                 timestamptz not null,
    sincronizado_en       timestamptz not null default now()
);

create index idx_compras_fecha on public.compras (fecha);

create table public.compra_detalle (
    id                      uuid primary key default gen_random_uuid(),
    compra_id               uuid not null references public.compras (id) on delete cascade deferrable initially deferred,
    renglon                 integer not null,
    producto_id             uuid not null references public.productos (id) deferrable initially deferred,
    presentacion_id         uuid references public.presentaciones (id) deferrable initially deferred,
    factor                  numeric(14, 3) not null check (factor > 0),
    cantidad_pagada         numeric(14, 3) not null check (cantidad_pagada > 0),
    cantidad_bonificada     numeric(14, 3) not null default 0 check (cantidad_bonificada >= 0),
    costo_unitario_centavos bigint not null check (costo_unitario_centavos >= 0),
    importe_centavos        bigint not null check (importe_centavos >= 0),
    cantidad_recibida_base  numeric(14, 3) not null check (cantidad_recibida_base > 0),
    costo_base_centavos     bigint not null check (costo_base_centavos >= 0),
    lote_id                 uuid not null references public.lotes (id) deferrable initially deferred,
    lote_bonificado_id      uuid references public.lotes (id) deferrable initially deferred,
    sincronizado_en         timestamptz not null default now()
);

create index idx_compra_detalle_compra on public.compra_detalle (compra_id);

create table public.devoluciones_proveedor (
    id                   uuid primary key default gen_random_uuid(),
    folio                text not null unique,
    proveedor_id         uuid not null references public.proveedores (id) deferrable initially deferred,
    compra_id            uuid references public.compras (id) deferrable initially deferred,
    sucursal_id          uuid not null references public.sucursales (id) deferrable initially deferred,
    motivo               text not null,
    tratamiento          text not null check (tratamiento in ('POR_DEFINIR', 'NOTA_CREDITO', 'REEMBOLSO', 'REPOSICION',
                                                              'SIN_COMPENSACION')),
    evidencia            text,
    total_costo_centavos bigint not null check (total_costo_centavos >= 0),
    usuario_id           uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha                timestamptz not null,
    sincronizado_en      timestamptz not null default now()
);

create table public.devolucion_proveedor_detalle (
    id              uuid primary key default gen_random_uuid(),
    devolucion_id   uuid not null references public.devoluciones_proveedor (id) on delete cascade
                        deferrable initially deferred,
    renglon         integer not null,
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    lote_id         uuid not null references public.lotes (id) deferrable initially deferred,
    cantidad        numeric(14, 3) not null check (cantidad > 0),
    costo_centavos  bigint not null check (costo_centavos >= 0),
    sincronizado_en timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Ajustes y transformaciones
-- ---------------------------------------------------------------------

create table public.ajustes_inventario (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    motivo          text not null check (motivo in ('SOBRANTE', 'FALTANTE', 'CADUCIDAD', 'DANO', 'CORRECCION',
                                                    'CORTESIA', 'DEVOLUCION', 'OTRO')),
    cantidad        numeric(14, 3) not null check (cantidad <> 0),
    costo_centavos  bigint not null,
    documento       text,
    nota            text,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_ajustes_sucursal_fecha on public.ajustes_inventario (sucursal_id, fecha);

create table public.transformaciones (
    id                              uuid primary key default gen_random_uuid(),
    folio                           text not null unique,
    sucursal_id                     uuid not null references public.sucursales (id) deferrable initially deferred,
    producto_origen_id              uuid not null references public.productos (id) deferrable initially deferred,
    cantidad_origen                 numeric(14, 3) not null check (cantidad_origen > 0),
    producto_destino_id             uuid not null references public.productos (id) deferrable initially deferred,
    cantidad_destino                numeric(14, 3) not null check (cantidad_destino > 0),
    equivalencia                    numeric(14, 6) not null check (equivalencia > 0),
    merma_origen                    numeric(14, 3) not null default 0 check (merma_origen >= 0),
    costo_origen_centavos           bigint not null check (costo_origen_centavos >= 0),
    costo_unitario_destino_centavos bigint not null check (costo_unitario_destino_centavos >= 0),
    lote_destino_id                 uuid not null references public.lotes (id) deferrable initially deferred,
    nota                            text,
    usuario_id                      uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha                           timestamptz not null,
    sincronizado_en                 timestamptz not null default now(),
    check (producto_origen_id <> producto_destino_id)
);

create index idx_transformaciones_sucursal_fecha on public.transformaciones (sucursal_id, fecha);

-- ---------------------------------------------------------------------
-- Devoluciones
-- ---------------------------------------------------------------------

create table public.devoluciones_venta (
    id                 uuid primary key default gen_random_uuid(),
    folio              text not null unique,
    venta_id           uuid not null references public.ventas (id) deferrable initially deferred,
    sucursal_id        uuid not null references public.sucursales (id) deferrable initially deferred,
    turno_id           uuid references public.turnos_caja (id) deferrable initially deferred,
    tipo               text not null check (tipo in ('DEVOLUCION', 'CAMBIO')),
    metodo_reembolso   text not null check (metodo_reembolso in ('EFECTIVO', 'TARJETA', 'TRANSFERENCIA', 'SIN_REEMBOLSO')),
    reembolso_centavos bigint not null check (reembolso_centavos >= 0),
    motivo             text not null,
    venta_cambio_id    uuid references public.ventas (id) deferrable initially deferred,
    usuario_id         uuid not null references public.usuarios (id) deferrable initially deferred,
    autorizado_por     uuid references public.usuarios (id) deferrable initially deferred,
    fecha              timestamptz not null,
    sincronizado_en    timestamptz not null default now()
);

create index idx_devoluciones_venta_venta on public.devoluciones_venta (venta_id);
create index idx_devoluciones_venta_fecha on public.devoluciones_venta (sucursal_id, fecha);

create table public.devolucion_venta_detalle (
    id               uuid primary key default gen_random_uuid(),
    devolucion_id    uuid not null references public.devoluciones_venta (id) on delete cascade deferrable initially deferred,
    venta_detalle_id uuid not null references public.venta_detalle (id) deferrable initially deferred,
    producto_id      uuid not null references public.productos (id) deferrable initially deferred,
    cantidad         numeric(14, 3) not null check (cantidad > 0),
    cantidad_base    numeric(14, 3) not null check (cantidad_base > 0),
    importe_centavos bigint not null check (importe_centavos >= 0),
    estado_producto  text not null check (estado_producto in ('VENDIBLE', 'DANADO', 'CADUCADO')),
    costo_centavos   bigint not null check (costo_centavos >= 0),
    sincronizado_en  timestamptz not null default now()
);

create index idx_devolucion_venta_detalle on public.devolucion_venta_detalle (devolucion_id);

create table public.devoluciones_sucursal (
    id                    uuid primary key default gen_random_uuid(),
    folio                 text not null unique,
    sucursal_id           uuid not null references public.sucursales (id) deferrable initially deferred,
    almacen_id            uuid not null references public.sucursales (id) deferrable initially deferred,
    motivo                text not null,
    total_costo_centavos  bigint not null check (total_costo_centavos >= 0),
    nota_credito_centavos bigint not null default 0 check (nota_credito_centavos >= 0),
    usuario_id            uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha                 timestamptz not null,
    sincronizado_en       timestamptz not null default now()
);

create table public.devolucion_sucursal_detalle (
    id              uuid primary key default gen_random_uuid(),
    devolucion_id   uuid not null references public.devoluciones_sucursal (id) on delete cascade
                        deferrable initially deferred,
    renglon         integer not null,
    producto_id     uuid not null references public.productos (id) deferrable initially deferred,
    cantidad        numeric(14, 3) not null check (cantidad > 0),
    estado_producto text not null check (estado_producto in ('VENDIBLE', 'DANADO', 'CADUCADO')),
    costo_centavos  bigint not null check (costo_centavos >= 0),
    lote_destino_id uuid references public.lotes (id) deferrable initially deferred,
    sincronizado_en timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Recepción, aclaración y cancelación de surtidos
-- ---------------------------------------------------------------------

create table public.recepciones_surtido (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    surtido_id      uuid not null references public.surtidos (id) deferrable initially deferred,
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    con_diferencia  boolean not null,
    notas           text,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

create index idx_recepciones_surtido on public.recepciones_surtido (surtido_id);

create table public.recepcion_surtido_detalle (
    id                 uuid primary key default gen_random_uuid(),
    recepcion_id       uuid not null references public.recepciones_surtido (id) on delete cascade
                           deferrable initially deferred,
    surtido_detalle_id uuid not null references public.surtido_detalle (id) deferrable initially deferred,
    producto_id        uuid not null references public.productos (id) deferrable initially deferred,
    lote_id            uuid not null references public.lotes (id) deferrable initially deferred,
    cantidad_enviada   numeric(14, 3) not null check (cantidad_enviada > 0),
    cantidad_recibida  numeric(14, 3) not null check (cantidad_recibida >= 0),
    diferencia         numeric(14, 3) not null check (diferencia >= 0),
    sincronizado_en    timestamptz not null default now()
);

create table public.aclaraciones_surtido (
    id                    uuid primary key default gen_random_uuid(),
    folio                 text not null unique,
    recepcion_detalle_id  uuid not null references public.recepcion_surtido_detalle (id) deferrable initially deferred,
    decision              text not null check (decision in ('REINGRESO_ALMACEN', 'PERDIDA')),
    cantidad              numeric(14, 3) not null check (cantidad > 0),
    nota_credito_centavos bigint not null default 0 check (nota_credito_centavos >= 0),
    nota                  text,
    usuario_id            uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha                 timestamptz not null,
    sincronizado_en       timestamptz not null default now()
);

create table public.cancelaciones_surtido (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    surtido_id      uuid not null references public.surtidos (id) deferrable initially deferred,
    motivo          text not null,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    fecha           timestamptz not null,
    sincronizado_en timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Fechas automáticas e índice de sincronización (tablas nuevas, y las de
-- gastos y nómina que se habían quedado sin ellos)
-- ---------------------------------------------------------------------

do $$
declare
    t text;
begin
    foreach t in array array['reglas_precio', 'escalas_precio', 'proveedores', 'compras', 'compra_detalle',
                             'devoluciones_proveedor', 'devolucion_proveedor_detalle', 'ajustes_inventario',
                             'transformaciones', 'devoluciones_venta', 'devolucion_venta_detalle',
                             'devoluciones_sucursal', 'devolucion_sucursal_detalle', 'recepciones_surtido',
                             'recepcion_surtido_detalle', 'aclaraciones_surtido', 'cancelaciones_surtido',
                             'gastos', 'nomina_semanal']
    loop
        if not exists (select 1 from pg_trigger
                       where tgname = 'marcar_sincronizado' and tgrelid = ('public.' || t)::regclass) then
            execute format('create trigger marcar_sincronizado before insert or update on public.%I '
                           'for each row execute function privado.marcar_sincronizado()', t);
        end if;
        if exists (select 1 from information_schema.columns
                   where table_schema = 'public' and table_name = t and column_name = 'actualizado_en')
           and not exists (select 1 from pg_trigger
                           where tgname = 'tocar_actualizado' and tgrelid = ('public.' || t)::regclass) then
            execute format('create trigger tocar_actualizado before update on public.%I '
                           'for each row execute function privado.tocar_actualizado()', t);
        end if;
        execute format('create index if not exists %I on public.%I (sincronizado_en)',
                       'idx_' || t || '_sincronizado', t);
    end loop;
end;
$$;

-- ---------------------------------------------------------------------
-- Permisos y RLS (mismo criterio que …050200_seguridad)
-- ---------------------------------------------------------------------

alter table public.reglas_precio enable row level security;
alter table public.escalas_precio enable row level security;
alter table public.proveedores enable row level security;
alter table public.compras enable row level security;
alter table public.compra_detalle enable row level security;
alter table public.devoluciones_proveedor enable row level security;
alter table public.devolucion_proveedor_detalle enable row level security;
alter table public.ajustes_inventario enable row level security;
alter table public.transformaciones enable row level security;
alter table public.devoluciones_venta enable row level security;
alter table public.devolucion_venta_detalle enable row level security;
alter table public.devoluciones_sucursal enable row level security;
alter table public.devolucion_sucursal_detalle enable row level security;
alter table public.recepciones_surtido enable row level security;
alter table public.recepcion_surtido_detalle enable row level security;
alter table public.aclaraciones_surtido enable row level security;
alter table public.cancelaciones_surtido enable row level security;

revoke all on public.reglas_precio, public.escalas_precio, public.proveedores, public.compras, public.compra_detalle,
    public.devoluciones_proveedor, public.devolucion_proveedor_detalle, public.ajustes_inventario,
    public.transformaciones, public.devoluciones_venta, public.devolucion_venta_detalle, public.devoluciones_sucursal,
    public.devolucion_sucursal_detalle, public.recepciones_surtido, public.recepcion_surtido_detalle,
    public.aclaraciones_surtido, public.cancelaciones_surtido
    from anon, authenticated;

grant select, insert on public.reglas_precio, public.escalas_precio, public.proveedores, public.compras,
    public.compra_detalle, public.devoluciones_proveedor, public.devolucion_proveedor_detalle,
    public.ajustes_inventario, public.transformaciones, public.devoluciones_venta, public.devolucion_venta_detalle,
    public.devoluciones_sucursal, public.devolucion_sucursal_detalle, public.recepciones_surtido,
    public.recepcion_surtido_detalle, public.aclaraciones_surtido, public.cancelaciones_surtido
    to authenticated;

-- Catálogos que cambian (los demás son solo alta). Un cambio liga la devolución con su venta nueva.
grant update on public.reglas_precio, public.escalas_precio, public.proveedores, public.devoluciones_venta
    to authenticated;

do $$
declare
    con_perfil  constant text := '(select privado.rol_actual()) is not null';
    es_admin    constant text := '(select privado.es_admin())';
    su_sucursal constant text := '((select privado.es_admin()) or sucursal_id = (select privado.sucursal_actual()))';
    t           text;
    hijo        record;
begin
    -- Catálogos de precios y proveedores: los ve cualquiera con perfil; los cambia el administrador.
    foreach t in array array['reglas_precio', 'escalas_precio', 'proveedores']
    loop
        execute format('create policy leer on public.%I for select to authenticated using (%s)', t, con_perfil);
        execute format('create policy alta on public.%I for insert to authenticated with check (%s)', t, es_admin);
        execute format('create policy cambio on public.%I for update to authenticated using (%s) with check (%s)',
                       t, es_admin, es_admin);
    end loop;

    -- Documentos del administrador, visibles para la sucursal que toca.
    foreach t in array array['compras', 'devoluciones_proveedor', 'devoluciones_sucursal']
    loop
        execute format('create policy leer on public.%I for select to authenticated using (%s)', t, su_sucursal);
        execute format('create policy alta on public.%I for insert to authenticated with check (%s)', t, es_admin);
    end loop;

    -- Operación de la sucursal (el supervisor ajusta, transforma, devuelve y recibe en la suya).
    foreach t in array array['ajustes_inventario', 'transformaciones', 'devoluciones_venta', 'recepciones_surtido']
    loop
        execute format('create policy leer on public.%I for select to authenticated using (%s)', t, su_sucursal);
        execute format('create policy alta on public.%I for insert to authenticated with check (%s)', t, su_sucursal);
    end loop;

    execute format('create policy cambio on public.devoluciones_venta for update to authenticated '
                   'using (%s) with check (%s)', su_sucursal, su_sucursal);

    -- Hijas: se ven y registran a través de su documento (el RLS del padre decide).
    for hijo in
        select * from (values
            ('compra_detalle',               'compras',                'compra_id',     'ADMIN'),
            ('devolucion_proveedor_detalle', 'devoluciones_proveedor', 'devolucion_id', 'ADMIN'),
            ('devolucion_sucursal_detalle',  'devoluciones_sucursal',  'devolucion_id', 'ADMIN'),
            ('devolucion_venta_detalle',     'devoluciones_venta',     'devolucion_id', 'PADRE'),
            ('recepcion_surtido_detalle',    'recepciones_surtido',    'recepcion_id',  'PADRE'),
            ('cancelaciones_surtido',        'surtidos',               'surtido_id',    'ADMIN')
        ) as h (tabla, padre, columna, alta)
    loop
        declare
            del_padre constant text := format('(%s or exists (select 1 from public.%I p where p.id = %I.%I))',
                                              es_admin, hijo.padre, hijo.tabla, hijo.columna);
            puede     constant text := case hijo.alta when 'ADMIN' then es_admin else del_padre end;
        begin
            execute format('create policy leer on public.%I for select to authenticated using (%s)',
                           hijo.tabla, del_padre);
            execute format('create policy alta on public.%I for insert to authenticated with check (%s)',
                           hijo.tabla, puede);
        end;
    end loop;
end;
$$;

-- Aclaraciones: las registra el administrador; las ve quien ve la recepción.
create policy leer on public.aclaraciones_surtido for select to authenticated
    using ((select privado.es_admin())
           or exists (select 1 from public.recepcion_surtido_detalle d
                      join public.recepciones_surtido r on r.id = d.recepcion_id
                      where d.id = aclaraciones_surtido.recepcion_detalle_id));
create policy alta on public.aclaraciones_surtido for insert to authenticated
    with check ((select privado.es_admin()));
