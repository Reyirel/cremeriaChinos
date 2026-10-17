# Pruebas de la implementación 2026

Cómo se probó lo descrito en [CAMBIOS_IMPLEMENTADOS_2026.md](CAMBIOS_IMPLEMENTADOS_2026.md). Todas las pruebas usan **bases SQLite temporales** (`@TempDir`) y una **nube simulada en memoria**; ninguna toca `~/.cremerias-pos` ni Supabase.

## Cómo correrlas

```bash
# Windows (Git Bash), JDK 21
JAVA_HOME="/c/Program Files/Java/jdk-21.0.11" ./mvnw -o clean test
# una sola clase
JAVA_HOME="/c/Program Files/Java/jdk-21.0.11" ./mvnw -o clean test -Dtest=EscenariosIntegralesTest
```

Usar siempre `clean`: el IDE también escribe en `target/classes` y puede dejar clases a medio compilar.

## Resultado

| | Antes (línea base) | Después |
|---|---|---|
| Pruebas ejecutadas | 117 | **182** (+65) |
| Fallas | 0 | **0** |
| Omitidas | 5 (requieren la nube real o una base generada a propósito) | 5 (las mismas) |
| Errores | 1–2 de entorno en `AppContextTest` | 1 a 3 de entorno en `AppContextTest` (varía entre corridas) |

Los errores de `AppContextTest` son del entorno Windows: al terminar, JUnit no puede borrar la carpeta temporal porque el log o los archivos de la base siguen abiertos un instante (`Failed to close extension context … Failed to delete temp directory`). Las tres pruebas de esa clase pasan sus verificaciones; lo que falla es la limpieza. Ya ocurría antes de estos cambios (también con los archivos de la base, en corridas del 16 de octubre previas a esta rama) y no es una falla de la lógica.

## Pruebas nuevas

### Precios, márgenes, crédito y mayoreo — `service/PreciosYMayoreoTest` (16)

| Prueba | Qué comprueba |
|---|---|
| `recargoSobreCostoYMargenSobrePrecioSonCalculosDistintos` | 25 % sobre costo = 20 % de margen; 23 % sobre costo ≠ 23 % de margen; redondeo al centavo. |
| `laListaDePreciosSoloSugierePrecioAlPublicoConRegla` | Lista por canal: al costo en canales internos sin regla; al público sin regla no hay precio sugerido; con regla, el precio sale del costo. |
| `sinReglaElPrecioInternoEsElCostoYGanaLaReglaMasEspecifica` | Precedencia producto > categoría > sucursal > general. |
| `dosReglasDelMismoAlcanceConFechasQueSeCruzanSeRechazan` | Sin contradicciones entre reglas. |
| `laReglaVieneConVigenciaYUnaFuturaNoAplicaHoy` | Vigencia por fechas. |
| `elSurtidoACreditoCargaElPrecioInternoNoElValorAlPublico` | El crédito usa el precio interno (investigación 5.1 de la auditoría). |
| `sinReglaElCargoSigueSiendoElCostoComoAntes` | Sin configurar, el comportamiento anterior no cambia. |
| `unaSucursalDeLaRutaUsaLaListaDeLaSierra` | Canal por sucursal. |
| `lasNotasPendientesAplicanLosAbonosAlCargoMasAntiguo` | Abonos a la nota más antigua; notas pendientes. |
| `elMayoreoSeAplicaJustoEnLosLimitesCincoSeisOnceYDoce` | Límites exactos 5/6 y 11/12 (configurados como dato de prueba). |
| `unaCajaCuentaComoDocePiezasParaElMayoreo` | Equivalencia caja = 12 piezas. |
| `unPrecioDeMayoreoNuncaCobraMasQueElMenudeo` | Nunca cobra más que el precio normal. |
| `lasEscalasContradictoriasSeRechazan` | Un nivel por alcance; súper mayoreo mayor y no peor que mayoreo. |
| `cambiarLasEscalasNoCambiaLosTicketsAnteriores` | Los tickets guardados no cambian. |
| `unDerivadoConMermaDescuentaLoVendidoYSuMermaDelOriginal` | Derivado con merma: sale lo vendido + la merma del mismo producto, registrada al costo. |
| `laMermaDeUnDerivadoCuentaParaNoVenderDeMas` | La merma cuenta para la existencia disponible. |

### Compras y bonificaciones — `service/ComprasTest` (9)

Varias líneas con un lote por línea; prorrateo de la bonificación; bonificación a costo cero en lote aparte; cada compra conserva su costo; **una línea inválida no deja nada de la compra**; repetir la misma compra no la duplica; piezas enteras y solo el administrador compra; devolución a proveedor desde el lote de la compra; proveedor sin repetir y deshabilitado sin compras.

### Ajustes y transformaciones — `service/AjustesYTransformacionesTest` (9)

Faltante PEPS con su costo; sobrante al lote más reciente (en sucursal, a uno con precio); signo y datos obligatorios por motivo; supervisor solo su sucursal y cajero no ajusta; un ajuste no sustituye una compra ni repite documento; idempotencia; 5 kg de queso → 10 paquetes con el costo del origen; merma real registrada y no se produce más de lo que alcanza; el lote nuevo en sucursal se vende en la caja.

### Devoluciones de clientes — `service/DevolucionesTest` (10)

Parcial: lo vendible regresa y el reembolso se resta del efectivo del corte; no más de lo vendido y el último resto exacto; dañado/caducado no regresa y queda al costo; reembolso al precio con mayoreo; el efectivo no pasa de lo que hay en caja y la tarjeta no toca el cajón; autoriza supervisor/administrador y el motivo es obligatorio; venta con devolución no se cancela y cancelada no se devuelve; el cambio queda ligado a la venta nueva; idempotencia; granel en gramos con importe proporcional.

### Surtidos — `service/SurtidosRecepcionTest` (8)

Recepción completa; faltante que sale de la sucursal y se aclara con regreso al almacén y nota de crédito; pérdida en el traslado sin regreso ni crédito si no se pide; no puede faltar lo que la sucursal ya vendió; cancelación de un surtido no recibido (todo regresa a los mismos lotes y el crédito queda en cero); no se cancela lo recibido o vendido; devolución de sucursal a bodega con nota de crédito a precio interno; el supervisor solo recibe su sucursal.

### Escenarios integrales — `service/EscenariosIntegralesTest` (3)

| Escenario | Recorrido | Se comprueba |
|---|---|---|
| **A** | Compra de 20 kg + 2 kg de regalo a $110 → regla de bodega 10 % → surtido de 10 kg a crédito → la sucursal recibe 9.5 kg → el administrador aclara (regresó al almacén, con nota de crédito) → abono de $500 → abono que excede el saldo (rechazado) → abono que liquida. | Costo del lote $100/kg por prorrateo; costo $1,000 e interno $1,100; existencias en almacén y sucursal en cada paso; estados del surtido; saldo $1,045 tras la nota; nota pendiente $545; antigüedad; saldo final 0 sin negativos; conceptos del historial. |
| **B** | Queso de 10 kg con presentación «Rallado» con 5 % de merma → compra a $100/kg → surtido → recepción → venta de 350 g rallados a $180/kg. | Venta $63.00; existencia 9.632 kg (10 − 0.350 − 0.018 de merma); merma registrada de 18 g a $1.80; venta neta $63, costo $35, utilidad bruta $28 y utilidad neta que descuenta la merma. |
| **C** | Escala de mayoreo 12+ con 10 % → venta de 12 refrescos → devolución de 3 → devolución de los 9 restantes → intento de devolver uno más. | Reembolsos al precio cobrado ($54 y $162, el último exacto); inventario de vuelta; efectivo esperado del corte; venta neta y costo de lo vendido; rechazo de la tercera devolución; el ticket original conserva total y nivel. |
| **D** | En `sync/SincronizacionModulosTest` (abajo): sin conexión se vende, se devuelve y se ajusta; se pierde la respuesta de la nube; el reintento no duplica ventas, devoluciones, ajustes ni movimientos, y la otra caja recibe todo una sola vez. | |

### Sincronización con varias cajas — `sync/SincronizacionModulosTest` (7)

Dos cajas copiadas de la misma base, cada una con su cola, contra una nube simulada que guarda por id (solo alta en movimientos y documentos, como Supabase) y puede «perder» la respuesta:

1. Dos cajas sin conexión venden las mismas últimas piezas; al sincronizar, la existencia queda negativa y aparece en la reconciliación.
2. Si solo quedan lotes en negativo, se reacomodan sin cambiar el total.
3. **Se pierde la respuesta de la nube y el reintento no duplica nada** (escenario D).
4. El cargo a crédito de un surtido llega una sola vez aunque se reintente.
5. **Surtido por recibir entre equipos**: la bodega surte, la sucursal confirma con faltante desde su caja, la bodega no lo ve hasta sincronizar y su aclaración (con nota de crédito) llega una vez.
6. **Devolución sin conexión** en una caja de un ticket cobrado en otra: llega una vez y el inventario cuadra en las dos.
7. Una caja desconectada vende con las escalas que conoce y después toma las nuevas.

### Los 10 escenarios de sincronización del encargo

| # | Escenario | Cubierto por |
|---|---|---|
| 1 | Venta sin internet | `SincronizacionTest` (la venta se guarda local y queda en la cola); `SincronizacionModulosTest` 1 y 6. |
| 2 | Recuperación de conexión | `SincronizacionTest.sinConexionTodoRegresaALaColaSinContarComoIntento` y los reintentos de `SincronizacionModulosTest` 3 y 4. |
| 3 | Sincronización de venta pendiente | `SincronizacionTest` (subida y bajada); `SincronizacionModulosTest` 1. |
| 4 | Reintento de la misma operación | `SincronizacionModulosTest` 3 y 4 (nube); idempotencia local en `ComprasTest`, `AjustesYTransformacionesTest` y `DevolucionesTest`. |
| 5 | Surtido pendiente de recepción | `SincronizacionModulosTest` 5. |
| 6 | Devolución sin conexión | `SincronizacionModulosTest` 6 y 3. |
| 7 | Dos cajas venden el mismo producto | `SincronizacionModulosTest` 1 y 2. |
| 8 | Cambio de precio con una caja desconectada | `SincronizacionModulosTest` 7 (cobra lo que conoce; el ticket ya cobrado no cambia). |
| 9 | Conflictos de existencias | `SincronizacionModulosTest` 1 y 2 (detección y reacomodo; si el total es negativo se cuenta y se ajusta). |
| 10 | Fallo a mitad de una sincronización | `SincronizacionTest.siLaNubeRechazaUnCambioLosDemasSiSuben`, `unCambioHechoMientrasSeSubeNoSePierde`; `SincronizacionModulosTest` 3. |

### Otras

| Clase | Prueba | Qué comprueba |
|---|---|---|
| `service/IndicadoresServiceTest` | `lasDevolucionesRestanVentaYSoloLoVendibleRegresaElCosto` | Venta neta menos reembolsos; solo lo vendible regresa al costo de lo vendido; la venta por día cuadra; resumen de devoluciones y ajustes; alcance del supervisor. |
| `service/CajaYVentasTest` | `cancelarExigeMotivoYQueAutoriceUnSupervisorOAdministrador` (nueva) y `movimientosYCorteDeCaja` (ajustada: el retiro del cajero ahora exige autorizador en el servicio). | |
| `db/MigracionesTest` | `unaBaseConDatosConservaMovimientosYCreditoAlMigrar` | Una base con datos en `V14` migra a la última versión conservando movimientos, existencias y crédito; los tipos nuevos se aceptan y se encolan; los surtidos anteriores cuentan como recibidos. |

## Validación visual

Además de las pruebas automáticas se revisó la interfaz con un arnés propio (fuera del repositorio) que abre la aplicación con una **copia aislada** de una base de prueba generada por `DatosPrueba` (no la base real), ejecuta pasos y toma capturas a 1366 × 689, en tema claro y oscuro. Ese arnés también comprobó que los 100+ íconos usados existen en el paquete de Ikonli.

Flujos recorridos desde la interfaz, de punta a punta:

- Alta de proveedor y **compra de dos líneas con bonificación** (folio y detalle).
- **Ajuste de faltante** con su costo.
- Surtido a crédito → **el supervisor recibe con faltante** → aparece en Avisos y en «Diferencias» → **el administrador aclara con nota de crédito** → el crédito baja y muestra la nota.
- **Devolución en la caja** desde «Ventas del turno»: 200 g de queso, reembolso en efectivo y comprobante.
- Consulta de devoluciones (administrador y supervisor), notas pendientes y antigüedad, canal de precios de una sucursal, ajustes del supervisor, precios y lista de precios en tema oscuro.

## Lo que no se pudo verificar

| Qué | Por qué |
|---|---|
| Migración de Supabase, RLS, triggers y `subir_cambios` con las tablas nuevas | No se tocó la nube real. Las pruebas usan una nube simulada. Debe probarse en un proyecto de Supabase de prueba antes del real. |
| Calidad de impresión | Depende de la impresora, el papel y el controlador. La opción de negritas no se probó en papel. |
| Varias cajas físicas en red real | Se simularon dos cajas con bases separadas y la nube en memoria. |
