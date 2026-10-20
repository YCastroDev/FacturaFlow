# 0002. Arquitectura hexagonal en cada servicio

## Estado

Aceptado — 2026-10-20

## Contexto

Los servicios integran sistemas externos que pueden cambiar (la API de Factus para facturación electrónica ante la DIAN, PostgreSQL, RabbitMQ). Queremos que las reglas de negocio no dependan de esos detalles y que se puedan probar sin ellos.

## Decisión

Cada servicio sigue la arquitectura hexagonal (ports & adapters), con tres capas:

- `domain/`: modelo y reglas de negocio (entidades, value objects, excepciones de dominio). Java puro: no depende de Spring, JPA, Jackson ni de Factus.
- `application/`: casos de uso (`service/`) y puertos: `port/in` (lo que el servicio ofrece) y `port/out` (lo que necesita de afuera: persistencia, proveedor de facturación, mensajería).
- `infrastructure/`: adaptadores que implementan o invocan los puertos (`adapter/in/rest`, `adapter/out/persistence`, …) y la configuración de Spring (`config/`).

Las dependencias apuntan siempre hacia adentro: infrastructure → application → domain.

`domain/` y `application/` son Java puro: los casos de uso no llevan `@Service` ni `@Transactional`; se registran como beans (y se les agrega transaccionalidad) desde `infrastructure/config`.

**Verificación automática:** estas reglas se hacen cumplir con tests de ArchUnit (`HexagonalArchitectureTest` en el paquete `architecture` de los tests de cada servicio). Una violación hace fallar `./scripts/mvn.sh <servicio> test`.

## Alternativas consideradas

- **Arquitectura en capas tradicional (controller / service / repository):** más simple y con menos clases, pero la lógica de negocio termina dependiendo de JPA y de los clientes HTTP concretos, lo que dificulta cambiar de proveedor y probar de forma aislada.

## Consecuencias

**Positivas**
- Se puede cambiar de proveedor de facturación (Factus por otro) o usar adaptadores falsos en pruebas sin tocar el dominio.
- El dominio se prueba con tests unitarios rápidos, sin Spring ni base de datos.
- La estructura de paquetes hace visible dónde va cada cosa.
- Las reglas no dependen de la memoria ni de las revisiones de código: ArchUnit rompe el build si se violan.

**Negativas**
- Más clases e interfaces que en una arquitectura en capas.
- Mapeos entre modelos (dominio ↔ entidades JPA ↔ DTOs REST) que hay que escribir y mantener.
- Sin `@Service`/`@Transactional` en los casos de uso: los beans y las transacciones se configuran a mano en `infrastructure/config`.
