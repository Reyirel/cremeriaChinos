-- =====================================================================
-- Vistas, funciones para web/móvil/cajas y tiempo real
-- =====================================================================

-- ---------------------------------------------------------------------
-- Vistas (security_invoker: respetan el RLS de quien consulta)
-- ---------------------------------------------------------------------

-- Existencia por producto y sucursal (suma de movimientos).
create view public.v_existencias
with (security_invoker = true) as
select producto_id, sucursal_id, round(sum(cantidad), 3) as existencia
from public.movimientos_inventario
group by producto_id, sucursal_id;

-- Existencia de cada lote (para vender primero el más antiguo).
create view public.v_existencias_lote
with (security_invoker = true) as
select lote_id, round(sum(cantidad), 3) as existencia
from public.movimientos_inventario
where lote_id is not null
group by lote_id;

-- Saldo de crédito de cada sucursal con el almacén (cargos − abonos).
create view public.v_saldo_credito
with (security_invoker = true) as
select sucursal_id,
       sum(case tipo when 'CARGO' then monto_centavos else -monto_centavos end) as saldo_centavos
from public.movimientos_credito
group by sucursal_id;

grant select on public.v_existencias, public.v_existencias_lote, public.v_saldo_credito to authenticated;

-- ---------------------------------------------------------------------
-- Quién soy (después de iniciar sesión en web/móvil o en la caja)
-- ---------------------------------------------------------------------

create function public.mi_perfil()
returns table (tipo text, id uuid, nombre text, rol text, sucursal_id uuid, sucursal text, es_almacen boolean)
language sql
stable
security definer
set search_path = ''
as $$
    select 'USUARIO', u.id, u.nombre_completo, u.rol, u.sucursal_id, s.nombre, coalesce(s.es_almacen, false)
    from public.usuarios u
    left join public.sucursales s on s.id = u.sucursal_id
    where u.auth_user_id = auth.uid() and u.activo and u.eliminado_en is null
    union all
    select 'CAJA', d.id, d.nombre, 'CAJA', d.sucursal_id, s.nombre, coalesce(s.es_almacen, false)
    from public.dispositivo d
    left join public.sucursales s on s.id = d.sucursal_id
    where d.auth_user_id = auth.uid() and d.activo
$$;

-- ---------------------------------------------------------------------
-- Alta de una caja en la nube
--
-- El administrador crea en el panel (Authentication > Users) una cuenta
-- para la caja. La caja inicia sesión con ella y se registra con el id
-- que ya tiene en su base local. Si el administrador ya había dado de
-- alta esa caja, solo se liga la cuenta y se conserva su sucursal.
-- ---------------------------------------------------------------------

create function public.registrar_caja(p_id uuid, p_nombre text, p_sucursal_id uuid)
returns public.dispositivo
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_caja public.dispositivo;
begin
    if auth.uid() is null then
        raise exception 'Hay que iniciar sesión con la cuenta de la caja' using errcode = '42501';
    end if;
    if exists (select 1 from public.usuarios where auth_user_id = auth.uid()) then
        raise exception 'Esta cuenta es de una persona, no de una caja' using errcode = '42501';
    end if;
    if exists (select 1 from public.dispositivo where auth_user_id = auth.uid() and id <> p_id) then
        raise exception 'Esta cuenta ya está ligada a otra caja' using errcode = '42501';
    end if;
    if exists (select 1 from public.dispositivo
               where id = p_id and auth_user_id is not null and auth_user_id <> auth.uid()) then
        raise exception 'Esta caja ya está ligada a otra cuenta' using errcode = '42501';
    end if;
    if not exists (select 1 from public.sucursales
                   where id = p_sucursal_id and activo and eliminado_en is null) then
        raise exception 'La sucursal no existe o está deshabilitada' using errcode = '23503';
    end if;

    insert into public.dispositivo (id, nombre, sucursal_id, auth_user_id)
    values (p_id, p_nombre, p_sucursal_id, auth.uid())
    on conflict (id) do update
        set nombre = excluded.nombre,
            auth_user_id = excluded.auth_user_id
    returning * into v_caja;

    return v_caja;
end;
$$;

-- ---------------------------------------------------------------------
-- Ligar a una persona con su cuenta de Auth (por correo) para que entre
-- a web/móvil. Lo hace el administrador; desde el editor SQL del panel
-- (sin sesión) también, para ligar al primer administrador.
-- ---------------------------------------------------------------------

create function public.vincular_usuario(p_usuario_id uuid, p_correo text)
returns public.usuarios
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_cuenta  uuid;
    v_usuario public.usuarios;
begin
    if auth.uid() is not null and not privado.es_admin() then
        raise exception 'Solo el administrador puede ligar cuentas' using errcode = '42501';
    end if;

    select a.id into v_cuenta from auth.users a where lower(a.email) = lower(p_correo);
    if v_cuenta is null then
        raise exception 'No hay una cuenta de Auth con el correo %', p_correo using errcode = 'P0002';
    end if;
    if exists (select 1 from public.dispositivo where auth_user_id = v_cuenta) then
        raise exception 'Esa cuenta ya es de una caja' using errcode = '23505';
    end if;

    update public.usuarios set auth_user_id = v_cuenta where id = p_usuario_id
    returning * into v_usuario;
    if v_usuario.id is null then
        raise exception 'No existe el usuario %', p_usuario_id using errcode = 'P0002';
    end if;

    return v_usuario;
end;
$$;

revoke all on function public.mi_perfil(), public.registrar_caja(uuid, text, uuid),
                       public.vincular_usuario(uuid, text)
    from public, anon;
grant execute on function public.mi_perfil(), public.registrar_caja(uuid, text, uuid),
                          public.vincular_usuario(uuid, text)
    to authenticated;

-- ---------------------------------------------------------------------
-- Tiempo real: ventas, cortes, órdenes y bloqueos se ven al momento en
-- web/móvil (Realtime también respeta el RLS).
-- ---------------------------------------------------------------------

do $$
begin
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        create publication supabase_realtime;
    end if;
end;
$$;

alter publication supabase_realtime
    add table public.ventas, public.turnos_caja, public.ordenes_reabastecimiento, public.bloqueos_acceso;
