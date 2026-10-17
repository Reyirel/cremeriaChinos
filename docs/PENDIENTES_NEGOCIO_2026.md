# Pendientes de negocio 2026

Decisiones que el sistema **no toma solo**. Todo lo de abajo ya se puede configurar o ya tiene un comportamiento seguro por omisión; falta que el negocio confirme el valor o la regla. Nada de esto se inventó en el código: donde hacía falta un valor se dejó configurable y vacío (0 %, sin escala, por definir).

Contexto técnico: [CAMBIOS_IMPLEMENTADOS_2026.md](CAMBIOS_IMPLEMENTADOS_2026.md).

## 1. Los porcentajes de la reunión: 23 %, 12 %, 10 % y 2 %

Se mencionaron, pero no quedó claro **a qué** corresponde cada uno ni **cómo** se calcula. El sistema no los tiene fijos: se capturan en *Precios y márgenes* como reglas con canal, tipo, alcance y vigencia.

Para cada porcentaje hay que decidir:

| Pregunta | Opciones en el sistema |
|---|---|
| ¿Para qué canal es? | Público en sucursal · Bodega a cremerías · Ruta de la Sierra |
| ¿Cómo se calcula? | **Sobre el costo** (precio = costo × 1.23) o **margen sobre el precio** (precio = costo ÷ 0.77). No dan lo mismo: 23 % sobre costo deja 18.7 % de margen. |
| ¿A qué productos aplica? | Todos · una categoría · un producto · una presentación; en todas las sucursales o en una. |
| ¿Desde cuándo? ¿Hasta cuándo? | Vigencia por fechas. |

Ejemplo de cómo **podría** quedar (no es una recomendación; está para que se vea la forma de responder):

| Porcentaje | Canal | Tipo | Alcance |
|---|---|---|---|
| 23 % | ¿Público? | ¿Sobre costo? | ¿General? |
| 12 % | ¿Bodega a cremerías? | ¿Sobre costo? | ¿General? |
| 10 % | ¿Ruta de la Sierra? | ¿Sobre costo? | ¿General? |
| 2 % | ¿Una categoría? ¿Un ajuste de redondeo? ¿Un descuento? | — | — |

## 2. Precio interno de surtido y utilidad de cada sucursal

- **Hoy**: con una regla de bodega, el crédito de la sucursal se carga al **precio interno** (costo + margen de bodega). Sin regla, al costo, como antes.
- El **lote que entra a la sucursal conserva el costo de la empresa**. Así la utilidad consolidada no cuenta dos veces el margen de bodega.
- **Por decidir**: ¿la utilidad de cada cremería se debe medir contra lo que le cobra la bodega (precio interno) en vez de contra el costo de la empresa? Si sí, hace falta un indicador adicional «utilidad de la sucursal a precio interno», separado del consolidado.
- **Por decidir**: ¿qué sucursales son de la **Ruta de la Sierra**? (se marca en *Sucursales → Canal de precios*) y si su precio interno es distinto al de las cremerías.

## 3. Mayoreo y súper mayoreo

La referencia de la reunión (menudeo 1–5, mayoreo 6–11, súper mayoreo 12+) **no está fija**: se configura por producto (y opcionalmente por presentación y sucursal).

- ¿A qué productos aplica? ¿Con descuento % o con precio fijo por pieza?
- ¿El mayoreo cuenta **por producto** (todas sus presentaciones juntas, como hace hoy el sistema) o por presentación?
- ¿El mayoreo aplica igual en todas las sucursales?
- ¿Puede combinarse con otra promoción? Hoy no existen otras promociones; si se agregan, hay que decidir cuál gana.

## 4. Devoluciones y reembolsos

Comportamiento actual, que conviene confirmar:

- Se devuelve sobre **cualquier ticket de la sucursal**, sin límite de días.
- El reembolso es **proporcional a lo cobrado** (con el descuento de mayoreo si lo hubo).
- Lo **dañado o caducado** que trae el cliente no regresa al inventario y queda como pérdida al costo.
- Reembolso en **efectivo** (solo si la caja tiene ese efectivo), **tarjeta**, **transferencia** o **sin reembolso**. Requiere autorización de supervisor si lo hace un cajero.

Por decidir:

1. ¿Plazo máximo para devolver (por ejemplo, 7 días)? ¿Distinto para perecederos?
2. ¿Se aceptan devoluciones **sin ticket**? Hoy no.
3. Una venta pagada con tarjeta, ¿se puede reembolsar en efectivo?
4. En un **cambio**, si lo nuevo cuesta más o menos, ¿cómo se cobra la diferencia? Hoy: se registra la devolución, se cobra lo nuevo como venta normal y quedan ligadas.
5. ¿Quién absorbe la pérdida de lo dañado: la sucursal o la bodega?

## 5. Bonificaciones de proveedores

- Política por omisión: **repartir el costo** de lo pagado entre todo lo recibido (el costo por pieza baja).
- Alternativa: **costo cero** para lo regalado, en un lote aparte.
- **Por decidir**: ¿cuál usa contabilidad? ¿Lo regalado se puede vender al mismo precio que lo pagado? ¿Se reporta aparte?

## 6. Devoluciones a proveedor

- Hoy la mercancía sale del almacén al costo de su lote y la **compensación queda «por definir»** hasta que se acuerde: nota de crédito del proveedor, reembolso, reposición o sin compensación.
- **Por decidir**: ¿se lleva una cuenta por cobrar a proveedores? Hoy el sistema solo registra el acuerdo, no un saldo con el proveedor.

## 7. Diferencias de entrega en surtidos

- Si la sucursal recibe menos, lo que falta sale de su existencia y el administrador decide: **regresó al almacén** o **se perdió en el traslado**, y si se le **acredita** a la sucursal (solo surtidos a crédito).
- **Por decidir**: ¿quién absorbe una pérdida en el traslado (bodega, sucursal, chofer)? ¿Hay tolerancia de peso (por ejemplo, ±20 g en quesos) que no se deba reportar como diferencia?
- ¿Qué pasa si **llega de más**? Hoy no se puede recibir más de lo enviado: se pide registrar un ajuste de sobrante.

## 8. Cancelación de surtidos ya pagados

- Solo se cancela un surtido que la sucursal no ha recibido ni tocado. Si fue a crédito, el cargo se cancela con una nota de crédito **que nunca deja el saldo en negativo**.
- **Por decidir**: si la sucursal ya había abonado ese surtido, ¿queda un **saldo a favor**? Hoy el sistema no maneja anticipos ni saldo a favor.

## 9. Derivados y transformaciones

- ¿Qué productos se preparan al venderse (rallado, rebanado) y con qué **% de merma** cada uno? Se captura por presentación en el producto.
- En una transformación, ¿la merma del proceso debe quedar en el costo del producto nuevo (como hoy) o registrarse como gasto aparte?

## 10. Permisos y límites

| Operación | Quién puede hoy | Por decidir |
|---|---|---|
| Ajustes de inventario | Administrador (cualquier ubicación); supervisor (su sucursal); el cajero no. | ¿Tope de monto para el supervisor? ¿Segunda firma arriba de cierto valor? |
| Transformaciones | Igual que ajustes. | — |
| Devoluciones de clientes | Cajero con autorización de supervisor; supervisor y administrador directo. | ¿Tope de reembolso sin administrador? |
| Retiros de efectivo | Cajero con autorización; supervisor y administrador directo. | — |
| Reglas de precio, mayoreo, compras, abonos, canal de sucursal | Solo administrador. | ¿Un segundo administrador debe aprobar cambios de precio? |
| Recepción de surtidos | Supervisor de esa sucursal o administrador. | ¿El cajero puede recibir? |

## 11. Unidades

- No existen **litros ni mililitros**; los líquidos se venden por pieza («Leche 1 L»). ¿Se necesita vender líquidos a granel?

## 12. Puesta en producción

Nada se ha aplicado a la base real ni a Supabase y no hay commits de esta rama. Se necesita autorización para:

1. Revisar y hacer commit de la rama `funcional-2026`.
2. Probar la migración de Supabase en un proyecto de prueba.
3. Elegir una ventana (fuera de horario), hacer respaldos y aplicar primero Supabase y después la versión nueva en cada caja (pasos en el documento de cambios, sección 6).
