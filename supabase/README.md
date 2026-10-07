# Supabase — base en la nube del punto de venta

Base **provisional para pruebas** del sistema en línea (web, móvil y la sincronización de las cajas de escritorio).

| | |
|---|---|
| Proyecto | `puntoVentaCremerias` (ref `lradrurqdllgbvhsgfbb`), región us-east-1, Micro |
| URL de la API | `https://lradrurqdllgbvhsgfbb.supabase.co` |
| Panel | https://supabase.com/dashboard/project/lradrurqdllgbvhsgfbb |
| Contraseña de Postgres | En el Llavero de macOS: «Supabase puntoVentaCremerias (BD)» |

## Claves

- **Publishable key** (antes *anon key*): la que usan web, móvil y cajas. Se obtiene en el panel → *Project Settings → API Keys*. Por sí sola no da acceso a nada: todo depende de la sesión del usuario y del RLS.
- **Secret key / service_role**: se salta toda la seguridad. **Nunca** va dentro de una app (web, móvil ni la caja); solo en servidores propios o Edge Functions.

## Cómo está organizado

Las migraciones están en [`migrations/`](migrations/) y se aplican en orden:

| Migración | Qué hace |
|---|---|
| `…050000_configuracion_base` | Tablas nuevas sin exposición automática, RLS automático, esquema `privado`, triggers de fechas |
| `…050100_tablas` | Las 30 tablas del punto de venta |
| `…050200_seguridad` | Permisos por tabla y políticas RLS |
| `…050300_vistas_y_funciones` | Vistas de existencias y crédito, funciones `mi_perfil`, `registrar_caja`, `vincular_usuario`, Realtime |
| `…050400_indices_y_perfil` | Índices para consultas frecuentes |
| `…060000_sincronizacion_cajas` | Las cajas sincronizan todo; función `subir_cambios` |
| `…060100_bajar_cambios` | Función `bajar_cambios` (lo que cambió desde la última vez, por tabla) |

Es el **mismo modelo y los mismos nombres** que la base local de las cajas (SQLite, `src/main/resources/db/migration`), para que la sincronización sea directa. Convenciones:

- **ids** `uuid`. Los genera quien crea el registro (así se puede trabajar sin conexión); si no se mandan, los genera el servidor.
- **Dinero en centavos** (`*_centavos`, `bigint`): `$12.50` = `1250`.
- **Cantidades** `numeric(14,3)` en la unidad base del producto: piezas, o kilos con 3 decimales (`0.250` = 250 g). Una presentación (caja, pieza de queso…) tiene un `factor` en unidades base.
- **Fechas** `timestamptz` (UTC).
- **No se borra nada**: `activo`, `eliminado_en` o `estado`. La API no permite `DELETE`.
- **`sincronizado_en`** (todas las tablas): hora del servidor en que llegó la última versión de la fila. Para bajar solo lo que cambió: `sincronizado_en > última vez`.
- **`actualizado_en`**: si el cliente lo manda (cambio hecho sin conexión) se respeta; si no, lo pone el servidor.
- Valores fijos como `text` con `check` (por ejemplo `rol in ('ADMINISTRADOR','SUPERVISOR','CAJERO')`).

### Tablas

| Área | Tablas |
|---|---|
| Sucursales y acceso | `sucursales` (una es el almacén central, `es_almacen`), `dispositivo` (cajas), `usuarios`, `usuarios_credenciales` (hash BCrypt para el acceso sin conexión), `sesiones`, `bitacora_accesos`, `horarios`, `bloqueos_acceso` |
| Catálogo | `categorias`, `productos`, `presentaciones` |
| Inventario | `lotes` (PEPS, costo por lote), `lote_precios` (precio por presentación en cada lote), `movimientos_inventario` |
| Caja | `turnos_caja`, `movimientos_caja`, `ventas`, `venta_detalle`, `venta_pagos` |
| Almacén | `limites_sucursal` (mín/máx), `ordenes_reabastecimiento`, `surtidos`, `surtido_detalle`, `movimientos_credito`, `mermas` |
| Administración | `reglas_comision`, `metas_sucursal`, `alertas_sucursal`, `configuracion_general`, `bitacora` |

Vistas: `v_existencias` (por producto y sucursal), `v_existencias_lote`, `v_saldo_credito` (cargos − abonos por sucursal). Respetan el RLS de quien consulta.

## Quién ve y cambia qué

Cada petición lleva la sesión de una **persona** (`usuarios.auth_user_id`) o de una **caja** (`dispositivo.auth_user_id`).

| Quién | Ve | Cambia |
|---|---|---|
| Sin sesión (`anon`) | Nada | Nada |
| Cuenta no ligada a un usuario o caja activa | Nada | Nada |
| Administrador, y cajas del almacén central | Todo | Todo (sin borrar) |
| Supervisor / cajero | Catálogo, sucursales y lo de **su** sucursal | Operación de su sucursal: turnos, ventas, movimientos, mermas, órdenes, bloqueos, bitácora |
| Caja de escritorio (cualquiera) | Todo | Todo (sin borrar) |

Las cajas sincronizan todo porque en cualquier caja puede entrar el administrador (surtidos, catálogo, usuarios…) o gente de otra sucursal, y la base local de cada caja ya guarda todas las sucursales. Las personas en web/móvil sí quedan limitadas a su sucursal.

Además:

- El catálogo, los lotes y precios, los surtidos, el crédito, los límites, las metas, las comisiones, los horarios y la configuración solo los cambia el administrador.
- Los movimientos, las bitácoras, el detalle y los pagos de venta, los lotes, los surtidos, las mermas y el crédito son **solo alta**: no se modifican después.
- Nadie fuera del administrador puede cambiar el rol, la sucursal o la cuenta de un usuario, ni la sucursal o la cuenta de una caja.
- `usuarios_credenciales` no lo ven supervisores ni cajeros.

Las reglas finas por rol (por ejemplo, quién autoriza una cancelación) las aplica la aplicación. La base garantiza que nadie salga de su sucursal ni toque lo que es del administrador.

## Cuentas

El registro libre está **desactivado**: las cuentas solo las crea el administrador.

**Persona (web/móvil):**

1. Que exista en `usuarios` (alta desde la caja del almacén o desde la web por un administrador).
2. Panel → *Authentication → Users → Add user*: correo y contraseña (marcar *Auto Confirm User*).
3. Ligarla: `select vincular_usuario('<id del usuario>', 'correo@ejemplo.com');`
   - El **primer administrador** se liga desde el *SQL Editor* del panel, que no lleva sesión de usuario.
   - Después, un administrador ya ligado puede ligar a los demás desde la app.

**Caja (escritorio):** ver [Cajas de escritorio](#cajas-de-escritorio).

## Funciones (RPC)

| Función | Para qué |
|---|---|
| `mi_perfil()` | Después de iniciar sesión: `tipo` (`USUARIO`/`CAJA`), `id`, `nombre`, `rol`, `sucursal_id`, `sucursal`, `es_almacen` |
| `registrar_caja(p_id, p_nombre, p_sucursal_id)` | Alta de una caja con su propia cuenta |
| `vincular_usuario(p_usuario_id, p_correo)` | Ligar una persona con su cuenta de Auth (solo administrador) |
| `subir_cambios(p_cambios)` | La caja sube un lote de filas tal como están en su base local, en una transacción |
| `bajar_cambios(p_cursores, p_limite)` | La caja baja lo que cambió en cada tabla desde su último cursor |

## Tiempo real

Publicadas en Realtime (respetan el RLS): `ventas`, `turnos_caja`, `ordenes_reabastecimiento`, `bloqueos_acceso`.

## Ejemplos (supabase-js)

```js
import { createClient } from '@supabase/supabase-js'

const supabase = createClient('https://lradrurqdllgbvhsgfbb.supabase.co', '<publishable key>')

await supabase.auth.signInWithPassword({ email, password })
const { data: [perfil] } = await supabase.rpc('mi_perfil')

// Ventas de hoy de su sucursal con detalle y pagos
const { data: ventas } = await supabase
  .from('ventas')
  .select('folio, fecha, total_centavos, estado, venta_detalle(descripcion, cantidad, importe_centavos), venta_pagos(metodo, monto_centavos)')
  .gte('fecha', new Date(new Date().setHours(0, 0, 0, 0)).toISOString())
  .order('fecha', { ascending: false })

// Existencias por producto y sucursal
const { data: existencias } = await supabase
  .from('v_existencias')
  .select('existencia, sucursal_id, producto_id')

// Avisos de ventas nuevas al momento
supabase.channel('ventas')
  .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'ventas' }, (cambio) => console.log(cambio.new))
  .subscribe()
```

## Cajas de escritorio

La app de escritorio sigue trabajando siempre sobre su base local (SQLite) y se sincroniza sola cada 10 segundos cuando hay internet ([`sync/`](../src/main/java/com/cremerias/puntoventa/sync)):

- **Sube** lo encolado en `sync_outbox` con `subir_cambios`: el estado actual de cada fila con sus hijas (detalle y pagos de una venta, precios de un lote, detalle de un surtido). Si la nube rechaza un cambio, los demás sí suben y ese queda en la cola con su error.
- **Baja** con `bajar_cambios` lo que cambió en la nube (otras cajas, web, móvil) y lo guarda sin volver a encolarlo. Un registro con cambios locales que aún no suben no se toca.
- Sin internet la caja trabaja igual; el indicador de la barra de estado dice si está en línea, sincronizando, sin conexión o con error (el detalle aparece al pasar el mouse).

**Conectar una caja:**

1. Crear su cuenta en el panel → *Authentication → Users → Add user* (un correo para la caja, por ejemplo `caja-7182@cremerias.local`, y *Auto Confirm User*).
2. En la carpeta de datos de la caja (`~/.cremerias-pos/`) crear `supabase.properties`:
   ```properties
   url=https://lradrurqdllgbvhsgfbb.supabase.co
   clave_publica=<publishable key>
   correo=caja-7182@cremerias.local
   contrasena=<contraseña de la cuenta de la caja>
   ```
3. Abrir la app. La primera vez la caja se registra sola con su id local (`registrar_caja`).
   - Si la caja ya tenía datos, sube lo pendiente y baja lo demás.
   - Si es una **caja nueva** (nadie ha entrado nunca), en vez de crear los usuarios y el catálogo de ejemplo toma todo de la nube; para eso necesita internet la primera vez.

Sin `supabase.properties` la caja trabaja solo en local, como antes. Cada caja necesita su propia cuenta.

Para probar la sincronización contra la nube sobre una **copia** de la base: `./mvnw test -Dtest=SincronizacionNubeTest -Dnube.bd=<copia.db> -Dnube.config=<supabase.properties>`.

## Cambios al esquema

1. `supabase migration new <nombre>` y escribir el SQL. **Nunca** editar una migración ya aplicada.
2. `supabase db push` (la CLI ya está ligada al proyecto; usa tu sesión de `supabase login`, no pide la contraseña).
3. Revisar con `supabase db advisors --linked`.
4. Si el cambio también aplica a las cajas, hacer la migración equivalente en SQLite (`V{n}__*.sql`).

Toda tabla nueva en `public` nace con RLS activado y **sin permisos**: hay que darle `grant` y políticas en la misma migración, o no se podrá usar desde la API.

`config.toml` está alineado con la configuración del proyecto. Antes de `supabase config push`, revisar `supabase config diff` para no cambiar nada sin querer.

## Pendiente

- Función para registrar una venta completa en una sola transacción (venta, detalle, pagos e inventario por PEPS) para web/móvil.
- Cargar los datos de prueba de la base local, si se quieren para probar.
