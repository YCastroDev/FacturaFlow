# 0009. Notas crédito: agregado separado, saldo por cantidades y bloqueo optimista sobre la factura

## Estado

Aceptado — 2026-10-23. Completa los ADR 0005 y 0006:
- un segundo agregado persistido;
- un puerto de salida nuevo;
- endpoints y filas nuevas en la tabla de errores.

Renombra el CUFE del documento electrónico a un nombre neutro.

> **Nota posterior (2026-10-23):** el adaptador de Factus para notas crédito ya está implementado (ADR 0008, decisión 10) y reemplaza al temporal de la decisión 7. Se verificó contra el sandbox real.

## Contexto

Una factura emitida ante la DIAN no se modifica ni se borra: se corrige con una **nota crédito** que la referencia. FacturaFlow necesita dos correcciones:
- **anular** una factura completa;
- **devolver** parte de ella, por líneas y cantidades.

Los ajustes de precio y los descuentos quedan fuera de alcance.

El [spike 0002](../spikes/0002-factus-credit-notes.md) contra el sandbox mostró lo que más pesa en el diseño:

- **Factus no controla lo acreditado.** Aceptó, sin error, cuatro casos:
  - una devolución de 5 unidades sobre 2 facturadas;
  - un acumulado de 4 sobre 3;
  - la anulación de una factura ya devuelta en parte;
  - una nota con el cliente de otra factura.
  
  **La regla "nunca acreditar más de lo facturado" depende solo de nosotros.**
- **Factus no toma nada de la factura.** Exige el cliente y cada ítem completo: precio, descuento, unidad, código estándar e impuestos. Solo comprueba que la factura exista.
- **La factura no cambia.** Sigue validada y con su total. Solo gana una lista de notas relacionadas, sin montos.
- **Diferencias con la factura:**
  - el `reference_code` tiene la misma idempotencia ciega;
  - la respuesta trae un **CUDE** en lugar de un CUFE;
  - el rango de numeración de notas es obligatorio.

## Decisión

### 1. Motivos

`CreditNoteReason` es un enum propio con dos valores. El código de la DIAN lo traduce el adaptador (ADR 0004, decisión A).

| Valor | Significado | Código DIAN (spike 0002) |
|---|---|---|
| `PARTIAL_RETURN` | Devolución de algunas líneas, o de parte de sus cantidades | `1` |
| `TOTAL_ANNULMENT` | Anulación: acredita **todo lo que aún no se ha acreditado** | `2` |

Los códigos `3` a `6` (rebajas, ajustes de precio y descuentos) quedan fuera de alcance. Agregar uno exige un valor nuevo del enum, una migración (`CHECK`) y su traducción.

### 2. `CreditNote`, un agregado separado

**Identidad y ciclo de vida.** `CreditNote` es un aggregate root propio:
- tiene su `CreditNoteId` (UUID), que será también el `reference_code` que reciba Factus, como en las facturas (ADR 0008);
- referencia su factura por `InvoiceId`, **no** como una asociación;
- sigue el ciclo de vida PENDING → ISSUED | REJECTED, el mismo de la factura, con `CreditNoteStatus`;
- tiene `version()` opaca (ADR 0005).

**La factura no cambia de estado:** sigue ISSUED. Su respuesta en la API muestra lo acreditado y el saldo (decisión 4).

**Contenido.**
- El código de referencia de la devolución (`ReferenceCode`, clave de idempotencia).
- La factura, el motivo y la fecha (la del servidor, en Colombia; nunca anterior a la de la factura).
- Una observación opcional de máximo 250 caracteres.
- El documento electrónico al emitirse, o el motivo de rechazo.

**Líneas: la referencia más una copia (snapshot).** Cada `CreditNoteLine` tiene:
- el número de la línea de la factura, desde 1, como en la API;
- la línea de la factura con la cantidad acreditada, obtenida con `InvoiceLine.withQuantity(q)`.

Motivos de la copia:
- los montos salen **exactamente** del mismo cálculo y redondeo de `InvoiceLine` (ADR 0004);
- la nota se reconstruye y se envía al proveedor sin recalcular desde fuera, y Factus exige los datos completos de cada ítem;
- la factura emitida es inmutable, así que la copia nunca se desincroniza. Es un snapshot, como el del cliente.

**Totales.** Se calculan con las mismas reglas que la factura: suma de los valores ya redondeados de cada línea. Como en la factura, el total oficial del proveedor se guarda con el documento, y `hasOfficialTotalDiscrepancy()` avisa si difiere.

**El cliente no se guarda en la nota.** Es **siempre** el de la factura guardada; nunca viene en la petición. Un campo `customer` en la petición responde 400, porque el campo no existe.

### 3. La regla que cruza agregados

**La regla.** Lo acreditado de cada línea de la factura nunca supera lo facturado, sumando las notas PENDING e ISSUED. Se expresa **en cantidades por línea**, no en dinero:
- **las PENDING cuentan**, porque aún pueden emitirse. Si no contaran, dos notas PENDING creadas a la vez podrían emitirse las dos;
- **las REJECTED no cuentan:** sus cantidades quedan libres.

**Dónde vive.**
- `CreditBalance` (dominio, Java puro) calcula, a partir de la factura y de **todas** sus notas, lo acreditado y lo pendiente de cada línea.
- `CreditNote.create(..., balance, ...)` valida contra esa foto:
  - que la factura esté ISSUED: si no, `InvoiceNotCreditableException`, 409;
  - en una devolución parcial: al menos una línea, sin repetir líneas y sin superar lo pendiente de cada una;
  - en una anulación: no lleva líneas y acredita todo lo pendiente; si no queda nada, responde 422.

**Concurrencia: bloqueo optimista sobre la factura.** Se eligió entre las opciones de la tabla de alternativas.
- `CreditNoteRepository.saveNew(note, hash, invoiceVersionRead)` hace dos cosas en **una** transacción:
  1. `UPDATE invoices SET version = version + 1 WHERE id = ? AND version = ?`, sin cargar la factura;
  2. el `INSERT` de la nota, sus líneas y sus impuestos.
- Si el `UPDATE` no actualiza ninguna fila, lanza `InvoiceConcurrentModificationException` y no inserta nada.

**Por qué funciona.**
- Toda nota nueva pasa por `saveNew`. Emitir o rechazar una nota nunca **aumenta** lo acreditado, así que esas operaciones no tocan la factura.
- El `UPDATE` va primero y toma el bloqueo de la fila. Dos transacciones con la misma versión quedan en fila. En PostgreSQL con `READ COMMITTED`, la segunda reevalúa el `WHERE` tras el commit de la primera, ve la versión nueva y no actualiza nada.
- Una nota que pasa a REJECTED en paralelo solo hace más conservadora a una creación simultánea: puede dar un 422 de más, pero nunca un exceso.

**Reintentos en el caso de uso.** `CreateCreditNoteService` lee la factura y sus notas (transacciones cortas, ADR 0006), valida y guarda. Ante un conflicto vuelve a leer y a validar, hasta **3 intentos**:
- si al revalidar ya no cabe, el dominio responde **422**;
- si los conflictos persisten, propaga el conflicto: **409** `concurrent-modification`.

**Evidencia.** `ConcurrentCreditNotesTest` lanza 8 hilos a la vez sobre la misma factura, con PostgreSQL real. Lo acreditado nunca supera lo facturado:
- de 8 notas de 2 unidades sobre una línea de 3, se crea exactamente una;
- control negativo: con el `UPDATE` de la versión desactivado a mano, se crean las 8 (16 unidades acreditadas de 3) y el test falla.

**Consecuencia aceptada.** La versión de la factura sube con cada nota nueva, aunque su contenido no cambie. Es un token opaco (ADR 0005) y una factura ISSUED no se vuelve a guardar: `IssueInvoiceService` la devuelve sin guardar, así que no hay conflictos espurios.

**Sin defensa en la base.** Un `CHECK` no puede sumar filas de varias tablas. **En producción** se agregaría una defensa en profundidad: una tabla de contadores por línea con `CHECK (credited <= invoiced)`, actualizada en la misma transacción, o un trigger. Aquí no se hace porque duplicaría la regla en SQL para proteger solo datos de prueba (principio de portafolio, `CLAUDE.md`).

### 4. Saldo en la respuesta de la factura

Una factura ISSUED agrega `credits` a su respuesta, en `GET` por id, por código y en la emisión:

| Campo | Significado |
|---|---|
| `issuedTotal` | Total de las notas ISSUED: lo acreditado con validez ante la DIAN |
| `pendingTotal` | Total de las notas PENDING: reservado, aún sin emitir |
| `balance` | **Valor de las cantidades que aún se pueden acreditar**, con el redondeo por línea de siempre |
| `lines[]` | Por línea: `invoicedQuantity`, `creditedQuantity` (PENDING + ISSUED) y `remainingQuantity` |

El saldo **no** es "total − acreditado". Acreditar una línea en varias partes puede sumar centavos de más por el redondeo por línea. Ejemplo verificado en `CreditBalanceTest`:
- la línea es 1,5 × 20.000,01 = 30.000,02;
- acreditada en tres notas de 0,5, da 3 × 10.000,01 = 30.000,03.

Por eso la regla compara cantidades, que no se redondean, y el dinero es información.

### 5. Idempotencia de la creación

Funciona como en las facturas (ADR 0006, decisión 2):
- **Clave:** un `referenceCode` elegido por el cliente, único en toda la tabla de notas (`uk_credit_notes_reference_code`).
- **Huella:** SHA-256 de la petición normalizada, con el prefijo `credit-note-request-v1`. Incluye la factura, el motivo, las líneas (número y cantidad, ordenadas por número) y la observación normalizada. La codificación canónica (`CanonicalText`) es la misma de las facturas.
- **Mismo código y misma huella:** 200 con la nota existente. No se revalida: un reintento de una nota que ya se creó no falla porque el saldo cambió después.
- **Mismo código con otra huella:** 409 `credit-note-reference-code-conflict`.
- **Dos peticiones simultáneas con el mismo código:** decide el `UNIQUE`, y se relee a la ganadora.

### 6. Puerto del proveedor

**Un puerto nuevo.** `ElectronicCreditNoteProvider` está separado de `ElectronicInvoiceProvider` (segregación de interfaces): la emisión de facturas no necesita saber de notas, y los dobles de prueba quedan pequeños.
- `issue(CreditNote, Invoice creditedInvoice)` recibe la factura porque de ella salen el cliente y la referencia al documento original.
- `downloadPdf(CreditNote)` descarga el PDF.

**Lo que se reutiliza de las facturas.**
- `IssuanceResult` (`Issued` | `Rejected`) e `InvoicePdf`.
- `ElectronicInvoiceProviderUnavailableException`, que ahora indica el tipo de documento (`forCreditNote(...)`). El 503 dice "La nota crédito sigue PENDING".

**La emisión sigue el patrón de la factura** (`IssueCreditNoteService`):
- se lee, se llama al proveedor **fuera de toda transacción** y se guarda;
- re-emitir una ISSUED devuelve 200 sin llamar al proveedor;
- una REJECTED da 409;
- una emisión simultánea se resuelve como en el ADR 0006, decisión 5.

### 7. Proveedores

**Falso.** Implementa los dos puertos. La simulación se controla con la **observación de la nota**:
- `SIMULAR_RECHAZO` da `Rejected`;
- `SIMULAR_CAIDA` da 503.

Una emisión falsa tiene el número `FAKE-NC-<referenceCode>`, el CUDE con la marca de 16 ceros, enlaces `.invalid` y un PDF "DOCUMENTO FALSO".

**Factus.** Al principio, `FactusCreditNotesNotYetSupported` respondía a la emisión y al PDF de una nota con una falla técnica (503). Hoy lo reemplaza el adaptador real (ADR 0008, decisión 10):
- `reference_code` = `CreditNoteId`;
- verificación de `reference_code`, del cliente y de `bill.number` antes de marcar la nota emitida;
- `credit-note-numbering-range-id` obligatorio.

### 8. Nombre neutro del código único

- **Dominio:** `ElectronicDocument.cufe` pasa a llamarse `uniqueCode`. Es el CUFE de una factura o el CUDE de una nota; los dos son un SHA-384 en hexadecimal.
- **Base:** la migración V6 renombra `invoices.edoc_cufe` → `edoc_unique_code`. Los `CHECK` de coherencia siguen la columna, y `V6MigrationTest` comprueba que se conserva el dato.
- **API:** los DTO son propios, así que la factura **conserva** `electronicDocument.cufe` y la nota muestra `electronicDocument.cude`. No se rompe la API de facturas, y cada documento usa su término legal.

### 9. Persistencia: migración V6

Sigue los criterios del ADR 0005 (entidades separadas, mapeo a mano, entity graph y bloqueo optimista).

| Tabla | Contenido |
|---|---|
| `credit_notes` | Columnas de la nota, documento electrónico (`edoc_*`), `request_hash` y `created_at` (`DEFAULT now()`) |
| `credit_note_lines` | `sort_order`, `invoice_line_number` (≥ 1) y la copia de la línea, con el `CHECK` de las 8 unidades |
| `credit_note_line_taxes` | Impuestos de cada línea, igual que `invoice_line_taxes` |

**Detalles de `credit_notes`:**
- su FK a `invoices` **no** tiene `ON DELETE CASCADE`: una factura con notas no se borra;
- `created_at` solo sirve para listar en orden de creación; el dominio no lo usa.

**Restricciones (`CHECK` y `UNIQUE`):**
- coherencia de estado, la misma regla que `CreditNote.reconstitute`;
- `UNIQUE (credit_note_id, invoice_line_number)`, porque una nota no repite una línea;
- valores de motivo y de estado.

**Lecturas y escrituras.**
- La nota, o **todas** las de una factura, se cargan en una sola consulta con el entity graph. Los tests lo cuentan con `Statistics`.
- Insertar una nota ejecuta el `UPDATE` de la versión de la factura y los `INSERT` de nota, líneas e impuestos.
- `ix_credit_notes_invoice (invoice_id, created_at)` sirve para leer las notas de una factura al calcular su saldo.

### 10. API REST

| Endpoint | Éxito | Errores |
|---|---|---|
| `POST /api/v1/invoices/{id}/credit-notes` | 201 + `Location` / 200 (reintento) | 400, 404, 409, 422 |
| `GET /api/v1/invoices/{id}/credit-notes` | 200 (lista, en orden de creación; vacía si no hay) | 404 (factura) |
| `GET /api/v1/credit-notes/{id}` | 200 | 400, 404 |
| `POST /api/v1/credit-notes/{id}/issue` | 200 (ISSUED o REJECTED) | 404, 409, 503 |
| `GET /api/v1/credit-notes/{id}/pdf` | 200 `application/pdf` | 404, 409, 503 |

- **Petición:** `referenceCode`, `reason`, `lines[{lineNumber, quantity}]` (solo en `PARTIAL_RETURN`) y `observation`. Bean Validation revisa solo la forma; las reglas responden 422 desde el dominio (ADR 0006, D6).
- **Swagger:** trae dos ejemplos, *Devolución parcial* y *Anulación total*.

**Filas nuevas en la tabla de errores del ADR 0006 (decisión 8):**

| Excepción | HTTP | `type` |
|---|---|---|
| `CreditNoteNotFoundException` | 404 | `credit-note-not-found` |
| `CreditNoteReferenceCodeConflictException` / `DuplicateCreditNoteReferenceCodeException` | 409 | `credit-note-reference-code-conflict` |
| `CreditNoteAlreadyRejectedException` | 409 | `credit-note-already-rejected` |
| `CreditNoteNotIssuedException` (PDF de una nota no emitida) | 409 | `credit-note-not-issued` |
| `CreditNoteConcurrentModificationException` | 409 | `concurrent-modification` |
| `InvoiceNotCreditableException` (dominio: acreditar una factura que no está ISSUED) | 409 | `invoice-not-issued` |
| `InvoiceConcurrentModificationException` tras 3 intentos de crear la nota | 409 | `concurrent-modification` |
| `InvalidCreditNoteException` (p. ej. acreditar más de lo pendiente) | 422 | `business-rule-violation` |

### 11. Tests

- **Dominio:** `CreditNoteTest` y `CreditBalanceTest`. Cubren las reglas, los totales del spike (39.158,33), la acumulación entre notas, las notas PENDING y REJECTED, el ciclo de vida, la reconstrucción y el caso concurrente: dos notas válidas contra la misma foto, y al revalidar la segunda ya no cabe.
- **Casos de uso:** con `InMemoryCreditNoteRepository`, que sube la versión de la factura como el adaptador real. Cubren:
  - idempotencia y carrera del código;
  - un conflicto que aún cabe y uno que ya no;
  - 3 conflictos que dan 409;
  - emisión, PDF y saldo.
- **Persistencia:** `JpaCreditNoteRepositoryAdapterTest` (Testcontainers) cubre el ida y vuelta, el bloqueo optimista sobre la factura y sobre la nota, el número de sentencias y los `CHECK`. `V6MigrationTest` cubre la migración.
- **Concurrencia real:** `ConcurrentCreditNotesTest`, descrito en la decisión 3.
- **Web y punta a punta:**
  - `CreditNoteControllerTest` tiene una prueba por cada fila de la tabla de errores;
  - `CreditNoteApiEndToEndTest` cubre la devolución, el saldo, el PDF, la anulación de lo pendiente, la idempotencia y la simulación;
  - `ProviderCalledOutsideTransactionTest` también cubre las notas.

## Limitaciones conocidas

**Una nota PENDING que nunca se logra emitir bloquea sus cantidades.**
- **El problema:** las notas PENDING cuentan para el saldo (decisión 3), y hoy no existe forma de cancelar una. Si su emisión falla una y otra vez por fallas técnicas, sus cantidades quedan reservadas y no se pueden acreditar con otra nota.
- **Por qué no es trivial cancelarla:** tras un timeout, Factus pudo haber recibido y validado la nota ante la DIAN sin que nos llegara la respuesta. Cancelarla localmente podría liberar cantidades que ya están acreditadas de verdad, y entonces sí se acreditaría de más.
- **Cómo se resolvería en producción:**
  1. **Cancelación local verificada.** Un estado nuevo `CANCELLED`, que no cuenta para el saldo. Solo se permitiría tras **confirmar con Factus**, consultando por `reference_code` (el `CreditNoteId`), que la nota no existe allá.
     - Si existe y está validada, se marca ISSUED con su documento, en lugar de cancelarla.
     - Si existe pero está pendiente en Factus (el 409 de la documentación), no se cancela.
     - La consulta por `reference_code` existe para facturas (spike 0001, sección 8); para notas falta verificarla.
  2. **Reconciliación periódica**, el mismo mecanismo previsto para la doble escritura del ADR 0006 (decisión 5): un proceso que recorre las notas PENDING antiguas, consulta a Factus y las resuelve (ISSUED, o cancelables) sin intervención manual.
- **Mitigación (ADR 0010, ya implementada):** cada emisión hace hasta 3 intentos con backoff ante timeouts, errores de red, 5xx, 429 y el 409 "pendiente". La mayoría de las fallas técnicas son transitorias, así que la nota termina ISSUED en la misma petición en lugar de quedar PENDING.
  - **Timeouts:** el caso más peligroso, un timeout en el que Factus **sí** validó la nota, también se resuelve. El reintento envía el mismo `reference_code`, Factus devuelve la nota existente (idempotencia ciega) y la verificación de identidad confirma que es la nuestra.
  - **Circuito abierto:** no se intenta nada y la nota sigue PENDING sin riesgo, porque nada salió hacia Factus.
  - **Lo que queda:** una nota PENDING tras una caída larga sigue bloqueando sus cantidades hasta que alguien reintente su emisión. La cancelación verificada y la reconciliación siguen pendientes y llegarán con RabbitMQ.
- **Hoy:** no se implementa. En un proyecto de portafolio solo afectaría a datos de prueba, y la solución correcta requiere la consulta a Factus de la fase siguiente.

**Otras limitaciones:**
- La DIAN de habilitación no valida las cantidades acreditadas (spike 0002, sección 5). En producción podría ser más estricta, pero no se puede comprobar en el sandbox. Nuestra regla es más estricta que lo observado.
- La regla del saldo no tiene defensa en la base (decisión 3).

## Alternativas consideradas

**Para la regla que cruza agregados (decisión 3):**

| Opción | Por qué no |
|---|---|
| Bloqueo pesimista (`SELECT … FOR UPDATE` de la factura) | La validación tendría que correr **dentro** de la transacción del adaptador, con la regla pasada como callback. Mete lógica de negocio en la persistencia y choca con "transacciones solo en persistence" (ADR 0006). |
| Restricción en la base (tabla de contadores + `CHECK`, o un trigger) | Duplica la regla en SQL. El contador se desincroniza si alguien olvida descontar al rechazar, y el trigger se escribe en PL/pgSQL. Queda como defensa en profundidad para producción. |
| Contadores de lo acreditado dentro de `Invoice` | Es la solución clásica de DDD (la regla dentro de un agregado). Pero modifica dos agregados por transacción, cambia la factura al rechazarse una nota (choca con "la factura no cambia") y guarda un dato que se puede calcular de las notas. |
| Transacción `SERIALIZABLE` o un *advisory lock* | Tiene el mismo problema que el bloqueo pesimista (hay que validar dentro de la transacción), y obliga a reintentar ante fallas de serialización. |

**Otras decisiones:**
- **Contar solo las notas ISSUED:** dos notas PENDING simultáneas podrían emitirse las dos y acreditar de más. Se descartó.
- **Header `Idempotency-Key`:** sería un segundo mecanismo, distinto del que ya usan las facturas.
- **Ampliar `ElectronicInvoiceProvider`:** obligaría a todos sus dobles de prueba a implementar notas que no usan.
- **Guardar solo el número de línea y la cantidad, y recalcular cargando la factura:** el agregado no se bastaría a sí mismo para calcular sus totales ni para enviarse al proveedor.
- **Reutilizar `InvoiceStatus` para la nota:** acoplaría los dos ciclos de vida; un estado futuro de la nota (p. ej. `CANCELLED`) no aplica a la factura.
- **Mostrar el saldo como total − acreditado:** puede diferir en centavos de lo que de verdad queda (decisión 4), e incluso dar negativo.

## Consecuencias

**Positivas**
- Nunca se acredita más de lo facturado, ni con peticiones simultáneas. Está probado con concurrencia real y con un control negativo, aunque Factus no lo controle.
- La regla vive en el dominio (Java puro), y la concurrencia usa la versión que ya existía, sin bloqueos retenidos ni transacciones en los casos de uso.
- Las notas usan exactamente el mismo cálculo que las facturas, y la API de facturas no se rompe.

**Negativas**
- La versión de una factura sube con cada nota nueva aunque su contenido no cambie.
- Una nota PENDING que nunca se emite bloquea sus cantidades hasta que exista la cancelación verificada (ver las limitaciones).
- Con muchas notas simultáneas sobre la misma factura, algunas peticiones pueden recibir 409 tras 3 intentos y deben reintentarse.
- Las líneas de la nota duplican datos de la factura (snapshot).
