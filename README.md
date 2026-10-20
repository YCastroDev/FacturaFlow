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

## Otros comandos

```bash
./scripts/mvn.sh invoicing test   # tests del servicio (Maven + Testcontainers en Docker)
./scripts/check-secrets.sh        # escaneo de secretos en todo el repo
```
