# Cambios implementados 2026

Qué se agregó y corrigió en la rama `funcional-2026` sobre el estado descrito en [AUDITORIA_FUNCIONAL_2026.md](AUDITORIA_FUNCIONAL_2026.md), siguiendo [PLAN_IMPLEMENTACION_2026.md](PLAN_IMPLEMENTACION_2026.md). Las pruebas están en [PRUEBAS_IMPLEMENTACION_2026.md](PRUEBAS_IMPLEMENTACION_2026.md) y las decisiones que faltan, en [PENDIENTES_NEGOCIO_2026.md](PENDIENTES_NEGOCIO_2026.md).

> **Nada de esto está en producción.** No se aplicó ninguna migración a la base real (`~/.cremerias-pos`) ni a Supabase, y no hay commits de esta rama. El orden de despliegue está al final.

## 1. Estado después de los cambios

| Área (sección de la auditoría) | Antes | Ahora |
|---|---|---|
| 4. Motor de reglas de precio por canal, categoría, producto, presentación, sucursal y vigencia | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 4. Precio interno de bodega a sucursal | FALTANTE | IMPLEMENTADO Y VERIFICADO (0 % mientras no se configure: igual que antes) |
| 4. Recargo sobre costo y margen sobre precio | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 4. Mayoreo y súper mayoreo | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 5. Crédito a precio interno, conceptos, notas de crédito | PARCIAL | IMPLEMENTADO Y VERIFICADO |
| 5. Notas pendientes y antigüedad de saldos | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 5. Cancelación de un cargo | FALTANTE | IMPLEMENTADO Y VERIFICADO (cancelación de surtido no recibido) |
| 6. Existencias negativas entre cajas | PARCIAL | IMPLEMENTADO Y VERIFICADO (detección y reacomodo; el conteo físico sigue siendo manual) |
| 7. Derivado con merma | PARCIAL | IMPLEMENTADO Y VERIFICADO |
| 7. Producto transformado | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 8. Compras con proveedor y varias líneas, todo o nada | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 9. Bonificaciones de proveedor | FALTANTE | IMPLEMENTADO Y VERIFICADO (política configurable) |
| 10. Motivo y autorizador de la cancelación validados en el servicio | DEFECTUOSO | CORREGIDO |
| 10. Devolución de cliente parcial/total y cambio | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 10. Devolución de sucursal a bodega y a proveedor | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 10. Caducados y dañados | PARCIAL | IMPLEMENTADO Y VERIFICADO (ajustes y devoluciones con estado del producto) |
| 11. Ajustes de inventario | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 12. Estados y recepción de surtidos | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 12. Listas de precios por canal | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 13. Devoluciones en el corte | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 14. Triggers faltantes de `gastos` y `nomina_semanal` en la nube | DEFECTUOSO | CORREGIDO en la migración nueva (NO VERIFICABLE sin Supabase) |
| 14. Sincronización de lo nuevo, reintentos sin duplicar | — | IMPLEMENTADO Y VERIFICADO con nube simulada |
| 15. Devoluciones y ajustes en indicadores | FALTANTE | IMPLEMENTADO Y VERIFICADO |
| 16. Impresión | PARCIAL | Opción de imprimir todo en negritas; la calidad real sigue NO VERIFICABLE sin la impresora |
| 17. Pantallas de los módulos nuevos | FALTANTE | IMPLEMENTADO (revisadas con el arnés de capturas) |
| 19. Permisos en servicios de administración | PARCIAL | Todos los servicios nuevos validan rol y sucursal; también surtidos, abonos y cancelación |

## 2. Base de datos

### SQLite (caja) — `src/main/resources/db/migration`

Registradas en `db/Migraciones.MIGRACIONES`. Ninguna migración anterior se editó.

| Migración | Contenido |
|---|---|
| `V15__movimientos_credito_y_precios.sql` | Reconstruye `movimientos_inventario` (como `V5`) para ampliar el `CHECK` de `tipo` con `COMPRA`, `DEVOLUCION_CLIENTE`, `DEVOLUCION_SUCURSAL_SALIDA`, `DEVOLUCION_SUCURSAL_ENTRADA`, `DEVOLUCION_PROVEEDOR`, `TRANSFORMACION_SALIDA`, `TRANSFORMACION_ENTRADA`, `DIFERENCIA_SURTIDO`, `CANCELACION_SURTIDO`; conserva filas, índices, trigger de la cola y vistas. `movimientos_credito` gana `concepto` y `referencia_id` (los cargos existentes quedan como `SURTIDO`, los abonos como `PAGO`). Columnas nuevas: `surtidos.total_interno_centavos`, `canal`; `surtido_detalle.importe_interno_centavos`, `regla_precio_id`; `venta_detalle.precio_lista_centavos`, `nivel_precio`, `escala_id`; `sucursales.canal_precio`; `presentaciones.merma_pct`. |
| `V16__reglas_y_escalas_de_precio.sql` | `reglas_precio`, `escalas_precio`. |
| `V17__compras_y_proveedores.sql` | `proveedores`, `compras`, `compra_detalle`, `devoluciones_proveedor`, `devolucion_proveedor_detalle`; configuración `compras.bonificacion = PRORRATEAR`. |
| `V18__ajustes_y_transformaciones.sql` | `ajustes_inventario`, `transformaciones`. |
| `V19__devoluciones.sql` | `devoluciones_venta`, `devolucion_venta_detalle`, `devoluciones_sucursal`, `devolucion_sucursal_detalle`. |
| `V20__recepcion_y_cancelacion_de_surtidos.sql` | `recepciones_surtido`, `recepcion_surtido_detalle`, `aclaraciones_surtido`, `cancelaciones_surtido`; `surtidos.requiere_recepcion` (los surtidos anteriores se consideran recibidos: el proceso de antes no pedía confirmación, así que no aparecen como «por recibir» ni se pueden cancelar). |

Todas las tablas nuevas tienen id UUID generado en el equipo, folio de bitácora, usuario, fecha, `sincronizado_en` y trigger de alta a `sync_outbox`.

### Supabase — `supabase/migrations/20261016090000_modulos_operacion.sql`

El equivalente en Postgres: cambio del `CHECK` de movimientos, columnas nuevas, tablas nuevas (llaves foráneas diferibles como el resto), triggers `marcar_sincronizado` / `tocar_actualizado` e índice de `sincronizado_en` para las tablas nuevas **y para `gastos` y `nomina_semanal`** (el defecto de la auditoría), RLS por sucursal igual que las demás tablas y permisos de solo alta en documentos y movimientos (los catálogos —proveedores, reglas, escalas— y `devoluciones_venta`, que se liga a la venta del cambio, también aceptan cambios).

## 3. Módulos

### 3.1 Precios y márgenes (`model/ReglaPrecio`, `service/admin/PreciosService`, `repository/PreciosRepository`)

- **Tres canales**: `PUBLICO` (precio al cliente; solo sugiere), `BODEGA_SUCURSAL` (lo que la cremería le debe a la bodega) y `RUTA_SIERRA`. Cada sucursal tiene su canal (`sucursales.canal_precio`, por omisión bodega a cremerías; se cambia en *Sucursales → Canal de precios*).
- **Dos tipos de porcentaje, distintos a propósito**: recargo sobre costo (precio = costo × (1 + %)) y margen sobre precio (precio = costo ÷ (1 − %)). 23 % sobre costo deja 18.7 % de margen; la pantalla muestra siempre el equivalente.
- **Alcance y precedencia**: general, sucursal, categoría (± sucursal), producto (± sucursal), presentación (± sucursal); gana la más específica y, a igual alcance, la de vigencia más reciente. Dos reglas activas con el mismo alcance y fechas que se cruzan se rechazan.
- **Vigencia por fechas**; desactivar no borra. Cada cambio queda en la bitácora con el valor anterior.
- **Ningún porcentaje está en el código.** Sin reglas, el precio interno es el costo (como antes) y no se sugiere precio al público.

### 3.2 Precio interno del surtido y crédito (`SurtidoService`, `CreditoService`)

- Cada renglón del surtido guarda su **costo PEPS** (como antes), su **importe interno** (costo con la regla del canal de la sucursal) y la regla usada. El **cargo a crédito usa el importe interno**.
- El lote que entra a la sucursal conserva el **costo de la empresa**, para que la utilidad consolidada no cuente dos veces el margen de bodega (ver pendientes de negocio).
- `CreditoService`: conceptos (surtido, abono, nota de crédito por devolución, por diferencia de entrega o por cancelación, ajuste); **notas pendientes** (los abonos se aplican a la nota más antigua; las notas de crédito, primero al surtido que mencionan); **antigüedad** en tramos de 0–30, 31–60, 61–90 y más de 90 días; ninguna nota de crédito deja el saldo en negativo; abonar requiere administrador.

### 3.3 Mayoreo (`model/Escala`, `model/Escalas`, `Carrito`, `VentaService`)

- Niveles **mayoreo** y **súper mayoreo** por producto, opcionalmente por presentación y sucursal, con cantidad mínima en **unidades del producto** (una caja de 12 cuenta 12 piezas) y descuento % o precio fijo por presentación.
- La caja suma lo que lleva el cliente de cada producto (todas sus presentaciones) y aplica el nivel alcanzado; los límites 5/6 y 11/12 (o los que se configuren) son exactos. Nunca cobra más que el menudeo.
- Carrito y servicio usan el mismo cálculo (`Escalas`); la venta guarda precio de lista, precio aplicado, nivel y escala. El ticket indica el nivel. Las ventas anteriores no cambian.
- Validaciones al guardar: un nivel por alcance y fechas, súper mayoreo empieza en una cantidad mayor y no da un precio peor que mayoreo.

### 3.4 Compras y bonificaciones (`CompraService`)

- Proveedores (alta, edición, deshabilitar; nombre único). Compra con proveedor, nota/factura, fecha y **varias líneas en una sola transacción** (todas o ninguna). Cada línea en la presentación comprada (caja, pieza, kilo).
- **Bonificación** por línea (pagado / regalado / recibido) con política configurable: **repartir el costo** entre todo lo recibido (por omisión) o **costo cero** (lote aparte). La política vigente se guarda en cada compra.
- **Idempotente**: el id de la compra lo genera la pantalla; un doble clic o reintento no la duplica.
- **Devolución a proveedor**: sale de los lotes de la compra (o PEPS), con motivo, compensación (por definir, nota de crédito, reembolso, reposición, sin compensación) y comprobante.

### 3.5 Ajustes, transformaciones y existencias negativas

- `AjusteService`: sobrante, faltante, caducidad, daño, corrección, cortesía, devolución, otro; signo validado por motivo; documento obligatorio para cortesía y devolución; nota obligatoria para corrección y otro; no acepta como documento un folio de compra, surtido, devolución o transformación, ni el mismo documento dos veces; valor al costo de los lotes. El cajero no ajusta; el supervisor solo su sucursal; el almacén solo el administrador.
- `TransformacionService`: el origen sale PEPS, el destino entra en un lote nuevo con **todo el costo consumido** (la merma del proceso queda en el costo del destino), la merma real queda registrada y se puede saber de qué lotes salió el lote nuevo. En sucursal pide el precio del lote nuevo.
- **Derivados con merma** (`presentaciones.merma_pct`): al vender, además de lo vendido sale el % de merma del mismo producto y se registra en `mermas` al costo; la existencia disponible considera esa merma.
- `ReconciliacionService`: lista productos con existencia o lotes negativos (dos cajas sin conexión vendieron lo mismo) y **reacomoda lotes** con ajustes que suman cero cuando el total alcanza. Si el total es negativo, se pide contar y ajustar: no se corrige solo.

### 3.6 Devoluciones (`DevolucionService`, `DevolucionSucursalService`)

- **De cliente**: sobre cualquier ticket de la sucursal, por renglón y cantidad, sin pasar de lo vendido menos lo ya devuelto. El reembolso es **proporcional a lo cobrado** (con mayoreo si se aplicó) y el último resto devuelve exacto lo que falta. Lo **vendible** regresa a los lotes de la venta; lo **dañado o caducado** no, y queda su costo como pérdida. Reembolso en efectivo (solo con turno abierto de la misma sucursal y si alcanza el efectivo), tarjeta, transferencia o sin reembolso. Requiere autorización de supervisor/administrador. **Cambio**: la siguiente venta de la caja queda ligada a la devolución. Comprobante para firma.
- **De sucursal a bodega**: sale PEPS de la sucursal; lo vendible entra al almacén en un lote nuevo; nota de crédito opcional por el precio interno de lo devuelto.
- **Cancelación de venta**: el servicio ahora exige motivo y que autorice un supervisor de esa sucursal o un administrador (antes solo la pantalla lo pedía), y no permite cancelar una venta con devoluciones.

### 3.7 Surtidos: recepción, diferencias y cancelación (`RecepcionSurtidoService`)

- Estados derivados de los documentos: **por recibir, recibido, recibido con diferencia, diferencia aclarada, cancelado**.
- La sucursal (supervisor o administrador) **confirma lo que llegó**; lo que falta sale de su existencia (`DIFERENCIA_SURTIDO`) y queda **por aclarar**. No se puede recibir más de lo enviado ni reportar faltante de lo que ya se vendió.
- El administrador **aclara**: regresó al almacén o pérdida en el traslado, con nota de crédito opcional (solo si fue a crédito).
- **Cancelación** de un surtido que no se ha recibido ni tocado: todo regresa a los mismos lotes del almacén y el cargo se cancela con nota de crédito.

### 3.8 Corte de caja e indicadores

- `ResumenTurno`: devoluciones del turno, reembolsos en efectivo y venta neta; **efectivo esperado = fondo + ventas en efectivo + entradas − retiros − reembolsos en efectivo**. El corte en pantalla y el ticket muestran las devoluciones.
- `IndicadoresRepository`: la **venta neta resta lo reembolsado** en el periodo (cuenta el día de la devolución, como el corte) y el **costo de lo vendido resta lo que regresó en buen estado**; la venta por día cuadra con la venta neta. Nuevo resumen de operación: devoluciones, reembolsos, costo no vendible, pérdidas y sobrantes de ajustes, faltantes y sobrantes de caja. **Sobre datos anteriores (sin devoluciones) las fórmulas dan exactamente lo mismo que antes.**
- `CajaService`: un retiro hecho por un cajero exige autorizador también en el servicio.

### 3.9 Sincronización (`sync/TablasNube`)

- Todas las tablas nuevas entran a `ORDEN` (padres antes que hijos) y los detalles viajan con su documento (`HIJAS`).
- Los documentos se guardan por id: un reintento después de perder la respuesta de la nube no duplica nada (probado con una nube simulada que guarda por id).
- `surtidos.requiere_recepcion` viaja con el surtido: la sucursal ve por recibir lo que surtió la bodega desde otro equipo, y la bodega ve la recepción y las diferencias al sincronizar (no antes: no hay consistencia inmediata entre equipos desconectados).
- Cobertura de los 10 escenarios de sincronización del encargo: tabla en [PRUEBAS_IMPLEMENTACION_2026.md](PRUEBAS_IMPLEMENTACION_2026.md).

### 3.10 Permisos (`service/Permisos`)

`exigirAdministrador`, `exigirSupervisorOAdministrador` (supervisor solo su sucursal), `exigirAlcance`, `exigirAutorizador` (nunca un cajero; supervisor de la misma sucursal) y `exigirTexto`. Los usan todos los servicios nuevos y, además, surtidos, abonos, canal de sucursal, cancelación de ventas y retiros.

### 3.11 Impresión (`ui/componentes/Impresion`, `DialogoTicket`)

Opción **«Imprimir todo en negritas (más oscuro)»** en la vista previa de cualquier ticket; se guarda como preferencia del equipo. No cambia el ancho ni el formato de 40 columnas. Si el ticket sale tenue por densidad del controlador, papel o cabezal, eso no se corrige desde el programa y **no se ha probado con la impresora real**.

## 4. Interfaz

Todo con los componentes del sistema de diseño (`TablaDatos`, `MaestroDetalle`, `PanelDetalle`, `Formulario`, `Dialogo`, `Pestanas`, `ResumenMetricas`), en tema claro y oscuro.

| Dónde | Qué hay |
|---|---|
| Administrador → **Precios y márgenes** (nuevo) | Márgenes por canal (resumen por canal, alta, edición, desactivar, vigencia, equivalencia recargo/margen), Mayoreo (alta y desactivar, con lo que ya tiene el producto), Lista de precios por canal y sucursal con costo, regla, precios por presentación, utilidad y margen. |
| Administrador → **Compras** (nuevo) | Compras con detalle por línea y política de bonificación; proveedores; devoluciones a proveedor. |
| Administrador → **Ajustes de inventario** (nuevo) | Ajustes con resumen de pérdidas y sobrantes; transformaciones con merma y costo nuevo; existencias negativas con reacomodo. |
| Administrador → **Devoluciones** (nuevo) | De clientes (con comprobante) y de sucursales a bodega. |
| Administrador → Surtidos | Columna de recepción, cargo a la sucursal (precio interno), cancelar, diferencias por aclarar, devolución de sucursal. |
| Administrador → Crédito | Movimientos con su concepto, notas pendientes y antigüedad del saldo. |
| Administrador → Sucursales | Canal de precios. |
| Administrador → Avisos | Existencias negativas y diferencias de surtido por aclarar (también en el contador del menú). |
| Diálogo de surtido | Cargo a la sucursal por línea y total; el saldo después del surtido usa el precio interno; columna y botón de precio con margen al público. |
| Diálogo de producto | Merma % por presentación (derivados). |
| Supervisor | **Recepción** (con contador), **Ajustes** de su sucursal, **Devoluciones** de sus clientes. |
| Caja | **F7 Devolución** por folio de ticket y botón «Devolución» en Ventas del turno; el carrito muestra el precio y el nivel de mayoreo aplicado. |

## 5. Cambios que se notan en la operación

1. Un **retiro** de efectivo hecho por un cajero necesita autorización también en el servicio (la pantalla ya la pedía).
2. **Cancelar una venta** exige motivo y autorizador válido en el servicio; una venta con devoluciones ya no se cancela (se devuelve lo que falta).
3. Con una regla de bodega configurada, el **crédito de los surtidos nuevos sube** al precio interno. Sin reglas, todo sigue al costo.
4. Los **surtidos nuevos** quedan «por recibir» hasta que la sucursal los confirma (la mercancía sigue entrando al registrar, como antes). Los anteriores cuentan como recibidos.
5. Los indicadores restan devoluciones a partir de que existan.

## 6. Despliegue y recuperación (cuando se autorice)

1. **Respaldo**: copiar `~/.cremerias-pos/cremerias.db` (con la caja cerrada) de cada equipo y hacer un respaldo de Supabase antes de tocar nada. Al arrancar, `db/Respaldos` hace una copia automática **antes de migrar**, pero solo si la última tiene más de un día (guarda las 15 más recientes); por eso conviene la copia manual justo antes de actualizar.
2. **Primero Supabase**: aplicar `supabase/migrations/20261016090000_modulos_operacion.sql` en un proyecto de prueba, revisar, y después en el real. Si la caja nueva sube antes, la nube rechaza los movimientos de tipos nuevos; quedan en la cola y se reintentan, sin perderse.
3. **Después la caja**: al abrir la versión nueva, `Migraciones` aplica `V15`–`V20` en la base local dentro de una transacción por migración, con *checksum*.
4. **Verificar**: entrar como administrador, revisar Avisos, Crédito (saldos iguales a los de antes), un surtido y una venta de prueba; sincronizar y ver que la cola baja a cero.
5. **Recuperación**: si algo falla al migrar, la migración que falló no se marca como aplicada; cerrar la caja y restaurar la copia del paso 1. No mezclar una base restaurada con una nube ya migrada sin revisar la cola pendiente.

## 7. Limitaciones conocidas

- El comportamiento contra **Supabase real** (RLS, triggers, `subir_cambios` con las tablas nuevas) no se probó: no se tocó la nube. Las pruebas usan una nube simulada.
- La **calidad de impresión** depende del equipo; solo se agregó la opción de negritas.
- **Litros y mililitros** no existen como unidad (se venden por pieza); queda en pendientes.
- La utilidad de una sucursal medida contra su **precio interno** no se calcula: los indicadores usan el costo de la empresa (pendiente de negocio).
- `AppContextTest` puede marcar 1 o 2 errores de entorno en Windows (carpeta temporal con el log abierto); también ocurría antes de estos cambios.
