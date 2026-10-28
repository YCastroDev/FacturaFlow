# FacturaFlow

[![CI](https://github.com/YCastroDev/facturaflow/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/YCastroDev/facturaflow/actions/workflows/ci.yml)
[![gitleaks](https://github.com/YCastroDev/facturaflow/actions/workflows/gitleaks.yml/badge.svg?branch=main)](https://github.com/YCastroDev/facturaflow/actions/workflows/gitleaks.yml)
[![Licencia: MIT](https://img.shields.io/badge/licencia-MIT-blue.svg)](LICENSE)
![Java 25](https://img.shields.io/badge/Java-25-orange.svg)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F.svg)
[![Quality Gate invoicing](https://sonarcloud.io/api/project_badges/measure?project=ycastrodev_facturaflow-invoicing&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ycastrodev_facturaflow-invoicing)
[![Cobertura invoicing](https://sonarcloud.io/api/project_badges/measure?project=ycastrodev_facturaflow-invoicing&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ycastrodev_facturaflow-invoicing)
[![Quality Gate catalog](https://sonarcloud.io/api/project_badges/measure?project=ycastrodev_facturaflow-catalog&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ycastrodev_facturaflow-catalog)
[![Cobertura catalog](https://sonarcloud.io/api/project_badges/measure?project=ycastrodev_facturaflow-catalog&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ycastrodev_facturaflow-catalog)
[![Quality Gate sales](https://sonarcloud.io/api/project_badges/measure?project=ycastrodev_facturaflow-sales&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ycastrodev_facturaflow-sales)
[![Cobertura sales](https://sonarcloud.io/api/project_badges/measure?project=ycastrodev_facturaflow-sales&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ycastrodev_facturaflow-sales)

Plataforma de ventas para pymes colombianas que emite facturas electrónicas ante la DIAN a través de la API de [Factus](https://www.factus.com.co/). Proyecto de portafolio construido con microservicios en Spring Boot.

## Arquitectura

- **Monorepo** con un proyecto independiente por servicio en `services/`. Hoy hay tres servicios de negocio y el gateway; luego vendrán notifications y el frontend.

  | Servicio | Responsabilidad | Arquitectura | Puerto local | Base |
  |---|---|---|---|---|
  | `invoicing` | Facturas y notas crédito electrónicas ante la DIAN (vía Factus) | Hexagonal ([ADR 0002](docs/adr/0002-arquitectura-hexagonal.md)) | `127.0.0.1:8081` | `invoicing_db` |
  | `catalog` | Productos y clientes (datos maestros) | En capas ([ADR 0011](docs/adr/0011-servicio-catalog-en-capas.md)) | `127.0.0.1:8082` | `catalog_db` |
  | `sales` | Ventas confirmadas, con cliente y productos tomados de catalog | Hexagonal ([ADR 0012](docs/adr/0012-servicio-sales.md)) | `127.0.0.1:8083` | `sales_db` |
  | `gateway` | Punto de entrada único a la API: enruta a los tres servicios | Spring Cloud Gateway WebMVC ([ADR 0017](docs/adr/0017-seguridad-keycloak-y-gateway.md)) | `127.0.0.1:8080` | — |
  | Keycloak | Proveedor de identidad: realm `facturaflow` como código (roles, usuarios de demostración, clientes) | Imagen oficial 26.8.1 ([ADR 0017](docs/adr/0017-seguridad-keycloak-y-gateway.md)) | `127.0.0.1:8180` | `keycloak_db` |

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
  - invoicing consume ese evento: crea la factura y la emite con los mismos casos de uso de su API, reintenta las fallas técnicas con espera creciente y aparta en una DLQ lo que no puede procesar ([ADR 0014](docs/adr/0014-consumo-de-sale-confirmed.md)). Vender ya factura sola;
  - invoicing publica el resultado de cada factura en `invoicing.events` (con su propio outbox), y sales deja la venta `INVOICED` (número y CUFE) o `INVOICE_FAILED` (motivo). La Saga se cierra por coreografía ([ADR 0015](docs/adr/0015-cierre-de-la-saga-de-facturacion.md)).
- **Seguridad (en curso):** Keycloak y el gateway ya funcionan, pero en esta Fase 1 nadie exige token; en la Fase 2, el gateway y cada servicio validarán el JWT y los roles (zero trust) ([ADR 0017](docs/adr/0017-seguridad-keycloak-y-gateway.md)).
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
3. **Levantar el entorno** (PostgreSQL, RabbitMQ, Keycloak, invoicing, catalog, sales y gateway):
   ```bash
   docker compose --env-file .env -f infra/docker-compose.yml up --build -d
   docker compose --env-file .env -f infra/docker-compose.yml ps     # los siete "healthy"
   ```
   `rabbitmq-init` aparece como terminado (`Exited (0)`): es un contenedor de un solo uso que crea el usuario de RabbitMQ de cada servicio.
4. **Comprobar** que los servicios responden:
   ```bash
   curl http://localhost:8081/actuator/health    # invoicing: {"status":"UP",...}
   curl http://localhost:8082/actuator/health    # catalog:   {"status":"UP",...}
   curl http://localhost:8083/actuator/health    # sales:     {"status":"UP",...}
   curl http://localhost:8080/actuator/health    # gateway:   {"status":"UP",...}
   ```
5. **Sembrar los datos de ejemplo** en catalog (los usan los ejemplos de Swagger de sales; se puede repetir):
   ```bash
   ./scripts/seed-catalog.sh
   ```

### Si ya tenías el entorno levantado antes de RabbitMQ

Agrega a tu `.env` `RABBITMQ_ADMIN_USER`, `RABBITMQ_ADMIN_PASSWORD`, `SALES_RABBITMQ_PASSWORD` e `INVOICING_RABBITMQ_PASSWORD` (ver `.env.example`) y vuelve a levantar todo con el comando del paso 3. RabbitMQ empieza con su volumen vacío y `rabbitmq-init` crea el vhost y los usuarios. La migración V2 de sales (el outbox) se aplica sola.

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

### Si ya tenías el entorno levantado antes de Keycloak

1. Agrega a tu `.env` las variables de la sección Keycloak de `.env.example`: `KEYCLOAK_ADMIN_USER`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KC_DEMO_ADMIN_PASSWORD`, `KC_DEMO_VENDEDOR_PASSWORD`, `KC_DEMO_CONTADOR_PASSWORD` y `SALES_SERVICE_CLIENT_SECRET`.
2. Crea `keycloak_db` como en la sección anterior: recrea `postgres` y ejecuta el script de init, que es idempotente.
3. Levanta todo con `up --build -d`. Keycloak importa el realm la primera vez.

## Seguridad: Keycloak y el gateway (Fase 1)

Detalles y decisiones en el [ADR 0017](docs/adr/0017-seguridad-keycloak-y-gateway.md). **En esta fase nadie exige token**: el token se puede enviar y llega hasta los servicios, pero todavía no se valida (eso es la Fase 2).

**Consola de Keycloak:** http://localhost:8180/admin, solo desde tu máquina.
- Entra con `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD` de tu `.env`.
- Elige el realm **facturaflow** arriba a la izquierda:
  - *Realm roles*: `admin`, `vendedor`, `contador`;
  - *Users*: `admin.demo`, `vendedor.demo`, `contador.demo`;
  - *Clients*: `sales-service`, `facturaflow-swagger`, `facturaflow-cli`;
  - *Client scopes*: `catalog.read`.

**Obtener un token desde la terminal.** El script pide la contraseña sin mostrarla: es la variable `KC_DEMO_<ROL>_PASSWORD` de tu `.env`, o el `SALES_SERVICE_CLIENT_SECRET` para `sales-service`.

```bash
./scripts/keycloak-token.sh --claims vendedor.demo     # issuer, usuario y roles: "realm_access": {"roles": ["vendedor"]}
./scripts/keycloak-token.sh --claims contador.demo
./scripts/keycloak-token.sh --claims admin.demo
./scripts/keycloak-token.sh --claims sales-service     # client credentials: "scope": "catalog.read", sin roles
TOKEN=$(./scripts/keycloak-token.sh vendedor.demo)     # solo el token, para usarlo en curl
```

- `facturaflow-cli` es un cliente **solo para desarrollo** (password grant). Los usuarios reales entrarán por authorization code + PKCE (`facturaflow-swagger`, Fase 2).
- El token dura 5 minutos.

**Llamar a los servicios por el gateway** (http://localhost:8080). Las rutas son las mismas de cada servicio:

```bash
curl -s "http://localhost:8080/api/v1/products?size=5"                            # → catalog
curl -s http://localhost:8080/api/v1/customers/by-document/NIT/890903938          # → catalog
curl -s -H "Authorization: Bearer $TOKEN" -X POST http://localhost:8080/api/v1/sales \
  -H 'Content-Type: application/json' -d '{ "referenceCode": "GW-0001",
  "customer": { "documentType": "NIT", "documentNumber": "890903938" },
  "lines": [ { "productCode": "CAFE-500", "quantity": 2 } ],
  "paymentMethod": "CASH", "paymentTerms": { "type": "CASH" } }'                 # → sales (201)
curl -s http://localhost:8080/api/v1/sales/by-reference/GW-0001                   # → sales: INVOICED en segundos
curl -s http://localhost:8080/api/v1/invoices/<invoiceId de la venta>             # → invoicing
curl -s http://localhost:8080/api/v1/nada                                         # → 404 Problem Details del gateway
```

- **Errores del gateway:**
  - un servicio detenido da 502 `upstream-unavailable` solo en sus rutas;
  - uno que no responde en 60 s da 504 `upstream-timeout`;
  - el health del gateway sigue UP en los dos casos.
- **Swagger UI** sigue en el puerto de cada servicio.

**Comprobar el issuer desde dentro de Docker** (debe ser el mismo `http://localhost:8180/realms/facturaflow` que en los tokens):

```bash
docker compose --env-file .env -f infra/docker-compose.yml exec gateway \
  wget -qO- http://keycloak:8080/realms/facturaflow/.well-known/openid-configuration
```

**Cambiar el realm o una contraseña de demostración.** Keycloak importa el archivo `infra/keycloak/import/facturaflow-realm.json` solo si el realm **no existe**. Para aplicar un cambio del archivo o del `.env`:

1. En la consola, entra al realm `facturaflow` → *Realm settings* → *Action* (arriba a la derecha) → *Delete*.
2. Reinicia Keycloak: `docker compose --env-file .env -f infra/docker-compose.yml restart keycloak`. Vuelve a importar el realm con los valores actuales.

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

## Facturación automática: la Saga venta → factura → venta

Con el entorno levantado y `./scripts/seed-catalog.sh` ejecutado, **no hace falta tocar invoicing**. Cada venta confirmada en sales se factura sola, e invoicing devuelve el resultado: la venta termina **`INVOICED`** (con el número y el CUFE) o **`INVOICE_FAILED`** (con el motivo). El flujo completo y su diagrama de secuencia están en el [ADR 0015](docs/adr/0015-cierre-de-la-saga-de-facturacion.md).

Consola de RabbitMQ: **http://localhost:15672**. En Queues and Streams:
- `invoicing.sale-confirmed` y `sales.invoice-results`;
- sus colas de espera `wait-10s`, `wait-1m` y `wait-5m`;
- sus DLQ `*.dlq`.

```bash
# Ayudantes: confirmar una venta (el ejemplo de contado de Swagger) con un referenceCode y una observación,
# ver su estado en sales y su factura en invoicing.
venta() { curl -s -o /dev/null -w "venta $1: HTTP %{http_code}\n" -X POST http://localhost:8083/api/v1/sales \
  -H 'Content-Type: application/json' -d "{\"referenceCode\":\"$1\",\"customer\":{\"documentType\":\"NIT\",\"documentNumber\":\"890903938\"},
  \"lines\":[{\"productCode\":\"CAFE-500\",\"quantity\":2,\"discountRate\":10}],\"paymentMethod\":\"CASH\",
  \"paymentTerms\":{\"type\":\"CASH\"},\"observation\":\"${2:-}\"}"; }
estado() { curl -s "http://localhost:8083/api/v1/sales/by-reference/$1" | python3 -c 'import sys,json; d=json.load(sys.stdin);
print(d["status"], d["invoice"] or "", d["invoiceFailure"] or "")'; }
factura() { curl -s "http://localhost:8081/api/v1/invoices?referenceCode=$1"; echo; }
```

1. **Venta normal → INVOICED con el número de factura** (en segundos, sin tocar invoicing):
   ```bash
   venta DEMO-1
   estado DEMO-1        # INVOICED {'invoiceId': ..., 'number': 'FAKE-DEMO-1', 'cufe': '0000...', 'invoicedAt': ...}
   ```
2. **Rechazo de la DIAN → INVOICE_FAILED con el motivo** (sin reintentos):
   ```bash
   venta DEMO-2 SIMULAR_RECHAZO
   estado DEMO-2        # INVOICE_FAILED  {'reason': 'Rechazo simulado por el proveedor falso (SIMULAR_RECHAZO): ...'}
   ```
3. **Falla técnica → DLQ → reproceso → INVOICED** (~6 min: esperas de 10 s, 1 min y 5 min):
   ```bash
   venta DEMO-3 SIMULAR_FALLA_TEMPORAL   # el proveedor falso falla 4 veces (todos los intentos) y luego funciona
   ./scripts/rabbitmq-dlq.sh invoicing status   # repetir: pasa por wait-10s, wait-1m, wait-5m y a los ~6 min, la DLQ
   estado DEMO-3        # CONFIRMED todo el tiempo: una falla técnica NO es un resultado (se puede reprocesar)
   ./scripts/rabbitmq-dlq.sh invoicing peek     # x-failure-category: technical, x-attempts-made: 4, el motivo
   ./scripts/rabbitmq-dlq.sh invoicing requeue  # o "Move messages" en la página de la DLQ en la consola
   estado DEMO-3        # INVOICED: el "proveedor volvió"
   ```
   Con `SIMULAR_CAIDA` (falla siempre), la venta queda `CONFIRMED` y el mensaje vuelve a la DLQ en cada reproceso: reprocesar sin corregir la causa no la arregla.

```bash
curl -s http://localhost:8083/actuator/health/dependencies     # sales: messaging.outbox y messaging.consumer
curl -s http://localhost:8081/actuator/health/dependencies     # invoicing: messaging.outbox y messaging.consumer
curl -s "http://localhost:8083/actuator/metrics/sales.invoice_results.processed?tag=outcome:applied"
docker compose --env-file .env -f infra/docker-compose.yml logs invoicing sales | grep -E "Reintento|DLQ|procesada|resultado de facturación"
```

Con RabbitMQ detenido, sales sigue vendiendo e invoicing sigue atendiendo su API; los eventos esperan en el outbox de cada uno. Al volver, todo se publica y la Saga continúa. El proveedor falso cuenta los fallos de `SIMULAR_FALLA_TEMPORAL` en memoria: un reinicio de invoicing los pone en cero.

## Integración continua (GitHub Actions)

Detalles y decisiones en el [ADR 0016](docs/adr/0016-integracion-continua.md).

- **`CI`** (`.github/workflows/ci.yml`) corre en cada push a `main`, en cada PR y a mano (Actions → CI → Run workflow, que prueba todos los servicios, incluido el gateway).
  - Detecta qué servicios cambiaron y, solo en esos, corre `./mvnw verify`: todos los tests con Testcontainers, sin los de sandbox, más la cobertura con JaCoCo.
  - También construye la imagen Docker. Desde `main` la publica en GHCR: `ghcr.io/ycastrodev/facturaflow-<servicio>:sha-<commit>` y `:latest`.
  - El job **`ci-result`** da el veredicto, y es el check que exige la protección de `main`.
  - Si los tests fallan, los reportes quedan como artefacto `surefire-reports-<servicio>`, y el resumen de cada ejecución muestra los tests de cada servicio.
- **`gitleaks`** (`.github/workflows/gitleaks.yml`) revisa todo el historial en cada push y PR, con la misma configuración que el hook local.
- **Dependabot** (`.github/dependabot.yml`): PRs semanales y agrupados para Maven, Docker, docker compose y GitHub Actions.
- **SonarCloud** (opcional): el análisis corre solo si existe el secreto `SONAR_TOKEN`. Sin él, se omite y la CI sigue en verde.

```bash
gh run list --workflow ci.yml --limit 5     # últimas ejecuciones
gh run watch                                 # seguir la ejecución en curso
gh run view --log-failed                     # solo los pasos que fallaron
```

## Otros comandos

```bash
./scripts/mvn.sh invoicing test   # tests de invoicing (Maven + Testcontainers + WireMock en Docker)
./scripts/mvn.sh catalog test     # tests de catalog (Maven + Testcontainers en Docker)
./scripts/mvn.sh sales test       # tests de sales (Maven + Testcontainers + WireMock en Docker)
./scripts/mvn.sh gateway test     # tests del gateway (enrutamiento con WireMock + realm en un Keycloak real)
./scripts/keycloak-token.sh --claims vendedor.demo   # token de un usuario de demostración (pide la contraseña)
./scripts/seed-catalog.sh         # datos de ejemplo en catalog (idempotente)
# Tests contra el sandbox real de Factus (no corren por defecto; crean 2 facturas y 1 nota crédito reales por ejecución)
MVN_ENV_FILE=.env ./scripts/mvn.sh invoicing test -Dgroups=sandbox -DexcludedGroups=none
./scripts/check-secrets.sh        # escaneo de secretos en todo el repo
```
