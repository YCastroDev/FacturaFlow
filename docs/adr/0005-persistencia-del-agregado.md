# 0005. Persistencia del agregado Invoice

## Estado

Aceptado — 2026-10-20

> **Nota posterior (ADR 0009):** las notas crédito son un segundo agregado persistido con los mismos criterios. Insertar una nota sube la versión de su factura con un `UPDATE … WHERE version = ?` (bloqueo optimista sobre la factura), sin cargarla. La columna `edoc_cufe` pasó a `edoc_unique_code` (V6). Las decisiones de este ADR no cambian.

## Contexto

El agregado `Invoice` (ADR 0004) debe guardarse y recuperarse en PostgreSQL (ADR 0003) sin que el dominio sepa que existe una base de datos (ADR 0002). Una factura es un agregado con colecciones anidadas (factura → líneas → impuestos), se modifica después de creada (cambios de estado) y puede ser leída y modificada por dos procesos a la vez. Además, una venta nunca debe facturarse dos veces.

## Decisión

### 1. Spring Data JPA con entidades separadas y mapeo a mano

- Puerto de salida `application.port.out.InvoiceRepository` (Java puro): `Invoice save(Invoice)`, `findById`, `findByReferenceCode`.
- Adaptador en `infrastructure.adapter.out.persistence`: entidades JPA propias (`InvoiceEntity`, `InvoiceLineEntity`, `LineTaxEmbeddable`), un repositorio Spring Data interno y `InvoicePersistenceMapper`, que traduce a mano entre ambos modelos. Todas son package-private: nada fuera del adaptador las ve.
- Los impuestos son un `@ElementCollection` de `@Embeddable`: igual que `LineTax` en el dominio, son valores sin identidad propia.

### 2. Reconstrucción con `Invoice.reconstitute(...)`

Recibe el estado completo (incluidos estado, documento electrónico, motivo de rechazo y versión), aplica las mismas reglas que `create` y exige coherencia: PENDING sin documento ni motivo; ISSUED con documento y sin motivo; REJECTED con motivo y sin documento. `create` no cambió.

### 3. Modelo relacional (migración V2)

- `invoices`: datos del cliente como columnas (snapshot, ADR 0003); `payment_type` (`CASH`/`CREDIT`) + `due_date` con CHECK; documento electrónico como columnas nullable; `reference_code` con `UNIQUE` (idempotencia también en la base).
- `invoice_lines` con `line_number`, e `invoice_line_taxes` con `sort_order`: el orden se guarda porque el dominio compara listas ("IVA, INC" ≠ "INC, IVA").
- Enums como texto con CHECK; dinero `NUMERIC(18,2)`, porcentajes `NUMERIC(5,2)`, cantidades `NUMERIC(14,2)`; nunca `float`.
- `VARCHAR(n)` solo donde el dominio impone ese límite; `TEXT` donde no lo impone, para que la base nunca rechace un valor válido para el dominio.
- Las reglas del dominio se repiten como CHECK (defensa en profundidad), incluida la **coherencia de estado** (la misma de `reconstitute`) y la PK `(line_id, tax_type)`, que impide repetir un tipo de impuesto. La regla "excluida de IVA ⇒ sin impuesto IVA" cruza dos tablas y queda solo en el dominio.
- Nombres de columna: se evitan palabras reservadas o ambiguas: `sort_order` en vez de `position` (reservada en el estándar SQL) e `iva_excluded` en vez de `excluded` (en PostgreSQL `EXCLUDED` es la pseudo-tabla de `ON CONFLICT`; además el nombre nuevo expresa su significado).
- No se guardan totales calculados (subtotal, impuestos, total): se derivan de las líneas, con una sola fuente de verdad. Solo se guarda el total oficial de Factus (ADR 0004, decisión B). Si un reporte los necesita, se agregan con un nuevo ADR.
- Las líneas son **inmutables** después de crear la factura (el dominio no permite cambiarlas): al actualizar, el adaptador solo toca la fila de `invoices`. Si algún día las líneas pudieran cambiar, el adaptador tendrá que sincronizarlas.

### 4. Transacción en el adaptador y bloqueo optimista con versión opaca en el agregado

**El problema: *lost update*.** Dos procesos leen la misma factura (versión 0). El primero la emite y guarda (versión 1). El segundo, con su copia vieja, la rechaza y guarda: sin control, pisa la emisión sin que nadie se entere.

**Por qué la versión vive en el agregado.** Para detectar ese conflicto alguien debe recordar *qué versión se leyó*. Si el adaptador, al guardar, cargara la fila y usara la versión actual, la comparación siempre coincidiría: solo protegería los milisegundos entre esa lectura y el `flush`, no el tiempo entre leer la factura (petición 1) y guardarla (petición 2). Por eso `Invoice` transporta un `version` **opaco**: el dominio no lo interpreta ni lo modifica; `create` lo deja vacío, `reconstitute` lo recibe y el repositorio lo asigna. Es una práctica habitual en DDD (el agregado lleva su token de concurrencia).

- `save` devuelve una **nueva instancia** con la versión actualizada; quien llama debe seguir trabajando con ella (igual que `save` de Spring Data).
- Doble control: (1) el adaptador compara la versión del agregado con la de la base y, si difieren, lanza `InvoiceConcurrentModificationException`; (2) el `UPDATE` lleva `WHERE version = ?` (`@Version`), que cubre una carrera entre esa lectura y el `flush`.
- `save` es `@Transactional` en el adaptador: factura, líneas e impuestos se guardan en una sola transacción atómica.

### Excepciones del puerto

`DuplicateReferenceCodeException` e `InvoiceConcurrentModificationException` viven en `application.exception`: forman parte del **contrato del puerto**, son errores que el dominio no puede detectar solo y no deben exponer tipos de Spring ni Hibernate. El adaptador traduce `DataIntegrityViolationException` (identificando la restricción `uk_invoices_reference_code` por nombre) y `OptimisticLockingFailureException`.

**La trampa del `flush()`.** La violación de `UNIQUE` (y el conflicto de `@Version`) aparece cuando Hibernate envía el SQL: al hacer *flush* o en el *commit*. Si se deja al commit, este ocurre en el proxy transaccional de Spring, **fuera** del método del adaptador, y su `try/catch` nunca ve la excepción: saldría una excepción de Spring hasta el llamador. Por eso el adaptador llama a `flush()` dentro del `try`.

### Carga sin N+1

- `@EntityGraph(attributePaths = {"lines", "lines.taxes"})` en `findWithLinesById` y `findWithLinesByReferenceCode`: factura, líneas e impuestos en **una** consulta con dos `LEFT JOIN`. Se usa también al actualizar, porque `save` devuelve el agregado completo (sin el graph, ese camino generaba 1 consulta de líneas + 1 por cada línea; los tests de conteo lo detectaron).
- **Listas indexadas para evitar `MultipleBagFetchException`.** Hibernate llama *bag* a una `List` sin índice; no puede traer dos *bags* en la misma consulta porque el producto cartesiano haría imposible saber qué filas son duplicadas. Con `@OrderColumn` (`line_number`, `sort_order`) las dos colecciones son listas indexadas, así que el fetch conjunto es válido y además conserva el orden.
- El producto cartesiano (líneas × impuestos) es despreciable: pocas líneas y 1-2 impuestos por línea.
- Los tests verifican el número de sentencias con `Statistics` de Hibernate (activado solo en tests): 1 al cargar; 2 (SELECT + UPDATE) al guardar un cambio de estado.

### Fechas

`ElectronicDocument.validatedAt` pasó de `OffsetDateTime` a `Instant`: `TIMESTAMPTZ` guarda el instante, no el offset, y lo legalmente relevante es el instante; la zona horaria es asunto de presentación.

## Alternativas consideradas

- **Spring Data JDBC:** modelo de agregados muy alineado con DDD y sin *lazy loading*, pero reescribe colecciones completas en cada guardado y el ecosistema (entity graphs, estadísticas, bloqueo) es más limitado. JPA era una decisión previa del proyecto.
- **jOOQ:** SQL tipado y control total; añade generación de código y una dependencia mayor para un modelo que JPA cubre bien.
- **`JdbcClient` (SQL a mano):** máximo control y cero magia, pero todo el mapeo, el control de versiones y la detección de cambios serían código propio.
- **Event Sourcing:** historial completo de la factura; complejidad (proyecciones, versionado de eventos) muy superior a la necesidad actual.
- **JSONB (líneas e impuestos en una columna):** una sola fila por factura; se pierden CHECK, FK y tipos por columna, y las consultas por línea se vuelven más difíciles.
- **Entidades JPA = modelo de dominio (anotar el dominio):** menos código, pero rompe el ADR 0002 y ArchUnit lo prohíbe.
- **Decorador transaccional en `infrastructure.config`:** útil para envolver casos de uso; aquí basta con que el propio adaptador sea transaccional, porque una operación del puerto es una unidad atómica.
- **`@Transactional` en `application`:** prohibido por el ADR 0002 (application es Java puro).
- **Bloqueo pesimista (`SELECT … FOR UPDATE`):** evita conflictos bloqueando filas, pero mantiene bloqueos durante la interacción y no protege entre peticiones HTTP separadas; los conflictos sobre una misma factura serán raros.
- **Versión solo en el adaptador (cargar y comparar con la versión actual):** no detecta el *lost update* entre peticiones (ver decisión 4).
- **Mapa de versiones en el adaptador (`InvoiceId → versión leída`):** adaptador con estado, frágil entre transacciones e hilos.
- **`@BatchSize` / `default_batch_fetch_size`:** evita el producto cartesiano, pero son 1 + 2 consultas y es menos evidente de verificar.

## Consecuencias

**Positivas**
- El dominio sigue libre de JPA; ArchUnit lo garantiza.
- La base protege por sí misma la idempotencia, la coherencia de estado y las reglas principales.
- Conflictos de concurrencia detectados entre peticiones, con una excepción propia y clara.
- Número de consultas acotado y verificado por tests.

**Negativas**
- Más código: entidades, mapper y adaptador, que hay que mantener sincronizados con el dominio y la migración.
- Las reglas viven en dos lugares (dominio y CHECK); un enum nuevo exige migración. Un test de integración guarda todos los valores de los enums para detectar desajustes.
- El agregado lleva un campo (`version`) que el dominio no usa, y quien llama a `save` debe usar la instancia devuelta.
- La carrera entre la comparación de versión y el `flush` (cubierta por `@Version`) no tiene un test propio: es difícil de reproducir de forma determinista.
