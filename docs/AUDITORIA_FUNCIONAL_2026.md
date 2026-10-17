# Auditoría funcional 2026 — estado inicial

Estado del sistema **antes** de los cambios de la rama `funcional-2026` (partida de `main` en el commit `4fc19d6`, 16 oct 2026). Se revisó el código local, no solo la auditoría anterior del repositorio público.

> El estado **después** de los cambios, punto por punto, está en [CAMBIOS_IMPLEMENTADOS_2026.md](CAMBIOS_IMPLEMENTADOS_2026.md) (sección 1). Este documento se conserva como foto del punto de partida.

## Clasificación

| Estado | Significado |
|---|---|
| **IMPLEMENTADO Y VERIFICADO** | Lógica, persistencia e interfaz presentes y cubiertas por pruebas que pasan. |
| **IMPLEMENTADO PARCIALMENTE** | Existe una parte (o existe sin prueba, o solo en la interfaz). |
| **DEFECTUOSO** | Existe pero se comporta mal o deja un hueco confirmado al leer el código. |
| **FALTANTE** | No existe. |
| **NO VERIFICABLE** | Depende de hardware, de la nube real o de una regla de negocio no confirmada. |

## Línea base

| Comprobación | Resultado |
|---|---|
| Compilación (`mvnw -o clean test`, JDK 21.0.11, Maven Wrapper) | Compila sin errores. |
| Pruebas | 117 ejecutadas, 0 fallas, 5 omitidas (requieren la nube real o una base generada) y **1 o 2 errores de entorno** en `AppContextTest` según la corrida: Windows no deja borrar la carpeta temporal porque el log sigue abierto (`Failed to delete temp directory … logs\puntoventa…log`). No son fallas de lógica. |
| Git | Rama `main` limpia y al día con `origin/main`; el trabajo se hace en la rama `funcional-2026`. El archivo `src/main/java/com/cremerias/puntoventa/assets/DASHBOARD 2026.xlsx` está ignorado por Git y no se toca. |

## Arquitectura encontrada

- **Escritorio JavaFX 21 + AtlantaFX + Ikonli**, Java 21, Maven. Punto de entrada `Launcher` → `App`; `AppContext` arma los servicios a mano.
- **SQLite local** (`db/Database`, WAL, `synchronous=FULL`, llaves foráneas activas). Migraciones versionadas con *checksum* en `db/Migraciones` (`V1`–`V14`).
- **Importes en centavos** (`long`), **cantidades en unidad base** (piezas, o kilos con 3 decimales). El inventario es un **libro de movimientos** (`movimientos_inventario`) por lote; la existencia es la suma (`v_existencias`, `v_existencias_lote`).
- **Lotes PEPS** (`lotes`) con costo por unidad base; en sucursal cada lote lleva el **precio de venta por presentación** (`lote_precios`), que se fija al surtir.
- **Presentaciones** (`presentaciones`): pieza, caja, kilo a granel…, con `factor` en unidades base y código de barras propio.
- **Sincronización offline-first**: triggers SQLite encolan cada alta/cambio en `sync_outbox`; `sync/Subida` sube el estado actual de cada fila (con sus filas hijas) a la función `subir_cambios` de Supabase; `sync/Bajada` baja por `sincronizado_en`. Ids UUID generados en el equipo. En la nube (`supabase/migrations`) las tablas de movimientos son **solo alta** (`on conflict do nothing`), con RLS por sucursal.
- **Roles**: `ADMINISTRADOR` (almacén central, todo), `SUPERVISOR` (su sucursal), `CAJERO`. La bitácora (`bitacora`) da un folio por acción.
- **Interfaz**: sistema de diseño reciente (`ui/componentes`: `TablaDatos`, `MaestroDetalle`, `PanelDetalle`, `Formulario`, `BarraHerramientas`, etc.), temas claro/oscuro.

---

## 4. Precios, márgenes y utilidades

| Requisito | Estado | Evidencia (ruta · clase · método) | Pruebas | Riesgo / nota |
|---|---|---|---|---|
| Costo del lote | IMPLEMENTADO Y VERIFICADO | `LoteRepository.insertar` (costo por unidad base). Almacén: `AlmacenService.reabastecer`. Sucursal: `SurtidoService.registrar` (costo PEPS de los lotes del almacén ÷ cantidad). | `AdministracionTest.elSurtidoSaleDelLoteMasAntiguoYSeCargaAlCredito` | El lote de sucursal promedia el costo de los lotes de almacén que se consumieron en ese surtido. |
| Precio del lote / por presentación | IMPLEMENTADO Y VERIFICADO | `lote_precios`; se captura al surtir (`DialogoSurtido`) y `SurtidoService.registrar` exige precio > 0 para cada presentación activa. | `enLaCajaSeVendePrimeroAlPrecioAnteriorYLuegoAlNuevo` | Los precios se escriben a mano; no hay regla que los sugiera desde el costo. |
| Precio mostrado en la caja | IMPLEMENTADO Y VERIFICADO | `CatalogoService.cargar` → `PreciosLote.de(...).precioActual()` (lote más antiguo con existencia). | `CarritoTest` | — |
| Precio utilizado al cobrar | IMPLEMENTADO Y VERIFICADO | `VentaService.registrar` relee lotes y precios de la base y cobra con `Tarifa.cotizar`, el mismo cálculo que `Carrito.recalcular`. | `CarritoTest.alAgotarseElLoteSeCobraAlPrecioDelSiguiente`, `CajaYVentasTest` | El total de pantalla y el guardado no pueden diferir. |
| Utilidad por producto | IMPLEMENTADO PARCIALMENTE | `IndicadoresService` da utilidad por sucursal (venta − costo PEPS de lo vendido). Por producto solo hay vendido e importe (`InventarioSucursalService.detallado`), no utilidad. | `IndicadoresServiceTest.calcularUsaVentaNetaYCostoRealDelLote` | — |
| Precio de surtido (bodega → sucursal) | **FALTANTE** | El surtido guarda `total_costo_centavos` (costo PEPS) y `total_venta_centavos` (valor a **precio al público** de la sucursal). No existe un precio interno de bodega a sucursal. | — | Ver 5.1. |
| Importe de crédito | IMPLEMENTADO PARCIALMENTE | `SurtidoService.registrar` carga a crédito `costoTotal`. | `elSurtidoSaleDelLoteMasAntiguoYSeCargaAlCredito` afirma saldo = costo. | Ver 5.1: no es una confusión de variables, es «transferencia al costo» deliberada; falta el margen comercial. |
| Motor de reglas de precio (producto, categoría, sucursal, canal, presentación, cantidad, vigencia) | **FALTANTE** | No hay tabla ni servicio de reglas. `SurtidoService.preciosSugeridos`/`preciosPromedio` solo repiten precios anteriores. | — | — |
| Distinción costo de adquisición / inventario / precio interno / precio al público / utilidad / recargo / margen | IMPLEMENTADO PARCIALMENTE | Existen costo de lote (adquisición = inventario) y precio al público por lote. No existen precio interno, recargo ni margen configurables. | — | — |
| Precios por volumen (menudeo, mayoreo, súper mayoreo) | **FALTANTE** | `Tarifa`, `Carrito` y `VentaService` solo conocen el precio por lote. | — | — |
| Sin errores de redondeo | IMPLEMENTADO Y VERIFICADO | Centavos `long`; `BigDecimal` con `HALF_UP` (`Dinero.importe`, `Tarifa.importe`). | `UtilTest.importeRedondeaAlCentavo` | — |
| Precio aplicado conservado en la venta | IMPLEMENTADO Y VERIFICADO | `venta_detalle.precio_unitario_centavos` e `importe_centavos`; el ticket se arma de ahí. | `CajaYVentasTest` | No guarda el origen (regla) porque no hay reglas. |
| Impedir precios negativos | IMPLEMENTADO Y VERIFICADO | `CHECK (precio_centavos >= 0)` en `lote_precios`; `SurtidoService.registrar` exige > 0. | — | — |
| Modificación de precios solo autorizada | IMPLEMENTADO PARCIALMENTE | Solo el panel del administrador fija precios (interfaz). Los servicios de administración no revisan el rol. | — | Ver 19. |

## 5. Créditos y saldos de sucursales

### 5.1 Investigación del posible defecto `costoTotal` vs `ventaTotal`

En `SurtidoService.registrar`:

- `costoTotal` = suma del costo PEPS de los lotes del almacén que salen.
- `ventaTotal` = `valorVenta(...)`: cantidad × **precio al público** que la sucursal cobrará (presentación principal).

El cargo a crédito usa `costoTotal`. **Conclusión**: no es una variable equivocada. Cargar `ventaTotal` sería incorrecto, porque es el precio al cliente final e incluye el margen de la sucursal. El sistema transfiere **al costo** (la prueba existente lo afirma). Lo que falta es el **precio interno de bodega a sucursal** (costo + margen de bodega) que se mencionó en la reunión y cuyo porcentaje no está confirmado. Se clasifica como **IMPLEMENTADO PARCIALMENTE (falta el importe comercial)**.

| Requisito | Estado | Evidencia | Pruebas | Riesgo / nota |
|---|---|---|---|---|
| Notas de surtido a crédito | IMPLEMENTADO Y VERIFICADO | `surtidos.forma_pago = 'CREDITO'` + `movimientos_credito` tipo `CARGO` con `surtido_id` y el mismo folio. | `elSurtidoSaleDelLoteMasAntiguoYSeCargaAlCredito` | — |
| Historial de cargos y abonos | IMPLEMENTADO Y VERIFICADO | `CreditoService.movimientos`; `CreditoPagina` con saldo después de cada movimiento. | Igual | — |
| Abonos parciales; sin saldo negativo | IMPLEMENTADO Y VERIFICADO | `CreditoService.abonar` rechaza abono ≤ 0 o mayor al saldo. | Igual (`abonar(800000)` lanza excepción) | No hay política de anticipos (correcto mientras no se confirme). |
| Saldo por sucursal | IMPLEMENTADO Y VERIFICADO | `CreditoService.saldos`, `saldo(sucursalId)`. | Igual | — |
| Consulta de notas pendientes (saldo por nota) | **FALTANTE** | Los abonos bajan el saldo global; no se aplican a notas. | — | — |
| Detalle de cada cargo / mercancía entregada | IMPLEMENTADO PARCIALMENTE | `SurtidoService.detalle(surtidoId)` existe; el estado de cuenta (`CreditoPagina`) muestra el folio pero no abre el detalle. | — | — |
| Reporte de antigüedad de saldos | **FALTANTE** | — | — | — |
| Cancelación de un cargo | **FALTANTE** | No se puede cancelar un surtido ni su cargo. | — | — |
| Sin cargos repetidos por reintentos de sincronización | IMPLEMENTADO PARCIALMENTE | Local: un surtido es una sola transacción con id UUID. Nube: `movimientos_credito` es solo alta (`on conflict (id) do nothing`). | `SincronizacionTest` (cola local) | El comportamiento en Postgres real es **NO VERIFICABLE** sin la nube. |

## 6. Inventarios y unidades de medida

| Requisito | Estado | Evidencia | Pruebas | Riesgo / nota |
|---|---|---|---|---|
| Caja ↔ pieza, paquete/cartón ↔ piezas | IMPLEMENTADO Y VERIFICADO | `presentaciones.factor`; venta de 1 caja descuenta 12 unidades base (`Tarifa.cotizar`, `VentaService.registrar` guarda `cantidad_base`). | `CarritoTest.unaCajaConsumeDoceUnidadesDelLote`, `CajaYVentasTest.lasPresentacionesDeUnMismoProductoSeSumanContraLaExistencia` | — |
| Kilogramo ↔ gramo | IMPLEMENTADO Y VERIFICADO | Base en kilos con 3 decimales; captura en gramos (`Cantidades.desdeCaptura`). | `CajaYVentasTest.aGranelNoSeVendeMasDelPesoQueHay` | — |
| Litro ↔ mililitro | **FALTANTE** (NO VERIFICABLE si se necesita) | `Unidad` solo tiene `PZA` y `KG`. Los líquidos se venden por pieza («Leche 1 L»). | — | Pendiente de negocio. |
| Un solo saldo por mercancía | IMPLEMENTADO Y VERIFICADO | Un libro de movimientos por lote; las presentaciones consumen el mismo inventario. | Igual | — |
| Lotes PEPS (costo, fecha, existencia, ubicación, origen, movimientos, agotamiento) | IMPLEMENTADO Y VERIFICADO | `LoteRepository` (`ORDEN_PEPS`), `AlmacenService.salidaPeps`, `v_existencias_lote`. | `AdministracionTest`, `InventarioSucursalServiceTest` | — |
| Valor de inventario y costo promedio ponderado | IMPLEMENTADO Y VERIFICADO | `AlmacenService.existencias` (valor por lote); `InventarioSucursalService` (costo promedio = valor ÷ existencia). | `calculaCostoPromedioYValorDelInventarioAlCosto` | El «precio promedio» (`SurtidoService.preciosPromedio`) es otra cosa: promedio de precios de venta. Están separados. |
| Existencia negativa entre cajas desconectadas | IMPLEMENTADO PARCIALMENTE | Cada caja impide vender más de lo que **ella** ve; dos cajas sin conexión pueden vender las mismas últimas unidades. | — | No hay reporte de reconciliación de existencias negativas. |

## 7. Productos compuestos, quesos y derivados

| Requisito | Estado | Evidencia | Riesgo / nota |
|---|---|---|---|
| Producto independiente | IMPLEMENTADO Y VERIFICADO | `productos`. | — |
| Presentación de un producto | IMPLEMENTADO Y VERIFICADO | `presentaciones` (factor, código de barras, precio por lote). | — |
| Derivado virtual (consume el inventario del original, código y precio propios) | IMPLEMENTADO PARCIALMENTE | Una presentación ya lo cubre (p. ej. «Rebanado 300 g», factor 0.300 kg, su propio código y precio). Falta la **regla de merma** del derivado. | Sin merma, rebanar no deja rastro de la pérdida. |
| Producto transformado (existencia propia, consumo del origen, costo asignado, merma, trazabilidad) | **FALTANTE** | — | — |
| Venta por gramos, varios códigos | IMPLEMENTADO Y VERIFICADO | Etiqueta de báscula (`CodigoBarras.leerEtiquetaBascula`), código por presentación. `CajaYVentasTest.busquedaSinAcentosYEtiquetaDeBascula`. | — |

## 8. Compras a proveedores

| Requisito | Estado | Evidencia | Riesgo / nota |
|---|---|---|---|
| Entrada de mercancía al almacén | IMPLEMENTADO Y VERIFICADO | `AlmacenService.reabastecer` (un producto por operación, crea su lote). `DialogoEntradaAlmacen`. | — |
| Documento de compra con proveedor, nota y varias líneas | **FALTANTE** | No hay `proveedores` ni `compras`. | — |
| Compra transaccional (todo o nada) | IMPLEMENTADO PARCIALMENTE | Cada entrada es atómica, pero una compra de 20 productos son 20 operaciones separadas. | Un error a la mitad deja la compra incompleta. |
| Costo real por lote (no reemplazar costos históricos) | IMPLEMENTADO Y VERIFICADO | Cada entrada crea su lote con su costo. `elSurtidoSaleDelLoteMasAntiguoYSeCargaAlCredito` ($133 y $150 conviven). | — |

## 9. Cortesías y bonificaciones de proveedores

| Requisito | Estado | Nota |
|---|---|---|
| Cantidad pagada, bonificada, recibida, valoración | **FALTANTE** | Hoy se tendría que capturar como entrada a costo cero o «inventar» una compra. |

## 10. Devoluciones, cambios y cancelaciones

| Requisito | Estado | Evidencia | Pruebas | Riesgo / nota |
|---|---|---|---|---|
| Cancelación de ticket | IMPLEMENTADO Y VERIFICADO | `VentaService.cancelar`: regresa lo vendido a los mismos lotes, motivo, quien cancela y quien autoriza, bitácora; solo ventas del turno abierto. | `CajaYVentasTest.cancelarVentaRegresaInventarioYNoCuentaEnElCorte`, `unCajeroNoPuedeAutorizar` | — |
| Sin doble cancelación | IMPLEMENTADO Y VERIFICADO | Revisión de estado + `UPDATE … WHERE estado = 'COMPLETADA'`. | Igual | — |
| Motivo obligatorio y autorizador válido en el servicio | **DEFECTUOSO** (menor) | El motivo y el rol del autorizador solo se validan en la interfaz (`DialogoTexto` obligatorio, `DialogoAutorizacion`); `VentaService.cancelar` acepta motivo vacío y un autorizador cajero. | — | Defensa en profundidad. |
| Devolución de cliente total/parcial | **FALTANTE** | — | — | Solo existe cancelar la venta completa y solo dentro del turno. |
| Cambio de mercancía | **FALTANTE** | — | — | — |
| Devolución de sucursal a bodega | **FALTANTE** | — | — | — |
| Devolución a proveedor | **FALTANTE** | — | — | — |
| Caducados y dañados fuera del inventario vendible | IMPLEMENTADO PARCIALMENTE | `AlmacenService.registrarMerma` (solo productos «sujetos a merma»). | `laMermaSoloAplicaAProductosMarcados` | No hay baja por caducidad/daño para el resto. |

## 11. Ajustes de inventario

| Requisito | Estado | Evidencia | Nota |
|---|---|---|---|
| Ajustes positivos y negativos con motivo, lote, ubicación, usuario, documento | **FALTANTE** | El tipo `AJUSTE` existe en el `CHECK` de `movimientos_inventario` pero ningún servicio lo usa. | — |

## 12. Bodega, surtidos, sucursales y rutas

| Requisito | Estado | Evidencia | Nota |
|---|---|---|---|
| Flujo bodega → sucursal | IMPLEMENTADO Y VERIFICADO | `SurtidoService.registrar` (salida PEPS del almacén, lote nuevo en la sucursal con precios, crédito opcional, orden de reabasto). | Pruebas de `AdministracionTest`. |
| Estados de surtido (enviado, recibido, con diferencia, cancelado) | **FALTANTE** | El surtido entra a la sucursal al registrarse. | — |
| Confirmación de recepción por la sucursal | **FALTANTE** | — | — |
| Listas de precios por canal (bodega → cremerías, ruta Sierra, público) | **FALTANTE** | — | — |
| Trazabilidad de lo pendiente de sincronizar | IMPLEMENTADO PARCIALMENTE | `IndicadorConexion` muestra cuántos cambios faltan por subir. | No dice cuáles. |

## 13. Cortes de caja, retiros y diferencias

| Requisito | Estado | Evidencia | Pruebas |
|---|---|---|---|
| Ventas por forma de pago, entradas, retiros (retiro con autorización), canceladas fuera del corte | IMPLEMENTADO Y VERIFICADO | `TurnoRepository.resumen`, `ResumenTurno.efectivoEsperado`, `CajaService.registrarMovimiento`. | `CajaYVentasTest.movimientosYCorteDeCaja` |
| Esperado, contado, faltante, sobrante; histórico sin tocar ventas | IMPLEMENTADO Y VERIFICADO | `CajaService.cerrar`, `turnos_caja.diferencia_centavos`. | Igual, `CortesSucursalTest` |
| Devoluciones en el corte | **FALTANTE** | No existen devoluciones con reembolso. | — |
| Apertura manual obligatoria | No se exige (correcto) | `CajaController.abrirTurno`: el turno se abre solo con fondo 0. | — |

## 14. Sincronización offline-first

| Requisito / escenario | Estado | Evidencia | Pruebas |
|---|---|---|---|
| Operación local sin internet | IMPLEMENTADO Y VERIFICADO | Todo se escribe primero en SQLite; `Sincronizador` reintenta cada 10 s. | `PruebaOfflineYModulosTest` (omitida sin nube), `SincronizacionTest` |
| Cola, reintentos, fallo a mitad | IMPLEMENTADO Y VERIFICADO | `SyncOutboxRepository` (`ENVIANDO`, `devolver`, `MAX_INTENTOS`), `Subida` parte el lote rechazado. | `sinConexionTodoRegresaALaColaSinContarComoIntento`, `unCambioHechoMientrasSeSubeNoSePierde`, `siLaNubeRechazaUnCambioLosDemasSiSuben` |
| Idempotencia | IMPLEMENTADO PARCIALMENTE | Ids UUID; tablas de movimientos solo alta en la nube. | Sin prueba que reenvíe la misma operación contra una nube que guarde por id. |
| Conflictos | IMPLEMENTADO PARCIALMENTE | Un registro con cambios locales sin subir no se pisa con lo que baja (`Bajada.guardar`). Movimientos: no hay conflicto (se suman). | `noPisaUnCambioLocalQueTodaviaNoSube` |
| Existencias en conflicto entre cajas | **FALTANTE** | Sin reporte de existencias negativas. | — |
| Tablas `gastos` y `nomina_semanal` en la nube | **DEFECTUOSO** | `supabase/migrations/20261007080000_gastos_y_nomina.sql` crea las tablas sin los triggers `marcar_sincronizado`/`tocar_actualizado` ni el índice de `sincronizado_en` (el ciclo que los crea solo corrió en `…050100_tablas`). Una edición o baja de gasto no actualiza `sincronizado_en` y las otras cajas no la bajan. | NO VERIFICABLE contra Supabase real; evidente en el SQL. |

## 15. Indicadores y reportes

| Requisito | Estado | Evidencia |
|---|---|---|
| Ventas por sucursal, utilidad bruta, costo de lo vendido, inventario al costo, deuda, comparativo | IMPLEMENTADO Y VERIFICADO | `IndicadoresService`, `IndicadoresServiceTest`. |
| Ventas por caja y diferencias de caja | IMPLEMENTADO Y VERIFICADO | Cortes por caja (`CajaService.cortesDelDia`, `CortesController`). |
| Ventas por vendedor | IMPLEMENTADO PARCIALMENTE | Solo dentro de comisiones (`ComisionService`). |
| Ventas por producto | IMPLEMENTADO Y VERIFICADO | «Productos por sucursal → Ventas y rotación». |
| Mercancía recibida y transferida | IMPLEMENTADO PARCIALMENTE | Entradas en el historial; surtidos en su página. No hay compras. |
| Merma | IMPLEMENTADO Y VERIFICADO | En la utilidad neta (`calculaLaUtilidadNetaRestandoGastosNominaYMermas`). |
| Devoluciones y ajustes | **FALTANTE** | No existen. |
| Transferencias no contadas como venta al público | IMPLEMENTADO Y VERIFICADO | La venta neta sale de `ventas`; los surtidos no cuentan. |

## 16. Impresión de tickets

| Requisito | Estado | Evidencia | Nota |
|---|---|---|---|
| Formato, rollo de 80 mm, márgenes, negritas | IMPLEMENTADO PARCIALMENTE | `Impresion`: `Monospaced` 9 pt, negrita solo en líneas destacadas, busca el papel de 80 mm con margen mínimo. `TicketTexto` a 40 columnas. | Calidad real **NO VERIFICABLE** sin la impresora: depende del papel, del cabezal y del controlador. |

## 17. Interfaz

| Requisito | Estado | Evidencia |
|---|---|---|
| Sistema de diseño, temas, menú, tablas, paneles, formularios, estados vacíos, teclado | IMPLEMENTADO Y VERIFICADO | `ui/componentes/*`, `css/tema-claro.css`, `css/tema-oscuro.css`, `css/app.css`. |
| Pantallas para márgenes, mayoreo, derivados, transformación, compras, cortesías, devoluciones, ajustes, recepción de surtidos, adeudos detallados | **FALTANTE** | — |

## 18. Migraciones

| Requisito | Estado | Evidencia |
|---|---|---|
| Migraciones incrementales con *checksum*, sin editar las aplicadas | IMPLEMENTADO Y VERIFICADO | `Migraciones.aplicarSiFalta`, `MigracionesTest`. |
| Equivalencia SQLite ↔ Supabase | IMPLEMENTADO PARCIALMENTE | Mismo modelo y nombres; ver el defecto de triggers en 14. |
| Respaldo y recuperación | IMPLEMENTADO PARCIALMENTE | `db/Respaldos` copia la base al arrancar; no hay guía escrita de recuperación. |

## 19. Seguridad, permisos y auditoría

| Requisito | Estado | Evidencia | Riesgo |
|---|---|---|---|
| Roles y alcance por sucursal en servicios | IMPLEMENTADO PARCIALMENTE | Sí validan en el servicio: `IndicadoresService.validarAlcance`, `GastoService`, `NominaService`, `InventarioSucursalService` (detalle), `CajaService.validarPermisoCorte`, `AuthService.autorizar`. No validan: `SurtidoService`, `AlmacenService`, `CreditoService`, `ProductoAdminService`, `SucursalService`, `UsuarioService`, `LimitesService`, `OrdenService` (dependen de que solo el administrador llega a su pantalla). | Un error de interfaz expondría operaciones de administrador. |
| RLS en la nube | IMPLEMENTADO PARCIALMENTE | `…050200_seguridad.sql` (por sucursal; administrador y cajas ven todo). | NO VERIFICABLE sin Supabase. |
| Bitácora de cambios sensibles | IMPLEMENTADO Y VERIFICADO | `BitacoraRepository.registrar` en cada operación con folio. `cadaAccionTieneFolioYQuedaEnElHistorial`. | — |
| Secretos fuera del repositorio | IMPLEMENTADO Y VERIFICADO | `nube.properties` solo lleva la URL y la llave publicable; la conexión de cada caja vive en `~/.cremerias-pos/supabase.properties`. | — |

## 20. Pruebas existentes

117 pruebas (JUnit 5) sobre servicios, caja, administración, indicadores, inventario de sucursal, sincronización con una nube falsa, migraciones y utilidades. No hay pruebas de precios por volumen, compras, devoluciones, ajustes, transformaciones ni recepción de surtidos porque esas funciones no existen.

## Resumen de prioridades

1. **Precio interno de bodega y su uso en el crédito** (sin cambiar el comportamiento mientras no se configure un margen).
2. **Precios por volumen** con recálculo en la caja y validación en el servicio.
3. **Compras con proveedor, varias líneas y bonificaciones** reutilizando lotes y entradas.
4. **Devoluciones de cliente** (parcial, total y cambio) con efecto en el corte.
5. **Ajustes de inventario** con motivos y permisos.
6. **Derivados con merma y transformaciones** con trazabilidad de lotes.
7. **Recepción de surtidos**, diferencias y cancelación; devoluciones de sucursal a bodega y a proveedor.
8. **Notas pendientes y antigüedad de saldos**.
9. **Sincronización** de todo lo nuevo y corrección de los triggers faltantes en la nube.
10. **Defensa en profundidad**: permisos en los servicios nuevos y en la cancelación.
