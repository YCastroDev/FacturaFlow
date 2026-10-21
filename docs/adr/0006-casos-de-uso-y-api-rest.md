# 0006. Casos de uso y API REST de invoicing

## Estado

Aceptado — 2026-10-21

## Contexto

El servicio ya tiene dominio (ADR 0004) y persistencia (ADR 0005), pero nadie puede usarlo: falta exponer por HTTP la creación, la consulta y la emisión de facturas. Esto plantea cinco problemas:

- **Reintentos:** un cliente que pierde una respuesta reintenta, y una venta nunca debe facturarse dos veces.
- **Emisión:** depende de un sistema externo (Factus) que puede rechazar la factura o simplemente no responder.
- **Transacciones:** la llamada a ese sistema no debe mantener una transacción abierta.
- **Concurrencia:** dos emisiones simultáneas de la misma factura deben resolverse de forma predecible.
- **Errores:** deben ser útiles para el cliente, pero sin filtrar detalles internos.

El adaptador real de Factus llega en la siguiente tarea. Por ahora la emisión usa un proveedor falso detrás del mismo puerto, y los casos de uso no deben cambiar cuando se reemplace.

## Decisión

### 1. Casos de uso en Java puro, registrados desde `infrastructure.config`

- **Puertos de entrada** en `application.port.in`: `CreateInvoiceUseCase`, `GetInvoiceUseCase` e `IssueInvoiceUseCase`, con sus comandos y resultados como records.
- **Implementaciones** en `application.service`, sin anotaciones. `UseCaseConfiguration` las registra como beans.
- **Reloj:** `java.time.Clock` inyectado, con zona `America/Bogota` (`invoicing.time-zone`). La fecha de emisión legal es la de Colombia, no la del servidor: un contenedor en UTC va cinco horas adelante.
- **Comandos:** tipos simples (String, BigDecimal) y enums del dominio. Los value objects se construyen **dentro** del caso de uso, así que toda regla de negocio se valida allí.
- **Resultados (D3):** envuelven el agregado (`CreateInvoiceResult(Invoice, created)`, o `Invoice` directamente). El adaptador REST **lee** el agregado para construir su DTO y nunca lo serializa. Se descartó un *read model* propio porque habría sido una tercera copia casi idéntica del DTO. El riesgo de que el adaptador llame a `markAs…` es teórico: sin un `save`, ese cambio no tiene efecto.

### 2. Idempotencia de la creación con huella de la petición (D1, D2)

`referenceCode` es la clave de idempotencia:

| Situación | Respuesta |
|---|---|
| Código nuevo | crea → **201** + `Location` |
| Mismo código, misma huella | devuelve la existente → **200** (no crea otra) |
| Mismo código, otra huella | `ReferenceCodeConflictException` → **409** |

**Qué entra en la huella.** Criterio: dos peticiones son iguales si producirían la misma factura, salvo el id y la fecha de emisión.

- **Entra:**
  - cliente: tipo y número de documento, DV, razón social, nombres, email;
  - por línea: código, nombre, cantidad, precio, % descuento, unidad, `ivaExcluded` y los impuestos (tipo y tarifa);
  - medio de pago;
  - forma de pago, con su `dueDate`;
  - observación.
- **No entra:**
  - `referenceCode`: es la propia clave;
  - la fecha de emisión: la pone el servidor, y un reintento al día siguiente debe dar 200, no 409;
  - el id.

**Cómo se normaliza.** La huella se calcula sobre los **value objects ya construidos**, no sobre el JSON. La normalización la define el dominio, que es una sola fuente de verdad: escala 2 (`"1"` = `"1.00"`), textos sin espacios en los extremos y textos vacíos iguales a `null`.

- Las líneas **conservan su orden**, porque definen la numeración del documento.
- Los impuestos de cada línea **se ordenan por tipo**, porque su orden no significa nada.
- El email **distingue mayúsculas**, porque el dominio lo guarda tal cual.

**Cómo se codifica.** El texto canónico empieza con el prefijo de versión `invoice-request-v1`. Cada valor va precedido de su longitud (`len:valor;`) y `null` se escribe `~;`, así ninguna concatenación es ambigua: `"A;B"` no se confunde con `"A"` + `"B"`. Sobre ese texto se calcula SHA-256 en hexadecimal (`InvoiceRequestHasher`, con `java.security`, sin librerías). La migración V3 agrega `invoices.request_hash` (`TEXT NOT NULL` con `CHECK ^[0-9a-f]{64}$`).

**El orden importa.** El flujo es:

1. construir los value objects;
2. calcular la huella;
3. buscar por código: si existe, comparar huellas;
4. solo si no existe, `Invoice.create` con la fecha del reloj, y `saveNew`.

La comparación se resuelve **antes** de `create` porque `create` valida que el vencimiento sea posterior a "hoy": un reintento legítimo de una venta a crédito podría fallar con 422 al día siguiente.

**Peticiones simultáneas.** Si dos peticiones pasan a la vez la consulta del paso 3, el `UNIQUE` de la base hace fallar a la segunda con `DuplicateReferenceCodeException`. El caso de uso relee a la ganadora y aplica la misma regla de huellas (200 o 409).

**La huella vive fuera del dominio (D2).** Es metadato de la petición, no un dato de negocio. Por eso el puerto distingue:

- `saveNew(Invoice, RequestHash)`: solo inserta;
- `save(Invoice)`: solo actualiza y exige versión;
- `findStoredByReferenceCode(...)`: devuelve la factura junto con su huella (`StoredInvoice`).

Pasar una factura al método equivocado lanza `IllegalArgumentException`.

### 3. Emisión: resultado sellado frente a excepción técnica

El puerto de salida `ElectronicInvoiceProvider.issue(Invoice)` separa dos tipos de respuesta:

- **Respuesta de negocio:** `sealed interface IssuanceResult` = `Issued(ElectronicDocument)` | `Rejected(String reason)`. Que la DIAN rechace una factura es un **resultado válido**, no un error. Al ser *sealed*, el `switch` del caso de uso es exhaustivo y el compilador avisa si aparece un caso nuevo.
- **Falla técnica** (proveedor caído, timeout, respuesta ilegible): es una **excepción**, `ElectronicInvoiceProviderUnavailableException`, en `application.exception`. Mezclarla con los resultados obligaría a cada llamador a tratar "no sé qué pasó" como si fuera una respuesta.

| Estado | Resultado del proveedor | Efecto | HTTP |
|---|---|---|---|
| PENDING | `Issued` | ISSUED, se guarda | 200 |
| PENDING | `Rejected` | REJECTED con motivo, se guarda | 200 (con la factura rechazada) |
| PENDING | falla técnica | nada se guarda; sigue PENDING | 503 + `Retry-After: 30` |
| ISSUED | (no se llama) | se devuelve tal cual | 200 |
| REJECTED | (no se llama) | `InvoiceAlreadyRejectedException` | 409 |

### 4. El proveedor se llama fuera de toda transacción

Una transacción abierta mientras se espera a un sistema externo retiene una conexión del pool y posibles bloqueos durante segundos. Con la arquitectura actual esto no puede pasar:

- Las **únicas** transacciones son las de los métodos del adaptador de persistencia, y cada una termina al salir del método.
- Los casos de uso son Java puro (ADR 0002), así que no pueden ser `@Transactional`.
- `UseCaseConfiguration` no los envuelve en ninguna transacción.
- El controlador no es transaccional y `open-in-view` está en `false`.

La secuencia de emisión es: `findById` (tx 1) → `provider.issue` (sin tx) → `markAs…` → `save` (tx 2).

Se verifica de dos formas:

- **Estática (D8):** una regla nueva de ArchUnit prohíbe depender de `org.springframework.transaction..` o `jakarta.transaction..` fuera de `infrastructure.adapter.out.persistence`.
- **En ejecución:** `ProviderCalledOutsideTransactionTest` usa un proveedor que anota `TransactionSynchronizationManager.isActualTransactionActive()` en el momento de la llamada. Tiene un control positivo que demuestra que la sonda sí detecta una transacción cuando la hay.

### 5. Emisiones simultáneas (D4)

Si dos peticiones emiten la misma factura a la vez, **las dos llaman al proveedor**. El bloqueo optimista (ADR 0005) hace fallar el `save` de la segunda con `InvoiceConcurrentModificationException`. Entonces el caso de uso relee la factura:

- si quedó ISSUED y a esta petición también se la emitieron, el resultado es equivalente: responde **200** con la factura guardada. El documento propio se descarta y se registra un WARN con su número, para conciliación;
- en cualquier otro caso responde **409** (`concurrent-modification`).

La protección real contra dos documentos ante la DIAN para la misma venta debe venir de la **idempotencia del proveedor por `reference_code`**. Hay que verificarla en la tarea del adaptador de Factus.

**Limitación conocida: doble escritura (*dual write*).** Emitir escribe en dos sistemas sin una transacción común: Factus/DIAN y nuestra base. Si el proveedor emite pero nuestro `save` falla (base caída, proceso que muere, conflicto), el documento existe ante la DIAN pero no está registrado aquí. No es un caso raro de concurrencia: es inherente a escribir en dos sistemas.

**Solución prevista: el patrón Outbox más una reconciliación consultando a Factus.**

- **Outbox:** la intención de emitir se registra en la misma transacción que el cambio de estado (por ejemplo, PENDING → "emisión solicitada" más una fila en una tabla *outbox*). Un proceso aparte la ejecuta y reintenta.
- **Reconciliación:** las facturas que quedaron en un estado intermedio o PENDING demasiado tiempo se consultan en Factus por `reference_code`. Si allí ya existe el documento, se registra localmente.

La alternativa descartada por ahora, un estado `ISSUING` para "reservar" la factura antes de llamar al proveedor, evita la doble llamada pero no resuelve la doble escritura: necesita igualmente la reconciliación para las facturas que queden atascadas.

### 6. Proveedor falso

`FakeElectronicInvoiceProvider` está en `infrastructure.adapter.out.provider.fake`. Se activa con `invoicing.provider=fake`, que es el valor por defecto en `application.yml` y se puede sobrescribir con `INVOICING_PROVIDER`. Un valor sin implementación deja la aplicación sin bean del proveedor y el arranque falla a propósito. Las reglas son deterministas y dependen de la observación de la factura:

| La observación contiene | Resultado |
|---|---|
| `SIMULAR_CAIDA` (tiene prioridad) | falla técnica → 503 |
| `SIMULAR_RECHAZO` | `Rejected` con un motivo de ejemplo |
| ninguna | `Issued` con datos **claramente falsos** |

En una emisión falsa:

- el número es `FAKE-<referenceCode>`;
- el CUFE empieza con **16 ceros** y sigue con caracteres derivados del id. La marca son ceros porque el dominio solo admite un CUFE hexadecimal de 96 caracteres;
- la URL del PDF está bajo `https://fake-provider.invalid/`, un dominio reservado por el RFC 2606 que nunca resuelve;
- la fecha de validación es la del reloj;
- el total oficial es igual al total calculado.

Al arrancar registra un WARN visible. Las palabras de simulación están documentadas en Swagger y en el README.

### 7. API REST

| Método y ruta | Éxito | Errores |
|---|---|---|
| `POST /api/v1/invoices` | 201 + `Location` / 200 (reintento) | 400, 409, 422 |
| `GET /api/v1/invoices/{id}` | 200 | 400 (no es UUID), 404 |
| `GET /api/v1/invoices?referenceCode=…` | 200 (un objeto, no una lista: el código es único, D7) | 400 (falta el parámetro), 404 |
| `POST /api/v1/invoices/{id}/issue` | 200 | 404, 409, 503 |

**DTOs y enums propios.** Los DTOs son records propios del adaptador, y también los enums (`ApiDocumentType`, `ApiUnitOfMeasure`…). Renombrar algo en el dominio no rompe el contrato público. Los `switch` exhaustivos en los dos sentidos hacen que un valor nuevo en cualquier lado no compile hasta mapearlo.

**Contenido de la respuesta.** Incluye los montos calculados por el dominio:

- por línea: bruto, descuento, base, impuestos y total;
- totales: subtotal, descuento, desglose por tipo de impuesto, impuesto total y total;
- `electronicDocument`, solo si está emitida, con `officialTotalDiscrepancy`;
- `rejectionReason`, solo si fue rechazada.

**Frontera de Bean Validation (D6).** El DTO valida solo la **forma**:

- presencia de los campos obligatorios, con `@Valid` en cascada;
- tipos, que resuelve Jackson;
- topes **anti-abuso** que no son reglas de negocio: 500 líneas, 10 impuestos por línea, 1000 caracteres por texto;
- `dueDate` prohibido con `CASH`.

Las reglas de negocio (longitudes exactas, formato del código, DV del NIT, positivos, decimales, "al menos una línea", vencimiento posterior a la emisión) **no se duplican**: las valida el dominio y responden 422. `taxes` es obligatorio aunque sea `[]`, para que una línea sin IVA nunca sea un olvido. `discountRate` (0) e `ivaExcluded` (`false`) son opcionales. `spring.jackson.deserialization.fail-on-unknown-properties=true`: un campo mal escrito da 400 en vez de ignorarse sin aviso, cosa que además alteraría la huella.

### 8. Errores con Problem Details (RFC 9457) (D5)

`ProblemDetailsHandler` (`@RestControllerAdvice`, hereda de `ResponseEntityExceptionHandler`) responde siempre `application/problem+json`. Cada problema lleva:

- un `type` estable `urn:facturaflow:problem:<slug>`: el RFC admite URIs no navegables y no usamos un dominio que no es nuestro;
- un `title` en español;
- `instance`.

| Excepción | HTTP | `type` |
|---|---|---|
| Bean Validation (`MethodArgumentNotValidException`) | 400 + `errors[{field, message}]` | `invalid-request` |
| JSON ilegible, tipo o enum inválido, campo desconocido | 400 + `field` (solo la ruta) | `malformed-request` |
| Parámetro con tipo inválido (id que no es UUID) | 400 | `invalid-parameter` |
| Falta un parámetro | 400 | `missing-parameter` |
| `InvoiceNotFoundException` | 404 | `invoice-not-found` |
| `ReferenceCodeConflictException` / `DuplicateReferenceCodeException` | 409 | `reference-code-conflict` |
| `InvoiceAlreadyRejectedException` | 409 | `invoice-already-rejected` |
| `InvoiceConcurrentModificationException` | 409 | `concurrent-modification` |
| `InvalidInvoiceStateTransitionException` | 409 | `invalid-state-transition` |
| Otra `DomainException` | 422 | `business-rule-violation` |
| `ElectronicInvoiceProviderUnavailableException` | 503 + `Retry-After: 30` | `provider-unavailable` |
| Cualquier otra excepción | 500 | `internal-error` |

Solo se exponen como `detail` los mensajes escritos para personas: los del dominio y los de las excepciones propias de la aplicación. Nunca se exponen trazas ni mensajes de Jackson, Spring o Hibernate. Los mensajes técnicos (como la causa de una caída del proveedor) van solo al log.

### 9. Documentación con springdoc-openapi

`springdoc-openapi-starter-webmvc-ui` **3.1.1** es la línea 3.x, la compatible con Spring Boot 4, y se construye sobre Boot 4.1.0. Como Spring Boot no la gestiona, se fija en el pom.

- Swagger UI en `/swagger-ui.html` y el documento en `/v3/api-docs`.
- Las anotaciones (`@Operation`, `@ApiResponse`, `@Schema`) viven solo en el adaptador REST.
- Hay dos ejemplos de petición (`ApiExamples`, text blocks): **contado con IVA** y **crédito con IVA + INC** (una moto de más de 200 cc, que lleva los dos impuestos en la misma línea). Un test de punta a punta envía los dos ejemplos para garantizar que siguen siendo válidos.

### 10. Estrategia de tests

| Nivel | Herramientas | Qué cubre |
|---|---|---|
| Casos de uso | JUnit + AssertJ, `InMemoryInvoiceRepository` y `StubElectronicInvoiceProvider` (sin Spring ni base) | Todos los caminos de idempotencia y de emisión. El repositorio en memoria respeta el contrato (UNIQUE, versión, bloqueo optimista, copias) y tiene *hooks* que reproducen las carreras de forma determinista. |
| Huella | JUnit parametrizado | Equivalencias de formato; que cambiar **cada** campo cambia la huella; ambigüedad de la concatenación. |
| Capa web | `@WebMvcTest` + `MockMvcTester`, casos de uso con `@MockitoBean` | Códigos HTTP, `Location`, forma del JSON, cada fila de la tabla de errores, que no se filtren mensajes internos. En Boot 4 requiere `spring-boot-starter-webmvc-test`. |
| Punta a punta | `@SpringBootTest(RANDOM_PORT)` + `RestTestClient` + Testcontainers | crear → consultar → emitir; rechazo; caída; doble creación; creación simultánea; los ejemplos de Swagger; publicación de OpenAPI. |
| Transacciones | `@SpringBootTest` con un proveedor-sonda | El proveedor se llama sin transacción (con control positivo). |
| Arquitectura | ArchUnit | La regla nueva de transacciones, más las del ADR 0002. |

## Alternativas consideradas

- **Huella sobre el JSON crudo:** más simple, pero `1` y `1.00`, o un espacio extra, producirían 409 para la misma venta. Además duplicaría la normalización fuera del dominio.
- **No guardar la huella y comparar contra la factura guardada:** posible, porque todos los campos de la huella se persisten. Pero ata la comparación al modelo de persistencia y no deja registro de *qué* se aceptó.
- **Huella como campo opaco en `Invoice` (como `version`):** el puerto no habría cambiado, pero mete en el agregado un concepto de la API que no es de negocio.
- **Cabecera `Idempotency-Key` separada del `referenceCode`:** es el estándar emergente para APIs genéricas, pero aquí la propia venta ya tiene un identificador natural único. Una segunda clave permitiría facturar dos veces la misma venta con claves distintas.
- **Rechazo como excepción:** obligaría a usar excepciones para el flujo normal y a "recuperar" la factura en el `catch` para guardarla REJECTED.
- **Falla técnica como tercer caso del resultado sellado:** cada llamador tendría que tratar "no sé qué pasó" como una respuesta, y se perdería la distinción entre reintentable y definitivo.
- **`@Transactional` en el caso de uso, o un decorador transaccional:** la llamada al proveedor quedaría dentro de la transacción. Además lo prohíbe el ADR 0002.
- **Estado `ISSUING` antes de llamar al proveedor:** ver la decisión 5; se aplaza.
- **`GET ?referenceCode` que devuelve una lista:** semántica REST clásica, pero obliga al cliente a desempaquetar un único elemento.
- **Reutilizar los enums del dominio en los DTOs:** menos código, pero acopla el contrato público a los nombres internos.
- **Validar en el DTO las mismas reglas que el dominio:** daría 400 con el campo exacto, pero duplica reglas que acabarían divergiendo.
- **`type` de Problem Details `about:blank`:** válido, pero obliga a los clientes a distinguir los errores por el texto.

## Consecuencias

**Positivas**
- Reintentar una creación es seguro, también con peticiones simultáneas, y el comportamiento está probado en los tres niveles.
- Cambiar el proveedor falso por el de Factus no toca los casos de uso: solo agrega un adaptador y un valor de `invoicing.provider`.
- Una llamada lenta al proveedor nunca retiene una conexión de base de datos, y ArchUnit impide que alguien lo rompa sin darse cuenta.
- Los errores son predecibles (`type` estable) y no filtran detalles internos.

**Negativas**
- **Doble escritura (*dual write*)** entre Factus y nuestra base. Pendiente: patrón Outbox más una reconciliación consultando a Factus (decisión 5).
- Dos emisiones simultáneas llaman dos veces al proveedor; se depende de su idempotencia por `reference_code`.
- Si alguna vez cambia el formato de la huella (v2), las facturas guardadas con v1 darían 409 al reintentarse. Habría que guardar la versión del formato o conservar el cálculo v1.
- Hay más código de mapeo: enums de la API duplicados con `switch` en los dos sentidos.
- Un `referenceCode` con formato inválido en `GET ?referenceCode=` responde 422 (regla general del dominio) y no 400.
- `save` devuelve el instante de validación truncado a microsegundos, la precisión de `TIMESTAMPTZ`, para que la factura devuelta sea idéntica a la que se relee. Lo detectó un test de punta a punta.
- No hay un test que fuerce de forma determinista la carrera del `UNIQUE` sobre HTTP real. La cubre de forma determinista el test del caso de uso; el de punta a punta con peticiones simultáneas solo comprueba que el resultado es correcto en cualquiera de los dos caminos.
