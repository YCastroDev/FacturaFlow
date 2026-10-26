# 0011. Servicio catalog: arquitectura en capas, datos maestros duplicados a propósito y concurrencia con ETag

## Estado

Aceptado — 2026-10-23. **No reemplaza al ADR 0002**: invoicing sigue siendo hexagonal. Este ADR solo decide la arquitectura de catalog.

## Contexto

`catalog` es el segundo servicio del monorepo. Es dueño de los **productos** y los **clientes** de la pyme. Más adelante, `sales` lo consultará por HTTP (síncrono) al crear una venta y le pasará a invoicing una copia de esos datos; invoicing no cambia en esta tarea.

El servicio es casi un CRUD:
- no llama a sistemas externos ni tiene procesos en segundo plano;
- sus reglas son de **coherencia del dato maestro**: formato del documento, DV del NIT, tipo de persona, impuestos…

El patrón hexagonal del ADR 0002 pagaba en invoicing por dos razones: tiene un proveedor externo intercambiable (falso o Factus) y la llamada a ese proveedor debe quedar fuera de la transacción. Aquí no existe ninguna de las dos. Sus puertos, adaptadores y mapeos dobles solo agregarían ceremonia.

Los ADR 0001 y 0003 siguen vigentes:
- los servicios no comparten código;
- cada servicio tiene su base y su usuario: `catalog_db` / `catalog_user`.

## Decisión

### 1. Capas: web → service → repository, sobre model

Paquetes bajo `com.facturaflow.catalog`:

| Paquete | Contenido | Puede usar |
|---|---|---|
| `web` (+ `web.dto`) | Controladores, `ApiMapper`, `IfMatch`, `ProblemDetailsHandler`; DTOs y enums propios de la API | `service`, `model` |
| `service` | `ProductService`, `CustomerService` (`@Service`, `@Transactional`) y sus excepciones | `repository`, `model` |
| `repository` | Interfaces de Spring Data JPA y las especificaciones de búsqueda (`CatalogSearch`) | `model` |
| `model` | Entidades JPA `Product` y `Customer`, value objects que se validan solos (`ProductDetails`, `ProductTax`, `CustomerDocument`, `CustomerProfile`), enums, `NitCheckDigit` | solo Java y `jakarta.persistence` |
| `config` | `Clock`, OpenAPI | — |

Reglas de ArchUnit (`LayeredArchitectureTest`):
1. Las dependencias van hacia abajo: ninguna capa conoce a una superior.
2. Los controladores no usan repositorios.
3. `@Transactional` solo en `service`. Aquí la transacción es el caso de uso completo: leer, comprobar la versión y guardar. En invoicing está prohibida en los casos de uso porque envolvería la llamada a Factus (ADR 0006); en catalog no hay llamadas externas.
4. `model` no usa Spring, Jackson ni Hibernate, solo JPA estándar. A diferencia del dominio puro de invoicing, en capas se aceptan las anotaciones de JPA en las entidades.
5. Los DTOs no exponen el modelo, y ningún controlador devuelve una entidad.
6. Cada estereotipo vive en su capa (`@RestController`, `@Service`, `Repository`).
7. Ningún código de otro servicio: nada de `com.facturaflow.*` fuera de catalog.
8. Sin ciclos entre paquetes.

Las reglas del dato viven en el modelo, no en el servicio. Así un controlador o un test no pueden construir un producto inválido.

### 2. Duplicación deliberada (ADR 0001)

Estas piezas son **copias** de invoicing, con los mismos nombres de valores y las mismas reglas, y cada copia tiene su test:

| En catalog | Copia de (invoicing) |
|---|---|
| `NitCheckDigit` (módulo 11 de la DIAN) | `NitCheckDigit`, con los mismos 8 NIT públicos en el test |
| `CustomerDocument` | `IdentificationDocument` (formatos numérico 3–15 y alfanumérico 3–20, DV) |
| `CustomerProfile` | Reglas de `CustomerSnapshot`, `FiscalProfile`, `Municipality` y `PhoneNumber` |
| `ProductDetails`, `ProductTax` | Reglas de la línea de factura: precio > 0 con 2 decimales, impuestos sin repetir, excluido sin IVA, tarifa 0–100 |
| `DocumentType`, `PersonType`, `TaxScheme`, `FiscalResponsibility`, `TaxType`, `UnitOfMeasure` | Los enums del mismo nombre |

Las APIs de catalog e invoicing usan los **mismos nombres de enum y de campo** (`documentType`, `checkDigit`, `ivaExcluded`, `taxes[].rate`…). Así sales puede pasar los datos tal cual. Es un contrato alineado, no una librería compartida.

**Regla de mantenimiento:** quien cambie una de estas reglas en un servicio revisa la copia en el otro. El costo es aceptable: son unas 8 reglas estables, de normas de la DIAN y del DANE. Compartirlas en una librería acoplaría los despliegues (ADR 0001).

### 3. Qué valida catalog y qué queda en invoicing

Catalog valida **todo lo necesario para que un dato maestro se pueda facturar**, con las mismas reglas que invoicing. Invoicing **sigue siendo la autoridad final**: revalida la copia que recibe con cada venta.

| Regla | catalog | invoicing |
|---|---|---|
| Formato del documento, DV del NIT, tipo de persona, situación tributaria, municipio DIVIPOLA (formato y departamento), teléfono, email, largos | sí (copia) | sí |
| Precio > 0 con máximo 2 decimales, impuestos sin repetir, tarifa 0–100, excluido sin IVA, las 8 unidades | sí (copia) | sí |
| Unicidad del código y del documento | **solo catalog** | — (la factura guarda una copia) |
| Cantidades, descuentos, totales, forma de pago, fechas, idempotencia de la venta | — | **solo invoicing** |
| Catálogo completo de municipios, tarifas permitidas por la DIAN, numeración | — | lo decide el proveedor (Factus) |

Duplicar estas reglas solo adelanta el error: un cliente mal capturado falla al guardarlo en catalog, frente a quien lo escribió, y no semanas después al facturar.

Un precio con más de 2 decimales responde **422**: no se redondea un dato maestro. Invoicing lo rechazaría igual.

### 4. Modelo y persistencia (migración V1)

- **Productos:**
  - tabla `products`;
  - impuestos en `product_taxes`, cuya PK `(product_id, tax_type)` impide repetir un tipo de impuesto.
- **Clientes:**
  - tabla `customers`, con `UNIQUE (document_type, document_number)`;
  - responsabilidades en `customer_fiscal_responsibilities`.
- **CHECK de defensa en profundidad:** repiten las reglas de las entidades. Lo que cruza filas o tablas lo validan solo las entidades: "excluido sin IVA", "NOT_RESPONSIBLE va sola" y el valor del DV.
- **Código único sin distinguir mayúsculas:** índice único sobre `lower(code)`. El código se guarda como se escribió, y la consulta por código usa `lower()` para aprovechar el índice.
- **Número de documento:** se guarda sin espacios y en mayúsculas, porque los pasaportes son alfanuméricos.
- **Colecciones:** son `@ElementCollection` EAGER, con `hibernate.default_batch_fetch_size: 100`. Una página de 20 productos son **3 sentencias**: página, conteo y los impuestos de toda la página. Lo verifica `QueryCountIntegrationTest`. El servicio devuelve entidades ya cargadas y la capa web las lee fuera de la transacción (`open-in-view: false`).
- **Unicidad ante carreras:** el servicio comprueba primero si el código o el documento ya existen (409). Si dos peticiones pasan la comprobación a la vez, el índice único rechaza la segunda. El servicio reconoce esa violación por el **nombre** de la restricción y responde también 409, nunca 500. `ConcurrencyIntegrationTest` lo prueba con 8 creaciones simultáneas.

### 5. Identidad inmutable; desactivar en vez de borrar

- **El código del producto y el documento del cliente no cambian.** Un `PUT` con otro valor responde 422. Para corregir uno, se desactiva el registro y se crea otro: ventas y facturas guardan esa identidad.
- **Lo que sí cambia:** el tipo de persona, porque una persona natural puede constituir una sociedad, y todos los demás datos.
- **`PUT` es un reemplazo completo**, sin `PATCH`.
- **Nada se borra:**
  - `POST /{id}/deactivate` y `POST /{id}/activate` son idempotentes;
  - un registro inactivo sigue ocupando su código o documento, y sigue consultable por id, por código o documento, y en el listado con `active=false`;
  - no hay `DELETE`.

### 6. Concurrencia: ETag fuerte + `If-Match` obligatorio en PUT

El riesgo es la **actualización perdida**: dos personas leen la versión 0, las dos editan y la segunda pisa a la primera sin saberlo. Un bloqueo optimista solo interno no lo evita, porque cada `PUT` leería la versión vigente en ese momento. Hay que comparar contra lo que **el cliente** vio.

- **Origen:** `@Version` de JPA (un `Long`, 0 al crear).
- **El ETag:** es esa versión entre comillas (`"3"`). Viaja en el header `ETag` de cada respuesta de un solo registro (GET, POST, PUT, activar y desactivar) y también como campo `version` del cuerpo.
- **El `PUT` exige `If-Match`:**

  | Situación | Respuesta |
  |---|---|
  | Sin el header | **428** Precondition Required (RFC 6585) |
  | Versión distinta de la actual | **412** Precondition Failed; no se modifica nada |
  | Otra transacción confirma entre la lectura y el `UPDATE` | **412**: el `UPDATE … WHERE version = ?` no actualiza ninguna fila y JPA lanza el bloqueo optimista. El servicio hace `flush` dentro de la transacción para traducirlo. |
  | `*`, listas de ETags o ETags débiles (`W/"3"`, que If-Match no admite según el RFC 9110) | **400** |

  - El `*` se rechaza porque "actualizar sin comprobar" es justo lo que el header evita.
  - Por comodidad en Swagger también se acepta el número sin comillas (`3`).
- **Un `PUT` sin cambios no sube la versión.** La entidad compara los datos antes de aplicarlos, así un reintento idéntico no invalida el ETag de nadie.
- **Activar y desactivar aceptan `If-Match` opcional.** Solo cambian una bandera y son idempotentes, así que no pueden pisar una edición.
  - Si envían `If-Match` y está viejo: 412.
  - Si no lo envían y pierden una carrera contra otra escritura: **409** (`concurrent-modification`), no 412, porque no hubo precondición.
- **Swagger:** documenta en cada `PUT` de dónde sale el ETag (header `etag` de `GET /{id}`, en *Response headers*) y un ejemplo `"0"`. Lo verifica `OpenApiIntegrationTest`.

### 7. Listado y búsqueda

- **Parámetros:**
  - `page` (desde 0, máximo 10 000);
  - `size` (1 a 100, por defecto 20);
  - `q` (máximo 100 caracteres);
  - `active` (opcional; sin él, todos).
- **Fuera de rango:** responde 400, en vez de recortarlo en silencio.
- **Respuesta:** DTO propio, `{ items, page, size, totalItems, totalPages }`; el `Page` de Spring no es contrato.
- **Orden fijo y estable**, con el id como desempate, para que la paginación no repita ni salte filas:
  - productos por `lower(code)`;
  - clientes por `lower(coalesce(company_name, names))`.

  No hay parámetro `sort`, para no exponer columnas.
- **Búsqueda:** `lower(campo) LIKE '%texto%'`. Los `%`, `_` y `\` del usuario se buscan literalmente.
  - Productos: código y nombre.
  - Clientes: número de documento, razón social, nombres y email.
- **Consulta exacta, con rutas propias** para que el listado siempre devuelva una página:
  - `GET /products/by-code/{code}`;
  - `GET /customers/by-document/{type}/{number}`.

  Un código con `/` no cabe en la ruta (Tomcat rechaza `%2F`); se busca con `q`.

### 8. Errores (Problem Details, mismo formato que invoicing)

| Situación | HTTP | `type` (`urn:facturaflow:problem:…`) |
|---|---|---|
| Cuerpo con campos faltantes o de formato inválido | 400 | `invalid-request` |
| JSON ilegible, campo inexistente, enum desconocido | 400 | `malformed-request` |
| Parámetro inválido o fuera de rango (`page`, `size`, `q`, id, tipo de documento) | 400 | `invalid-parameter` |
| `If-Match` con `*`, lista o ETag débil | 400 | `invalid-if-match` |
| Producto / cliente inexistente | 404 | `product-not-found` / `customer-not-found` |
| Código / documento ya usado (activo o no) | 409 | `product-code-conflict` / `customer-document-conflict` |
| Carrera al activar o desactivar sin `If-Match` | 409 | `concurrent-modification` |
| `If-Match` viejo, o carrera con `If-Match` | 412 | `precondition-failed` |
| Regla del dato maestro, o cambio de código o documento | 422 | `business-rule-violation` |
| `PUT` sin `If-Match` | 428 | `precondition-required` |
| Cualquier otra cosa | 500 | `internal-error` (sin detalles; el log tiene la traza) |

### 9. Infraestructura

- **Proyecto independiente:** `services/catalog`, con su pom, su Maven Wrapper y un `Dockerfile` en dos etapas con usuario `app` sin root. Se compila con `./scripts/mvn.sh catalog test`.
- **Compose:** servicio `catalog` en `127.0.0.1:8082`, con healthcheck y `CATALOG_DB_PASSWORD` desde `.env`.
- **Script de inicialización idempotente:** `infra/postgres/init/01-create-service-databases.sh` crea el rol y la base solo si faltan (`\gexec` con `format(%I, %L)`), sin tocar lo existente ni cambiar contraseñas.
  - Sigue corriendo solo con el volumen vacío.
  - También se puede ejecutar a mano para agregar un servicio a un volumen con datos, **sin `down -v`**. Así se creó `catalog_db` conservando las facturas locales.

### Nota (2026-10-23): consulta por lote para sales (ADR 0012)

Se agregó `POST /api/v1/products/lookup` con `{ "codes": [...] }` (de 1 a 100 códigos). No cambia ninguna decisión de este ADR.
- **Qué devuelve:** los productos que existen con esos códigos, sin distinguir mayúsculas, **activos o no**, ordenados por código. Quien consulta decide qué hacer con un inactivo. Los inexistentes no aparecen.
- **Por qué POST:** es una **lectura** que se puede repetir, pero 100 códigos no caben con seguridad en una URL.
- **Costo:** 2 sentencias para cualquier cantidad de productos (`QueryCountIntegrationTest`).
- **Errores:** 400 `invalid-request` si la lista está vacía, tiene más de 100 códigos o un código vacío.

## Alternativas consideradas

- **Hexagonal, igual que invoicing:**
  - **Pro:** una sola forma de trabajar en todo el repo.
  - **Contra:** puertos e implementaciones que solo delegan, y un doble mapeo entidad ↔ dominio, sin ningún adaptador intercambiable que lo justifique.
  - **Se revisaría si:** catalog llegara a integrarse con un sistema externo (p. ej. un ERP), o si sus reglas dejaran de ser de coherencia del dato.
- **Librería compartida** para el DV, los documentos y los enums: prohibida por el ADR 0001, porque acopla versiones y despliegues.
- **Validar solo la forma en catalog y dejar todo a invoicing:** duplica menos, pero los errores aparecen al facturar, lejos de quien capturó el dato.
- **Solo `@Version` interno, sin ETag:** no evita la actualización perdida entre clientes.
- **Versión en el cuerpo en vez de `If-Match`:** más cómoda en Swagger, pero no es estándar y mezcla metadatos con datos.
- **`DELETE` lógico:** confunde la semántica HTTP (un `DELETE` repetido de algo que sigue existiendo). Los verbos explícitos `deactivate` y `activate` dicen lo que pasa.

## Consecuencias

**Positivas**
- **Menos ceremonia:** el código es directo para lo que es un CRUD, y ArchUnit sigue haciendo cumplir el sentido de las dependencias.
- **Datos facturables:** un producto o cliente del catálogo siempre se puede facturar, porque tiene las mismas reglas que invoicing.
- **Concurrencia probada en dos niveles:** la actualización perdida es imposible y se demuestra contra PostgreSQL real. En el nivel HTTP, con ETag; en el de la base, con el bloqueo optimista (8 hilos, carrera explícita).
- **Lista para sales:** la API usa los mismos nombres que la de invoicing.

**Negativas**
- **Dos formas de organizar un servicio en el repo.** Este ADR explica cuándo usar cada una.
- **Reglas duplicadas** en dos servicios, con la disciplina de revisarlas juntas.
- **El cliente HTTP debe guardar el ETag** para actualizar.

## Cómo se haría en producción

Por el principio de portafolio, hoy se eligió lo simple. En producción:

- **Búsqueda:** `pg_trgm` con índices GIN sobre los campos buscados, y `unaccent` para ignorar tildes. Hoy `ILIKE '%…%'` recorre la tabla, lo que basta para un catálogo de pyme, y "cafe" no encuentra "café".
- **Caché:** con Redis (descartado por ahora) para las consultas por id y código que hará sales, invalidada al actualizar.
- **Paginación por cursor** (keyset) si el catálogo crece mucho: un `OFFSET` grande es lento.
- **Historial de cambios** (auditoría de quién cambió qué), cuando exista identity.
- **Catálogo completo de municipios DIVIPOLA** como tabla de referencia, para rechazar códigos inexistentes antes de facturar.
