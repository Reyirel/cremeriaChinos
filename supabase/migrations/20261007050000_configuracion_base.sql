-- =====================================================================
-- Configuración base del proyecto
--
--   * Las tablas nuevas de public NO se exponen solas por la API (equivale
--     a desactivar "Automatically expose new tables"): cada tabla recibe
--     sus permisos a mano en la migración de seguridad.
--   * Toda tabla nueva de public nace con RLS activado (equivale a
--     "Enable automatic RLS"): red de seguridad por si una migración
--     futura olvida activarlo.
--   * Esquema privado: funciones internas (RLS y triggers). No está
--     expuesto en la API, así que no se puede llamar desde fuera.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Sin exposición automática
-- ---------------------------------------------------------------------

alter default privileges for role postgres in schema public revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public revoke all on functions from anon, authenticated;
-- Postgres da EXECUTE a PUBLIC en toda función nueva; se quita para que
-- cada función diga explícitamente quién la puede llamar.
alter default privileges for role postgres revoke execute on functions from public;

-- ---------------------------------------------------------------------
-- Esquema privado
-- ---------------------------------------------------------------------

create schema if not exists privado;

-- ---------------------------------------------------------------------
-- RLS automático en tablas nuevas de public
-- ---------------------------------------------------------------------

create function privado.activar_rls_en_tablas_nuevas()
returns event_trigger
language plpgsql
set search_path = ''
as $$
declare
    obj record;
begin
    for obj in
        select *
        from pg_event_trigger_ddl_commands()
        where command_tag in ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
          and object_type = 'table'
          and schema_name = 'public'
    loop
        execute format('alter table %s enable row level security', obj.object_identity);
    end loop;
end;
$$;

create event trigger activar_rls_tablas_nuevas
    on ddl_command_end
    when tag in ('CREATE TABLE', 'CREATE TABLE AS', 'SELECT INTO')
    execute function privado.activar_rls_en_tablas_nuevas();

-- ---------------------------------------------------------------------
-- Triggers genéricos de fechas
-- ---------------------------------------------------------------------

-- sincronizado_en: hora del servidor en que llegó la última versión de la
-- fila (no la hora de la caja, que puede estar desfasada). Las cajas bajan
-- cambios pidiendo "sincronizado_en > lo último que ya tengo".
create function privado.marcar_sincronizado()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.sincronizado_en := now();
    return new;
end;
$$;

-- actualizado_en: si el cliente lo manda (un cambio hecho sin conexión)
-- se respeta; si no lo tocó, lo pone el servidor.
create function privado.tocar_actualizado()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if new.actualizado_en is not distinct from old.actualizado_en then
        new.actualizado_en := now();
    end if;
    return new;
end;
$$;
