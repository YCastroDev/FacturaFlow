# FacturaFlow

Plataforma de ventas para pymes colombianas que emite facturas electrónicas ante la DIAN a través de la API de [Factus](https://www.factus.com.co/). Proyecto de portafolio construido con microservicios en Spring Boot.

## Arquitectura

- **Monorepo** con un proyecto independiente por servicio en `services/` (hoy: `invoicing`; luego catalog, sales, notifications, identity con Keycloak, gateway y frontend).
- **Arquitectura hexagonal** (ports & adapters) dentro de cada servicio.
- **Base de datos por servicio**: un PostgreSQL con una base y un usuario por servicio; esquema gestionado con Flyway.
- Comunicación entre servicios solo por HTTP o eventos (RabbitMQ, más adelante).
- Todo corre en Docker: no se necesita Java ni Maven instalados.

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
3. **Levantar el entorno:**
   ```bash
   docker compose --env-file .env -f infra/docker-compose.yml up --build
   ```
4. **Comprobar** que invoicing responde:
   ```bash
   curl http://localhost:8081/actuator/health    # {"status":"UP"}
   ```

## Probar la API con Swagger

Con el entorno levantado, abre **http://localhost:8081/swagger-ui.html**. Para probar el flujo completo:

1. **Crear** (`POST /api/v1/invoices`, sección *Facturas*): elige uno de los ejemplos del desplegable (*Contado con IVA* o *Crédito con IVA + INC*) y pulsa *Execute*. La respuesta es `201` con el header `Location` y los totales calculados.
2. **Repetir** la misma petición: responde `200` con la misma factura, sin crear otra. Si cambias cualquier dato y mantienes el `referenceCode`, responde `409`.
3. **Emitir** (`POST /api/v1/invoices/{id}/issue`) con el `id` de la respuesta.
4. **Consultar** por id (`GET /api/v1/invoices/{id}`) o por código (`GET /api/v1/invoices?referenceCode=...`).

La especificación OpenAPI está en http://localhost:8081/v3/api-docs. Los errores siguen el formato Problem Details (RFC 9457).

### Proveedor de facturación falso

Mientras no exista la integración con Factus, la emisión usa un proveedor **simulado** (`invoicing.provider=fake`, el valor por defecto). Su comportamiento depende de la **observación** de la factura:

| La observación contiene | Resultado de `POST /{id}/issue` |
|---|---|
| `SIMULAR_RECHAZO` | `200` con la factura `REJECTED` y un motivo de ejemplo |
| `SIMULAR_CAIDA` | `503` con `Retry-After`; la factura sigue `PENDING` y se puede reintentar |
| ninguna de las dos | `200` con la factura `ISSUED` |

Una emisión "exitosa" del proveedor falso **no tiene validez ante la DIAN**:
- el número es `FAKE-<referenceCode>`;
- el CUFE empieza con 16 ceros;
- el PDF apunta a `https://fake-provider.invalid/...`, un dominio reservado que nunca resuelve.

Los ejemplos a crédito usan un vencimiento lejano (`2030-01-31`) porque el vencimiento debe ser posterior a la fecha de emisión, que es la de hoy en Colombia.

## Otros comandos

```bash
./scripts/mvn.sh invoicing test   # tests del servicio (Maven + Testcontainers en Docker)
./scripts/check-secrets.sh        # escaneo de secretos en todo el repo
```
