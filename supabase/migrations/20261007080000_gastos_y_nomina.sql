-- =====================================================================
-- Gastos y nómina semanal por sucursal (equivalente a V13__gastos_y_nomina.sql)
-- Solo el administrador ve y cambia estas dos tablas: a diferencia de
-- metas_sucursal/limites_sucursal, aquí el supervisor no tiene acceso.
-- =====================================================================

create table public.gastos (
    id              uuid primary key default gen_random_uuid(),
    folio           text not null unique,
    sucursal_id     uuid not null references public.sucursales (id) deferrable initially deferred,
    fecha           timestamptz not null,
    categoria       text not null check (categoria in ('RENTA', 'ELECTRICIDAD', 'INTERNET', 'SEGURO_LOCAL',
                     'IMSS_INFONAVIT', 'IMPUESTO_NOMINA', 'TRANSPORTE', 'PUBLICIDAD', 'INDIRECTOS')),
    tipo            text not null check (tipo in ('FIJO', 'VARIABLE')),
    concepto        text not null,
    monto_centavos  bigint not null check (monto_centavos > 0),
    nota            text,
    usuario_id      uuid not null references public.usuarios (id) deferrable initially deferred,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now(),
    eliminado_en    timestamptz,
    sincronizado_en timestamptz not null default now()
);

create index idx_gastos_sucursal_fecha on public.gastos (sucursal_id, fecha);

create table public.nomina_semanal (
    id               uuid primary key default gen_random_uuid(),
    sucursal_id      uuid not null references public.sucursales (id) deferrable initially deferred,
    usuario_id       uuid not null references public.usuarios (id) deferrable initially deferred,
    semana_inicio    date not null,
    salario_centavos bigint not null check (salario_centavos >= 0),
    comida_centavos  bigint not null default 0 check (comida_centavos >= 0),
    creado_en        timestamptz not null default now(),
    actualizado_en   timestamptz not null default now(),
    sincronizado_en  timestamptz not null default now(),
    unique (sucursal_id, usuario_id, semana_inicio)
);

create index idx_nomina_semanal_sucursal on public.nomina_semanal (sucursal_id, semana_inicio);

alter table public.gastos enable row level security;
alter table public.nomina_semanal enable row level security;

revoke all on public.gastos, public.nomina_semanal from anon;
revoke all on public.gastos, public.nomina_semanal from authenticated;
grant select, insert, update on public.gastos, public.nomina_semanal to authenticated;

create policy leer on public.gastos for select to authenticated using ((select privado.es_admin()));
create policy alta on public.gastos for insert to authenticated with check ((select privado.es_admin()));
create policy cambio on public.gastos for update to authenticated
    using ((select privado.es_admin())) with check ((select privado.es_admin()));

create policy leer on public.nomina_semanal for select to authenticated using ((select privado.es_admin()));
create policy alta on public.nomina_semanal for insert to authenticated with check ((select privado.es_admin()));
create policy cambio on public.nomina_semanal for update to authenticated
    using ((select privado.es_admin())) with check ((select privado.es_admin()));
