# FacturaFlow

Plataforma de ventas para pymes colombianas que emite facturas electrónicas ante la DIAN a través de la API de [Factus](https://www.factus.com.co/). Proyecto de portafolio construido con microservicios en Spring Boot.

## Arquitectura

- **Monorepo** con un proyecto independiente por servicio en `services/`. Hoy hay tres; luego vendrán notifications, identity (con Keycloak), gateway y frontend.

  | Servicio | Responsabilidad | Arquitectura | Puerto local | Base |
  |---|---|---|---|---|
  | `invoicing` | Facturas y notas crédito electrónicas ante la DIAN (vía Factus) | Hexagonal ([ADR 0002](docs/adr/0002-arquitectura-hexagonal.md)) | `127.0.0.1:8081` | `invoicing_db` |
  | `catalog` | Productos y clientes (datos maestros) | En capas ([ADR 0011](docs/adr/0011-servicio-catalog-en-capas.md)) | `127.0.0.1:8082` | `catalog_db` |
  | `sales` | Ventas confirmadas, con cliente y productos tomados de catalog | Hexagonal ([ADR 0012](docs/adr/0012-servicio-sales.md)) | `127.0.0.1:8083` | `sales_db` |

- **Cada servicio usa la arquitectura que necesita:**
  - invoicing, hexagonal: integra un proveedor externo intercambiable;
  - sales, hexagonal: tiene reglas de negocio, llama a catalog y publica eventos;
  - catalog, en capas (controller → service → repository): es casi un CRUD.

  Las dos se validan con ArchUnit.
- **Base de datos por servicio:** un PostgreSQL con una base y un usuario por servicio; el esquema lo gestiona Flyway.
- **Sin código compartido entre servicios:** los conceptos repetidos, como el DV del NIT, se duplican a propósito.
- **Comunicación entre servicios** solo por HTTP o eventos:
  - sales consulta catalog por HTTP al crear una venta (2 llamadas por venta, con circuit breaker);
  - sales publica el evento `SaleConfirmed` en RabbitMQ con un *transactional outbox*: la venta y su evento se guardan en la misma transacción y se publican con confirmación del broker, aunque RabbitMQ esté caído al vender ([ADR 0013](docs/adr/0013-mensajeria-y-outbox.md)). El contrato está en el [catálogo de eventos](docs/events/README.md);
  - en la siguiente fase, invoicing facturará las ventas a partir de ese evento.
- **Todo corre en Docker:** no se necesita Java ni Maven instalados.

Las decisiones y sus motivos están en los [ADRs](docs/adr/README.md).

## Primeros pasos tras clonar

Requisito: Docker Engine con Docker Compose.

1. **Activar los hooks de Git** (bloquean commits con secretos usando gitleaks):
   ```bash
   git config core.hooksPath .githooks
   ```
2. **Crear el `.env`** a partir del ejemplo y reemplazar cada `CAMBIAME`:
   ```bash
   cp .env.example .env
   ```
   El `.env` está en `.gitignore`: nunca se sube al repositorio.
3. **Levantar el entorno** (PostgreSQL, RabbitMQ, invoicing, catalog y sales):
   ```bash
   docker compose --env-file .env -f infra/docker-compose.yml up --build -d
   docker compose --env-file .env -f infra/docker-compose.yml ps     # los cinco "healthy"
   ```
   `rabbitmq-init` aparece como terminado (`Exited (0)`): es un contenedor de un solo uso que crea el usuario de RabbitMQ de cada servicio.
4. **Comprobar** que los tres servicios responden:
   ```bash
   curl http://localhost:8081/actuator/health    # invoicing: {"status":"UP",...}
   curl http://localhost:8082/actuator/health    # catalog:   {"status":"UP",...}
   curl http://localhost:8083/actuator/health    # sales:     {"status":"UP",...}
   ```
5. **Sembrar los datos de ejemplo** en catalog (los usan los ejemplos de Swagger de sales; se puede repetir):
   ```bash
   ./scripts/seed-catalog.sh
   ```

### Si ya tenías el entorno levantado antes de RabbitMQ

Agrega a tu `.env` `RABBITMQ_ADMIN_USER`, `RABBITMQ_ADMIN_PASSWORD` y `SALES_RABBITMQ_PASSWORD` (ver `.env.example`) y vuelve a levantar todo con el comando del paso 3. RabbitMQ empieza con su volumen vacío y `rabbitmq-init` crea el vhost y los usuarios. La migración V2 de sales (el outbox) se aplica sola.

### Si ya tenías el entorno levantado antes de catalog o sales

El script que crea las bases (`infra/postgres/init/`) corre automáticamente solo con el volumen vacío. Para crear `catalog_db` y `sales_db` **sin borrar tus datos** (sin `down -v`):

1. Agrega `CATALOG_DB_PASSWORD` y `SALES_DB_PASSWORD` a tu `.env` (ver `.env.example`).
2. Recrea el contenedor de PostgreSQL para que reciba la variable; el volumen se conserva.
3. Ejecuta el script a mano. Es idempotente: solo crea lo que falta.
4. Levanta todo.

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d postgres
docker compose --env-file .env -f infra/docker-compose.yml exec postgres \
  bash /docker-entrypoint-initdb.d/01-create-service-databases.sh
docker compose --env-file .env -f infra/docker-compose.yml up --build -d
```

## Probar la API con Swagger

Con el entorno levantado, abre **http://localhost:8081/swagger-ui.html**. Para probar el flujo completo:

1. **Crear** (`POST /api/v1/invoices`, sección *Facturas*): elige uno de los ejemplos del desplegable (*Contado con IVA* o *Crédito con IVA + INC*) y pulsa *Execute*. La respuesta es `201` con el header `Location` y los totales calculados.
2. **Repetir** la misma petición: responde `200` con la misma factura, sin crear otra. Si cambias cualquier dato y mantienes el `referenceCode`, responde `409`.
3. **Emitir** (`POST /api/v1/invoices/{id}/issue`) con el `id` de la respuesta. La factura emitida trae su número, el CUFE, `qrUrl` (consulta en la DIAN) y `publicUrl` (vista pública en el proveedor).
4. **Descargar el PDF** (`GET /api/v1/invoices/{id}/pdf`): solo para facturas `ISSUED` (si no, `409`).
5. **Consultar** por id (`GET /api/v1/invoices/{id}`) o por código (`GET /api/v1/invoices?referenceCode=...`). Una factura `ISSUED` trae `credits`: lo acreditado con notas crédito y el saldo por línea.

### Notas crédito (sección *Notas crédito*)

Corrigen una factura `ISSUED` sin cambiar su estado ([ADR 0009](docs/adr/0009-notas-credito.md)):

1. **Crear** (`POST /api/v1/invoices/{id}/credit-notes`) con uno de los ejemplos:
   - *Devolución parcial*: líneas y cantidades;
   - *Anulación total*: sin líneas, acredita todo lo que queda.
   
   El cliente, los precios y los impuestos salen de la factura. Si se pide más de lo que queda por acreditar, responde `422`. Es idempotente por `referenceCode`, como la factura.
2. **Emitir** (`POST /api/v1/credit-notes/{id}/issue`): la nota emitida trae su número y su CUDE.
3. **Consultar el saldo** en `GET /api/v1/invoices/{id}` → `credits`.
4. **Descargar el PDF** (`GET /api/v1/credit-notes/{id}/pdf`).

Las notas de una factura se listan con `GET /api/v1/invoices/{id}/credit-notes`. Una nota `PENDING` ya reserva sus cantidades; una `REJECTED` las libera.

La especificación OpenAPI está en http://localhost:8081/v3/api-docs. Los errores siguen el formato Problem Details (RFC 9457).

### Elegir el proveedor: falso o Factus

La variable `INVOICING_PROVIDER` decide quién emite (ver [ADR 0008](docs/adr/0008-integracion-con-factus.md)):

| Valor | Proveedor | Necesita |
|---|---|---|
| `fake` (por defecto) | Simulado, sin red ni validez legal | nada |
| `factus` | Sandbox real de Factus: la DIAN (habilitación) valida cada factura y cada nota crédito | `FACTUS_CLIENT_ID`, `FACTUS_CLIENT_SECRET`, `FACTUS_USERNAME`, `FACTUS_PASSWORD` y `FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID` en `.env` |

Para usar Factus, pon `INVOICING_PROVIDER=factus` en `.env`, o pásalo solo para esa ejecución:

```bash
INVOICING_PROVIDER=factus docker compose --env-file .env -f infra/docker-compose.yml up --build -d
```

Si falta una credencial, el servicio no arranca y el log dice cuál. Opcionales: `FACTUS_SEND_EMAIL` (por defecto `false`; en el sandbox, déjalo así) y `FACTUS_NUMBERING_RANGE_ID` (vacío = el rango por defecto de la cuenta). Con Factus, `SIMULAR_RECHAZO` y `SIMULAR_CAIDA` no tienen efecto: los rechazos y las caídas son reales. Para las notas crédito con Factus también se necesita `FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID`; es obligatorio, y en el sandbox vale `390`.

### Resiliencia hacia Factus

Con `INVOICING_PROVIDER=factus`, toda llamada a Factus (facturas, notas, PDF y token) tiene tres protecciones ([ADR 0010](docs/adr/0010-resiliencia-factus.md)):
- **Reintentos cortos** (3 intentos con backoff y jitter), solo ante fallas técnicas: nunca ante un rechazo de la DIAN.
- **Circuit breaker:** tras fallas repetidas (unas 2 emisiones fallidas) se deja de llamar a Factus 30 s. Mientras tanto, emitir responde `503` al instante, con `Retry-After` igual al tiempo que falta y `"reason": "circuit-open"`.
- **Limitador** de 100 peticiones/min, por debajo del límite real de 120.

El health del servicio **no** depende de Factus: `/actuator/health` sigue `UP`. El estado se ve en:

```bash
curl -s http://localhost:8081/actuator/health/providers    # componente "factus": estado del circuito y del limitador
curl -s http://localhost:8081/actuator/circuitbreakers
curl -s http://localhost:8081/actuator/metrics/resilience4j.retry.calls
curl -s http://localhost:8081/actuator/metrics/invoicing.factus.rejected
```

**Demostración: abrir el circuito** con un Factus inalcanzable, solo para esa ejecución, sin tocar `.env`:

```bash
INVOICING_PROVIDER=factus FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID=390 FACTUS_BASE_URL=http://10.255.255.1 \
  docker compose --env-file .env -f infra/docker-compose.yml up --build -d
# Crear una factura (ejemplo "Contado con IVA" de Swagger) y emitirla 3 o 4 veces (con facturas distintas):
#   1.ª: 503 tras los reintentos (reason "failure")   2.ª: el circuito se abre (reason "circuit-open")
#   siguientes: 503 inmediato con Retry-After decreciente
curl -s http://localhost:8081/actuator/health/providers   # "circuitBreaker":"OPEN", "retryAfterSeconds":...
curl -s http://localhost:8081/actuator/health             # sigue {"status":"UP"}
docker compose --env-file .env -f infra/docker-compose.yml logs invoicing | grep "Circuito de Factus"
docker compose --env-file .env -f infra/docker-compose.yml up --build -d   # volver al proveedor falso
```

### Proveedor de facturación falso

Con `INVOICING_PROVIDER=fake` (el valor por defecto) la emisión usa un proveedor **simulado**. Su comportamiento depende de la **observación** del documento que se emite (factura o nota crédito):

| La observación contiene | Resultado de `POST /{id}/issue` |
|---|---|
| `SIMULAR_RECHAZO` | `200` con el documento `REJECTED` y un motivo de ejemplo |
| `SIMULAR_CAIDA` | `503` con `Retry-After`; el documento sigue `PENDING` y se puede reintentar |
| ninguna de las dos | `200` con el documento `ISSUED` |

Una emisión "exitosa" del proveedor falso **no tiene validez ante la DIAN**:
- el número es `FAKE-<referenceCode>` (`FAKE-NC-<referenceCode>` en las notas);
- el CUFE (o el CUDE) empieza con 16 ceros;
- `qrUrl` y `publicUrl` apuntan a `https://fake-provider.invalid/...`, un dominio reservado que nunca resuelve;
- el PDF es un documento mínimo que dice "DOCUMENTO FALSO - SIN VALIDEZ".

Los ejemplos a crédito usan un vencimiento lejano (`2030-01-31`) porque el vencimiento debe ser posterior a la fecha de emisión, que es la de hoy en Colombia.

## Catálogo: productos y clientes (catalog)

Swagger UI: **http://localhost:8082/swagger-ui.html**, especificación en http://localhost:8082/v3/api-docs.

1. **Crear** un producto (`POST /api/v1/products`) o un cliente (`POST /api/v1/customers`) con uno de los ejemplos del desplegable. Responde `201`, con el header `ETag: "0"`.
   - El código del producto es único sin distinguir mayúsculas, y el cliente es único por tipo + número de documento: repetirlos responde `409`.
   - Las reglas son las mismas que exige una factura (DV del NIT, tipo de persona, impuestos…): si no se cumplen, responde `422`.
2. **Consultar:**
   - por id: `GET /{id}`;
   - por código: `GET /api/v1/products/by-code/{code}`;
   - por documento: `GET /api/v1/customers/by-document/{tipo}/{número}`;
   - o listar con `?q=texto&active=true&page=0&size=20`.
3. **Actualizar** (`PUT /{id}`): es un reemplazo completo y **exige `If-Match`**, para que nadie pise sin saberlo un cambio ajeno.
   1. Copia el header `etag` de la respuesta del `GET` (en Swagger UI, en *Response headers*), p. ej. `"0"`.
   2. Pégalo, con comillas, en el campo **If-Match** del `PUT`. La respuesta trae el ETag nuevo (`"1"`).

   Con un ETag viejo responde `412`, y sin el header `428`. El código del producto y el documento del cliente no se pueden cambiar (`422`).
4. **Desactivar** (`POST /{id}/deactivate`) y **reactivar** (`POST /{id}/activate`): nada se borra, porque ventas y facturas lo referencian.

Lo mismo con curl:

```bash
curl -si -X POST localhost:8082/api/v1/products -H 'Content-Type: application/json' \
  -d '{"code":"CAFE-500","name":"Café molido 500 g","unitPrice":18500,"unit":"UNIT","taxes":[{"type":"IVA","rate":5}]}'
# -> 201, ETag: "0", Location: .../api/v1/products/<id>
curl -si -X PUT localhost:8082/api/v1/products/<id> -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"code":"CAFE-500","name":"Café molido 500 g","unitPrice":19900,"unit":"UNIT","taxes":[{"type":"IVA","rate":5}]}'
# -> 200, ETag: "1". Repetir el mismo PUT con If-Match: "0" -> 412
```

## Ventas (sales): una venta de punta a punta

Swagger UI: **http://localhost:8083/swagger-ui.html**. Con el entorno levantado y `./scripts/seed-catalog.sh` ejecutado:

1. **Confirmar una venta** (`POST /api/v1/sales`) con uno de los ejemplos del desplegable. La petición solo trae el documento del cliente, los códigos de producto con cantidad y descuento, y la forma de pago: precios, nombres, impuestos y datos fiscales salen de catalog.
   - *Contado*: NIT `890903938`, `CAFE-500` con 10 % de descuento y `ARROZ-KG` excluido de IVA. Responde `201` con un total de `41265.00`.
   - *Crédito*: cédula `1020304050`, `CERVEZA-330` con IVA 19 % + INC 8 %. Responde `201` con un total de `72765.00`.
   - *422*: un producto inactivo (`DEMO-CAFE`, si existe en tu catalog) y uno inexistente. Responde `422` con `missingProducts` e `inactiveProducts`.
2. **Repetir** la misma petición: `200` con la misma venta. Con otro contenido y el mismo `referenceCode`: `409`.
3. **Consultar:** `GET /api/v1/sales/{id}`, `GET /api/v1/sales/by-reference/{referenceCode}` o el listado `GET /api/v1/sales?page=0&size=20`. La venta trae la copia del cliente y de cada producto, y los totales (calculados con las mismas reglas que invoicing).
4. **Cambiar un precio en catalog** (`PUT` con `If-Match`) y consultar la venta: no cambia, porque guarda una copia.

### Catalog caído: demostración

```bash
docker compose --env-file .env -f infra/docker-compose.yml stop catalog
# Confirmar 3 o 4 ventas nuevas (cambiando el referenceCode del ejemplo de contado):
#   1.ª y 2.ª: 503 en ~2 s (reason "failure", Retry-After: 5)
#   3.ª: el circuito se abre (reason "circuit-open")
#   siguientes: 503 inmediato (milisegundos), sin llamar a catalog
curl -s http://localhost:8083/actuator/health                # sigue {"status":"UP",...}
curl -s http://localhost:8083/actuator/health/dependencies   # "circuitBreaker":"OPEN"
# Repetir una venta YA creada: 200 aunque catalog esté caído
docker compose --env-file .env -f infra/docker-compose.yml logs sales | grep "Circuito de catalog"
docker compose --env-file .env -f infra/docker-compose.yml start catalog
# A los 30 s el circuito deja pasar una prueba (HALF_OPEN → CLOSED) y las ventas vuelven a crearse.
```

### Eventos: ver `SaleConfirmed` en RabbitMQ

Consola de RabbitMQ: **http://localhost:15672**, con el usuario `RABBITMQ_ADMIN_USER` y la contraseña `RABBITMQ_ADMIN_PASSWORD` de tu `.env`. Ahí se ven el exchange `sales.events` y las conexiones.

Todavía no hay consumidores (invoicing llegará en la Fase 2), así que el exchange descartaría cada mensaje. Para verlos, se crea una **cola de depuración temporal** enlazada a `sales.events`. Se borra sola tras 1 h sin uso y guarda como máximo 100 mensajes.

```bash
./scripts/rabbitmq-debug-queue.sh create
# Confirmar una venta en Swagger (ejemplo de contado, con un referenceCode nuevo) → 201
./scripts/rabbitmq-debug-queue.sh peek     # el mensaje SaleConfirmed, sin sacarlo de la cola
# O en la consola: Queues and Streams → debug.sales-events → Get messages → Get Message(s)
curl -s http://localhost:8083/actuator/health/dependencies      # "messaging": relay OK, pendingEvents 0
./scripts/rabbitmq-debug-queue.sh delete   # al terminar
```

### RabbitMQ caído: demostración

```bash
./scripts/rabbitmq-debug-queue.sh create
docker compose --env-file .env -f infra/docker-compose.yml stop rabbitmq
# Confirmar una venta nueva en Swagger → 201: vender no depende del broker
curl -s http://localhost:8083/actuator/health                    # sigue {"status":"UP",...}
curl -s http://localhost:8083/actuator/health/dependencies       # "relay":"FAILING", "pendingEvents":1
curl -s http://localhost:8083/actuator/metrics/sales.outbox.pending
curl -s http://localhost:8083/actuator/metrics/sales.outbox.oldest.pending.age
docker compose --env-file .env -f infra/docker-compose.yml start rabbitmq
# En segundos: "relay":"OK", "pendingEvents":0, y el mensaje está en la cola de depuración
./scripts/rabbitmq-debug-queue.sh peek
docker compose --env-file .env -f infra/docker-compose.yml logs sales | grep "Outbox:"
#   WARN ... no se pudieron publicar eventos ...    (una sola línea al empezar a fallar)
#   INFO ... publicación restablecida tras N ciclo(s) fallido(s); 1 evento(s) publicado(s)
```

## Otros comandos

```bash
./scripts/mvn.sh invoicing test   # tests de invoicing (Maven + Testcontainers + WireMock en Docker)
./scripts/mvn.sh catalog test     # tests de catalog (Maven + Testcontainers en Docker)
./scripts/mvn.sh sales test       # tests de sales (Maven + Testcontainers + WireMock en Docker)
./scripts/seed-catalog.sh         # datos de ejemplo en catalog (idempotente)
# Tests contra el sandbox real de Factus (no corren por defecto; crean 2 facturas y 1 nota crédito reales por ejecución)
MVN_ENV_FILE=.env ./scripts/mvn.sh invoicing test -Dgroups=sandbox -DexcludedGroups=none
./scripts/check-secrets.sh        # escaneo de secretos en todo el repo
```
