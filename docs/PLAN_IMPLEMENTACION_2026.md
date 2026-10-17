# Plan de implementación 2026

Plan para cerrar los huecos de [AUDITORIA_FUNCIONAL_2026.md](AUDITORIA_FUNCIONAL_2026.md) sobre el sistema existente, sin reconstruirlo. Rama de trabajo: `funcional-2026`.

## Principios

1. **Reutilizar el modelo actual**: lotes PEPS, libro de movimientos, presentaciones con factor, bitácora con folio, triggers de sincronización y componentes de interfaz.
2. **Nada cambia de comportamiento mientras no se configure**: el precio interno arranca en 0 % (igual al costo, como hoy), sin escalas de mayoreo no hay descuento, sin merma configurada un derivado se vende igual que hoy.
3. **Solo alta en los movimientos**: devoluciones, ajustes, recepciones y cancelaciones son documentos nuevos con sus movimientos, nunca ediciones de ventas, surtidos o lotes ya registrados. Así la sincronización sigue siendo por suma de movimientos y sin conflictos.
4. **Permisos en el servicio**, no solo en la pantalla (`service/Permisos`).
5. **Idempotencia por operación**: los documentos nuevos reciben su id desde la pantalla; repetir la misma operación (doble clic, reintento) devuelve el documento ya registrado en vez de duplicarlo.
6. **Porcentajes y reglas comerciales no confirmadas son configurables**, nunca constantes (ver [PENDIENTES_NEGOCIO_2026.md](PENDIENTES_NEGOCIO_2026.md)).

## Orden y dependencias

| # | Bloque | Depende de | Prioridad |
|---|---|---|---|
| 1 | Base: tipos de movimiento nuevos, concepto en crédito, permisos, idempotencia | — | Crítica |
| 2 | Reglas de precio por canal (márgenes) y precio interno de surtido usado en el crédito | 1 | Crítica |
| 3 | Escalas de mayoreo en carrito, cobro y ticket | 1 | Alta |
| 4 | Compras con proveedor, varias líneas y bonificaciones | 1 | Alta |
| 5 | Ajustes de inventario | 1 | Alta |
| 6 | Devoluciones de cliente (parcial, total, cambio) y su efecto en el corte | 1 | Alta |
| 7 | Derivados con merma y transformaciones | 1 | Alta |
| 8 | Recepción y cancelación de surtidos; devolución de sucursal a bodega | 1, 2 | Media |
| 9 | Devolución a proveedor | 4 | Media |
| 10 | Notas pendientes y antigüedad de saldos | 2 | Media |
| 11 | Sincronización de todo lo nuevo y triggers faltantes en la nube | 1–10 | Crítica para la nube |
| 12 | Reportes, impresión, documentación | 1–11 | Media |

## Decisiones técnicas

### Base de datos

- **Migraciones nuevas, nunca editar las aplicadas**: `V15` en adelante en `src/main/resources/db/migration` y su equivalente en `supabase/migrations`. Se registran en `Migraciones.MIGRACIONES`.
- **`movimientos_inventario` se reconstruye** (como ya lo hizo `V5`) para ampliar el `CHECK` de `tipo` con: `COMPRA`, `DEVOLUCION_CLIENTE`, `DEVOLUCION_SUCURSAL_SALIDA`, `DEVOLUCION_SUCURSAL_ENTRADA`, `DEVOLUCION_PROVEEDOR`, `TRANSFORMACION_SALIDA`, `TRANSFORMACION_ENTRADA`, `DIFERENCIA_SURTIDO`, `CANCELACION_SURTIDO`. Se conservan todas las filas, índices, el trigger de la cola y las vistas de existencias. En Postgres basta cambiar el `CHECK`.
- **`lotes` no se reconstruye** (tiene hijos con llave foránea): los lotes de compras, transformaciones y devoluciones usan `origen = 'ENTRADA'` con `referencia_id` y `folio` del documento; la interfaz describe el origen por el prefijo del folio.
- **Crédito**: `movimientos_credito` recibe `concepto` (`SURTIDO`, `PAGO`, `DEVOLUCION`, `DIFERENCIA_ENTREGA`, `CANCELACION`, `AJUSTE`) y `referencia_id`. Una nota de crédito es un `ABONO` con concepto distinto de `PAGO`: el saldo sigue siendo cargos − abonos y la vista `v_saldo_credito` de la nube no cambia.
- Tablas nuevas: `reglas_precio`, `escalas_precio`, `proveedores`, `compras`, `compra_detalle`, `devoluciones_proveedor`, `devolucion_proveedor_detalle`, `ajustes_inventario`, `devoluciones_venta`, `devolucion_venta_detalle`, `devoluciones_sucursal`, `devolucion_sucursal_detalle`, `transformaciones`, `recepciones_surtido`, `recepcion_surtido_detalle`, `cancelaciones_surtido`. Todas con id UUID, folio de bitácora, usuario, fecha y `sincronizado_en`; las de detalle viajan con su documento (`TablasNube.HIJAS`).
- Columnas nuevas: `venta_detalle.precio_lista_centavos`, `nivel_precio`, `escala_id`; `surtidos.total_interno_centavos`, `canal`; `surtido_detalle.importe_interno_centavos`, `regla_precio_id`; `sucursales.canal_precio`; `presentaciones.merma_pct`.

### Precios

- **Costos y precios separados**: el costo del lote (adquisición = inventario) no cambia; el precio interno de bodega a sucursal se guarda aparte en el surtido; el precio al público vive en `lote_precios` como hoy.
- **Reglas por canal** (`PUBLICO`, `BODEGA_SUCURSAL`, `RUTA_SIERRA`) con tipo `RECARGO_COSTO` (precio = costo × (1 + %)) o `MARGEN_PRECIO` (precio = costo ÷ (1 − %)). Alcance opcional por categoría, producto, presentación y sucursal; vigencia por fechas.
- **Precedencia**: gana la regla más específica (presentación + sucursal > presentación > producto + sucursal > producto > categoría + sucursal > categoría > sucursal > general); con la misma especificidad, la de inicio de vigencia más reciente. Dos reglas activas con el mismo alcance y vigencias que se cruzan se rechazan al guardar (sin contradicciones).
- **Precio interno del surtido** = costo PEPS del renglón con la regla `BODEGA_SUCURSAL` (o `RUTA_SIERRA` si la sucursal es de ese canal). Sin regla, 0 % → igual al costo. **El cargo a crédito usa el precio interno**. Los surtidos anteriores (sin precio interno) se leen al costo.
- **El costo del lote de la sucursal sigue siendo el costo de la empresa**, para que la utilidad consolidada no cuente dos veces el margen de bodega. La utilidad de la sucursal contra su precio interno queda como pendiente de negocio.
- **Precio al público sugerido** al surtir: costo × factor de la presentación con la regla `PUBLICO`. Solo sugiere; el administrador sigue confirmando el precio por lote.
- **Escalas de mayoreo**: `MENUDEO` es el precio del lote; `MAYOREO` y `SUPER_MAYOREO` se definen por producto (y opcionalmente presentación y sucursal) con una cantidad mínima en **unidades base** (así una caja de 12 cuenta 12 piezas) y un descuento en % o un precio fijo por unidad de la presentación. El nivel se decide con la cantidad total del producto en la venta. Se calcula en `model/Escalas` y lo usan igual `Carrito` y `VentaService`; la venta guarda precio de lista, precio aplicado, nivel y escala. Los tickets anteriores no cambian.

### Inventario

- **Compras**: un documento con proveedor, nota, fecha y varias líneas; cada línea crea su lote con su costo en una sola transacción. La **bonificación** se registra aparte (pagada, bonificada, recibida) con la política configurable `compras.bonificacion`: `PRORRATEAR` (el costo por unidad baja: importe ÷ unidades recibidas; valor por omisión) o `COSTO_CERO` (lote aparte a costo cero).
- **Ajustes**: motivo (sobrante, faltante, caducidad, daño, corrección, cortesía, devolución, otro), lote, ubicación, documento de referencia. Negativos salen PEPS (o del lote elegido); positivos entran al lote más reciente con precio (en sucursal un lote sin precio no se podría vender). La merma de productos sujetos a merma sigue en su flujo actual. No se acepta el mismo documento dos veces para el mismo producto y ubicación.
- **Derivado virtual** = presentación con código, nombre y precio propios y `merma_pct` opcional: al venderse, además del movimiento de venta se registra la merma correspondiente del producto original (mismo lote PEPS), visible en mermas y en la utilidad.
- **Transformación**: consume PEPS el origen, crea un lote del producto destino con el costo del origen consumido (la merma de proceso se absorbe en el costo del destino), registra la merma real y liga los lotes de origen y destino. En sucursal pide el precio de venta del lote nuevo.
- **Productos dañados o caducados** devueltos por un cliente no regresan al inventario vendible: quedan en la devolución con su costo.

### Devoluciones y caja

- **Cancelación** se queda como está (venta del turno, completa) y gana validación de motivo y autorizador en el servicio.
- **Devolución de cliente**: sobre cualquier ticket de la sucursal; por renglón y cantidad, sin pasar lo vendido menos lo ya devuelto; el importe es proporcional a lo cobrado en el renglón; la mercancía vendible regresa a los mismos lotes de la venta. Reembolso en efectivo sale del turno abierto y **se resta del efectivo esperado del corte**; otros métodos se registran sin tocar el cajón. Un cajero necesita autorización de supervisor o administrador.
- **Cambio**: devolución marcada como cambio; la venta de la mercancía nueva queda ligada a ella.
- **Devolución de sucursal a bodega**: sale de la sucursal (PEPS), entra al almacén en un lote nuevo con el costo del lote de origen si es vendible; opcionalmente genera una nota de crédito por el precio interno de lo devuelto.
- **Devolución a proveedor**: sale del lote de la compra (o PEPS), con motivo, tratamiento financiero (por definir con el negocio) y evidencia.

### Surtidos

- El surtido sigue entrando a la sucursal al registrarse (no se cambia el proceso actual). La sucursal **confirma la recepción** por renglón; una diferencia de menos sale de la sucursal (`DIFERENCIA_SURTIDO`) y queda pendiente de aclarar. El administrador la resuelve: regresa al almacén o se registra como pérdida, con nota de crédito opcional si el surtido fue a crédito.
- **Cancelación de surtido** solo si la sucursal no ha vendido nada del lote y no se ha recibido: regresa la mercancía a los lotes de origen y cancela el cargo con una nota de crédito.
- Estado del surtido derivado de esos documentos: Enviado, Recibido, Recibido con diferencia, Diferencia aclarada, Cancelado.
- Solo los surtidos registrados con la versión nueva esperan confirmación (`surtidos.requiere_recepcion`); los anteriores ya entraron con el proceso de antes y cuentan como recibidos, para que la sucursal no vea cientos de surtidos viejos «por recibir».

### Crédito

- **Notas pendientes**: los abonos se aplican a los cargos más antiguos primero (PEPS de cuentas por cobrar), calculado al consultar, sin cambiar lo guardado.
- **Antigüedad**: saldo pendiente por nota en tramos de 0–30, 31–60, 61–90 y más de 90 días.

### Sincronización

- Cada tabla nueva tiene trigger de alta a `sync_outbox` y entra en `TablasNube.ORDEN` (padres antes que hijos); las de detalle viajan con su documento.
- Nube: tablas equivalentes, solo alta (salvo catálogos: proveedores, reglas y escalas), RLS por sucursal igual que el resto, y triggers `marcar_sincronizado`/`tocar_actualizado` con índice de `sincronizado_en`, incluyendo los que faltaban en `gastos` y `nomina_semanal`.
- **Orden de despliegue**: primero las migraciones de Supabase (amplían el `CHECK` de movimientos y crean tablas), después la versión nueva de la caja. Al revés, la nube rechazaría los movimientos nuevos; quedarían en la cola y se reintentarían al abrir la app.

### Interfaz

- Todo con los componentes actuales (`TablaDatos`, `MaestroDetalle`, `PanelDetalle`, `Formulario`, `Dialogo`, `Pestanas`, `ResumenMetricas`).
- Administrador: **Precios** (reglas de margen, mayoreo, lista de precios por canal), **Compras** (compras, proveedores, devoluciones a proveedor), **Ajustes** (ajustes y transformaciones), **Devoluciones** (clientes y sucursales), surtidos con recepción y cancelación, crédito con notas pendientes y antigüedad.
- Supervisor: **Recepción de surtidos**, ajustes de su sucursal y consulta de devoluciones.
- Caja: botón **Devolución** (F7) con autorización; el carrito muestra el nivel de mayoreo aplicado.

## Lo que no se hace

- No se aplica ninguna migración a la base real ni a Supabase.
- No se cambia el proceso de surtido inmediato, ni la apertura automática del turno, ni las fórmulas de indicadores existentes. La venta neta y el costo de lo vendido ahora restan las devoluciones, que antes no existían: sobre los datos anteriores dan exactamente lo mismo.
- No se fijan los porcentajes de la reunión (23 %, 12 %, 10 %, 2 %) como constantes.
