-- =====================================================================
-- Seguridad: quién puede ver y cambiar qué (permisos + RLS)
--
-- Cada petición a la API llega con la sesión de Supabase Auth de:
--   * una persona (usuarios.auth_user_id): web o móvil;
--   * una caja (dispositivo.auth_user_id): la app de escritorio al
--     sincronizar. El acceso de cajeros en la caja sigue siendo local.
--
-- Reglas generales:
--   * anon (sin sesión) no ve nada.
--   * Una cuenta de Auth que no está ligada a un usuario o caja activa no
--     ve nada.
--   * El administrador, y las cajas del almacén central (ahí trabaja el
--     administrador y desde ahí se surte a todas), ven y cambian todo.
--   * Los demás ven y registran solo lo de su sucursal.
--   * Catálogo, precios, lotes, surtidos, crédito, límites, metas,
--     comisiones, horarios y configuración: solo los cambia el
--     administrador.
--   * Movimientos, bitácoras, detalle de ventas, lotes, surtidos, mermas
--     y crédito no se modifican una vez registrados (solo alta).
--   * Nadie borra por la API (borrado lógico). service_role se salta
--     todo esto: solo para servidores de confianza, nunca en la app.
--
-- Las reglas finas de cada rol (p. ej. quién autoriza una cancelación)
-- las sigue aplicando la aplicación; aquí se garantiza que nadie salga de
-- su sucursal ni cambie lo que es del administrador.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Quién hace la petición
-- (security definer: leen usuarios/dispositivo sin pasar por su propio RLS)
-- ---------------------------------------------------------------------

-- Persona ligada a la sesión, si está activa.
create function privado.usuario_actual()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
    select u.id
    from public.usuarios u
    where u.auth_user_id = auth.uid() and u.activo and u.eliminado_en is null
$$;

-- Caja ligada a la sesión, si está activa.
create function privado.dispositivo_actual()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
    select d.id
    from public.dispositivo d
    where d.auth_user_id = auth.uid() and d.activo
$$;

-- Sucursal de la persona o caja de la sesión.
create function privado.sucursal_actual()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select u.sucursal_id
         from public.usuarios u
         where u.auth_user_id = auth.uid() and u.activo and u.eliminado_en is null),
        (select d.sucursal_id
         from public.dispositivo d
         where d.auth_user_id = auth.uid() and d.activo))
$$;

-- ADMINISTRADOR, SUPERVISOR o CAJERO si es una persona; CAJA si es un
-- dispositivo; nulo si la cuenta no está ligada a nadie activo.
create function privado.rol_actual()
returns text
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select u.rol
         from public.usuarios u
         where u.auth_user_id = auth.uid() and u.activo and u.eliminado_en is null),
        (select 'CAJA'
         from public.dispositivo d
         where d.auth_user_id = auth.uid() and d.activo))
$$;

-- El administrador y las cajas del almacén central.
create function privado.es_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (select 1
                   from public.usuarios u
                   where u.auth_user_id = auth.uid() and u.activo and u.eliminado_en is null
                     and u.rol = 'ADMINISTRADOR')
        or exists (select 1
                   from public.dispositivo d
                   join public.sucursales s on s.id = d.sucursal_id
                   where d.auth_user_id = auth.uid() and d.activo and s.es_almacen)
$$;

create function privado.es_caja()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (select 1
                   from public.dispositivo d
                   where d.auth_user_id = auth.uid() and d.activo)
$$;

revoke all on all functions in schema privado from public;
grant usage on schema privado to authenticated;
grant execute on function privado.usuario_actual(), privado.dispositivo_actual(), privado.sucursal_actual(),
                          privado.rol_actual(), privado.es_admin(), privado.es_caja()
    to authenticated;

-- ---------------------------------------------------------------------
-- Columnas que solo cambia el administrador
-- (aplica a peticiones de la API; funciones de confianza y service_role no)
-- ---------------------------------------------------------------------

-- Una caja sí actualiza usuarios (último acceso, cambio de contraseña),
-- pero no puede subir de rol a nadie ni moverlo de sucursal o de cuenta.
create function privado.proteger_usuarios()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if current_user = 'authenticated' and not privado.es_admin() and (
           new.rol is distinct from old.rol
        or new.sucursal_id is distinct from old.sucursal_id
        or new.auth_user_id is distinct from old.auth_user_id) then
        raise exception 'Solo el administrador puede cambiar el rol, la sucursal o la cuenta de un usuario'
            using errcode = '42501';
    end if;
    return new;
end;
$$;

create trigger proteger_usuarios
    before update on public.usuarios
    for each row execute function privado.proteger_usuarios();

-- Una caja puede renombrarse, pero no cambiarse de sucursal, de cuenta ni
-- reactivarse sola.
create function privado.proteger_dispositivo()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if current_user = 'authenticated' and not privado.es_admin() and (
           new.sucursal_id is distinct from old.sucursal_id
        or new.auth_user_id is distinct from old.auth_user_id
        or new.activo is distinct from old.activo) then
        raise exception 'Solo el administrador puede cambiar la sucursal, la cuenta o el estado de una caja'
            using errcode = '42501';
    end if;
    return new;
end;
$$;

create trigger proteger_dispositivo
    before update on public.dispositivo
    for each row execute function privado.proteger_dispositivo();

-- ---------------------------------------------------------------------
-- Permisos por tabla
-- ---------------------------------------------------------------------

do $$
declare
    t record;
begin
    -- El trigger de eventos ya activó RLS; aquí queda explícito.
    for t in select c.relname
             from pg_class c
             join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public' and c.relkind = 'r'
    loop
        execute format('alter table public.%I enable row level security', t.relname);
    end loop;
end;
$$;

revoke all on all tables in schema public from anon;
revoke all on all tables in schema public from authenticated;

grant select, insert on all tables in schema public to authenticated;

-- Solo estas tablas tienen cambios después del alta; las demás son solo alta.
grant update on
    public.sucursales, public.dispositivo, public.usuarios, public.usuarios_credenciales, public.sesiones,
    public.categorias, public.productos, public.presentaciones,
    public.turnos_caja, public.ventas,
    public.limites_sucursal, public.ordenes_reabastecimiento,
    public.reglas_comision, public.metas_sucursal, public.horarios, public.bloqueos_acceso,
    public.configuracion_general, public.alertas_sucursal
    to authenticated;

-- ---------------------------------------------------------------------
-- Políticas
-- ---------------------------------------------------------------------

do $$
declare
    -- (select …) hace que Postgres evalúe la función una vez por consulta y no por fila.
    con_perfil  constant text := '(select privado.rol_actual()) is not null';
    es_admin    constant text := '(select privado.es_admin())';
    su_sucursal constant text := '((select privado.es_admin()) or sucursal_id = (select privado.sucursal_actual()))';
    t           text;
    hijo        record;
begin
    -- Catálogo general: lo ve cualquiera con perfil; lo cambia el administrador.
    foreach t in array array['sucursales', 'categorias', 'productos', 'presentaciones',
                             'reglas_comision', 'configuracion_general']
    loop
        execute format('create policy leer on public.%I for select to authenticated using (%s)', t, con_perfil);
        execute format('create policy alta on public.%I for insert to authenticated with check (%s)', t, es_admin);
        execute format('create policy cambio on public.%I for update to authenticated using (%s) with check (%s)',
                       t, es_admin, es_admin);
    end loop;

    -- De la sucursal, pero los define el administrador.
    foreach t in array array['limites_sucursal', 'alertas_sucursal', 'metas_sucursal',
                             'lotes', 'surtidos', 'movimientos_credito']
    loop
        execute format('create policy leer on public.%I for select to authenticated using (%s)', t, su_sucursal);
        execute format('create policy alta on public.%I for insert to authenticated with check (%s)', t, es_admin);
    end loop;

    foreach t in array array['limites_sucursal', 'alertas_sucursal', 'metas_sucursal']
    loop
        execute format('create policy cambio on public.%I for update to authenticated using (%s) with check (%s)',
                       t, es_admin, es_admin);
    end loop;

    -- Operación diaria: cada quien en su sucursal.
    foreach t in array array['turnos_caja', 'ventas', 'ordenes_reabastecimiento',
                             'movimientos_inventario', 'mermas', 'bitacora']
    loop
        execute format('create policy leer on public.%I for select to authenticated using (%s)', t, su_sucursal);
        execute format('create policy alta on public.%I for insert to authenticated with check (%s)', t, su_sucursal);
    end loop;

    foreach t in array array['turnos_caja', 'ventas', 'ordenes_reabastecimiento']
    loop
        execute format('create policy cambio on public.%I for update to authenticated using (%s) with check (%s)',
                       t, su_sucursal, su_sucursal);
    end loop;

    -- Tablas sin sucursal propia: se ve y se registra lo que cuelga de un
    -- registro visible (el RLS del padre decide).
    --   alta = 'ADMIN': solo el administrador; 'PADRE': quien ve el padre.
    for hijo in
        select * from (values
            ('lote_precios',     'lotes',       'lote_id',        'ADMIN', false),
            ('surtido_detalle',  'surtidos',    'surtido_id',     'ADMIN', false),
            ('horarios',         'usuarios',    'usuario_id',     'ADMIN', true),
            ('movimientos_caja', 'turnos_caja', 'turno_id',       'PADRE', false),
            ('venta_detalle',    'ventas',      'venta_id',       'PADRE', false),
            ('venta_pagos',      'ventas',      'venta_id',       'PADRE', false),
            ('bloqueos_acceso',  'usuarios',    'usuario_id',     'PADRE', true),
            ('sesiones',         'dispositivo', 'dispositivo_id', 'PADRE', true),
            ('bitacora_accesos', 'dispositivo', 'dispositivo_id', 'PADRE', false)
        ) as h (tabla, padre, columna, alta, con_cambio)
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
            if hijo.con_cambio then
                execute format('create policy cambio on public.%I for update to authenticated using (%s) with check (%s)',
                               hijo.tabla, puede, puede);
            end if;
        end;
    end loop;
end;
$$;

-- Cajas: cada quien ve las de su sucursal; el alta la hace el
-- administrador (o la propia caja con public.registrar_caja).
create policy leer on public.dispositivo for select to authenticated
    using ((select privado.es_admin()) or sucursal_id = (select privado.sucursal_actual()));
create policy alta on public.dispositivo for insert to authenticated
    with check ((select privado.es_admin()));
create policy cambio on public.dispositivo for update to authenticated
    using ((select privado.es_admin()) or id = (select privado.dispositivo_actual()))
    with check ((select privado.es_admin()) or id = (select privado.dispositivo_actual()));

-- Usuarios: los de la sucursal y uno mismo. Las cajas además ven a los
-- administradores, que pueden entrar en cualquier caja (p. ej. para
-- desbloquear a un empleado).
create policy leer on public.usuarios for select to authenticated
    using ((select privado.es_admin())
           or sucursal_id = (select privado.sucursal_actual())
           or id = (select privado.usuario_actual())
           or ((select privado.es_caja()) and rol = 'ADMINISTRADOR'));
create policy alta on public.usuarios for insert to authenticated
    with check ((select privado.es_admin()));
create policy cambio on public.usuarios for update to authenticated
    using ((select privado.es_admin())
           or ((select privado.es_caja()) and (sucursal_id = (select privado.sucursal_actual()) or rol = 'ADMINISTRADOR')))
    with check ((select privado.es_admin())
           or ((select privado.es_caja()) and (sucursal_id = (select privado.sucursal_actual()) or rol = 'ADMINISTRADOR')));

-- Hash de contraseñas: solo el administrador y las cajas (que lo necesitan
-- para el acceso sin conexión de su gente y de los administradores).
create policy leer on public.usuarios_credenciales for select to authenticated
    using ((select privado.es_admin())
           or ((select privado.es_caja()) and exists (
                   select 1 from public.usuarios u
                   where u.id = usuarios_credenciales.usuario_id
                     and (u.sucursal_id = (select privado.sucursal_actual()) or u.rol = 'ADMINISTRADOR'))));
create policy alta on public.usuarios_credenciales for insert to authenticated
    with check ((select privado.es_admin())
           or ((select privado.es_caja()) and exists (
                   select 1 from public.usuarios u
                   where u.id = usuarios_credenciales.usuario_id
                     and (u.sucursal_id = (select privado.sucursal_actual()) or u.rol = 'ADMINISTRADOR'))));
create policy cambio on public.usuarios_credenciales for update to authenticated
    using ((select privado.es_admin())
           or ((select privado.es_caja()) and exists (
                   select 1 from public.usuarios u
                   where u.id = usuarios_credenciales.usuario_id
                     and (u.sucursal_id = (select privado.sucursal_actual()) or u.rol = 'ADMINISTRADOR'))))
    with check ((select privado.es_admin())
           or ((select privado.es_caja()) and exists (
                   select 1 from public.usuarios u
                   where u.id = usuarios_credenciales.usuario_id
                     and (u.sucursal_id = (select privado.sucursal_actual()) or u.rol = 'ADMINISTRADOR'))));
