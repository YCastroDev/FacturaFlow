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
| PostgreSQL | 18 (`postgres:18-alpine`) |
| Migraciones | Flyway |
| Tests de integración | Testcontainers 2.x |
| Escaneo de secretos | gitleaks v8.30.1 (vía Docker) |

## Estructura

- `services/<servicio>/` — un proyecto Spring Boot independiente por servicio (hoy solo `invoicing`).
- `infra/` — `docker-compose.yml` y scripts de init de PostgreSQL.
- `scripts/` — utilidades (`mvn.sh`, `check-secrets.sh`).
- `.githooks/` — hook pre-commit con gitleaks.
- `docs/adr/` — Architecture Decision Records.

## Reglas de arquitectura

- Estructura hexagonal obligatoria en cada servicio (ADR 0002), bajo `com.facturaflow.<servicio>`:
  `domain/{model,exception}`, `application/{port/in,port/out,service}`, `infrastructure/{adapter/in/...,adapter/out/...,config}`.
- **Nada de anotaciones ni imports de Spring, JPA o Jackson en `domain/`.** El dominio es Java puro.
- Las dependencias apuntan hacia adentro: `infrastructure → application → domain`. `application` no conoce adaptadores concretos.
- **Prohibido** importar código de otro servicio o acceder a la base de datos de otro servicio (ADR 0001, ADR 0003). La comunicación entre servicios es solo por HTTP o eventos.

## Reglas de seguridad

- Ninguna contraseña, token, llave o credencial en código, `application.yml`, `docker-compose.yml`, scripts ni documentación. Todo se lee de variables de entorno, sin valores por defecto que contengan secretos.
- `.env` (raíz, ignorado por Git) contiene los valores reales. Claude **puede generar o modificar `.env` para desarrollo local**, pero **nunca mostrar su contenido** (ni con `cat`, `grep`, logs, `docker compose config`, etc.) **ni copiar sus valores a otros archivos**. Para saber qué variables existen, leer `.env.example`.
- Al agregar o renombrar una variable, actualizar `.env.example` (con valor `CAMBIAME`) en el mismo cambio.
- No desactivar ni saltarse el hook de gitleaks (`--no-verify`).

## Convenciones

- Código, clases, paquetes y endpoints en inglés. Documentación y ADRs en español.
- Esquema de base de datos solo con migraciones Flyway (`src/main/resources/db/migration/V<n>__<descripcion>.sql`). Nunca `ddl-auto=update`; se usa `validate`.
- Una migración ya aplicada no se edita: se crea una nueva.

## Comandos útiles (desde la raíz)

```bash
# Tests de un servicio (Maven en contenedor, Testcontainers incluido)
./scripts/mvn.sh invoicing test

# Cualquier otro goal de Maven
./scripts/mvn.sh invoicing -B package -DskipTests

# Levantar el entorno (PostgreSQL + invoicing)
docker compose --env-file .env -f infra/docker-compose.yml up --build -d

# Estado, logs y apagado
docker compose --env-file .env -f infra/docker-compose.yml ps
docker compose --env-file .env -f infra/docker-compose.yml logs -f invoicing
docker compose --env-file .env -f infra/docker-compose.yml down       # conserva datos
docker compose --env-file .env -f infra/docker-compose.yml down -v    # BORRA datos (re-ejecuta init de PostgreSQL)

# Health de invoicing
curl -s http://localhost:8081/actuator/health

# Escaneo de secretos de todo el repo
./scripts/check-secrets.sh
```

## Regla de trabajo

- Si una decisión técnica importante no está cubierta por un ADR, **preguntar antes de implementarla**.
- **No hacer commits** ni push; los hace el usuario.
