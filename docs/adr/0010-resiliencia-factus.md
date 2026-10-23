# 0010. Resiliencia de las llamadas a Factus: reintentos, circuit breaker y limitador

## Estado

Aceptado — 2026-10-23. Completa el ADR 0008 y reemplaza su limitación "sin resiliencia todavía".

## Contexto

`invoicing` llama a Factus para emitir facturas y notas crédito, descargar sus PDF y obtener el token. Hasta ahora, cualquier falla de red, 5xx o 429 respondía 503 al primer intento. No había límite de peticiones: el límite real de Factus es 120/min en la API y 60/min en `/oauth/token`, y el sandbox es compartido. Un Factus caído o lento seguía recibiendo una llamada (y un timeout) por cada petición.

Restricciones:
- **Arquitectura:** la resiliencia es un detalle del adaptador. El dominio y los casos de uso no cambian, y ArchUnit lo exige.
- **Sin procesos en segundo plano:** los reintentos largos llegarán con RabbitMQ.

## Decisión

### 1. Resilience4j, programático, en un solo punto

- **Dependencia:** `io.github.resilience4j:resilience4j-spring-boot4` **2.4.0**. Es la primera versión para Spring Boot 4 y la última publicada (marzo de 2026); su POM declara Boot 4.0.0 y funciona con nuestro 4.1.1. El BOM de Boot no la gestiona, así que la versión está fijada en el pom (`resilience4j.version`).
- **Un solo punto:** todas las llamadas a Factus salen por `FactusHttp.exchange`, también las del token. Ahí `FactusResilience` envuelve **cada llamada HTTP** con decoradores, sin anotaciones ni AOP. Del más externo al más interno:

  **Retry → CircuitBreaker → RateLimiter → HTTP**

  - Cada intento real cuenta en el circuito y consume un permiso del limitador.
  - Con el circuito abierto, no se gasta ningún permiso.
- **Registros del starter:** las instancias (todas llamadas `factus`) se crean en los registros que trae el starter, así sus métricas Micrometer y sus endpoints de Actuator las ven. Su configuración sale de `invoicing.factus.resilience.*` (un record validado), no de las claves `resilience4j.*`, por dos razones:
  - las reglas de qué se reintenta y qué cuenta como falla son código (decisión 2);
  - así la configuración solo existe con `invoicing.provider=factus`.

### 2. Qué se reintenta y qué cuenta como falla

| Resultado de una llamada HTTP | Reintento | Falla del circuito | Por qué |
|---|---|---|---|
| Timeout, error de red | sí | sí | Es la falla técnica típica. |
| 5xx | sí | sí | El servidor de Factus falló. |
| 429 | sí, si su `Retry-After` ≤ 5 s | sí | Es sobrecarga: abrir el circuito nos hace dejar de insistir. |
| 409 "pendiente por enviar a la DIAN" | sí | **no** (se ignora) | Es el estado de **un** documento, no una caída de Factus. Un documento atascado no debe cortar las emisiones de todos. |
| 2xx, 401, 422, otros 4xx | no: la respuesta sube tal cual | no | Son respuestas de negocio o de configuración. |
| Circuito abierto, limitador agotado | no | no (el limitador se ignora) | Son decisiones nuestras, no fallas de Factus. |

**Lo que no pasa por aquí:**
- **422 por datos:** se convierte en `Rejected` más arriba, en el intérprete. Nunca se reintenta y nunca abre el circuito: los rechazos de la DIAN no son fallas.
- **Identidad distinta** (otra factura u otra nota, ADR 0008, decisión 6): se detecta en el intérprete, **después** de la llamada. No se reintenta ni cuenta como falla.
- **401:** lo sigue resolviendo `FactusClient`, que renueva el token y reintenta **una** vez. El retry nunca ve un 401, así que no se duplica.

**Reintentos agotados.** Si se agotan con una respuesta HTTP (p. ej. tres 500), esa última respuesta sube al intérprete, que produce el mismo mensaje técnico de siempre ("HTTP 409, … pendiente").

**Reintentar un `POST /validate` es seguro.**
- Factus es idempotente por `reference_code`, y verificamos la identidad de la respuesta.
- Además, tras un timeout en el que Factus **sí** procesó el documento, el reintento lo recupera. Eso reduce el caso de la doble escritura del ADR 0006.
- Reintentar un refresh del token tras un timeout también es seguro: si el refresh viejo quedó revocado, `InMemoryTokenProvider` vuelve a autenticarse con la contraseña (ADR 0008, decisión 3).

### 3. Circuito abierto: 503 inmediato con el `Retry-After` real

- **Respuesta:** con el circuito abierto, la llamada ni sale. `ElectronicInvoiceProviderUnavailableException` lleva ahora un **motivo** (`FAILURE`, `CIRCUIT_OPEN`, `RATE_LIMITED`) y un **`retryAfter`** opcional.
- **`Retry-After`:** es lo que falta para el semiabierto. Se calcula con el instante en que el circuito pasó a OPEN, que registra el `EventPublisher`, y se redondea hacia arriba, con un mínimo de 1 s.
- **`ProblemDetailsHandler`:**
  - responde con ese `Retry-After`, o con los 30 s de siempre si el adaptador no lo sabe;
  - agrega `reason: circuit-open | rate-limited | failure`;
  - el detalle explica el motivo, por ejemplo: "El proveedor de facturación electrónica falló varias veces seguidas y se dejó de llamar por un momento para no saturarlo. La factura sigue PENDING; reintente la emisión en 29 s".
  - Un 429 de Factus que no se reintenta (`Retry-After` > 5 s) pasa ese mismo `Retry-After` al cliente.
- **Log:** cada cambio de estado del circuito queda como WARN, por ejemplo `Circuito de Factus: CLOSED → OPEN. Las llamadas a Factus responderán 503 durante 30 s…`. Los 503 por circuito abierto o por el limitador se registran sin traza, porque no son fallas nuevas.

### 4. Valores (`invoicing.factus.*` en `application.yml`)

| Valor | Elegido | Por qué |
|---|---|---|
| `connect-timeout` | 3 s (antes 5 s) | Conectar a Factus toma milisegundos; 3 s ya indica un problema. |
| `read-timeout` | 10 s (antes 30 s) | La validación tardó unos 2,3 s y el PDF menos de 1 s. Con 30 s y 3 intentos, una petición pasaría de 90 s. |
| `retry.max-attempts` | 3 | 1 + 2 reintentos: suficiente para una falla transitoria sin alargar la petición. |
| `retry.initial-wait` / `multiplier` / `jitter` | 500 ms / ×2 / ±50 % | Esperas de unos 250–750 ms y 500–1500 ms. El jitter evita que varias peticiones reintenten a la vez. |
| `retry.max-retry-after` | 5 s | El máximo `Retry-After` de un 429 que vale la pena esperar dentro de la petición. |
| `circuit-breaker.sliding-window-size` | 10 llamadas (por conteo) | |
| `circuit-breaker.minimum-number-of-calls` | 5 | No se abre por 1 o 2 fallas aisladas. |
| `circuit-breaker.failure-rate-threshold` | 50 % | Con 3 intentos por petición, unas **2 emisiones fallidas** lo abren. |
| `circuit-breaker.wait-duration-in-open-state` | 30 s | Coincide con el `Retry-After` que ya usábamos. |
| `circuit-breaker.permitted-number-of-calls-in-half-open-state` | 2 | Dos llamadas de prueba deciden si cierra o vuelve a abrir. |
| `rate-limiter.limit-for-period` / `limit-refresh-period` | 10 cada 6 s (= 100/min) | Ver abajo. |
| `rate-limiter.timeout` | 2 s | Espera máxima por un permiso; luego, 503 (`rate-limited`, `Retry-After` 6 s). |

**Por qué ventanas de 6 s y no una de 60 s.** El limitador de Resilience4j usa ventanas fijas. Con 100 cada 60 s, se podrían enviar 100 peticiones al final de un minuto y otras 100 al inicio del siguiente: 200 en pocos segundos, por encima del límite real de 120. Con ventanas de 6 s, ningún intervalo de 60 s pasa de 110 (11 ventanas). El costo es que solo se permiten ráfagas de 10.

El token comparte el limitador. Se pide una vez por hora, así que su límite propio (60/min) nunca se acerca.

**Sin regla de llamadas lentas.** El circuito no usa `slowCallRateThreshold`: los timeouts ya cuentan como fallas, y una llamada lenta que termina bien es un éxito.

### 5. Peor caso de duración

**Una llamada HTTP a Factus**, contando sus reintentos:

| Escenario | Duración |
|---|---|
| Peor caso teórico: 3 × (2 s de limitador + 3 s de conexión + 10 s de lectura) + 2,25 s de esperas | **~47 s** |
| Factus acepta la conexión y no responde: 3 × 10 s + 2,25 s | ~32 s |
| Host inalcanzable (timeout de conexión): 3 × 3 s + 2,25 s | ~11 s |
| Conexión rechazada o cortada | ~2,3 s |

Una emisión suele hacer una sola llamada, porque el token está en memoria. El caso extremo encadena el token, la validación y el camino del 401, que vuelve a pedir el token y reintenta.

**~47 s supera el timeout típico de navegadores y gateways (~30 s).** El cliente podría cortar la conexión mientras el servidor sigue intentando. Es aceptable **hoy**, por tres razones:
- **El circuito se abre tras unas 2 emisiones fallidas** (5 llamadas en la ventana con ≥ 50 % de fallas). Desde ahí, todas las peticiones responden 503 al instante (en la demostración, 0,007 s) hasta el semiabierto. El caso lento lo sufren unas pocas peticiones, no todas.
- Si el cliente corta, la factura sigue PENDING, y reintentar es seguro por la idempotencia de la creación y de Factus.
- Es un proyecto de portafolio, y el peor caso teórico exige que se combinen el limitador lleno, la conexión lenta y la lectura lenta.

**Deja de ser un problema con RabbitMQ:** la emisión pasará a ser **asíncrona**. La petición HTTP solo encola la orden y responde 202; un consumidor llama a Factus. Entonces la duración de los reintentos no la percibe ningún cliente.

### 6. El health no depende de Factus

- **Componente propio:** `FactusHealthIndicator` es el componente `factus` del health. **Siempre reporta UP**. Sus detalles son informativos:
  - el estado del circuito, la tasa de fallas y las llamadas en la ventana, fallidas y no permitidas;
  - los segundos para el semiabierto;
  - los permisos libres del limitador.
- **Por qué siempre UP:** que Factus esté caído no significa que este servicio esté mal. Puede seguir creando y consultando facturas y notas. Un health en DOWN haría que un orquestador reiniciara o sacara de servicio la instancia, sin arreglar nada y cortando también lo que sí funciona.
- **Configuración:**
  - `/actuator/health` conserva `show-details: never` y `show-components: never`: solo muestra `"status":"UP"`, sin detalles de la base ni del disco.
  - El grupo **`/actuator/health/providers`** (`include: factus`, `show-details: always`) muestra el componente con sus detalles. El grupo no puede llamarse `factus` porque Spring Boot no permite que un grupo y un componente compartan nombre.
  - `liveness` y `readiness` no incluyen `factus`.
  - `validate-group-membership: false`: con el proveedor falso, el componente no existe y el grupo queda vacío en vez de impedir el arranque.
  - Los indicadores del starter están desactivados (`management.health.circuitbreakers.enabled: false` y `ratelimiters.enabled: false`). Con el circuito abierto bajarían el health o reportarían un estado `CIRCUIT_OPEN` que complica la agregación.

### 7. Observabilidad

**Endpoints de Actuator expuestos**, todos de solo lectura:

| Endpoint | Muestra |
|---|---|
| `health` (+ `/health/providers`) | Estado del servicio y componente informativo `factus` |
| `info` | Nombre y versión |
| `metrics` | Métricas, entre ellas las de la tabla siguiente |
| `circuitbreakers` y `circuitbreakerevents` (Resilience4j) | Estado del circuito y sus últimos eventos |
| `retries` (Resilience4j) | La instancia de reintentos `factus` |
| `ratelimiters` (Resilience4j) | La instancia del limitador `factus` |

**Métricas:**

| Métrica | Mide |
|---|---|
| `resilience4j.circuitbreaker.state` | Estado del circuito |
| `resilience4j.circuitbreaker.calls{kind=successful\|failed\|ignored}` | Llamadas por resultado |
| `resilience4j.circuitbreaker.not.permitted.calls` | Llamadas rechazadas por el circuito |
| `resilience4j.circuitbreaker.failure.rate` | Tasa de fallas |
| `resilience4j.retry.calls{kind=successful_without_retry\|successful_with_retry\|failed_with_retry\|failed_without_retry}` | Llamadas según cuántos reintentos necesitaron |
| `resilience4j.ratelimiter.available.permissions` / `waiting_threads` | Estado del limitador |
| `invoicing.factus.rejected{reason=circuit_open\|rate_limited}` | Llamadas que no se hicieron. Es propia, porque las métricas del limitador no cuentan los rechazos |

**Nada sensible.** Nada de esto contiene tokens, credenciales ni la URL pública. `FactusActuatorTest` lo verifica.

**En producción**, Actuator iría en un puerto de gestión aparte (`management.server.port`), con autenticación, y las métricas se exportarían a Prometheus. Aquí el puerto solo se publica en `127.0.0.1` (docker-compose).

### 8. Tests

| Test | Qué cubre |
|---|---|
| `FactusResilienceTest` (WireMock con escenarios, tiempos de milisegundos) | Reintentos: falla, falla y éxito (3 peticiones); notas crédito; timeout; 422 con un solo intento; identidad distinta con un solo intento; el 401 sin duplicarse; el 409 reintentado pero no contado; 429 con `Retry-After` corto (espera) y largo (no reintenta, se propaga). Circuito: se abre, y las llamadas siguientes de facturas **y** notas no llegan a WireMock; semiabierto y cerrado; los 422 no lo abren; log de las transiciones; métrica de rechazos. Limitador: agotado da 503 en menos de 1 s, sin llamar a Factus ni contar en el circuito. |
| `FactusActuatorTest` (aplicación completa con `provider=factus`) | Con el circuito abierto: `/actuator/health`, `liveness` y `readiness` siguen UP; `/actuator/health/providers` muestra OPEN sin secretos; emitir da 503 en menos de 1 s con `Retry-After` y `reason: circuit-open`, sin peticiones a Factus; están los endpoints y las métricas |
| `InvoiceControllerTest` | El 503 con `Retry-After` dinámico y su `reason` |
| `FactusElectronicInvoiceProviderTest`, `FactusCreditNoteProviderTest` | Siguen probando la **tabla de resultados**, con un solo intento (`FactusTestData.singleAttempt()`) |
| `FactusSandboxTest` | Contra el sandbox real, con los valores de producción |

## Lo que queda para RabbitMQ

- **Reintentos largos:** minutos u horas, en segundo plano, con cola de reintentos y *dead letter*. Hoy solo hay reintentos cortos dentro de la petición, y si se agotan la factura o la nota queda PENDING y el cliente debe reintentar.
- **Emisión asíncrona:** elimina el problema del peor caso de duración (decisión 5).
- **Notas PENDING atascadas:** los reintentos cortos ya reducen el caso (ADR 0009). La resolución definitiva es la consulta a Factus por `reference_code` desde un consumidor o un proceso de reconciliación.
- **Varias instancias:** el circuito y el limitador son **por instancia**, en memoria. Con N réplicas, el límite efectivo sería N × 100/min. Un limitador compartido (Redis, o el propio *broker* regulando el consumo) llegaría con el escalado horizontal.

## Alternativas consideradas

- **Anotaciones (`@Retry`, `@CircuitBreaker`) con AOP:** dependen de proxies de Spring alrededor de beans públicos. El adaptador es package-private y no es un bean por cada llamada. Además, ocultan el orden de los decoradores y las reglas de clasificación.
- **Claves estándar `resilience4j.*` en el yml:** los predicados tendrían que declararse por nombre de clase, mezclando yml y código, y la configuración existiría también con el proveedor falso.
- **Un circuito por operación** (facturas, notas, PDF, token): es la misma API y el mismo servidor. Si cae, cae para todos, y un solo circuito se abre antes.
- **Contar el 409 como falla del circuito:** sería más literal, pero un solo documento atascado podría cortar las emisiones de todos.
- **Una sola ventana de 100 cada 60 s:** permitiría 200 peticiones en pocos segundos (decisión 4).
- **Indicador de health del starter, o un health que baje con Factus:** ver la decisión 6.
- **`TimeLimiter` para acotar la duración total:** exige ejecutar la llamada en otro hilo (asíncrona) y no corta la petición HTTP en curso. Ajustar los timeouts y abrir el circuito acota el caso, y RabbitMQ lo elimina.

## Consecuencias

**Positivas**
- **Fallas transitorias:** se resuelven solas en la misma petición, sin que el usuario reintente, y sin riesgo de duplicar documentos.
- **Factus caído:** deja de recibir llamadas, y nuestras peticiones responden al instante con un `Retry-After` honesto.
- **Límite de peticiones:** nunca superamos el de Factus desde una instancia.
- **Observabilidad:** el estado se ve en Actuator, en métricas y en los logs, sin que el health mienta sobre el servicio.

**Negativas**
- El peor caso de duración (~47 s) supera el timeout típico de un cliente HTTP hasta que la emisión sea asíncrona.
- El circuito y el limitador son por instancia.
- La configuración de resiliencia es propia (`invoicing.factus.resilience.*`), no la estándar de Resilience4j. Quien conozca Resilience4j debe buscarla aquí.
