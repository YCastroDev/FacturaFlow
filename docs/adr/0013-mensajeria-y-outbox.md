# 0013. Mensajería entre servicios: RabbitMQ, coreografía y transactional outbox

## Estado

Aceptado — 2026-10-26. Resuelve el punto "Publicación" del ADR 0012 (decisión 8). Cubre la **Fase 1** de tres:

| Fase | Alcance |
|---|---|
| **1 (este ADR)** | RabbitMQ en el entorno; sales guarda `SaleConfirmed` en un outbox y lo publica de forma confiable. Invoicing no cambia. |
| 2 | Invoicing consume `SaleConfirmed` y crea la factura (idempotencia por `referenceCode`), con colas de reintento de espera creciente y DLQ. |
| 3 | Invoicing publica el resultado; sales pasa la venta a `INVOICED` o `INVOICE_FAILED`. |

Las fases 2 y 3 agregarán sus decisiones a un ADR propio. Las que ya están tomadas para las tres fases quedan aquí.

## Contexto

Cuando sales confirma una venta, invoicing debe facturarla sin que el vendedor espere a la DIAN. La comunicación tiene que ser asíncrona y **confiable**: una venta confirmada nunca puede quedarse sin factura porque un mensaje se perdió.

**El problema de fondo es la doble escritura.** Guardar la venta (PostgreSQL) y publicar el evento (RabbitMQ) son dos sistemas sin una transacción común. Cualquier orden que se elija deja un hueco:

| Orden | Qué sale mal |
|---|---|
| Commit de la venta, luego publicar | Si el servicio se cae o el broker no responde entre los dos pasos, la venta existe pero el evento se perdió. Nadie la factura. |
| Publicar, luego commit | Si el commit falla, invoicing factura una venta que no existe. |
| Publicar dentro de la transacción, antes del commit | Igual que el anterior, y además la transacción espera al broker. |

**Restricciones:**
- ADR 0001: sin código compartido; cada servicio tiene su propia copia de lo que necesite.
- ADR 0002 y 0012: hexagonal, con el dominio y la aplicación en Java puro; las transacciones solo en el adaptador de persistencia.
- RabbitMQ caído no puede impedir vender.

## Decisión

### 1. RabbitMQ, no Kafka

| | RabbitMQ | Kafka |
|---|---|---|
| Modelo | Colas: cada consumidor recibe, confirma (ack) o rechaza cada mensaje | Log particionado: cada consumidor lleva su posición (offset) |
| Reintentos con espera y DLQ (Fase 2) | Nativos: TTL + dead-letter exchange por cola | Hay que construirlos (topics de reintento y consumidores propios) |
| Una factura que falla | Se reintenta ESE mensaje; los demás siguen | Bloquea su partición, o hay que sacarlo a otro topic |
| Releer el histórico, stream processing | No | Sí: es su razón de ser |
| Operación local | Un contenedor, consola web incluida | Broker + controlador KRaft, más memoria, sin consola oficial |

Aquí cada evento es una **tarea** (facturar una venta) que se confirma o se reintenta de a uno, a un volumen de pyme. No se necesita releer el histórico ni procesar flujos. Kafka sería la opción si los eventos fueran la fuente de verdad o si hubiera analítica sobre ellos.

**Imagen:** `rabbitmq:4.3.6-management-alpine`, la última versión de parche de la línea 4.3. La 4.4.0 salió cuatro días antes de esta decisión y se descartó por eso. La misma imagen corre en compose y en Testcontainers.

> RabbitMQ 4.3 ya no admite **colas transitorias no exclusivas** (`transient_nonexcl_queues`). Las colas de prueba y la de depuración son durables.

### 2. Coreografía, no orquestación

Cada servicio publica sus hechos y reacciona a los de los demás. No hay un coordinador que diga "ahora factura" ni que lleve el estado del proceso.

- **A favor:**
  - sales no conoce a invoicing: publica `SaleConfirmed` aunque nadie escuche, y un consumidor nuevo (p. ej. inventario) se agrega sin tocar sales;
  - no hay un servicio central que caiga o que concentre la lógica de todos.
- **En contra:**
  - el flujo completo no está escrito en un solo lugar. Se documenta aquí y en `docs/events/`;
  - el estado del proceso hay que deducirlo de varios servicios. En la Fase 3, la venta lo refleja (`INVOICED` / `INVOICE_FAILED`).
- **Cuándo cambiaría:** con un proceso de muchos pasos y compensaciones (una saga larga), un orquestador lo haría más legible. Facturar una venta son dos pasos.

### 3. Topología

- **Un exchange por servicio:** `sales.events` (y `invoicing.events` en la Fase 3), de tipo `topic` y durable. Cada servicio solo puede escribir en el suyo (decisión 9).
- **Routing key `<agregado>.<hecho>.v<versión>`:** `sale.confirmed.v1`. Un consumidor elige con patrones (`sale.confirmed.*`, `sale.#`), y una v2 puede convivir con la v1.
- **Una cola por consumidor** (Fase 2): `invoicing.sale-confirmed`, con sus colas de espera y su DLQ.
- **Sin `mandatory`:** si ninguna cola está enlazada, el broker descarta el mensaje y aun así lo confirma. Es lo correcto en coreografía: el productor no sabe quién escucha. Por eso en la Fase 1 hace falta una cola de depuración para ver los mensajes (README).

### 4. Transactional outbox

**La venta y su evento se guardan en la MISMA transacción**, en la tabla `outbox_events`. Un proceso aparte (el relay) los publica después. El commit es atómico: o quedan los dos, o ninguno. Si el broker no está, el evento espera en la base.

**Alternativas descartadas:**

| Alternativa | Por qué no |
|---|---|
| Publicar después del commit (con reintentos en memoria) | Un reinicio entre el commit y la publicación pierde el evento. |
| Transacción distribuida (XA / 2PC) entre PostgreSQL y RabbitMQ | RabbitMQ no la soporta. Aunque lo hiciera, acopla la disponibilidad de los dos sistemas: sin broker no se vende. |
| CDC (Debezium leyendo el WAL de PostgreSQL) | Es la variante de producción del mismo patrón: sin sondeo y con menos latencia. Pero agrega Kafka Connect o Debezium Server, slots de replicación y su operación. Mucho para un portafolio (ver "Cómo se haría en producción"). |
| *Listen to yourself* (publicar primero y que sales consuma su propio evento para guardar la venta) | La venta dejaría de existir de forma síncrona: el `201` no podría devolverla y la idempotencia por `referenceCode` se complicaría. |
| Event sourcing | Cambia todo el modelo de persistencia por un problema que el outbox resuelve con una tabla. |

**Cómo respeta la hexagonal (opción aprobada A):**
- El agregado registra el hecho. `Sale.confirm` crea un evento de dominio `SaleEvent.SaleConfirmed(saleId, occurredAt)` en `pendingEvents()`, una lista inmutable. `SaleEvent` es `sealed`: cada evento nuevo obliga a decidir su mensaje en el adaptador.
- `SaleRepository.saveNew` amplía su contrato: guarda la venta **y sus eventos pendientes** en la misma transacción. El caso de uso `CreateSaleService` no cambió: no puede olvidarse de publicar.
- `Sale.reconstitute` no trae eventos. La instancia que devuelve `saveNew` ya no los tiene, así que no hay un `clearEvents()` que mute el agregado.
- El mensaje se serializa **al guardar** (`SaleEventMessages`, en el adaptador de persistencia). Se publica, y se republica en cada reintento, exactamente lo que había en ese momento.

**Tabla `outbox_events` (V2):**
- `id` es el `eventId`.
- Columnas: `aggregate_type`, `aggregate_id`, `event_type`, `event_version`, `routing_key`, `occurred_at`, `published_at` (NULL = pendiente), `attempts` y `last_error` (para diagnosticar).
- **`payload TEXT` con `CHECK (payload IS JSON OBJECT)`, y no `JSONB`:** JSONB reordena las claves, y el mensaje debe salir igual byte a byte en cada intento. Para consultarlo basta un cast: `payload::jsonb -> 'payload'`.
- **Índice parcial `(occurred_at, id) WHERE published_at IS NULL`:** el relay y la métrica solo leen los pendientes, y el índice no crece con el histórico.
- `event_type` y `aggregate_type` llevan un CHECK: cada tipo nuevo exige una migración, como los enums persistidos.

### 5. El relay: SKIP LOCKED y publisher confirms

**Hexagonal también:**
- puerto de entrada `PublishPendingEventsUseCase`, implementado por `PublishPendingEventsService` (Java puro, probado sin Spring);
- puertos de salida `OutboxRepository` y `EventPublisher`;
- el disparador es un adaptador de entrada (`adapter/in/outbox/OutboxRelayScheduler`, `@Scheduled` con `fixedDelay`): en una instancia, los ciclos nunca se solapan.

**Un ciclo** (`JpaOutboxAdapter.publishPending`, en UNA transacción):
1. `SELECT … WHERE published_at IS NULL ORDER BY occurred_at, id LIMIT 50 FOR UPDATE SKIP LOCKED`.
2. El publicador envía el lote y espera las confirmaciones.
3. `published_at = now()` **solo para los que el broker confirmó** (ack). Los demás suman `attempts` y guardan `last_error`.
4. Commit.

**Por qué SKIP LOCKED:**
- Con dos instancias de sales, o dos ciclos que coinciden, `FOR UPDATE` a secas haría esperar al segundo publicador hasta que el primero termine. Sin bloqueo, los dos tomarían el mismo lote y lo publicarían dos veces.
- Con `SKIP LOCKED`, el segundo **se salta** las filas bloqueadas y toma las siguientes: los publicadores se reparten el trabajo sin coordinarse.
- `JpaOutboxAdapterTest` lo prueba con PostgreSQL real. Mientras A retiene 5 eventos, B pide 10 y recibe los otros 5, sin esperar. Ningún evento se publica dos veces.

**La transacción sigue abierta durante la publicación (opción aprobada A).** Es la única excepción a "nada externo dentro de una transacción" (ADR 0006), y se acepta porque:
- es un proceso de fondo, fuera de las peticiones de los usuarios;
- la acotan el lote (50) y el timeout de confirmación (5 s);
- solo bloquea filas del outbox, nunca ventas, así que no compite con la creación de ventas;
- la regla de ArchUnit se mantiene: el `@Transactional` sigue en el adaptador de persistencia, y el caso de uso le pasa el publicador como función.

La alternativa (B) era reservar el lote con un `claimed_until` y publicar fuera de la transacción. Necesita columnas y una duración de reserva que hay que ajustar, y produce duplicados cuando la reserva vence. Queda en "Cómo se haría en producción".

**Publicación (`RabbitEventPublisher`, en `adapter/out/messaging`):**
- **Confirms por mensaje:** `publisher-confirm-type: correlated`. Cada mensaje lleva su `CorrelationData`, se envía el lote completo y luego se espera cada confirmación con un plazo común de 5 s.
  - Solo un **ack** cuenta como confirmado.
  - Un nack, un plazo vencido o un canal cerrado (p. ej. el exchange no existe: 404) dejan el evento pendiente.
  - El publicador nunca lanza por una falla del broker. Si algo inesperado se escapa, el caso de uso lo convierte en falla de todo el lote.
- **Propiedades del mensaje:**
  - `delivery_mode=2` (persistente: una cola durable lo guarda en disco);
  - `message_id` = `eventId`;
  - `type` = `SaleConfirmed`, `app_id` = `sales`, `timestamp` = `occurredAt`;
  - `content_type=application/json`, UTF-8;
  - cabecera `eventVersion`.

  Así se puede enrutar o filtrar sin abrir el cuerpo.
- **Exchange:** se declara como bean. `RabbitAdmin` lo crea en cada conexión, así que no importa si el broker estaba caído al arrancar.

**Valores** (`sales.outbox.*` y `sales.messaging.*`):

| Valor | Por qué |
|---|---|
| `poll-interval: 1s` | Invoicing empieza a facturar casi enseguida. Sin pendientes, cada ciclo es una lectura vacía del índice parcial. |
| `batch-size: 50` | Acota la transacción y el tiempo de espera de las confirmaciones. Rendimiento máximo: ~50 eventos/s por instancia, de sobra para una pyme. |
| `confirm-timeout: 5s` | Un lote se confirma en milisegundos; 5 s solo se agotan con el broker en problemas. |
| `spring.rabbitmq.connection-timeout: 2s` | Con el broker caído, cada ciclo falla rápido. |

### 6. Entrega "al menos una vez"

El outbox garantiza que **ningún evento se pierde**. No garantiza que se entregue **una sola vez**. Hay duplicados cuando:
- el broker confirma, pero el servicio se cae (o falla el commit) antes de marcar `published_at`; el siguiente ciclo lo reenvía;
- vence el `confirm-timeout` aunque el broker sí lo guardó: se registra como falla y se reenvía;
- en la Fase 2, el consumidor procesa el mensaje y se cae antes de su ack; RabbitMQ lo reentrega.

"Exactamente una vez" de punta a punta no existe entre dos sistemas sin una transacción común. Lo que se logra es **un efecto único**: **el consumidor descarta los duplicados**.
- Cada reenvío conserva el mismo `eventId` (el `message_id`).
- En la Fase 2, invoicing usa el `referenceCode` de la venta como el de la factura. Su idempotencia por `referenceCode` + huella (ADR 0006) convierte un duplicado en "la factura ya existe", sin facturar dos veces.

**Orden:** no se garantiza entre eventos. Con varios publicadores, SKIP LOCKED puede invertir dos eventos cercanos, y un reintento publica un evento viejo después de uno nuevo. Hoy hay un solo evento por venta. Los consumidores no deben depender del orden: en la Fase 3, las transiciones de estado (`canTransitionTo`) rechazan las que no aplican.

**Mensaje que nunca se publica** (p. ej. siempre rechazado): se reintenta en cada ciclo y sus `attempts` crecen. Como el lote va del más antiguo al más nuevo, 50 mensajes así bloquearían a los demás. Hoy no hay un caso real que lo produzca (no hay `mandatory` ni límites de tamaño), así que no hay un límite de intentos. La métrica de antigüedad (decisión 8) lo haría visible, y "Cómo se haría en producción" describe el apartado.

### 7. Contrato del evento: JSON Schema en `docs/events/`

- **Sobre (envelope):** `eventId`, `eventType`, `eventVersion`, `occurredAt` (cuándo pasó, no cuándo se publicó), `producer` y `payload`.
- **`SaleConfirmed` v1:**
  - lleva todo lo que invoicing necesita para crear la factura **sin consultar a catalog** (ADR 0012, D3), con los nombres de `POST /api/v1/invoices`;
  - suma `saleId`, `customerId` y `productId` para la trazabilidad;
  - los opcionales vacíos van como `null` explícito: la forma del mensaje es siempre la misma.
- **Totales INFORMATIVOS (opción aprobada B):** `totals` (`subtotal`, `totalDiscount`, `taxes[]`, `totalTax`, `total`), con el desglose de la respuesta de invoicing. **Invoicing no los usa para calcular: la autoridad sigue siendo invoicing** (ADR 0004).
  - El cálculo está duplicado a propósito (ADR 0012, D7), y el riesgo de esa duplicación es que las dos copias diverjan sin que nadie lo note.
  - Con el total en el evento, en la Fase 2 invoicing comparará sus totales con estos y registrará un **WARN** si difieren. Así la divergencia se detecta en la primera venta afectada.
  - El esquema lo dice en la descripción del campo.
- **Esquema:** `docs/events/sale-confirmed.v1.schema.json` (draft 2020-12), con `additionalProperties: false` en todos los objetos, enums cerrados y reglas condicionales (contado sin vencimiento, crédito con él; razón social o nombres según el tipo de persona).
- **Validación sin librería compartida:**
  - el archivo vive en `docs/events/` y lo lee el test de cada servicio;
  - `./scripts/mvn.sh` ahora monta la raíz del repo y trabaja en `services/<servicio>`, así los tests lo leen sin copiarlo (opción aprobada A);
  - validador: `com.networknt:json-schema-validator` **3.0.8**, solo en test, porque la línea 3.x usa Jackson 3, el de Boot 4;
  - el test exige también los `format` (uuid, date, date-time);
  - además comprueba que el esquema **rechace** mensajes rotos, para que no sea permisivo de más.
- **Evolución:** reglas en `docs/events/README.md`. Un cambio compatible (un campo opcional nuevo) se publica en la misma versión, y los consumidores toleran campos desconocidos. Uno incompatible es una `v2` con su propio esquema y su routing key, y convive con la v1 mientras haya consumidores de ella.

### 8. Health, métricas y logs

- **RabbitMQ no afecta el health.** Spring Boot registra solo un indicador `rabbit` en cuanto ve un `ConnectionFactory`, y con el broker caído pondría `/actuator/health` en DOWN. Se desactiva con `management.health.rabbit.enabled: false` (`/actuator/health/rabbit` → 404).
- **Lo reemplaza el componente informativo `messaging`** (`MessagingHealthIndicator`), igual que el de Factus (ADR 0010) y el de catalog (ADR 0012):
  - siempre UP;
  - en `/actuator/health/dependencies`, junto a `catalog`;
  - detalles: `relay` (`IDLE` / `OK` / `FAILING`), `pendingEvents`, `oldestPendingSince`, `lastPublishedAt`, `lastFailure`, `lastFailureAt` y `consecutiveFailures`;
  - no entra en liveness ni readiness: RabbitMQ caído no significa que sales esté mal (vende y guarda los eventos), y un reinicio no arreglaría nada.
- **Métricas** (Micrometer, en `/actuator/metrics`), calculadas al leerlas con una consulta sobre el índice parcial:
  - `sales.outbox.pending`: eventos sin confirmar;
  - `sales.outbox.oldest.pending.age`: segundos que lleva esperando el más antiguo. Es la que se vigilaría en producción: muchos pendientes pueden ser un pico normal, pero un pendiente que envejece significa que nada se publica.
- **Logs:** el relay registra solo los **cambios de estado**: WARN al empezar a fallar, INFO al recuperarse (con cuántos ciclos falló). No escribe una línea por ciclo mientras el broker está caído.

### 9. Seguridad: un usuario por servicio (opción aprobada B)

Igual que PostgreSQL (ADR 0003):
- **Administrador** (`RABBITMQ_ADMIN_USER` / `RABBITMQ_ADMIN_PASSWORD`): solo para la consola y el script de init.
- **Usuario por servicio**, en el vhost `facturaflow`, con lo mínimo:

  | Usuario | configure | write | read |
  |---|---|---|---|
  | `sales` | `^sales\.events$` | `^sales\.events$` | `^$` (nada) |

  Sales solo puede declarar su exchange y publicar en él. No puede leer colas ni tocar las de otro servicio. Invoicing tendrá el suyo en la Fase 2.
- **Creación:** el contenedor de un solo uso `rabbitmq-init` ejecuta `infra/rabbitmq/init/create-service-users.sh` con `rabbitmqadmin` (incluido en la imagen), contra la API HTTP. El script es idempotente: cada `declare` es un PUT.
  - La contraseña de cada servicio se convierte en el script en su hash salado (formato SHA-256 de RabbitMQ). Solo el hash viaja como argumento.
  - La del administrador llega en una variable de entorno.
  - Nada se imprime.
  - A diferencia del script de PostgreSQL, este **sí** actualiza la contraseña si cambió en el `.env`.
- **Arranque:** sales no espera a `rabbitmq-init` ni a que RabbitMQ esté sano (`service_started`). Si su usuario aún no existe, el relay falla y reintenta solo.
- **Puertos:** solo la consola, en `127.0.0.1:15672`. El 5672 (AMQP) no se publica.

### 10. Riesgo del vencimiento frente a la fecha de emisión (punto abierto del ADR 0012): aceptado

Sales valida que el vencimiento de una venta a crédito sea posterior a **la fecha de la venta**. Invoicing lo compara con **la fecha de emisión de la factura**. Con la facturación asíncrona, si la factura se crea días después (cola detenida, reintentos, DLQ), un vencimiento cercano puede quedar igual o anterior a esa fecha, e invoicing la rechazaría con un 422.

- **Se acepta el riesgo:** en operación normal, la factura se crea segundos después de la venta.
- **Si ocurre:** la factura se rechaza y, en la Fase 3, la venta queda **`INVOICE_FAILED`** con ese motivo, visible para corregirla. No se pierde en silencio.
- **Descartado por ahora:**
  - emitir con la fecha de la venta: depende de lo que acepte la DIAN para emisiones con fecha anterior, y no está verificado;
  - un margen mínimo de días en sales: cambia una regla de negocio por un caso raro.

### 11. Tests

| Test | Qué demuestra |
|---|---|
| `SaleTest` (eventos) | `confirm` registra `SaleConfirmed`; `reconstitute` no trae eventos; la lista es inmutable. |
| `CreateSaleServiceTest` | La venta llega al repositorio con su evento; un reintento idempotente no genera otro. |
| `PublishPendingEventsServiceTest` (sin Spring) | Lote del más antiguo al más nuevo; solo se marca lo confirmado; broker caído → pendiente y publicado al volver; confirmación parcial; una excepción del publicador no escapa; un id ajeno no se marca. |
| `JpaSaleRepositoryAdapterTest` | Venta y evento se guardan juntos; si la venta falla (código repetido), no queda su evento. |
| `JpaOutboxAdapterTest` (PostgreSQL real) | Se marca solo lo confirmado, con intentos y motivo; dos publicadores simultáneos toman lotes distintos (SKIP LOCKED) y cada evento se publica exactamente una vez. |
| `RabbitMessagingIntegrationTest` (RabbitMQ real) | El mensaje llega persistente, con `message_id` = `eventId` y sus propiedades. Con el exchange borrado, el broker rechaza la publicación: el evento queda pendiente con un intento y se publica cuando el exchange vuelve. |
| `SaleConfirmedSchemaTest` | Los mensajes (contado y crédito, persona jurídica y natural) cumplen el esquema; diez mensajes rotos lo incumplen; totales informativos correctos. |
| `SaleApiEndToEndTest` | POST → outbox → relay → RabbitMQ: el mensaje cumple el contrato, el outbox queda vacío y `messaging` está `OK`. |
| `BrokerDownEndToEndTest` | Sin broker: la venta responde 201, el evento queda pendiente con intentos, health, liveness y readiness siguen UP, `messaging` muestra `FAILING` y las métricas, el pendiente. |
| `HexagonalArchitectureTest`, regla 11 | Spring AMQP y el cliente de RabbitMQ solo se usan dentro de `adapter/out/messaging`, y nada de ese paquete sale de él. |

## Cómo se haría en producción

- **Limpieza del outbox:** borrar los publicados de más de N días con un job, o particionar la tabla por mes y descartar particiones. Hoy los publicados se acumulan, a volumen de portafolio.
- **CDC en vez de sondeo** (Debezium sobre el WAL): sin sondeo, con menos latencia, sin transacción abierta durante la publicación, y con el orden del commit.
- **Reserva del lote** (`claimed_until`) en vez de mantener el bloqueo durante la publicación, si los lotes o la latencia del broker crecieran.
- **Mensajes venenosos:** un límite de `attempts` que aparte el evento (estado `PARKED`) con una alerta, para que no frene al resto del lote.
- **Alertas** sobre `sales.outbox.oldest.pending.age` (p. ej. > 5 min) y sobre `consecutiveFailures`.
- **RabbitMQ en clúster** (3 nodos) con **quorum queues** para las colas de los consumidores, TLS en AMQP y en la consola, y credenciales desde un gestor de secretos con rotación.
- **Registro de esquemas** o *contract tests* dirigidos por el consumidor (Pact) cuando haya muchos productores y consumidores.

## Consecuencias

**Positivas**
- **Ningún evento se pierde:** la venta y su evento se confirman juntos, y el relay insiste hasta que el broker confirma.
- **Sales vende con RabbitMQ caído:** el `201` no depende del broker, y los eventos salen solos cuando vuelve.
- **Escala horizontal sin coordinación:** varias instancias publican en paralelo gracias a SKIP LOCKED.
- **El contrato es explícito y verificado:** un archivo, validado por el productor hoy y por el consumidor en la Fase 2.
- **Visibilidad:** pendientes y antigüedad en métricas y health; los intentos y el último error, en la tabla.

**Negativas**
- **Duplicados posibles:** cada consumidor debe ser idempotente.
- **Latencia de hasta un `poll-interval`** (1 s) entre la venta y la publicación, más una consulta por segundo con el outbox vacío.
- **Una transacción abierta durante la publicación** (acotada, decisión 5).
- **El outbox crece** hasta que haya limpieza (producción).
- **Más piezas:** un broker, un contenedor de inicio y un usuario por servicio.
