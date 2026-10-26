# Catálogo de eventos

Los eventos que los servicios de FacturaFlow publican en RabbitMQ y el contrato de cada uno. Las decisiones de fondo están en el [ADR 0013](../adr/0013-mensajeria-y-outbox.md) (productor: RabbitMQ, coreografía, transactional outbox, entrega al menos una vez) y en el [ADR 0014](../adr/0014-consumo-de-sale-confirmed.md) (consumidor: clasificación, reintentos con espera, DLQ y reprocesamiento).

Cada contrato es un **JSON Schema** (draft 2020-12) en esta carpeta. Es la única fuente de verdad: el productor y cada consumidor lo validan en sus propios tests, leyendo **este mismo archivo**, sin copiarlo ni empaquetarlo en una librería compartida (ADR 0001).

## Eventos

| Evento | Versión | Productor | Exchange | Routing key | Esquema | Consumidores |
|---|---|---|---|---|---|---|
| `SaleConfirmed` | 1 | sales | `sales.events` | `sale.confirmed.v1` | [sale-confirmed.v1.schema.json](sale-confirmed.v1.schema.json) | invoicing: cola `invoicing.sale-confirmed` (crea y emite la factura) |

### `SaleConfirmed` v1

**Cuándo:** una venta quedó confirmada en sales (`POST /api/v1/sales` → 201). No se publica en un reintento idempotente (200): la venta ya existía y su evento ya se guardó.

**Qué lleva:**
- todo lo necesario para crear la factura **sin consultar a catalog**: el cliente, las líneas con precio, unidad e impuestos, el pago y la observación, con los mismos nombres que `POST /api/v1/invoices` de invoicing;
- `saleId`, `customerId` y `productId`, para la trazabilidad;
- `referenceCode`, que en la Fase 2 será el `referenceCode` de la factura. Así la idempotencia de invoicing evita facturar dos veces la misma venta;
- **`totals`, informativo:** los totales según la copia del cálculo que tiene sales. Invoicing **no** los usa para calcular, porque es la autoridad (ADR 0004). Los compara con los suyos y registra un WARN si difieren, para detectar que las dos copias del cálculo (ADR 0012, D7) divergieron.

**Qué hace invoicing con él** (ADR 0014):
- lo traduce al mismo comando de `POST /api/v1/invoices`, crea la factura (idempotente por `referenceCode`) y la emite;
- un mensaje repetido no vuelve a emitir;
- emitida o rechazada por la DIAN → ack;
- falla técnica → reintento a los 10 s, 1 min y 5 min, y luego a la DLQ;
- mensaje inválido → DLQ sin reintentos.

**Ejemplo** (con los datos de `scripts/seed-catalog.sh`):

```json
{
  "eventId": "cb971997-a3db-47a8-98b5-950f38fead6d",
  "eventType": "SaleConfirmed",
  "eventVersion": 1,
  "occurredAt": "2026-10-26T16:38:58.404374822Z",
  "producer": "sales",
  "payload": {
    "saleId": "c375a76e-3820-4b55-a9d1-4d32f0172bb1",
    "referenceCode": "VENTA-2026-0001",
    "saleDate": "2026-10-26",
    "customer": {
      "customerId": "74c3ba4f-b067-416e-8255-39cfc3aa5d43",
      "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
      "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.", "names": null,
      "taxScheme": "IVA", "fiscalResponsibilities": ["LARGE_TAXPAYER", "SELF_WITHHOLDER"],
      "email": "facturacion@cliente.example", "municipalityCode": "05001",
      "address": "Carrera 48 # 10-45", "phone": "+576044445555"
    },
    "lines": [
      { "productId": "076af8f7-1a67-41a1-978e-0ba9af9cd1f4", "code": "CAFE-500", "name": "Café molido 500 g",
        "quantity": 2.00, "unitPrice": 18500.00, "discountRate": 10.00, "unit": "UNIT", "ivaExcluded": false,
        "taxes": [ { "type": "IVA", "rate": 5.00 } ] }
    ],
    "paymentMethod": "CASH",
    "paymentTerms": { "type": "CASH", "dueDate": null },
    "observation": null,
    "totals": { "subtotal": 37000.00, "totalDiscount": 3700.00,
                "taxes": [ { "type": "IVA", "amount": 1665.00 } ], "totalTax": 1665.00, "total": 34965.00 }
  }
}
```

## Convenciones (todos los eventos)

### Sobre (envelope)

Todo mensaje tiene la misma forma externa:

| Campo | Qué es |
|---|---|
| `eventId` | UUID del evento. **Un reenvío conserva el mismo `eventId`**: es la clave para descartar duplicados. |
| `eventType` | Nombre del hecho, en pasado y en PascalCase (`SaleConfirmed`). |
| `eventVersion` | Versión del contrato de ese evento (entero, desde 1). |
| `occurredAt` | Cuándo ocurrió el hecho (UTC, ISO-8601). **No** es cuándo se publicó: puede publicarse más tarde si el broker estaba caído. |
| `producer` | Servicio que lo publica. |
| `payload` | Los datos del hecho, definidos por el esquema de cada evento. |

### Mensaje AMQP

| Propiedad | Valor |
|---|---|
| exchange | `<servicio>.events`, de tipo topic y durable (uno por servicio productor) |
| routing key | `<agregado>.<hecho>.v<versión>`, p. ej. `sale.confirmed.v1` |
| `delivery_mode` | `2` (persistente) |
| `message_id` | el `eventId` |
| `type` | el `eventType` |
| `app_id` | el `producer` |
| `timestamp` | el `occurredAt` (en segundos) |
| `content_type` / `content_encoding` | `application/json` / `UTF-8` |
| cabecera `eventVersion` | el `eventVersion` |

Spring AMQP agrega además la cabecera `spring_returned_message_correlation` (igual al `eventId`), que el productor usa internamente para las confirmaciones. No es parte del contrato: los consumidores la ignoran.

### Formato de los datos

- Nombres en inglés y en camelCase, alineados con la API REST del servicio que los usa.
- Enums como texto, con los mismos valores que las APIs (`NIT`, `LEGAL_ENTITY`, `IVA`…).
- Montos en COP como números JSON con 2 decimales y sin notación científica (`18500.00`). Porcentajes como número (`19` = 19 %).
- Fechas `YYYY-MM-DD`; instantes ISO-8601 en UTC.
- Los opcionales vacíos van como `null` explícito: la forma del mensaje no cambia.
- Esquemas con `additionalProperties: false`: el productor no puede mandar un campo que el contrato no tenga.

## Garantías para un consumidor

- **Al menos una vez:** un evento puede llegar más de una vez (ADR 0013, decisión 6). Hay que procesarlo de forma idempotente: por `eventId`, o por la clave de negocio (`referenceCode`).
- **Sin orden garantizado** entre eventos: no hay que depender de él.
- **Tolerancia:** el consumidor debe ignorar los campos que no conoce. Así un cambio compatible del productor no lo rompe, aunque el esquema sea estricto para el productor.
- **Coreografía:** el productor no sabe quién escucha. Cada consumidor declara su cola (`<consumidor>.<evento>`) y la enlaza al exchange del productor con la routing key que le interesa.

## Evolución de un contrato

| Cambio | Cómo se publica |
|---|---|
| Campo **opcional** nuevo, un valor nuevo de un enum que el consumidor ya trata de forma genérica | Compatible: misma versión. Se actualiza el esquema y el test del productor en el mismo cambio; los consumidores lo ignoran hasta que lo usen. |
| Campo obligatorio nuevo, quitar o renombrar un campo, cambiar su tipo o su significado | **Incompatible:** nueva versión (`sale-confirmed.v2.schema.json`, `eventVersion: 2`, routing key `sale.confirmed.v2`). El productor publica las dos mientras haya consumidores de la v1. |

Un esquema publicado no se edita de forma incompatible, igual que una migración aplicada.

## Agregar un evento

1. El hecho en el dominio del productor (un caso nuevo de su interfaz sellada de eventos) y quién lo registra.
2. Su esquema en esta carpeta (`<evento>.v1.schema.json`) y su fila en la tabla de eventos.
3. Su serialización en el adaptador del productor, y el test que valida contra el esquema, incluidos mensajes rotos que el esquema debe rechazar.
4. El valor nuevo en el CHECK `event_type` del outbox (migración).
5. Si lo publica un servicio nuevo: su exchange, y su usuario de RabbitMQ en `infra/rabbitmq/init/create-service-users.sh` (y la declaración de su exchange allí).
6. Para cada consumidor: su cola, sus esperas y su DLQ con el prefijo del consumidor, permiso de `read` sobre el exchange del productor, y un test que valide sus eventos de prueba contra el esquema.

## Consumidores: cola, reintentos y DLQ (ADR 0014)

Cada consumidor declara su propia topología con el prefijo de su servicio. Para invoicing:

| Recurso | Para qué |
|---|---|
| `invoicing.sale-confirmed` | Su cola, enlazada a `sales.events` con `sale.confirmed.v1` |
| `invoicing.retry` → `invoicing.sale-confirmed.wait-10s`, `wait-1m`, `wait-5m` | Colas de espera con TTL, sin consumidores. La cabecera `x-attempt` cuenta los reintentos. |
| `invoicing.requeue` | Por aquí vuelve un mensaje a la cola cuando vence su espera |
| `invoicing.dlx` → `invoicing.sale-confirmed.dlq` | La DLQ |

Los mensajes de la DLQ conservan el cuerpo y el `message_id`, y llevan `x-failure-category` (`technical`/`invalid`), `x-failure-reason`, `x-attempts-made` y `x-failed-at`.

**Reprocesar la DLQ** (tras corregir la causa):
- `./scripts/rabbitmq-dlq.sh requeue`, o "Move messages" en la consola;
- los mensajes vuelven con todos sus intentos;
- con `status` y `peek` se ve qué hay sin sacarlo.

**Exchanges de los productores:** `infra/rabbitmq/init/create-service-users.sh` también los declara, para que un consumidor pueda enlazar su cola aunque el productor aún no haya arrancado.

## Ver los mensajes en local sin un consumidor

```bash
./scripts/rabbitmq-debug-queue.sh create   # cola temporal debug.sales-events enlazada a sales.events
./scripts/rabbitmq-debug-queue.sh peek     # hasta 5 mensajes, sin sacarlos de la cola
./scripts/rabbitmq-debug-queue.sh delete
```

Consola: <http://localhost:15672> (usuario `RABBITMQ_ADMIN_USER` del `.env`), en Queues and Streams → `debug.sales-events` → Get messages.
