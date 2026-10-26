# 0014. Invoicing consume `SaleConfirmed`: adaptador de entrada, clasificación, reintentos con espera y DLQ

## Estado

Aceptado — 2026-10-26. **Fase 2** de la mensajería del [ADR 0013](0013-mensajeria-y-outbox.md): invoicing escucha `SaleConfirmed` v1, crea la factura y la emite. Sales no cambia. El camino de vuelta (sales pasa la venta a `INVOICED` o `INVOICE_FAILED`) es la Fase 3.

## Contexto

Desde la Fase 1, sales publica `SaleConfirmed` v1 en `sales.events` con un outbox. El contrato está en `docs/events/sale-confirmed.v1.schema.json`. La entrega es "al menos una vez" y sin orden garantizado.

Invoicing ya sabe crear facturas de forma idempotente (ADR 0006) y emitirlas con resiliencia hacia Factus (ADR 0010). Lo que falta es una entrada por eventos que:
- reutilice ese núcleo **sin modificarlo**;
- distinga los resultados de negocio de las fallas técnicas y de los mensajes inválidos;
- reintente lo que vale la pena, con espera creciente, y aparte lo que no se puede procesar sin perderlo;
- no tumbe invoicing si RabbitMQ cae.

Decisiones ya tomadas: la cola es del consumidor (`invoicing.sale-confirmed`); invoicing tiene su propio usuario de RabbitMQ con permisos mínimos; el ack es manual y solo después de procesar; los totales del evento son informativos (WARN si difieren). Con base en eso, se aprobaron D1–D9 del plan.

## Decisión

### 1. Un adaptador de entrada nuevo, el mismo núcleo

`infrastructure/adapter/in/messaging` es un Anti-Corruption Layer, todo package-private:

| Clase | Rol |
|---|---|
| `SaleConfirmedMessage` | DTO del evento (sobre + payload). Solo lo que invoicing usa. |
| `SaleConfirmedJson` | `JsonMapper` propio y **tolerante** con los campos desconocidos (la API REST sigue rechazándolos). |
| `SaleConfirmedTranslator` | Evento → `CreateInvoiceCommand`, el MISMO comando de `POST /api/v1/invoices`. |
| `SaleConfirmedProcessor` | create → comparar totales → issue; clasifica el resultado. Sin AMQP: se prueba sin broker. |
| `SaleConfirmedListener` | `@RabbitListener` con ack manual: aplica la clasificación. |
| `ConfirmedRepublisher` | Republica en una cola de espera o en la DLQ con publisher confirms. |
| `SaleConfirmedTopology` | Declara colas, exchanges y bindings. |
| `MessagingHealthIndicator`, `ConsumerState` | Health informativo y métricas. |

- **`CreateInvoiceUseCase` e `IssueInvoiceUseCase` no cambian**, y los endpoints REST tampoco: es la prueba de la hexagonal, otro adaptador de entrada sobre el mismo núcleo.
- **ArchUnit:**
  - **regla 10:** nada de `adapter/in/messaging` sale del paquete;
  - **regla 11:** Spring AMQP y `com.rabbitmq` solo se usan dentro de él.
- **Validación en ejecución (D6):** estructural, en el traductor:
  - tipo y versión del evento;
  - los objetos anidados que se recorren;
  - enums conocidos.

  El resto de las reglas son las del dominio. El JSON Schema se valida en los **tests**: los eventos de prueba se comparan contra el mismo `docs/events/` que usa sales. En ejecución no se valida contra el esquema porque el contenedor se construye con `services/invoicing` como contexto y no ve `docs/events/`, y copiarlo dejaría dos versiones del contrato. El consumidor exige solo lo que usa (lector tolerante, `docs/events/README.md`).

### 2. Idempotencia y duplicados (D5)

- **El `referenceCode` de la venta es el de la factura.** Un mensaje repetido llega a `CreateInvoiceUseCase` con la misma huella, que devuelve la factura existente (`created=false`).
- **Solo se emite si la factura sigue `PENDING`.** Una ya `ISSUED` o `REJECTED` no vuelve a llamar al proveedor: es un ack con `outcome=duplicate` y un log INFO con el `eventId`.
- **Un `PENDING` heredado de un intento anterior** (falla técnica tras crear) se emite en el reintento: así la factura llega a emitirse.
- **No hay una tabla *inbox* de `eventId` procesados.** No agrega corrección: la clave de negocio ya garantiza el efecto único, y registrar el `eventId` no puede ser atómico con la emisión (son varias transacciones y una llamada externa). Solo ahorraría dos lecturas. Queda en "Cómo se haría en producción".
- **Un mismo `referenceCode` con otro contenido** (p. ej. una factura creada antes por REST) es `ReferenceCodeConflictException`, y el mensaje es inválido.

### 3. Clasificación del resultado (D7)

| Situación | Clasificación | Acción |
|---|---|---|
| Emitida (`ISSUED`) o rechazada por la DIAN (`REJECTED`) | resultado de negocio | ack |
| Duplicado de una factura ya `ISSUED` o `REJECTED` (incluida `InvoiceAlreadyRejectedException`) | resultado de negocio | ack (`duplicate`) |
| `ElectronicInvoiceProviderUnavailableException` (falla, circuito abierto, limitador) | técnica | espera y reintento; agotados, DLQ |
| `InvoiceConcurrentModificationException` | técnica | espera y reintento |
| Cualquier otra excepción (p. ej. la base caída) | técnica | espera y reintento, más log ERROR con traza. Una base caída no manda mensajes buenos a la DLQ al primer intento; un bug llega a la DLQ tras 4 intentos |
| JSON ilegible, tipo o versión de evento desconocidos, campo necesario ausente, enum desconocido | inválido | DLQ, sin reintentos |
| `DomainException` (los 422 del dominio) o `ReferenceCodeConflictException` | inválido | DLQ, sin reintentos |
| No se pudo republicar en espera o en la DLQ (el broker falló justo ahí) | — | `basicNack(requeue=true)`: el original vuelve a la cola y nada se pierde |

**Ack manual, siempre DESPUÉS de procesar.** En un reintento o una DLQ, la secuencia es:
1. republicar la copia y esperar la confirmación del broker;
2. hacer ack del original.

Si el servicio cae entre los dos pasos, el mensaje queda duplicado (en la cola y en la espera), y la idempotencia lo absorbe. Nunca se pierde.

**Vencimiento (riesgo aceptado en el ADR 0013, decisión 10):** una venta a crédito procesada días después (p. ej. reprocesada desde la DLQ) puede tener un vencimiento ya no posterior a la fecha de emisión. Es una `DomainException`, y el mensaje va a la DLQ. En la Fase 3 la venta pasará a `INVOICE_FAILED`.

### 4. Topología: quién declara qué (D2)

| Recurso | Tipo | Declara | Detalle |
|---|---|---|---|
| `sales.events` | exchange topic | sales y **también `rabbitmq-init`** | Así el binding de invoicing no depende del orden de arranque: un binding a un exchange inexistente falla. Invoicing NO lo declara: no tiene permiso `configure` sobre él. |
| `invoicing.sale-confirmed` | cola durable | invoicing | Binding `sales.events` / `sale.confirmed.v1`. `x-dead-letter-exchange: invoicing.dlx` como red de seguridad ante un `reject` no previsto. |
| `invoicing.retry` | exchange direct | invoicing | Destino de los reintentos; routing key = nombre de la cola de espera. |
| `invoicing.sale-confirmed.wait-10s`, `wait-1m`, `wait-5m` | colas durables, sin consumidores | invoicing | `x-message-ttl`. Al vencer, `x-dead-letter-exchange: invoicing.requeue`. |
| `invoicing.requeue` | exchange direct | invoicing | Devuelve el mensaje a `invoicing.sale-confirmed`, y SOLO a ella (no a otros consumidores de `sales.events`). |
| `invoicing.dlx` / `invoicing.sale-confirmed.dlq` | exchange direct / cola durable | invoicing | La DLQ. |

- **Republicación explícita** con la cabecera `x-attempt`, en vez del dead-lettering nativo: el broker no sabe elegir la espera según el intento ni guardar nuestro motivo.
- **Cabeceras de los mensajes en la DLQ:**
  - `x-failure-category` (`technical` / `invalid`);
  - `x-failure-reason` (motivo corto, sin datos sensibles);
  - `x-attempts-made`;
  - `x-failed-at`.

  Conservan el cuerpo intacto y el `message_id` (= `eventId`), y **no** llevan `x-attempt`.
- **Nombre de la espera con su duración:** cambiar un retraso crea otra cola, en vez de chocar con el `PRECONDITION_FAILED` de RabbitMQ al redeclarar una cola con otro TTL.
- **TTL por cola, no por mensaje:** con el TTL por mensaje, un mensaje solo vence al llegar a la cabeza de la cola. Con una cola por espera, todos sus mensajes tienen el mismo TTL y vencen en orden.

**Hallazgo al implementar:** primero las esperas volvían por el exchange por defecto (`""`). Pero declarar una cola con un dead-letter exchange **exige permiso de escritura sobre él**, y escribir en `amq.default` permitiría a invoicing publicar en CUALQUIER cola del vhost. Con su usuario real, la declaración fallaba (`ACCESS_REFUSED`); los tests no lo veían porque se conectaban como administrador. Se corrigió de dos formas:
- el exchange propio `invoicing.requeue`, cubierto por `^invoicing\.`;
- los tests de invoicing ahora ejecutan el **mismo script de init** en Testcontainers y se conectan con los usuarios reales (decisión 11).

### 5. Reintentos: 10 s → 1 min → 5 min

`invoicing.messaging.retry-delays: 10s,1m,5m`:
- **10 s** cubre un reinicio;
- **1 min** cubre el circuito abierto (30 s, ADR 0010);
- **5 min** cubre una caída real.

En total son 4 intentos en unos 6 min 10 s; agotados, el mensaje va a la DLQ. Las esperas crecen para no insistir sobre un proveedor caído. Además, cada intento ya incluye los reintentos cortos de `FactusResilience` (ADR 0010).

### 6. Reprocesar la DLQ (D4)

- **Herramienta:** el plugin **shovel** de RabbitMQ (`rabbitmq_shovel` y `rabbitmq_shovel_management`, que ya vienen en la imagen). Se habilita montando `infra/rabbitmq/enabled_plugins`.
- **En la consola:** botón **"Move messages"** en la página de la DLQ.
- **En scripts:** `./scripts/rabbitmq-dlq.sh requeue` declara un shovel dinámico DLQ → `invoicing.sale-confirmed`:
  - `ack-mode: on-confirm`: ningún mensaje se pierde;
  - `src-delete-after: queue-length`: mueve los mensajes que hay en ese momento y se borra solo.
- El script también tiene `status`, `peek` (sin sacar los mensajes) y `purge --yes`.
- **Los mensajes vuelven sin `x-attempt`:** el consumidor les da todos sus intentos otra vez.
- **Cuándo reprocesar:** solo tras corregir la causa (el proveedor de vuelta, los datos corregidos). Si no, el mensaje vuelve a la DLQ: un técnico, tras 4 intentos; un inválido, enseguida.
- **Otra vía para las técnicas:** la factura quedó `PENDING`, así que también se puede emitir por `POST /api/v1/invoices/{id}/issue`. Un reproceso posterior la encontrará `ISSUED` y será un duplicado.

**Alternativa descartada:** un endpoint de administración en invoicing. Más código, más superficie de seguridad y un endpoint sin autenticación.

### 7. Concurrencia (D3)

**1 consumidor con prefetch 1** (`invoicing.messaging.concurrency`, `spring.rabbitmq.listener.simple.prefetch`):
- **Rendimiento:** el limitador de Factus permite 100 por minuto, y cada emisión tarda unos 2–3 s. Secuencial no llega a 30 por minuto, de sobra para una pyme.
- **Duplicados:** dos copias del mismo evento nunca se procesan a la vez. Eso evita que dos emisiones simultáneas lleguen a Factus antes de que el bloqueo optimista (ADR 0005) lo detecte.
- **Prefetch 1:** un mensaje lento no retiene otros en memoria del consumidor.
- **Si el limitador se agota** de todos modos (por emisiones REST simultáneas), es una falla técnica y entra en el ciclo de esperas.

Para escalar: más consumidores o más instancias funcionan sin cambios de corrección (idempotencia + bloqueo optimista). Solo habría que vigilar el limitador compartido.

### 8. Totales informativos

Solo cuando la factura se **crea** (`created=true`), `SaleTotalsCheck` compara `subtotal`, `totalDiscount`, `totalTax` y `total` del evento con los de la factura. Si difieren, registra un **WARN** con el `referenceCode`, el id de la factura y los dos juegos de valores.
- La factura usa siempre los de invoicing, que es la autoridad (ADR 0004).
- No se compara en los duplicados, para no repetir el WARN.
- Un evento sin `totals` no se compara: el dato es informativo.

### 9. Health y métricas (D9)

- `management.health.rabbit.enabled: false`: el indicador de Spring Boot bajaría el health con el broker caído.
- **Componente informativo `messaging`**, siempre UP, en el grupo nuevo `/actuator/health/dependencies`:
  - detalles: cola, si el listener corre (`RUNNING`/`STOPPED`), último resultado y última falla;
  - no entra en liveness ni readiness;
  - el grupo `providers` (Factus) no cambia.
- **Sin RabbitMQ:** invoicing arranca, su API REST atiende y el listener se reconecta solo (`missing-queues-fatal: false`). En compose, invoicing depende de rabbitmq solo con `service_started`.
- **Métrica** `invoicing.sale_confirmed.processed{outcome}`, con `issued`, `rejected`, `duplicate`, `retried`, `dead-lettered` y `requeued`.

### 10. Proveedor falso: `SIMULAR_FALLA_TEMPORAL` (D8)

`SIMULAR_CAIDA` falla siempre: sirve para ver cómo un mensaje llega a la DLQ y cómo un reproceso sin corregir la causa vuelve a ella.

`SIMULAR_FALLA_TEMPORAL` (en la observación) hace fallar las primeras N emisiones de **ese** documento y luego emite, como un proveedor que vuelve.
- `invoicing.fake.temporary-failures` (`INVOICING_FAKE_TEMPORARY_FAILURES`): **4 en compose**, los intentos del consumidor. La venta llega a la DLQ y el reproceso la emite.
- En el test de "temporal → reintento → emitida" vale 1.
- El contador vive en memoria (es un falso) y se reinicia con el servicio.
- El nombre no contiene `SIMULAR_CAIDA`, para que las dos palabras no se confundan.
- Funciona también por REST y en las notas crédito.

### 11. Seguridad

**Usuario `invoicing`** en el vhost `facturaflow` (`INVOICING_RABBITMQ_PASSWORD`), creado por `infra/rabbitmq/init/create-service-users.sh`:

| configure | write | read |
|---|---|---|
| `^invoicing\..*` | `^invoicing\..*` | `^(invoicing\..*\|sales\.events)$` |

- Puede declarar y usar sus recursos, y leer `sales.events` solo para enlazar su cola: un binding exige `read` sobre el exchange de origen.
- No puede publicar en `sales.events` ni en `amq.default`.

**Los tests usan los permisos reales:** `TestcontainersConfiguration` ejecuta el mismo script dentro del RabbitMQ de Testcontainers. La aplicación se conecta como `invoicing` y los tests publican como `sales`. Un permiso que falte en la topología hace fallar los tests, no la demo.

### 12. Tests

| Test | Qué demuestra |
|---|---|
| `SaleConfirmedProcessorTest` (sin Spring ni broker) | Cada fila de la tabla de clasificación; los duplicados no vuelven a emitir; un `PENDING` heredado se emite; WARN de totales solo al crear. |
| `SaleConfirmedTranslatorTest` | Evento → el mismo comando de la API; crédito con vencimiento; campos desconocidos ignorados; 8 mensajes fuera del contrato son inválidos. |
| `SaleConfirmedContractTest` | Los eventos de prueba cumplen `docs/events/sale-confirmed.v1.schema.json`; el inválido de la DLQ lo incumple. |
| `SaleConfirmedConsumerIntegrationTest` (RabbitMQ y PostgreSQL reales, usuarios reales, esperas de 200/400/600 ms) | venta → `ISSUED`; duplicado → 1 factura, 1 emisión (spy); temporal → reintento → `ISSUED`; permanente → DLQ tras 4 intentos con sus cabeceras y la factura `PENDING`; inválido → DLQ sin reintentos; rechazo → `REJECTED` sin reintentos; totales distintos → WARN y la factura con los totales de invoicing. |
| `BrokerDownTest` | Sin broker: health, liveness y readiness UP; `/actuator/health/rabbit` 404; `messaging` UP; la API responde. |
| `FakeElectronicInvoiceProviderTest` | `SIMULAR_FALLA_TEMPORAL`: falla N veces por documento y luego emite. |
| `HexagonalArchitectureTest`, reglas 10 y 11 | El ACL del evento y AMQP no salen de `adapter/in/messaging`. |

## Cómo se haría en producción

- **Tabla *inbox*** (`processed_events`) para auditoría y para descartar duplicados sin tocar las facturas, con mucho volumen.
- **Quorum queues** en un clúster de 3 nodos para la cola, las esperas y la DLQ.
- **Esperas según `Retry-After`:** con el circuito abierto o un 429, esperar lo que indica la excepción en vez de la escalera fija.
- **Alertas** sobre la profundidad y la antigüedad de la DLQ, y sobre `outcome=dead-lettered`. Una consola de soporte para inspeccionar, corregir y reprocesar mensajes uno por uno.
- **Más consumidores** con el limitador de Factus compartido (p. ej. en Redis) si el volumen lo pide.
- **Credenciales** de RabbitMQ desde un gestor de secretos, con TLS.

## Consecuencias

**Positivas**
- **Una venta confirmada se factura sola**, sin que el vendedor espere a la DIAN y sin tocar la API de invoicing.
- **Ningún mensaje se pierde:** ack solo después de procesar o de republicar con confirmación; los inválidos quedan apartados con su motivo.
- **Los reintentos no saturan a Factus:** espera creciente, un consumidor y el circuito del ADR 0010.
- **El núcleo no cambió:** el consumidor reutiliza los casos de uso, la idempotencia y la resiliencia existentes.
- **Los permisos mínimos se prueban**, no solo se documentan.

**Negativas**
- **Una factura técnica puede tardar unos 6 min** en llegar a la DLQ, y hay que reprocesarla a mano.
- **Más piezas en el broker:** 5 colas y 3 exchanges por consumidor, más un plugin.
- **Los duplicados cuestan dos lecturas:** la idempotencia es por clave de negocio, sin inbox.
- **El vencimiento de las ventas a crédito** reprocesadas tarde termina en la DLQ hasta la Fase 3.
