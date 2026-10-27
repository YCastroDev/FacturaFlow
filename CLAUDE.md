# CLAUDE.md — FacturaFlow

Plataforma de ventas para pymes colombianas que emite facturas electrónicas ante la DIAN a través de la API de Factus (sandbox: https://api-sandbox.factus.com.co). Proyecto de portafolio. Las decisiones de arquitectura están en [docs/adr/](docs/adr/README.md).

## Entorno

- Ubuntu en WSL 2 con Docker Engine nativo (sin Docker Desktop).
- **No hay Java ni Maven instalados localmente.** Todo se compila, prueba y ejecuta en contenedores. No sugerir instalar nada en el host.

## Stack

| Pieza | Versión |
|---|---|
| Java | 25 (LTS) — Eclipse Temurin |
| Spring Boot | 4.1.1 |
| Maven | 3.9.16 vía Maven Wrapper (`mvnw`) |
| PostgreSQL | 18 (`postgres:18.6-alpine`, parche fijado) |
| Migraciones | Flyway |
| Tests de integración | Testcontainers 2.x |
| Tests de arquitectura | ArchUnit 1.5.1 |
| Documentación de la API | springdoc-openapi 3.1.1 (Swagger UI) |
| Escaneo de secretos | gitleaks v8.30.1 (vía Docker) |
| Mock HTTP (tests del adaptador de Factus) | WireMock 3.13.1 (`wiremock-standalone`) |
| Resiliencia (llamadas a Factus) | Resilience4j 2.4.0 (`resilience4j-spring-boot4`, versión fijada en el pom) |
| Mensajería | RabbitMQ 4.3.6 (`rabbitmq:4.3.6-management-alpine`, en compose y Testcontainers, que la lee del compose; plugins de la imagen + shovel) + `spring-boot-starter-amqp` (sales y invoicing) |
| Validación de contratos de eventos (tests) | `com.networknt:json-schema-validator` 3.0.8 (Jackson 3; solo test) |

## Estructura

- `services/<servicio>/` — un proyecto Spring Boot independiente por servicio: `invoicing` (hexagonal, ADR 0002), `catalog` (en capas, ADR 0011) y `sales` (hexagonal, ADR 0012).
- `infra/` — `docker-compose.yml` y scripts de init de PostgreSQL (`postgres/init`) y de RabbitMQ (`rabbitmq/init`).
- `scripts/` — utilidades (`mvn.sh`, `check-secrets.sh`, `seed-catalog.sh`, `rabbitmq-debug-queue.sh`, `rabbitmq-dlq.sh`).
- `docs/events/` — catálogo y contratos (JSON Schema) de los eventos entre servicios (ADR 0013).
- `.githooks/` — hook pre-commit con gitleaks.
- `.github/` — CI (ADR 0016): `workflows/ci.yml` (orquestador), `workflows/_service.yml` (reutilizable por servicio), `workflows/gitleaks.yml`, `scripts/test-summary.py`, `dependabot.yml`.
- `docs/adr/` — Architecture Decision Records.
- `docs/spikes/` y `scripts/spikes/` — investigaciones contra sistemas externos (hallazgos y scripts reproducibles).

## Reglas de arquitectura (invoicing y sales)

- Estructura hexagonal obligatoria en invoicing y sales (ADR 0002, ADR 0012); catalog es en capas (ADR 0011, sección propia abajo), bajo `com.facturaflow.<servicio>`:
  `domain/{model,exception}`, `application/{port/in,port/out,service}`, `infrastructure/{adapter/in/...,adapter/out/...,config}`.
- **Nada de anotaciones ni imports de Spring, JPA o Jackson en `domain/`.** El dominio es Java puro.
- Las dependencias apuntan hacia adentro: `infrastructure → application → domain`. `application` no conoce adaptadores concretos.
- `application/` también es Java puro: sin `@Service`, `@Transactional` ni nada de Spring. Los casos de uso se registran como beans en `infrastructure/config`.
- En `application/port/in` y `application/port/out` solo hay interfaces (puertos) y records (comandos/resultados). Las excepciones del contrato de los puertos van en `application/exception`.
- `infrastructure/adapter/in` nunca usa `infrastructure/adapter/out`: siempre pasa por un puerto de entrada.
- Sin ciclos entre paquetes.
- **Prohibido** importar código de otro servicio o acceder a la base de datos de otro servicio (ADR 0001, ADR 0003). La comunicación entre servicios es solo por HTTP o eventos.
- **Estas reglas se validan automáticamente** con ArchUnit (`src/test/java/.../architecture/HexagonalArchitectureTest.java`) y corren con `./scripts/mvn.sh <servicio> test`. Si un test de arquitectura falla, se corrige el código, no la regla; cambiar una regla requiere preguntar (y, si es una decisión nueva, un ADR).

## Reglas de seguridad

- Ninguna contraseña, token, llave o credencial en código, `application.yml`, `docker-compose.yml`, scripts ni documentación. Todo se lee de variables de entorno, sin valores por defecto que contengan secretos.
- `.env` (raíz, ignorado por Git) contiene los valores reales. Claude **puede generar o modificar `.env` para desarrollo local**, pero **nunca mostrar su contenido** (ni con `cat`, `grep`, logs, `docker compose config`, etc.) **ni copiar sus valores a otros archivos**. Para saber qué variables existen, leer `.env.example`.
- Al agregar o renombrar una variable, actualizar `.env.example` (con valor `CAMBIAME`) en el mismo cambio.
- No desactivar ni saltarse el hook de gitleaks (`--no-verify`).

## Reglas del dominio (ADR 0004)

- Conceptos propios (enums, value objects); **nunca** IDs de catálogos de Factus en `domain/`. La traducción va en el adaptador.
- El dominio no llama a `LocalDate.now()`, `Instant.now()` ni al reloj: las fechas entran como parámetro.
- Las excepciones de `domain.exception` reciben solo valores simples (String, UUID, números), nunca objetos de `domain.model`.
- Montos con `Money` (COP, 2 decimales, `HALF_UP`), redondeo por línea; `unitPrice` sin impuestos ni descuentos.
- Impuestos por línea como `List<LineTax>` (`TaxType` + `TaxRate`), sin tipos repetidos, cada uno redondeado por separado. `excluded` = "excluida de IVA" (no admite `LineTax` de tipo IVA).
- Value objects como records que se validan en su constructor; el aggregate `Invoice` sin setters.
- Tests de dominio: JUnit 5 + AssertJ puros (sin Spring), con `@DisplayName` en español como frase de negocio.
- Cliente (ADR 0007): `PersonType` con exclusión estricta (jurídica → solo `companyName`; natural → solo `names`); `FiscalProfile` obligatorio (`TaxScheme` + conjunto no vacío de `FiscalResponsibility`, `NOT_RESPONSIBLE` siempre sola); `Municipality` (DIVIPOLA, 5 dígitos), dirección y `PhoneNumber` opcionales.
- `UnitOfMeasure` solo contiene unidades que el proveedor acepta (ver spike 0001). Agregar una exige comprobarlo y una migración.

## Reglas de persistencia (ADR 0005)

- Entidades JPA solo en `infrastructure/adapter/out/persistence`, package-private, separadas del dominio; mapeo a mano en `InvoicePersistenceMapper`.
- El dominio se reconstruye con `Invoice.reconstitute(...)`, nunca saltándose sus validaciones.
- `Invoice.version()` es un token opaco: no se interpreta ni se modifica fuera del adaptador. `saveNew` (insertar, con la huella de la petición) y `save` (actualizar) devuelven una instancia nueva; usar siempre la devuelta.
- Los adaptadores no dejan escapar excepciones de Spring/Hibernate: se traducen a las de `application.exception`. Hacer `flush()` dentro del `try` para poder traducirlas.
- Cargar agregados con entity graph (sin N+1); colecciones como listas indexadas (`@OrderColumn`). Los tests verifican el número de sentencias con `Statistics`.
- Cada valor nuevo de un enum persistido exige migración (CHECK). Columnas: sin palabras reservadas; `VARCHAR(n)` solo si el dominio impone ese límite.

## Reglas de casos de uso y API REST (ADR 0006)

- Casos de uso: interfaz en `port/in` + implementación en `application/service` registrada en `UseCaseConfiguration`. Reciben comandos con tipos simples y construyen los value objects ellos mismos. La fecha sale del `Clock` inyectado (zona `invoicing.time-zone`).
- **Transacciones solo en `infrastructure/adapter/out/persistence`** (regla de ArchUnit). Nunca envolver un caso de uso en una transacción: las llamadas a sistemas externos (proveedor de facturación) van fuera de la base.
- Idempotencia de creación por `referenceCode` + huella SHA-256 de la petición normalizada (`InvoiceRequestHasher`, formato v2). Si se agrega un campo a la factura, agregarlo también a la huella y a su test parametrizado (la huella no se versiona: ADR 0007, decisión 6).
- Emisión: respuestas de negocio como `IssuanceResult` sellado (`Issued` | `Rejected`); fallas técnicas como `ElectronicInvoiceProviderUnavailableException`. Un adaptador de proveedor nunca devuelve un `Rejected` por un timeout.
- Proveedor activo según `invoicing.provider` (`fake` por defecto, `factus` para el sandbox real). El falso se controla con `SIMULAR_RECHAZO`, `SIMULAR_CAIDA` (falla siempre) y `SIMULAR_FALLA_TEMPORAL` (falla las primeras `invoicing.fake.temporary-failures` emisiones de ese documento y luego emite; 4 en compose; contador en memoria) en la observación (de la factura o de la nota crédito).
- REST: DTOs y enums propios en `adapter/in/rest/dto`, con `switch` exhaustivos hacia y desde el dominio. El controlador lee el agregado, nunca lo serializa. Bean Validation solo para forma (presencia, tipos, topes anti-abuso); las reglas de negocio no se duplican (responden 422 desde el dominio).
- Errores: siempre Problem Details desde `ProblemDetailsHandler`, con `type` `urn:facturaflow:problem:<slug>`. Nunca exponer mensajes de Spring, Jackson o Hibernate ni trazas. Una excepción nueva de `application.exception` necesita su fila en la tabla del ADR 0006 y su test en el test del controlador que la expone (`InvoiceControllerTest` o `CreditNoteControllerTest`).
- Tests: casos de uso con `InMemoryInvoiceRepository` y `StubElectronicInvoiceProvider` (sin Spring); web con `@WebMvcTest` + `MockMvcTester`; punta a punta con `RestTestClient` + Testcontainers.

## Reglas de las notas crédito (ADR 0009)

- `CreditNote` es un agregado SEPARADO: referencia la factura por `InvoiceId`, con su ciclo PENDING → ISSUED | REJECTED. La factura no cambia de estado al acreditarse.
- Motivos propios (`PARTIAL_RETURN`, `TOTAL_ANNULMENT`); los códigos de la DIAN solo en el adaptador.
- El cliente de la nota es SIEMPRE el de la factura guardada; nunca viene en la petición.
- Cada línea es el número de línea de la factura (desde 1) + `InvoiceLine.withQuantity(q)`: los montos salen del mismo cálculo que la factura.
- **Regla que cruza agregados:** lo acreditado (notas PENDING + ISSUED; las REJECTED no cuentan) nunca supera lo facturado, **en cantidades por línea**, nunca en dinero. La calcula `CreditBalance`; la protege `CreditNoteRepository.saveNew`, que sube la versión de la factura solo si sigue siendo la leída (bloqueo optimista). Toda nota nueva DEBE pasar por `saveNew`. El caso de uso reintenta hasta 3 veces (422 si ya no cabe, 409 si persisten los conflictos).
- Emitir o rechazar una nota no toca la factura. No hay cancelación de notas PENDING (limitación documentada en el ADR 0009).
- `ElectronicDocument.uniqueCode` es el CUFE o el CUDE; la API usa `cufe` en facturas y `cude` en notas.
- Con Factus (ADR 0008, decisión 10): `reference_code` = `CreditNoteId`; cliente e ítems con el mismo código que la factura; pago de contado; `FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID` obligatorio. Antes de marcar una nota emitida se verifican `reference_code`, la identificación del cliente y `bill.number`. `CBF02` es una notificación, no un rechazo. Un 422 por `bill_number` es falla técnica: la factura no existe en esa cuenta.

## Reglas del adaptador de Factus (ADR 0008)

- Anti-Corruption Layer en `infrastructure/adapter/out/provider/factus`, todo package-private. **Ningún DTO, código ni cliente de Factus sale de ese paquete** (regla 9 de ArchUnit); se usa solo a través de `ElectronicInvoiceProvider`.
- Códigos de catálogo solo en `FactusCodes` (`switch` exhaustivos) y cada uno con su fila en `FactusCodesTest`. Un código nuevo exige verificarlo contra el sandbox.
- `reference_code` = `InvoiceId` (UUID). Una respuesta con otro `reference_code` o con otra identificación del cliente es otra factura: falla técnica y log ERROR, nunca `Issued`. Si solo difiere el total: `Issued` con el total oficial y log WARN.
- Resultados según la tabla del ADR 0008, la misma para facturas y notas crédito: solo un 422 por los datos del documento es `Rejected`; todo lo demás (incluido `is_validated: false`, 409, 429, 5xx, timeouts y un 422 de configuración) es falla técnica. `data.errors` con `is_validated: true` son notificaciones, no errores.
- Nunca borrar nada en Factus. Sin reintentos ni limitadores a mano: la resiliencia es la del ADR 0010 (abajo); el 401 lo sigue resolviendo `FactusClient` con un único reintento.
- **Nunca registrar** tokens, `client_secret`, contraseñas ni `public_url` (enlace-capacidad); los `toString` de propiedades y tokens enmascaran. Los mensajes de error solo llevan el estado HTTP, nunca el cuerpo.
- Tests con WireMock y las respuestas reales del spike (`src/test/resources/factus/`, con los casos no observados marcados como NO VERIFICADOS). Los de sandbox real llevan `@Tag("sandbox")` y no corren por defecto; crean facturas reales: con moderación y `send_email=false`.

## Reglas de resiliencia (ADR 0010)

- Solo en el adaptador de Factus, programática (sin anotaciones): `FactusResilience` envuelve CADA llamada HTTP en `FactusHttp.exchange` con Retry → CircuitBreaker → RateLimiter. Toda llamada nueva a Factus debe pasar por `FactusHttp`.
- Se reintenta (máx. 3 intentos, backoff con jitter) solo ante timeouts, errores de red, 5xx, 429 (si su `Retry-After` ≤ 5 s) y 409 "pendiente". NUNCA ante 422, 401 (lo maneja `FactusClient`) ni una identidad distinta (se detecta después, en el intérprete).
- Un solo circuito `factus`. Cuentan como falla: timeouts, red, 5xx y 429; el 409 y el limitador se ignoran; los rechazos de la DIAN no cuentan.
- Circuito abierto o limitador agotado → `ElectronicInvoiceProviderUnavailableException` con `reason` y `retryAfter` → 503 con ese `Retry-After`.
- El health no depende de Factus: el componente `factus` siempre es UP (detalles en `/actuator/health/providers`) y no entra en liveness/readiness.
- Los valores viven en `invoicing.factus.resilience.*` (`application.yml`); los tests del adaptador usan `FactusTestData.singleAttempt()` salvo `FactusResilienceTest`.

## Servicio catalog (ADR 0011)

- Dueño de productos y clientes (datos maestros). Arquitectura en CAPAS, no hexagonal: `com.facturaflow.catalog.{web(+dto),service,repository,model,config}`. Reglas en `LayeredArchitectureTest`: dependencias hacia abajo (web → service → repository, todas sobre model), controladores sin repositorios, `@Transactional` solo en `service`, `model` solo con Java + `jakarta.persistence`, DTOs sin tipos del modelo, sin ciclos y **nada de `com.facturaflow.invoicing`**.
- Las reglas del dato viven en el modelo (`ProductDetails`, `ProductTax`, `CustomerDocument`, `CustomerProfile`) y son una **copia deliberada** de las de invoicing (DV del NIT, documentos, tipo de persona, situación tributaria, DIVIPOLA, teléfono, impuestos, 8 unidades). Si cambia una regla en un servicio, revisar su copia en el otro (tabla en el ADR 0011). Mismos nombres de enums y campos que la API de invoicing.
- Código de producto único sin distinguir mayúsculas (`lower(code)`); cliente único por tipo + número (número en mayúsculas). Código y documento son inmutables (422). Nada se borra: `POST /{id}/deactivate` y `/activate`, idempotentes.
- Concurrencia: `@Version` = ETag fuerte (`"3"`). `PUT` exige `If-Match` (sin él 428; viejo 412; `*`, listas o `W/` 400); activar y desactivar lo aceptan opcional (carrera sin él: 409). Un PUT sin cambios no sube la versión. El servicio hace `flush` dentro de la transacción para traducir el bloqueo optimista.
- Duplicados: comprobación previa + nombre de la restricción única (`uq_products_code`, `uq_customers_document`) → 409, nunca 500.
- Listados: `page`/`size` (1–100)/`q`/`active`, orden fijo y estable, DTO `PageResponse` propio; colecciones EAGER cargadas por lotes (`default_batch_fetch_size`): 3 sentencias por página (`QueryCountIntegrationTest`).
- Problem Details con el mismo formato que invoicing; una excepción nueva necesita su fila en la tabla del ADR 0011 y su test en el test del controlador.
- Puerto local `127.0.0.1:8082`; base `catalog_db` / usuario `catalog_user` (`CATALOG_DB_PASSWORD`).
- Consulta por lote `POST /api/v1/products/lookup` (1–100 códigos, activos o no; la usa sales). Es una lectura: no cambiarla sin revisar el adaptador de catalog en sales.

## Servicio sales (ADR 0012)

- Hexagonal con las mismas reglas de ArchUnit que invoicing (`HexagonalArchitectureTest`), más: nada de `adapter/out/catalog` sale de su paquete (ACL) y ningún código de otro servicio.
- La petición solo trae `referenceCode`, documento del cliente (tipo + número), líneas (código, cantidad, descuento), pago y observación. Precios, nombres, impuestos y datos fiscales salen SIEMPRE de catalog y la venta guarda un snapshot con los mismos nombres que pide invoicing (lo que llevará `SaleConfirmed`). Cliente o producto inexistente o inactivo → 422 `catalog-reference-invalid` con todos los problemas.
- 2 llamadas a catalog por venta (cliente + lote de productos), nunca una por línea. Toda llamada nueva a catalog pasa por `CatalogHttp` → `CatalogResilience` (Retry → CircuitBreaker): 1 reintento solo si la petición no salió o ante 502/503/504; nunca tras un timeout de lectura; el 404 no es falla. Catalog caído o circuito abierto → 503 `catalog-unavailable` con `Retry-After`; la venta no se crea.
- Health: componente `catalog` siempre UP (`/actuator/health/dependencies`); fuera de liveness/readiness.
- Idempotencia como invoicing, pero la huella es de la PETICIÓN normalizada (no de los datos de catalog) y se resuelve ANTES de llamar a catalog (un reintento responde 200 con catalog caído). Si se agrega un campo a la petición, agregarlo a `SaleRequestHasher` y a su test.
- Totales: COPIA deliberada del cálculo de invoicing (`Money`, `Quantity`, `DiscountRate`, `LineTax`, `SaleLine`) con sus tests copiados. Si cambia en un servicio, cambia en el otro.
- `SaleStatus`: `CONFIRMED` (pendiente de facturar) → `INVOICED` | `INVOICE_FAILED`; `INVOICE_FAILED` → `INVOICED`; `INVOICED` final (ADR 0015, sección propia abajo). Un estado nuevo exige su caso en `canTransitionTo`, migración (CHECK de estado y de coherencia) y su fila en la tabla del ADR 0015.
- El snapshot del cliente solo se valida en estructura (las reglas fiscales las validan catalog e invoicing).
- Puerto local `127.0.0.1:8083`; base `sales_db` / usuario `sales_user` (`SALES_DB_PASSWORD`); `SALES_CATALOG_BASE_URL` (por defecto `http://catalog:8080`); RabbitMQ con el usuario `sales` (`SALES_RABBITMQ_PASSWORD`).

## Mensajería y outbox (ADR 0013; en invoicing, copia en el ADR 0015)

- RabbitMQ, coreografía (sin orquestador). Un exchange topic durable por servicio productor (`sales.events`), routing key `<agregado>.<hecho>.v<n>` (`sale.confirmed.v1`), una cola por consumidor (Fase 2). Sin `mandatory`.
- **Transactional outbox, propio en cada servicio (sin código compartido).** En sales: el agregado registra el evento (`Sale.confirm` → `SaleEvent.SaleConfirmed` en `pendingEvents()`; `reconstitute` no trae eventos) y `SaleRepository.saveNew` guarda venta + eventos en la MISMA transacción (`outbox_events`). **Nunca publicar directo desde un caso de uso ni después del commit.**
- El mensaje se serializa al guardar, en `SaleEventMessages` (adaptador de persistencia): es el ÚNICO lugar que conoce el contrato. `payload` es `TEXT` con `IS JSON OBJECT` (no JSONB: reordena claves). Cada `event_type` nuevo exige migración (CHECK).
- Relay: `OutboxRelayScheduler` (`adapter/in/outbox`, `fixedDelay` `sales.outbox.poll-interval`) → `PublishPendingEventsUseCase` → `OutboxRepository.publishPending` (`FOR UPDATE SKIP LOCKED`, lote `batch-size`, transacción abierta mientras se publica: excepción documentada) → `EventPublisher` (`RabbitEventPublisher`, confirms `correlated`). Se marca publicado SOLO lo confirmado (ack); lo demás suma `attempts`/`last_error`. El publicador nunca lanza por fallas del broker.
- Mensajes persistentes, `message_id` = `eventId`, `type`, `app_id`, `timestamp`, cabecera `eventVersion`.
- **Al menos una vez:** puede haber duplicados y no hay orden garantizado; los maneja el consumidor (por `eventId` / `referenceCode`). En la Fase 2, el `referenceCode` de la venta es el de la factura.
- **Contrato:** JSON Schema en `docs/events/<evento>.v<n>.schema.json`, validado por los tests de cada servicio leyendo ESE archivo (`EventContracts` en sales; `mvn.sh` monta la raíz del repo). Cambiar un campo del mensaje exige cambiar el esquema y su test; un cambio incompatible es una versión nueva (reglas en `docs/events/README.md`). `totals` es INFORMATIVO: invoicing no lo usa para calcular (en la Fase 2 lo compara y registra un WARN si difiere).
- **Health:** `management.health.rabbit.enabled: false` (el indicador de Spring Boot bajaría el health); en cada servicio UN componente `messaging` informativo, siempre UP, en `/actuator/health/dependencies`, fuera de liveness/readiness, con las partes `outbox` y `consumer` (`MessagingHealthPart`, interfaz pública en `infrastructure/config` que aporta cada adaptador; el indicador las reúne). Métricas `<servicio>.outbox.pending` y `<servicio>.outbox.oldest.pending.age`. El relay solo registra cambios de estado (WARN al fallar, INFO al recuperarse).
- ArchUnit regla 11: Spring AMQP y el cliente de RabbitMQ solo dentro de `adapter/out/messaging` (package-private), y nada de ese paquete sale de él.
- **Seguridad:** administrador solo para consola e init; un usuario por servicio en el vhost `facturaflow` con permisos mínimos (sales: configure/write `^sales\.events$`, read `^$`), creado por `infra/rabbitmq/init/create-service-users.sh` (contenedor `rabbitmq-init`, idempotente, sin contraseñas en argumentos). Un servicio nuevo que publique o consuma necesita su línea en ese script y su variable en `.env.example` y compose. Solo la consola se publica (`127.0.0.1:15672`); el 5672 no.
- RabbitMQ 4.3 no admite colas transitorias no exclusivas: las colas de tests y de depuración son durables.
- Tests: los que inspeccionan el outbox apagan el relay (`sales.outbox.relay.enabled=false`) y lo invocan a mano; `TestcontainersConfiguration` levanta PostgreSQL y RabbitMQ.

## Consumo de eventos en invoicing (ADR 0014)

- `infrastructure/adapter/in/messaging` (ACL, package-private; ArchUnit reglas 10 y 11: nada sale del paquete y Spring AMQP/`com.rabbitmq` solo ahí). Traduce `SaleConfirmed` v1 al MISMO `CreateInvoiceCommand` de la API y llama a `CreateInvoiceUseCase` e `IssueInvoiceUseCase` **sin modificarlos**. Lector tolerante (`SaleConfirmedJson`); validación estructural en el traductor; el JSON Schema se valida en los tests (`EventContracts`, copia propia de la de sales).
- Idempotencia: `referenceCode` de la venta = de la factura. Solo se emite si la factura está `PENDING`; un duplicado de una `ISSUED`/`REJECTED` es ack sin llamar al proveedor. Sin tabla inbox de `eventId` (decisión documentada).
- `SaleConfirmedProcessor` clasifica (sin AMQP): `Completed` (ISSUED, REJECTED, duplicado, `InvoiceAlreadyRejected`) → ack; `RetryLater` (`ElectronicInvoiceProviderUnavailable`, `InvoiceConcurrentModification`, cualquier excepción inesperada) → espera y reintento; `Invalid` (JSON/contrato, `DomainException`, `ReferenceCodeConflict`) → registra `InvoiceRequestRejected` (si el `referenceCode` es legible; si registrarlo falla → `RetryLater`) y luego DLQ sin reintentos. Una falla técnica NUNCA registra un rechazo. Una clasificación nueva exige su fila en la tabla del ADR 0014 y su test.
- Ack MANUAL y solo después de procesar o de republicar CON confirmación (`ConfirmedRepublisher`); si republicar falla → `basicNack(requeue=true)`. Nunca hacer ack antes.
- Topología (la declara invoicing; `sales.events` NO, lo declaran sales y `rabbitmq-init`): `invoicing.sale-confirmed` ← `sales.events`/`sale.confirmed.v1`; `invoicing.retry` → `invoicing.sale-confirmed.wait-<duración>` (TTL por cola; el nombre lleva la duración); al vencer vuelven por `invoicing.requeue` (NUNCA por el exchange por defecto: exigiría write sobre `amq.default`); `invoicing.dlx` → `invoicing.sale-confirmed.dlq`. Cabecera `x-attempt`; la DLQ lleva `x-failure-category`, `x-failure-reason`, `x-attempts-made`, `x-failed-at` y no `x-attempt`.
- `invoicing.messaging.retry-delays: 10s,1m,5m`; 1 consumidor, prefetch 1 (limitador de Factus y sin duplicados en paralelo).
- Totales del evento: informativos; WARN con ambos valores solo al CREAR la factura (`SaleTotalsCheck`).
- Health: parte `consumer` del componente `messaging` (`/actuator/health/dependencies`); métrica `invoicing.sale_confirmed.processed{outcome}`. Sin RabbitMQ, invoicing arranca y su API funciona.
- Usuario `invoicing` (`INVOICING_RABBITMQ_PASSWORD`): configure/write `^invoicing\..*`, read `^(invoicing\..*|sales\.events)$`. **Los tests de invoicing ejecutan el script de init real en Testcontainers** y se conectan como `invoicing` (publican como `sales` con `TestcontainersConfiguration.salesTemplate`): un permiso que falte debe fallar en los tests.
- Reprocesar la DLQ: `./scripts/rabbitmq-dlq.sh invoicing requeue` (shovel dinámico) o "Move messages" en la consola, solo tras corregir la causa.

## Cierre de la Saga: resultados de facturación (ADR 0015)

- **Invoicing publica** en `invoicing.events` (outbox propio, V7; copia del de sales: relay, SKIP LOCKED, confirms; `adapter/out/messaging`, `adapter/in/outbox`; `invoicing.publishing.*`, `invoicing.outbox.*`): `InvoiceIssued` (`invoice.issued.v1`), `InvoiceRejected` (`invoice.rejected.v1`), `InvoiceRequestRejected` (`invoice-request.rejected.v1`). Esquemas en `docs/events/invoice-*.v1.schema.json`, validados por los tests de LOS DOS servicios. Nunca `publicUrl` ni `qrUrl`.
- `Invoice.markAsIssued`/`markAsRejected` registran `InvoiceEvent` en `pendingEvents()`; `InvoiceRepository.save` guarda estado + eventos en la MISMA transacción (instante = el del guardado, con el `Clock` del adaptador; el dominio no lee el reloj). `IssueInvoiceService` no cambia. `InvoiceRequestRejected` lo registra `RejectInvoiceRequestUseCase` → `InvoiceEventStore` (sin agregado). Cada `event_type` nuevo exige migración (CHECK) y su serialización en `InvoiceEventMessages` (único lugar con el contrato).
- Se publican eventos de TODAS las facturas (también REST). Sales correlaciona por `referenceCode` y comprueba el documento del cliente (o el `saleId` en `InvoiceRequestRejected`); lo que no es suyo: ack e ignorar.
- **Sales consume** en `adapter/in/messaging` (ACL, regla 12; mismo mecanismo que el ADR 0014): `sales.invoice-results` ← `invoicing.events` (3 routing keys), `sales.retry` → `wait-*`, `sales.requeue`, `sales.dlx` → `.dlq`; `sales.invoice-results.*`. `RecordInvoiceResultUseCase` aplica la tabla del ADR 0015: "el éxito gana y el fallo no deshace"; repetidos = no-op. `Sale.invoiced`/`invoiceFailed` devuelven una venta NUEVA con `InvoicingOutcome`; `SaleRepository.save` con bloqueo optimista (`SaleConcurrentModificationException` → reintento).
- DLQ de invoicing: inválido → `InvoiceRequestRejected` → venta `INVOICE_FAILED` (incluido el vencimiento); técnica → SIN evento, la venta sigue `CONFIRMED` y se reprocesa.
- `GET /api/v1/sales/{id}`: `status`, `invoice {invoiceId, number, cufe, invoicedAt}`, `invoiceFailure {reason, rejectedInvoiceId, failedAt}`.
- Permisos: `sales` configure/write `^sales\..*`, read `^(sales\..*|invoicing\.events)$`. Los tests de sales también ejecutan el script de init real y se conectan como `sales` (publican resultados como invoicing con `TestcontainersConfiguration.invoicingTemplate`); sus colas de prueba llevan el prefijo `sales.`.
- ArchUnit en los dos: regla 11 (AMQP solo en `adapter/in/messaging` y `adapter/out/messaging`; nada de `out/messaging` sale) y regla 12 (el ACL del otro adaptador de mensajería).

## Integración continua (ADR 0016)

- `ci.yml` SIEMPRE arranca; el job `changes` (paths-filter) decide qué servicios probar con `_service.yml`; `ci-result` es el ÚNICO check obligatorio (junto con `gitleaks`). Nunca usar `on.paths` en un workflow cuyo check sea obligatorio: quedaría "Pending" y bloquearía el PR.
- Rutas por servicio en `ci.yml`: invoicing y sales también por `docs/events/**` e `infra/rabbitmq/**`; todos por `.github/workflows/**`, `.github/scripts/**` e `infra/docker-compose.yml`. **Si un servicio empieza a depender de otra carpeta, agregarla al filtro** (y a la tabla del ADR 0016).
- La CI corre `./mvnw -B -ntp verify` en el runner (no `scripts/mvn.sh`): todo test debe pasar sin `.env` ni credenciales; los que las necesitan llevan `@Tag("sandbox")`.
- Acciones fijadas por SHA completo con `# vX.Y.Z`; permisos mínimos por job; `persist-credentials: false`; nada interpolado dentro de `run:` (va por `env`). Validar con `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12`.
- Ningún secreto en los workflows. SonarCloud usa el secreto opcional `SONAR_TOKEN`: sin él el paso se omite (la CI no debe fallar). Las propiedades `sonar.*` y JaCoCo viven en cada pom.
- Imágenes en GHCR solo desde `main` (`sha-<7>` y `latest`), con el `GITHUB_TOKEN`.
- **Imágenes de infraestructura de los tests: una sola fuente, `infra/docker-compose.yml`.** Los tests nunca fijan `postgres:`/`rabbitmq:` en el código: usan `InfraImages.POSTGRES`/`RABBITMQ` (copia en cada servicio, lee el compose); `InfraImagesTest` exige versiones fijadas (mínimo `mayor.menor`, nunca `latest`) e iguales para la misma imagen. Así Dependabot (`docker-compose`) las actualiza y el PR dispara los tests.
- Dependabot no ve la imagen de gitleaks (hook, script, workflow) ni la de Maven en `scripts/mvn.sh`: al cambiar una, cambiar todas sus apariciones.
- `main` protegida (ruleset): se trabaja con ramas y PRs (`gh pr create`, `gh pr merge --squash`); los commits los hace el usuario.

## Convenciones

- Código, clases, paquetes y endpoints en inglés. Documentación y ADRs en español.
- Esquema de base de datos solo con migraciones Flyway (`src/main/resources/db/migration/V<n>__<descripcion>.sql`). Nunca `ddl-auto=update`; se usa `validate`.
- Una migración ya aplicada no se edita: se crea una nueva.

## Comandos útiles (desde la raíz)

```bash
# Tests de un servicio (Maven en contenedor, Testcontainers incluido)
./scripts/mvn.sh invoicing test

# Solo los tests de arquitectura (ArchUnit, ~2 s)
./scripts/mvn.sh invoicing test -Dtest=HexagonalArchitectureTest

# Tras borrar o mover clases, limpiar target/ para no evaluar .class viejos
./scripts/mvn.sh invoicing clean test

# Cualquier otro goal de Maven
./scripts/mvn.sh invoicing -B package -DskipTests

# Tests de catalog y de sales (y solo su ArchUnit)
./scripts/mvn.sh catalog test
./scripts/mvn.sh catalog test -Dtest=LayeredArchitectureTest
./scripts/mvn.sh sales test
./scripts/mvn.sh sales test -Dtest=HexagonalArchitectureTest

# Levantar el entorno (PostgreSQL + RabbitMQ + invoicing + catalog + sales; rabbitmq-init termina en Exited (0))
docker compose --env-file .env -f infra/docker-compose.yml up --build -d

# Estado, logs y apagado
docker compose --env-file .env -f infra/docker-compose.yml ps
docker compose --env-file .env -f infra/docker-compose.yml logs -f invoicing
docker compose --env-file .env -f infra/docker-compose.yml down       # conserva datos
docker compose --env-file .env -f infra/docker-compose.yml down -v    # BORRA datos (re-ejecuta init de PostgreSQL)

# Health de invoicing y de catalog
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8083/actuator/health
curl -s http://localhost:8083/actuator/health/dependencies   # circuito hacia catalog

# Sembrar en catalog los datos de los ejemplos de Swagger de sales (idempotente)
./scripts/seed-catalog.sh

# RabbitMQ: consola http://localhost:15672 (RABBITMQ_ADMIN_USER del .env). Sin consumidores todavía, para VER
# los eventos de sales se usa una cola de depuración temporal (se borra sola tras 1 h sin uso)
./scripts/rabbitmq-debug-queue.sh create   # o peek / delete
# Consumidor de invoicing: colas, esperas y DLQ; reprocesar la DLQ (shovel)
./scripts/rabbitmq-dlq.sh invoicing status   # o sales; y peek / requeue / purge --yes
curl -s http://localhost:8081/actuator/health/dependencies          # messaging: outbox y consumidor de invoicing
curl -s "http://localhost:8081/actuator/metrics/invoicing.sale_confirmed.processed?tag=outcome:issued"
curl -s http://localhost:8083/actuator/health/dependencies          # messaging: outbox y consumidor de sales
curl -s http://localhost:8083/actuator/metrics/sales.outbox.pending

# Swagger UI (API documentada con ejemplos)
# invoicing: http://localhost:8081/swagger-ui.html   catalog: http://localhost:8082/swagger-ui.html
# sales:     http://localhost:8083/swagger-ui.html

# Agregar la base de un servicio nuevo a un volumen con datos (script idempotente, sin down -v)
docker compose --env-file .env -f infra/docker-compose.yml up -d postgres
docker compose --env-file .env -f infra/docker-compose.yml exec postgres bash /docker-entrypoint-initdb.d/01-create-service-databases.sh

# Tests contra el sandbox REAL de Factus (excluidos por defecto; crean 2 facturas y 1 nota crédito por ejecución).
# Docker inyecta el .env con --env-file; nadie lo lee ni lo muestra.
MVN_ENV_FILE=.env ./scripts/mvn.sh invoicing test -Dgroups=sandbox -DexcludedGroups=none

# Levantar con Factus en vez del proveedor falso (o INVOICING_PROVIDER=factus en .env)
INVOICING_PROVIDER=factus docker compose --env-file .env -f infra/docker-compose.yml up --build -d

# Estado de la resiliencia hacia Factus (con INVOICING_PROVIDER=factus)
curl -s http://localhost:8081/actuator/health/providers
curl -s http://localhost:8081/actuator/circuitbreakers
curl -s http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.not.permitted.calls

# Escaneo de secretos de todo el repo
./scripts/check-secrets.sh
```

## Regla de trabajo

- Proyecto de portafolio sin datos ni usuarios en producción: cuando la solución robusta solo proteja datos de prueba o casos hipotéticos, prefiere la solución simple y documenta en el ADR cómo se haría en producción. Aplica rigor completo en arquitectura, modelado del dominio, tests y seguridad. Si dudas, pregúntame.
- Si una decisión técnica importante no está cubierta por un ADR, **preguntar antes de implementarla**.
- **No hacer commits** ni push; los hace el usuario.
- Nunca ejecutes acciones en servicios externos a mi nombre sin que te lo pida explícitamente en ese momento: git push, comandos de gh que creen o modifiquen algo en GitHub (PRs, rulesets, secretos, releases, configuración), ni llamadas a APIs externas con mis credenciales. Única excepción: el sandbox de Factus para spikes y tests `@Tag("sandbox")`, con `send_email` en `false` y sin borrar nada. Leer información pública o del repositorio sí está permitido.
