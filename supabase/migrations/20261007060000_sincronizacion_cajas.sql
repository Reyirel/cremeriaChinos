-- =====================================================================
-- Sincronización de las cajas de escritorio
--
--   * Las cajas sincronizan todo, no solo su sucursal: en cualquier caja
--     puede entrar el administrador (surtidos, catálogo, usuarios…) o
--     gente de otra sucursal (la caja carga la sucursal de quien entra), y
--     la base local de cada caja ya guarda todas las sucursales. Las
--     personas en web/móvil siguen viendo solo su sucursal.
--   * subir_cambios: la caja manda en un solo lote las filas que cambió
--     (tal como están en su base local) y se aplican en una transacción.
--     Las llaves foráneas son diferibles, así que el orden dentro del lote
--     no importa.
-- =====================================================================

create or replace function privado.es_admin()
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
                   where d.auth_user_id = auth.uid() and d.activo)
$$;

-- p_cambios: [{"tabla": "ventas", "fila": {…columnas de la base local…}}, …]
--   * Se toman solo las columnas que existen en la nube (las locales de más
--     se ignoran); sincronizado_en y auth_user_id los pone la nube.
--   * Tablas que admiten cambios: se reemplaza la fila. Tablas de solo
--     alta: si ya existe se deja igual (reenviar no duplica).
--   * En la caja el hash de la contraseña vive en usuarios; aquí se guarda
--     en usuarios_credenciales.
-- Corre con los permisos de quien llama: aplican los mismos RLS que en la API.
create function public.subir_cambios(p_cambios jsonb)
returns integer
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_cambio   jsonb;
    v_tabla    text;
    v_fila     jsonb;
    v_llave    text;
    v_columnas text;
    v_accion   text;
    v_total    integer := 0;
begin
    for v_cambio in select value from jsonb_array_elements(p_cambios) loop
        v_tabla := v_cambio ->> 'tabla';
        v_fila := v_cambio -> 'fila';
        if v_tabla is null or v_tabla = 'usuarios_credenciales'
           or not exists (select 1 from pg_tables where schemaname = 'public' and tablename = v_tabla) then
            raise exception 'No se puede sincronizar la tabla %', coalesce(v_tabla, '(sin nombre)')
                using errcode = '42P01';
        end if;
        v_llave := case v_tabla
                       when 'configuracion_general' then 'clave'
                       when 'alertas_sucursal' then 'sucursal_id'
                       else 'id'
                   end;

        select string_agg(quote_ident(a.attname), ', ' order by a.attnum)
        into v_columnas
        from pg_attribute a
        where a.attrelid = ('public.' || quote_ident(v_tabla))::regclass
          and a.attnum > 0 and not a.attisdropped
          and a.attname not in ('sincronizado_en', 'auth_user_id')
          and v_fila ? a.attname;

        v_accion := 'nothing';
        if has_table_privilege(('public.' || quote_ident(v_tabla))::regclass, 'UPDATE') then
            select coalesce('update set ' || string_agg(format('%1$I = excluded.%1$I', a.attname), ', '), 'nothing')
            into v_accion
            from pg_attribute a
            where a.attrelid = ('public.' || quote_ident(v_tabla))::regclass
              and a.attnum > 0 and not a.attisdropped
              and a.attname not in ('sincronizado_en', 'auth_user_id', v_llave)
              and v_fila ? a.attname;
        end if;

        execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I, $1) '
                       'on conflict (%I) do %s',
                       v_tabla, v_columnas, v_columnas, v_tabla, v_llave, v_accion)
            using v_fila;

        if v_tabla = 'usuarios' and v_fila ? 'password_hash' then
            insert into public.usuarios_credenciales (usuario_id, password_hash, actualizado_en)
            values ((v_fila ->> 'id')::uuid, v_fila ->> 'password_hash',
                    coalesce((v_fila ->> 'actualizado_en')::timestamptz, now()))
            on conflict (usuario_id) do update
                set password_hash = excluded.password_hash,
                    actualizado_en = excluded.actualizado_en
                where public.usuarios_credenciales.password_hash is distinct from excluded.password_hash;
        end if;

        v_total := v_total + 1;
    end loop;
    return v_total;
end;
$$;

revoke all on function public.subir_cambios(jsonb) from public, anon;
grant execute on function public.subir_cambios(jsonb) to authenticated;
