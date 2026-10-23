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

Para una nueva decisión: copia el formato de un ADR existente, usa el siguiente número y agrégalo a esta tabla. Un ADR aceptado no cambia su decisión: si la decisión cambia, se crea uno nuevo que lo reemplaza. Sí se pueden agregar notas que no la alteren (p. ej. cómo se verifica).
