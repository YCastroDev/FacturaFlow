# 0015. Cierre de la Saga de facturación: eventos de resultado de invoicing y estado de la venta en sales

## Estado

Aceptado — 2026-10-26. **Fase 3** (última) de la mensajería del [ADR 0013](0013-mensajeria-y-outbox.md):
- invoicing publica el resultado de cada factura;
- sales actualiza la venta.

Con esto se cierra la Saga por coreografía que empezó con `SaleConfirmed` (Fase 1) y su consumo en invoicing ([ADR 0014](0014-consumo-de-sale-confirmed.md), Fase 2). Implementa las transiciones `INVOICED` e `INVOICE_FAILED` previstas en el [ADR 0012](0012-servicio-sales.md) (decisión 8) y resuelve, como `INVOICE_FAILED`, el riesgo del vencimiento aceptado en el ADR 0013 (decisión 10).

## Contexto

Tras la Fase 2, una venta confirmada se facturaba sola, pero sales no se enteraba: la venta quedaba `CONFIRMED` para siempre. Faltaba el camino de vuelta, con las mismas garantías del de ida:
- **sin perder resultados:** outbox, publisher confirms y ack manual;
- **idempotente:** los duplicados no cambian nada;
- **tolerante al desorden.**

Además había que decidir qué pasa con una venta cuyo `SaleConfirmed` termina en la DLQ de invoicing. Restricciones:
- se reutilizan los patrones de las fases 1 y 2;
- no hay código compartido entre servicios (ADR 0001);
- la hexagonal se mantiene;
- se toca lo mínimo del núcleo de invoicing.

## Decisión

### 1. Flujo completo (Saga por coreografía)

```mermaid
sequenceDiagram
    autonumber
    actor V as Vendedor
    participant S as sales
    participant SO as outbox de sales
    participant MQ as RabbitMQ
    participant I as invoicing
    participant IO as outbox de invoicing
    participant P as Proveedor (Factus / falso)

    V->>S: POST /api/v1/sales
    S->>SO: venta CONFIRMED + SaleConfirmed (misma transacción)
    S-->>V: 201 (status CONFIRMED)
    SO->>MQ: relay: SaleConfirmed → sales.events (publisher confirm)
    MQ->>I: invoicing.sale-confirmed (ack manual)
    I->>I: CreateInvoice (idempotente por referenceCode)
    alt datos inválidos (dominio, vencimiento, contrato, código ya usado)
        I->>IO: InvoiceRequestRejected
        I->>MQ: mensaje → invoicing.sale-confirmed.dlq
    else factura creada
        I->>P: IssueInvoice (fuera de transacción)
        alt emitida
            I->>IO: factura ISSUED + InvoiceIssued (misma transacción)
        else rechazada por la DIAN
            I->>IO: factura REJECTED + InvoiceRejected (misma transacción)
        else falla técnica
            I->>MQ: espera 10 s → 1 min → 5 min → DLQ (SIN evento; la venta sigue CONFIRMED)
            Note over I,MQ: reprocesar: ./scripts/rabbitmq-dlq.sh invoicing requeue
        end
    end
    IO->>MQ: relay: evento → invoicing.events (publisher confirm)
    MQ->>S: sales.invoice-results (ack manual)
    S->>S: RecordInvoiceResult: INVOICED (número, CUFE) o INVOICE_FAILED (motivo)
    V->>S: GET /api/v1/sales/{id}
    S-->>V: status + invoice | invoiceFailure
```

**Ningún orquestador.** Cada servicio reacciona a los hechos del otro (ADR 0013, decisión 2). El estado del proceso queda visible en la venta: `CONFIRMED` = pendiente de facturar.

### 2. Eventos de resultado de invoicing

| Evento v1 | Routing key | Cuándo | Payload |
|---|---|---|---|
| `InvoiceIssued` | `invoice.issued.v1` | la factura quedó `ISSUED` | `invoiceId`, `referenceCode`, `customer {documentType, documentNumber}`, `number`, `cufe`, `officialTotal`, `issueDate`, `validatedAt` |
| `InvoiceRejected` | `invoice.rejected.v1` | la factura quedó `REJECTED` (la DIAN no la validó) | `invoiceId`, `referenceCode`, `customer {…}`, `reason`, `issueDate` |
| `InvoiceRequestRejected` | `invoice-request.rejected.v1` | un `SaleConfirmed` no se pudo convertir en factura (decisión 4) | `referenceCode`, `saleId`, `sourceEventId`, `reason` |

- **Sobre:** el mismo que `SaleConfirmed` (`eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer: "invoicing"`, `payload`).
- **Contratos:** `docs/events/invoice-*.v1.schema.json`, validados en los tests de **los dos** servicios.
- **Enlaces:** nunca incluyen `publicUrl` ni `qrUrl`, porque son enlaces-capacidad (ADR 0008).
- **Notas crédito:** no publican eventos en esta fase; se agregarían como `CreditNoteIssued` y `CreditNoteRejected` con el mismo patrón.

**Productor: outbox propio en invoicing**, copia deliberada del de sales (ADR 0013):
- tabla `outbox_events` (V7) con `FOR UPDATE SKIP LOCKED`;
- relay `OutboxRelayScheduler`, que llama a `PublishPendingEventsUseCase`;
- `RabbitEventPublisher` con confirms `correlated` y mensajes persistentes;
- exchange `invoicing.events`;
- métricas `invoicing.outbox.pending` e `invoicing.outbox.oldest.pending.age`.

### 3. Eventos en la misma transacción que el cambio de estado (qué se tocó del núcleo de invoicing)

- **Dominio:**
  - `Invoice.markAsIssued` y `markAsRejected` registran `InvoiceEvent.InvoiceIssued` / `InvoiceRejected` (interfaz sellada) en `pendingEvents()`;
  - `create` y `reconstitute` no traen eventos.
  - Es el mismo patrón que `Sale` en la Fase 1, pero `Invoice` es mutable, así que la lista se va llenando y `pendingEvents()` devuelve una copia.
- **Puerto:** `InvoiceRepository.save` amplía su contrato y guarda el cambio de estado **y** sus eventos en la misma transacción. Devuelve una instancia sin eventos ("usar siempre la devuelta", ADR 0005). Un conflicto de versión no deja ni el cambio ni el evento (probado).
- **`IssueInvoiceService` no cambió:** ya guardaba con `save`.
- **El instante del hecho:** el dominio no lee el reloj (ADR 0004) y `markAsRejected` no recibe una fecha. El `occurredAt` lo pone el adaptador de persistencia con su `Clock` al guardar, en la misma transacción. `InvoiceIssued` lleva además `validatedAt`, la hora oficial del proveedor.
  - **Descartado:** agregar un `Instant` a las transiciones. Cambiaba firmas, `IssueInvoiceService` y sus tests sin ganar exactitud (la diferencia es de milisegundos).
- **Caso de uso nuevo, aditivo:** `RejectInvoiceRequestUseCase` registra `InvoiceRequestRejected` mediante el puerto `InvoiceEventStore`, en su propia transacción, porque no hay factura que guardar.

### 4. Qué pasa con la venta cuando su `SaleConfirmed` termina en la DLQ de invoicing (D2)

| Causa | ¿Evento? | Venta | Por qué |
|---|---|---|---|
| **Mensaje inválido**: `DomainException` (incluido el **vencimiento** no posterior a la fecha de emisión), `ReferenceCodeConflict`, fuera del contrato | **`InvoiceRequestRejected`** con el motivo | **`INVOICE_FAILED`** | Es definitivo: con esos datos nunca habrá factura. Así se cumple lo acordado para el riesgo del vencimiento. |
| Mensaje inválido **sin `referenceCode` legible** | ninguno (no hay a quién avisar) | sigue `CONFIRMED` | Queda en la DLQ con su motivo; las alertas de la DLQ lo muestran. |
| **Falla técnica** agotada (proveedor caído, circuito abierto, base caída) | **ninguno** | sigue **`CONFIRMED`** ("pendiente de facturar") | No es un resultado: se reprocesa con `rabbitmq-dlq.sh invoicing requeue` o se emite por `POST /invoices/{id}/issue`. Así **nunca se marca como fallida una venta que luego se factura bien**. |

- **El rechazo se registra ANTES de mandar el mensaje a la DLQ** y forma parte del procesamiento. Si registrarlo falla (p. ej. la base), el mensaje se trata como falla técnica (espera y reintento) y no se pierde.
- **El `reason` es el mensaje del dominio** (en español, de negocio). Sales lo guarda hasta 1000 caracteres.

### 5. Todas las facturas publican eventos; sales distingue las suyas (D1)

Invoicing publica los resultados de **todas** sus facturas, también las de la API REST: es coreografía pura, porque publica hechos suyos sin saber quién escucha. Sales reconoce las suyas en dos pasos:
1. **Correlación por `referenceCode`:** el de la factura es el de la venta (ADR 0014). Si no hay una venta con ese código (o el código no es válido para una venta), hace ack, lo ignora y lo cuenta en `outcome=ignored`.
2. **Comprobación de identidad:**
   - en `InvoiceIssued` e `InvoiceRejected`, el **documento del cliente** (tipo + número) debe ser el de la venta. Es el mismo criterio de identidad del ADR 0008 ("otra identificación del cliente es otra factura");
   - en `InvoiceRequestRejected`, el `saleId`, si viene, debe ser el de la venta.
   - Si no coincide, hace ack, lo ignora y registra un INFO con el motivo.

Así, una factura REST que reutiliza el código de una venta con otro cliente nunca la marca. Si además fuera del mismo cliente, sería en la práctica la factura de esa venta hecha a mano, y marcarla `INVOICED` es lo correcto.

**Descartado:** publicar solo las facturas de una venta. Invoicing tendría que guardar el origen (`saleId`), lo que exige cambiar `CreateInvoiceCommand` y su caso de uso, más una migración. Además, el productor quedaría acoplado a su consumidor.

### 6. Transiciones de la venta, duplicados y orden inesperado (D3)

`SaleStatus.canTransitionTo`:
- `CONFIRMED` → `INVOICED` | `INVOICE_FAILED`;
- `INVOICE_FAILED` → `INVOICED`;
- `INVOICED` es final.

`RecordInvoiceResultService` aplica esta tabla:

| Venta \ evento | `InvoiceIssued` | `InvoiceRejected` / `InvoiceRequestRejected` |
|---|---|---|
| `CONFIRMED` | → `INVOICED` (número, CUFE, id, instante) | → `INVOICE_FAILED` (motivo, factura rechazada si existe, instante) |
| `INVOICED` | misma factura: **duplicado** (no-op, ni siquiera guarda); otra: **ignorar** | **ignorar**: un fallo tardío no deshace una factura emitida |
| `INVOICE_FAILED` | → **`INVOICED`**: el éxito gana, porque una factura emitida es un hecho legal | **duplicado**: se conserva el primer motivo |

- **"El éxito gana y el fallo no deshace":** con eventos repetidos o desordenados, el estado final no depende del orden de llegada.
- **El caso típico de desorden:** un `InvoiceRequestRejected` seguido de una factura emitida a mano para esa venta.
- **Dominio:** `Sale` sigue siendo inmutable. `invoiced(InvoiceReference, Instant)` e `invoiceFailed(reason, rejectedInvoiceId, Instant)` devuelven una venta nueva con su `InvoicingOutcome` sellado (`Invoiced` | `Failed`). `reconstitute` exige que el estado y el resultado sean coherentes, y la base tiene un CHECK de defensa en profundidad (V3).
- **Persistencia:** `SaleRepository.save` aplica bloqueo optimista con la versión leída. Un conflicto es `SaleConcurrentModificationException`, una falla técnica que se reintenta en la cola de espera.
- **API:** `GET /api/v1/sales/{id}` incluye `status`, `invoice {invoiceId, number, cufe, invoicedAt}` e `invoiceFailure {reason, rejectedInvoiceId, failedAt}`; los dos últimos son nulos si no aplican. El filtro `status` del listado acepta los estados nuevos.

### 7. Consumidor en sales (mismo mecanismo que el ADR 0014)

- `adapter/in/messaging` es un ACL package-private:
  - `InvoiceResultMessage` es un DTO tolerante: el `eventType` dice qué campos aplican;
  - `InvoiceResultTranslator` produce un `RecordInvoiceResultCommand` sellado;
  - `InvoiceResultProcessor` clasifica sin AMQP;
  - `InvoiceResultListener` usa ack manual.
- **Clasificación:**

  | Situación | Acción |
  |---|---|
  | Aplicado, duplicado o ignorado | ack |
  | Conflicto de versión o falla inesperada | espera 10 s / 1 min / 5 min y luego DLQ |
  | JSON ilegible, fuera del contrato o resultado incompleto (`DomainException`) | DLQ directa |

- **Topología, con el prefijo de sales:**
  - `sales.invoice-results`, enlazada a `invoicing.events` con las tres routing keys;
  - `sales.retry` → `sales.invoice-results.wait-*`;
  - `sales.requeue`;
  - `sales.dlx` → `sales.invoice-results.dlq`.
- **1 consumidor, prefetch 1:** solo actualiza ventas, es rápido y nunca procesa a la vez dos resultados de la misma venta.
- **Métrica** `sales.invoice_results.processed{outcome}`.
- **Reprocesar:** `./scripts/rabbitmq-dlq.sh sales requeue`. El script ahora recibe el servicio (D10).

### 8. Permisos de RabbitMQ (D7)

| Usuario | configure | write | read |
|---|---|---|---|
| `sales` | `^sales\..*` (antes, solo `sales.events`) | `^sales\..*` | `^(sales\..*\|invoicing\.events)$` (antes, nada) |
| `invoicing` | `^invoicing\..*` | `^invoicing\..*` | `^(invoicing\..*\|sales\.events)$` (sin cambios) |

- Cada servicio declara y usa solo sus recursos, y lee el exchange del otro únicamente para enlazar su cola. Ninguno puede publicar en el exchange del otro ni en `amq.default`.
- `rabbitmq-init` predeclara `sales.events` e `invoicing.events`.
- **Los tests de los dos servicios ejecutan el script de init real en Testcontainers** y se conectan con sus usuarios reales. Las colas de prueba de sales se renombraron con el prefijo `sales.` porque su usuario no podría declarar otras. Un permiso que falte hace fallar los tests.

### 9. Health (D8) y reglas de ArchUnit (D6)

- **Un componente `messaging` por servicio** en `/actuator/health/dependencies`, siempre UP, con dos partes en los detalles:
  - `outbox`: relay, pendientes y antigüedad;
  - `consumer`: cola, listener y último resultado.
- **Cómo se arma:**
  - `MessagingHealthIndicator` vive en `infrastructure/config` y reúne las `MessagingHealthPart`, una interfaz pública que aporta cada adaptador;
  - los adaptadores siguen package-private;
  - las rutas del health de sales cambiaron de `details.relay` a `details.outbox.relay`.
- **ArchUnit en los dos servicios:**
  - **regla 11:** AMQP solo en `adapter/in/messaging` y `adapter/out/messaging`, y nada de `adapter/out/messaging` sale de él;
  - **regla 12** en invoicing: el ACL de la publicación;
  - **regla 12** en sales: el ACL del consumidor.

### 10. Tests

| Servicio | Test | Qué demuestra |
|---|---|---|
| invoicing | `InvoiceTest` (eventos) | Las transiciones registran su evento; una transición inválida no; la lista es una copia |
| invoicing | `JpaInvoiceRepositoryAdapterTest` | El cambio de estado y el evento se guardan juntos; un conflicto de versión no deja el evento; crear (`PENDING`) no publica |
| invoicing | `JpaOutboxAdapterTest` | SKIP LOCKED, solo lo confirmado, `append` de una petición rechazada |
| invoicing | `InvoiceEventsSchemaTest` | Los tres eventos cumplen sus esquemas y 6 mensajes rotos no; sin enlaces |
| invoicing | `InvoiceEventsPublicationTest` | Publicación real en `invoicing.events` con el usuario real |
| invoicing | `SaleConfirmedProcessorTest`, `SaleConfirmedConsumerIntegrationTest` | Inválido → `InvoiceRequestRejected` y luego DLQ; técnica → ningún evento; emitida → `InvoiceIssued`; rechazada → `InvoiceRejected`; si registrar el rechazo falla, se reintenta |
| sales | `SaleInvoicingTest` | Transiciones, venta nueva e inmutable, coherencia estado ↔ resultado, validaciones |
| sales | `RecordInvoiceResultServiceTest` (sin Spring) | Cada celda de la tabla de la decisión 6; facturas ajenas (REST, otro cliente, otro `saleId`) ignoradas |
| sales | `JpaSaleRepositoryAdapterTest` | INVOICED e INVOICE_FAILED → INVOICED reconstruidos; bloqueo optimista; CHECK de coherencia |
| sales | `InvoiceResultProcessorTest` | Traducción y clasificación |
| sales | `InvoiceResultConsumerIntegrationTest` (RabbitMQ y PostgreSQL reales, usuarios reales) | Los tres eventos, el GET con la factura o el motivo, duplicado (una sola actualización), los dos órdenes inesperados, facturas ajenas sin DLQ, mensaje inválido a la DLQ sin reintentos; cada evento validado contra su esquema |

No hay un test de integración con los dos servicios a la vez. Cada lado valida contra el MISMO esquema, y la demostración de punta a punta (README) recorre el flujo completo con el proveedor falso.

## Cómo se haría en producción

- **Reconciliación periódica:** las ventas `CONFIRMED` más viejas que N minutos se consultan en invoicing por `referenceCode`. Así una venta cuyo mensaje quedó en una DLQ, o cuyo `InvoiceRequestRejected` no pudo emitirse (mensaje ilegible), no se queda pendiente en silencio.
- **Origen explícito de la factura** (`source=sale`, `saleId`) si hubiera muchos productores de peticiones, para correlacionar sin depender de un espacio de `referenceCode` compartido.
- **Eventos de las notas crédito**, y en sales un estado de devolución.
- **Lo demás, como en los ADR 0013 y 0014:** inbox, quorum queues, alertas sobre las DLQ y la antigüedad del outbox, limpieza del outbox.

## Consecuencias

**Positivas**
- **La Saga se cierra sola:** la venta muestra su factura (número y CUFE) o el motivo del fallo, sin que nadie consulte a invoicing.
- **El riesgo del vencimiento ya no se pierde en una DLQ:** la venta queda `INVOICE_FAILED` con el motivo.
- **Resultado final determinista:** no depende de duplicados ni del desorden.
- **Mismo núcleo:** `IssueInvoiceService` y `CreateSaleService` no cambiaron; los eventos nacen del dominio.
- **Los permisos mínimos se prueban en los dos servicios.**

**Negativas**
- **Más duplicación deliberada:** outbox, relay, publicador y consumidor existen dos veces, una por servicio.
- **Ventas que pueden quedar `CONFIRMED`:** las que tienen una falla técnica sin reprocesar o un mensaje ilegible. Lo resolvería la reconciliación de producción.
- **Espacio de `referenceCode` compartido** entre las ventas y las facturas REST. Se mitiga con la comprobación del documento del cliente.
- **Invoicing publica eventos de facturas que nadie consume** (las de la API REST). Su costo es mínimo.
