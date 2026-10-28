# Architecture Decision Records (ADR)

Un ADR es un documento corto que registra una decisión técnica importante, su contexto y sus consecuencias, para que en el futuro se entienda *por qué* el sistema es como es.

| # | Decisión | Estado |
|---|----------|--------|
| [0001](0001-monorepo.md) | Monorepo para todos los servicios | Aceptado |
| [0002](0002-arquitectura-hexagonal.md) | Arquitectura hexagonal en cada servicio | Aceptado |
| [0003](0003-base-de-datos-por-servicio.md) | Base de datos por servicio (un PostgreSQL, una base y un usuario por servicio) | Aceptado |
| [0004](0004-modelo-de-dominio-de-factura.md) | Modelo de dominio de la factura: conceptos propios, totales propios + oficial, redondeo HALF_UP por línea | Aceptado |
| [0005](0005-persistencia-del-agregado.md) | Persistencia del agregado: JPA con entidades separadas, reconstitute, versión opaca y bloqueo optimista | Aceptado |
| [0006](0006-casos-de-uso-y-api-rest.md) | Casos de uso y API REST: idempotencia con huella, emisión con resultado sellado fuera de transacción, proveedor falso, Problem Details y springdoc | Aceptado |
| [0007](0007-datos-fiscales-del-cliente.md) | Datos fiscales del cliente (tipo de persona, situación tributaria, municipio DIVIPOLA) y unidades de medida emitibles | Aceptado (reemplaza parcialmente al 0004) |
| [0008](0008-integracion-con-factus.md) | Integración con Factus: Anti-Corruption Layer, token en memoria, mapeo de respuestas, verificación de identidad y PDF | Aceptado (completa el 0006) |
| [0009](0009-notas-credito.md) | Notas crédito: agregado separado, saldo por cantidades y bloqueo optimista sobre la factura | Aceptado (completa el 0005 y el 0006) |
| [0010](0010-resiliencia-factus.md) | Resiliencia de las llamadas a Factus: reintentos, circuit breaker y limitador (Resilience4j), health informativo | Aceptado (completa el 0008) |
| [0011](0011-servicio-catalog-en-capas.md) | Servicio catalog: arquitectura en capas (el 0002 sigue vigente para invoicing), datos maestros duplicados a propósito y concurrencia con ETag / If-Match | Aceptado |
| [0012](0012-servicio-sales.md) | Servicio sales: hexagonal, catalog por HTTP síncrono (consulta por lote, reintento corto y circuit breaker), snapshot de la venta y totales duplicados de invoicing | Aceptado (completa el 0011) |
| [0013](0013-mensajeria-y-outbox.md) | Mensajería entre servicios: RabbitMQ, coreografía, transactional outbox con SKIP LOCKED y publisher confirms, entrega al menos una vez, contrato con JSON Schema y health informativo | Aceptado (resuelve la publicación del 0012) |
| [0014](0014-consumo-de-sale-confirmed.md) | Invoicing consume `SaleConfirmed`: adaptador de entrada sobre los casos de uso existentes, clasificación del resultado, reintentos con colas de espera (10 s / 1 min / 5 min), DLQ, reprocesamiento con shovel y permisos mínimos probados | Aceptado (Fase 2 del 0013) |
| [0015](0015-cierre-de-la-saga-de-facturacion.md) | Cierre de la Saga: eventos de resultado de invoicing (`InvoiceIssued`, `InvoiceRejected`, `InvoiceRequestRejected`) con outbox, venta `INVOICED` / `INVOICE_FAILED`, "el éxito gana y el fallo no deshace", la DLQ y diagrama de secuencia | Aceptado (Fase 3 del 0013; completa el 0012) |
| [0016](0016-integracion-continua.md) | Integración continua con GitHub Actions: orquestador con filtros de ruta y `ci-result` como check único, workflow reutilizable por servicio, imágenes en GHCR, gitleaks del historial, Dependabot agrupado y SonarCloud opcional | Aceptado (cumple el 0001) |
| [0017](0017-seguridad-keycloak-y-gateway.md) | Seguridad, Fase 1: Keycloak 26.8 como proveedor de identidad con el realm como código (secretos por `${VAR}`, scope `catalog.read` para sales) y API Gateway con Spring Cloud Gateway WebMVC (rutas, 404/502/504 propios, health independiente), sin exigir token todavía | Aceptado (Fase 1 de 2) |
| [0018](0018-seguridad-resource-servers.md) | Seguridad, Fase 2: gateway y servicios como resource servers (JWT con audiencia `facturaflow-api`, roles de `realm_access` con un conversor copiado), matriz de permisos por URL con denegación por defecto, 401/403/503 en Problem Details, sales → catalog con client credentials dentro del circuito, Swagger con PKCE y arranque sin Keycloak | Aceptado (Fase 2 del 0017) |

Para una nueva decisión: copia el formato de un ADR existente, usa el siguiente número y agrégalo a esta tabla. Un ADR aceptado no cambia su decisión: si la decisión cambia, se crea uno nuevo que lo reemplaza. Sí se pueden agregar notas que no la alteren (p. ej. cómo se verifica).
