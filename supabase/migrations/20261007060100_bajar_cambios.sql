-- =====================================================================
-- Bajada de cambios para las cajas en una sola llamada
--
-- p_cursores: {"ventas": {"desde": "<sincronizado_en>", "llave": "<id>"}, "productos": {}, …}
--   Por cada tabla pedida devuelve hasta p_limite filas que cambiaron
--   después de (desde, llave), en orden. Sin "desde", desde el principio.
--   Si una tabla regresa p_limite filas, hay más: se vuelve a pedir con la
--   última fila como cursor.
-- Corre con los permisos de quien llama (aplica el RLS).
-- =====================================================================

create function public.bajar_cambios(p_cursores jsonb, p_limite integer default 500)
returns jsonb
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
    v_tabla     text;
    v_cursor    jsonb;
    v_llave     text;
    v_filas     jsonb;
    v_resultado jsonb := '{}';
begin
    for v_tabla, v_cursor in select key, value from jsonb_each(p_cursores) loop
        if not exists (select 1 from pg_tables where schemaname = 'public' and tablename = v_tabla) then
            raise exception 'No se puede sincronizar la tabla %', v_tabla using errcode = '42P01';
        end if;
        v_llave := case v_tabla
                       when 'configuracion_general' then 'clave'
                       when 'alertas_sucursal' then 'sucursal_id'
                       when 'usuarios_credenciales' then 'usuario_id'
                       else 'id'
                   end;

        execute format(
            'select coalesce(jsonb_agg(to_jsonb(t) order by t.sincronizado_en, t.%1$I::text), ''[]'') '
            'from (select * from public.%2$I '
            '      where $1 is null or (sincronizado_en, %1$I::text) > ($1::timestamptz, coalesce($2, '''')) '
            '      order by sincronizado_en, %1$I::text '
            '      limit $3) t',
            v_llave, v_tabla)
        into v_filas
        using v_cursor ->> 'desde', v_cursor ->> 'llave', p_limite;

        v_resultado := v_resultado || jsonb_build_object(v_tabla, v_filas);
    end loop;
    return v_resultado;
end;
$$;

revoke all on function public.bajar_cambios(jsonb, integer) from public, anon;
grant execute on function public.bajar_cambios(jsonb, integer) to authenticated;
