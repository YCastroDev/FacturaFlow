# 0003. Base de datos por servicio

## Estado

Aceptado — 2026-10-20

## Contexto

En microservicios, si dos servicios leen o escriben las mismas tablas quedan acoplados: un cambio de esquema en uno rompe al otro y no se pueden desplegar por separado. Al mismo tiempo, el proyecto corre en una máquina de desarrollo con RAM limitada.

## Decisión

Aplicar el patrón *Database per Service* con la variante **un solo servidor PostgreSQL, una base de datos y un usuario por servicio**. Cada usuario (p. ej. `invoicing_user`) solo tiene permisos sobre su propia base (`invoicing_db`). Las bases y usuarios se crean con un script de inicialización en `infra/postgres/init/`. Cada servicio gestiona su propio esquema con Flyway.

## Alternativas consideradas

- **Un contenedor PostgreSQL por servicio:** aislamiento físico total, pero multiplica el consumo de RAM y la administración en desarrollo.
- **Una base compartida por todos los servicios:** descartada; acopla los servicios a través del esquema y anula su independencia.

## Consecuencias

**Positivas**
- Separación lógica real desde hoy: un servicio no puede consultar la base de otro.
- Separación física mañana cambiando solo la URL de conexión del servicio.
- Cada servicio evoluciona su esquema de forma independiente con Flyway.

**Negativas**
- Los datos se duplican entre servicios cuando hace falta (p. ej. una venta guarda una copia del precio del producto en el momento de la venta) y hay que mantenerlos consistentes por eventos.
- No hay JOINs ni transacciones entre servicios; las consultas que cruzan datos se resuelven por API o eventos.
- Un único servidor PostgreSQL es un punto único de falla en desarrollo (aceptable en este contexto).
