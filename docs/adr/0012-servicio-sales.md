# 0012. Servicio sales: hexagonal, catalog por HTTP síncrono, snapshot de la venta y totales duplicados

## Estado

Aceptado — 2026-10-23. Aplica el ADR 0002 (hexagonal) a sales y completa el ADR 0011 con la consulta por lote de catalog.

> **Nota posterior (ADR 0018):** la API exige un token del realm con el rol `admin` o `vendedor`. Los 401 (`unauthorized`), 403 (`forbidden`) y 503 (`authentication-unavailable`) los escribe la configuración de seguridad con el mismo formato de Problem Details; su tabla está en la decisión 6 del ADR 0018 y sus tests en `SaleSecurityTest`. Sales, además, llama a catalog con su propio token (client credentials de `sales-service`, dentro del reintento y del circuito de catalog: decisión 7 del ADR 0018). Las decisiones de este ADR no cambian.

## Contexto

`sales` es el tercer servicio. Registra ventas de una pyme tomando el cliente y los productos de `catalog`. Más adelante publicará un evento `SaleConfirmed` y `invoicing` creará la factura a partir de él. En esta tarea todavía no hay RabbitMQ: invoicing no cambia.

Características del servicio:
- tiene reglas de negocio propias (totales, vencimiento, idempotencia);
- depende de otro servicio en cada creación;
- pronto publicará eventos.

## Decisión

### 1. Hexagonal (ADR 0002), con las mismas reglas de ArchUnit que invoicing

Paquetes: `com.facturaflow.sales.{domain,application,infrastructure}`.
- `domain` y `application` son Java puro.
- Las transacciones solo existen en `adapter/out/persistence`, así la llamada a catalog nunca queda dentro de una transacción.
- `HexagonalArchitectureTest` es copia del de invoicing, con dos cambios:
  - la regla del Anti-Corruption Layer protege `adapter/out/catalog` en vez de Factus;
  - se suma la regla "ningún código de otro servicio".

La razón para elegir hexagonal aquí, a diferencia de catalog (ADR 0011), es que sales tiene justo lo que lo justifica:
- un sistema externo detrás de un puerto (`CatalogClient`), que en los tests de los casos de uso se reemplaza por uno en memoria;
- un segundo adaptador de salida en camino (el publicador de eventos);
- reglas de negocio que se prueban sin Spring.

### 2. Qué viene de la petición y qué de catalog

- **De la petición**, solo lo que decide el vendedor:
  - `referenceCode`;
  - el documento del cliente (tipo + número);
  - las líneas (código de producto, cantidad, descuento opcional);
  - el medio y la forma de pago (contado, o crédito con vencimiento);
  - la observación.
- **De catalog, SIEMPRE:** precios, nombres, unidades, impuestos y datos fiscales del cliente.
  - Un campo de precio en la petición responde 400, porque no existe en la API.
  - Un cliente o producto inexistente o inactivo responde **422**, con **todos** los problemas juntos (`customerProblem`, `missingProducts`, `inactiveProducts`), para corregirlos de una vez.
- **Formato de la petición:** de 1 a 100 líneas, y un producto puede repetirse en dos líneas. El tope de 100 coincide con el de la consulta por lote de catalog. Los códigos no distinguen mayúsculas, como en catalog.

### 3. Comunicación síncrona con catalog, sin N+1

Cada venta hace **2 llamadas**: `GET /customers/by-document/{tipo}/{número}` y `POST /products/lookup`, una consulta por lote agregada a catalog para esta tarea (nota en el ADR 0011).
- El lote usa POST para no depender del largo máximo de una URL: 100 códigos de 50 caracteres rozan los 8 KB.
- Es una lectura: no modifica nada y se puede repetir.

Alternativas descartadas:
- **Una llamada por línea:** N+1 entre servicios, con la latencia creciendo con cada línea.
- **Llamadas en paralelo con virtual threads:** N veces la carga sobre catalog, y fallas parciales que habría que combinar.

El adaptador (`adapter/out/catalog`) es un Anti-Corruption Layer:
- sus DTOs no salen del paquete;
- usa un `JsonMapper` propio y tolerante, así un campo nuevo en catalog no rompe las ventas;
- los enums se traducen por nombre, porque son los mismos en los tres servicios.

Un estado inesperado (p. ej. un 400 porque los servicios no coinciden en el contrato) es un error de programación: responde 500 y no cuenta en el circuito.

### 4. Resiliencia hacia catalog (mismo enfoque programático que el ADR 0010)

`CatalogResilience` envuelve **cada** llamada HTTP en `CatalogHttp`, el único punto de salida hacia catalog, con **Retry → CircuitBreaker → HTTP**. No hay limitador, porque catalog es nuestro.

| Resultado de un intento | Reintento | Falla del circuito | Por qué |
|---|---|---|---|
| La petición no salió (conexión rechazada, nombre sin resolver, timeout de **conexión**), 502, 503, 504 | sí, **1 vez** (200 ms ± 50 %) | sí | Típico de un reinicio o despliegue de catalog; repetirlo es seguro (GET o lectura por lote) y barato. |
| Timeout de **lectura**, otro 5xx | no | sí | El timeout ya gastó el presupuesto de tiempo, y repetirlo duplicaría la espera justo cuando catalog está lento; un 500 no es pasajero. |
| 2xx, 404 y otros 4xx | no | no | Un cliente o producto inexistente es una respuesta normal. |

**Valores:**
- connect 1 s, read 2 s;
- circuito con ventana de 10 llamadas, evaluado desde la 5.ª, abre al 50 %, abierto 30 s y 2 llamadas de prueba.

Con catalog caído, cada venta acumula 2 fallas, así que el circuito abre **en la 3.ª venta**. Medido en el entorno local con `docker compose stop catalog`:

| Venta | Tiempo | Respuesta |
|---|---|---|
| 1.ª y 2.ª | 2,3 s | 503 `failure`, `Retry-After: 5` (2 intentos × timeout de conexión de 1 s) |
| 3.ª | 1,1 s | 503 `circuit-open` |
| Siguientes | ~6 ms | 503 `circuit-open`, `Retry-After: 30` |

Al volver catalog, el circuito pasa a HALF_OPEN y luego a CLOSED, y la siguiente venta se crea.

**Peor caso de una venta:** unos 4 s (dos lecturas con timeout de 2 s). Está por debajo del timeout típico de navegadores y gateways.

**Health:**
- el componente `catalog` es **informativo** y siempre está UP, con el estado del circuito en `/actuator/health/dependencies`;
- no entra en liveness ni readiness: catalog caído no significa que sales esté mal, porque sigue consultando ventas y respondiendo reintentos;
- el indicador de Resilience4j está desactivado;
- en compose, sales depende de catalog solo como `service_started`.

### 5. Idempotencia de la creación (igual que invoicing, ADR 0006)

- **La huella** es SHA-256 de la **petición normalizada** (`SaleRequestHasher`, formato `sale-request-v1`), no de lo que devolvió catalog:
  - documento normalizado;
  - las líneas en orden, con el código en minúsculas y cantidad y descuento con escala 2;
  - el pago y la observación.
- **Se resuelve antes de llamar a catalog:**
  - misma huella: 200 con la venta existente, **aunque catalog esté caído**;
  - otra huella: 409.
- **Si un precio cambió en catalog** entre la petición y su reintento, sigue siendo la misma venta y conserva los precios de entonces.
- **Carrera entre dos peticiones simultáneas:** la resuelve el UNIQUE de `reference_code`; se relee la ganadora y se aplica la misma regla.

### 6. Snapshot de la venta (ADR 0003): todo lo que necesitará `SaleConfirmed`

La venta guarda una copia de lo que devolvió catalog, con los mismos nombres de campo que `POST /api/v1/invoices` de invoicing:

| Parte | Campos |
|---|---|
| Cliente | `customerId` (catalog), tipo, número y DV del documento, tipo de persona, razón social o nombres, tributo, responsabilidades fiscales, municipio, dirección, teléfono, email |
| Cada línea | `productId` (catalog), código, nombre, unidad, precio unitario neto, `ivaExcluded`, impuestos (tipo y tarifa), cantidad, descuento |
| Venta | `referenceCode` (será el de la factura), fecha de la venta, medio de pago, forma de pago y vencimiento, observación, estado |

Las versiones de catalog no se guardan: bastan los ids para la trazabilidad. Los totales no se guardan: se calculan del snapshot, que no cambia, y así no hay dos fuentes.

**Validación del cliente:** sales solo valida su **estructura**:
- lo obligatorio está presente;
- el nombre corresponde al tipo de persona.

No copia por tercera vez las reglas fiscales: catalog las validó al guardar e invoicing las revalida.

### 7. Totales: copia deliberada del cálculo de invoicing

`Money`, `Quantity`, `DiscountRate`, `TaxRate`, `Percentages`, `LineTax` y `SaleLine` repiten el cálculo de la factura:
- el descuento se aplica antes del impuesto;
- se redondea HALF_UP a 2 decimales por valor;
- cada impuesto se redondea por separado;
- el total de la venta es la suma de los totales de línea.

**Tests copiados** con los mismos números: `MoneyTest`, `QuantityTest`, `PercentagesTest`, `InvoiceLineTest` (→ `SaleLineTest`) y el bloque "Totales" de `InvoiceTest` (→ `SaleTest`).

**Mantenimiento:** si el cálculo cambia en un servicio, cambia en el otro junto con sus tests. Invoicing sigue siendo la autoridad final: la factura tendrá los totales oficiales del proveedor (ADR 0004).

### 8. Preparación para los eventos (tarea siguiente)

- **`SaleStatus`** tiene hoy solo `CONFIRMED`, y `canTransitionTo` no permite ninguna transición. Para agregar `INVOICED` (factura emitida) e `INVOICE_FAILED` (rechazada):
  - el valor en el enum;
  - su caso en `canTransitionTo`;
  - la migración (`ck_sales_status`);
  - el método de `Sale` que haga la transición.
- **`sales.status`** es la única columna actualizable, junto con la versión para el bloqueo optimista. Todo el snapshot es de solo inserción.
- **El evento** sale completo del agregado `Sale`, sin consultar a catalog.
- **Publicación:** lo previsible es un *transactional outbox* (escribir el evento en la misma transacción que la venta), para no perder eventos si RabbitMQ no responde. Se decidirá en esa tarea.

  > **Nota (2026-10-26):** resuelto en el [ADR 0013](0013-mensajeria-y-outbox.md): outbox en sales, `SaleConfirmed` v1 y su contrato en `docs/events/`. El punto abierto del vencimiento se acepta como riesgo allí (decisión 10): si la factura se rechaza, la venta quedará `INVOICE_FAILED` (Fase 3).

**Punto abierto (para la tarea de eventos), sin resolver aquí:** sales valida que el vencimiento de una venta a crédito sea posterior a **la fecha de la venta**. Invoicing lo compara con **la fecha de emisión de la factura**. Cuando la factura se cree de forma asíncrona, días después de la venta (cola detenida, reintentos), un vencimiento cercano podría quedar igual o anterior a esa fecha, e invoicing rechazaría la factura con un 422. Opciones a evaluar entonces:
- que la factura use la fecha de la venta como fecha de emisión, si la DIAN lo permite;
- que sales exija un margen mínimo de días;
- que la venta pase a `INVOICE_FAILED` con ese motivo.

### 9. API y errores

- **Rutas:** `POST /api/v1/sales` (201 o 200), `GET /{id}`, `GET /by-reference/{referenceCode}` y `GET /` paginado (`page`, `size` de 1 a 100, `status` opcional, de la más reciente a la más antigua). La página tiene el mismo formato que catalog.
- **Swagger:** tres ejemplos con los datos de `scripts/seed-catalog.sh`: contado, crédito y el 422.
- **Enums:** los de la API se traducen por nombre, con un test que exige el mismo conjunto de valores (como en catalog).
- **Errores (Problem Details, `urn:facturaflow:problem:…`):**

  | Situación | HTTP | `type` |
  |---|---|---|
  | Cuerpo con campos faltantes o con formato inválido | 400 | `invalid-request` |
  | JSON ilegible, campo inexistente (p. ej. un precio), enum desconocido | 400 | `malformed-request` |
  | Parámetro inválido o fuera de rango | 400 | `invalid-parameter` |
  | Venta inexistente | 404 | `sale-not-found` |
  | `referenceCode` ya usado con otro contenido | 409 | `reference-code-conflict` |
  | Cliente o productos inexistentes o inactivos en catalog | 422 | `catalog-reference-invalid` (con `customerProblem`, `missingProducts`, `inactiveProducts`) |
  | Regla de la venta (cantidad, descuento, líneas, vencimiento, observación) | 422 | `business-rule-violation` |
  | Catalog no responde o circuito abierto (la venta no se crea) | 503 | `catalog-unavailable` (con `reason` y `Retry-After`) |
  | Cualquier otra cosa | 500 | `internal-error` |

### 10. Persistencia e infraestructura

- **Esquema (V1):**
  - tabla `sales`, con el snapshot del cliente y las responsabilidades como `TEXT[]`, como en invoicing;
  - tabla `sale_lines`, ordenada por `line_index`;
  - tabla `sale_line_taxes`;
  - CHECK de defensa en profundidad, incluido "crédito con vencimiento posterior a la fecha de la venta".
- **Consultas:**
  - una venta se carga con entity graph en 1 sentencia;
  - una página son 4 sentencias (página, conteo, líneas e impuestos por lote).
- **Base:** `sales_db` / `sales_user`, con el script de inicialización idempotente (ADR 0011), sin `down -v`.
- **Compose:** puerto `127.0.0.1:8083`. `SALES_CATALOG_BASE_URL` es configurable (por defecto `http://catalog:8080`) y no es un secreto.

## Consecuencias

**Positivas**
- **Sin N+1:** una venta de 100 líneas cuesta 2 llamadas a catalog.
- **Catalog caído no tumba sales:** 503 rápido y explícito, health intacto, consultas y reintentos funcionando.
- **Venta autocontenida:** el evento de la próxima tarea no necesita volver a catalog.
- **Mismo resultado que la factura:** los totales coinciden con los de invoicing, porque comparten casos de prueba.

**Negativas**
- **Disponibilidad:** sin catalog no se puede vender. El ADR lo deja como límite conocido; un caché de productos (con Redis, descartado por ahora) lo mitigaría.
- **Duplicación:** el cálculo de totales vive en dos servicios, con la disciplina de cambiarlos juntos.
- **Punto abierto:** el del vencimiento frente a la fecha de emisión (decisión 8).

## Cómo se haría en producción

- **Caché de lectura** de productos y clientes, invalidado por eventos de catalog, para vender aunque catalog esté caído unos minutos.
- **Autenticación entre servicios:** tokens de servicio con identity/Keycloak.
- **Contract tests** (p. ej. Spring Cloud Contract o Pact) entre sales y catalog, en lugar de mantener a mano los DTOs del adaptador y los stubs de WireMock.
- **Outbox y eventos**, la tarea siguiente.
