# 0019. Observabilidad, Fase 1: trazas distribuidas de punta a punta (OpenTelemetry), con el contexto de la traza guardado en el outbox

## Estado

Aceptado — 2026-11-10. Fase 1 de 2 de la observabilidad. La Fase 2 (logs JSON correlacionados en Loki y dashboards como código con métricas de negocio) tendrá su propio ADR. Completa los ADR 0010 (health independiente), 0013, 0014 y 0015 (outbox, consumo y republicación), 0016 (prueba de humo) y 0018 (cabeceras que no se registran).

## Contexto

Una venta recorre cuatro procesos y un broker:
1. gateway;
2. sales, que llama a catalog (y a Keycloak por su token);
3. outbox de sales → RabbitMQ;
4. invoicing, que emite con Factus (o con el proveedor falso);
5. outbox de invoicing → RabbitMQ;
6. sales otra vez, que deja la venta `INVOICED`.

Hasta ahora, para seguir una venta había que cruzar a mano los logs de cuatro contenedores por `referenceCode`. Tampoco había forma de ver dónde se va el tiempo, ni qué intento falló en un reintento.

**Objetivo de esta fase:** que una venta se vea como **una sola traza**, desde la petición al gateway hasta que la venta vuelve `INVOICED`.

**Restricción:** la observabilidad es infraestructura. El dominio y los casos de uso no cambian, y ArchUnit sigue en verde. El único cambio en `application` es un campo opaco en un record de un puerto (decisión 5).

Decisiones tomadas antes de esta fase (para las dos fases):
1. **Backend:** `grafana/otel-lgtm` con versión fijada y Grafana solo en `127.0.0.1`.
2. **La traza sobrevive al outbox:** el `traceparent` se guarda en la fila del outbox, en la misma transacción, y el publicador lo reanuda.
3. **(Fase 2)** Logs JSON correlacionados con la traza, enviados a Loki.
4. **(Fase 2)** Dashboards como código con métricas de negocio.

## Decisión

### 1. Backend: `grafana/otel-lgtm:0.40.0` en el compose

- **Un solo contenedor** con el colector de OpenTelemetry, Tempo (trazas), Prometheus, Loki y Grafana. Es una imagen pensada para desarrollo: en producción cada pieza va por separado (ver al final).
- **Versión fijada** (`0.40.0`). Dependabot (ecosistema `docker-compose`) la actualiza como las demás imágenes.
- **Puertos:** solo Grafana se publica, en `127.0.0.1:3000`. El OTLP (4317 gRPC, 4318 HTTP) queda dentro de la red de Docker: los servicios exportan a `http://lgtm:4318/v1/traces`.
- **Datos:** volumen `lgtm-data` (`/data`). Sobrevive a `down` y se borra con `down -v`.
- **Healthcheck:** el archivo `/tmp/ready`, que la imagen crea cuando todos sus componentes responden.
- **Sin plugins descargados al arrancar:** `GF_PLUGINS_PREINSTALL_DISABLED`. La imagen preinstala uno para perfiles (Pyroscope) que aquí no se usa, y así el entorno arranca sin salir a grafana.com.

### 2. Acceso a Grafana (D3): lector anónimo con Explore y administrador desde el `.env`

| Opción | A favor | En contra |
|---|---|---|
| Anónimo como Admin (lo que trae la imagen) | Cero configuración | Cualquier proceso local puede cambiar datasources o borrar dashboards |
| Solo credenciales | Lo más cerrado | La demo exige iniciar sesión; una contraseña más que manejar para solo mirar |
| **Anónimo `Viewer` + `viewers_can_edit` + admin del `.env` (elegida)** | La demo no pide login; nadie anónimo cambia la configuración | Una variable más en `.env` |

- Un `Viewer` sin `GF_USERS_VIEWERS_CAN_EDIT=true` **no puede usar Explore**, que es donde se buscan las trazas. Con esa opción puede consultar, pero no guardar dashboards ni tocar datasources.
- El administrador es `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`, con `:?` en el compose y `CAMBIAME` en `.env.example`.
- Todo está solo en `127.0.0.1`.

### 3. Micrometer Tracing con OpenTelemetry en los cuatro servicios

- **Dependencia:** `spring-boot-starter-opentelemetry` (Boot 4.1). Trae el puente `micrometer-tracing-bridge-otel`, el SDK de OpenTelemetry y el exportador OTLP. En los tests, `spring-boot-starter-opentelemetry-test` (más `opentelemetry-sdk-testing` en sales e invoicing, para el exportador en memoria).
- **Clientes HTTP:** sales e invoicing agregan `spring-boot-starter-restclient`. En Boot 4, el `RestClient.Builder` autoconfigurado (y su instrumentación) vive en ese módulo; el gateway ya lo tenía por Spring Cloud Gateway.
- **Exportación:** `management.opentelemetry.tracing.export.otlp.endpoint`, solo por variable de entorno (`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`, en el compose):
  - Sin la variable no hay exportador. Al correr un servicio fuera de Docker, o en los tests, no se intenta exportar.
  - No se pone un valor por defecto en `application.yml`: con la propiedad presente, aunque vacía, Boot crearía el exportador.
- **`service.name`** = `spring.application.name` (gateway, catalog, sales, invoicing).
- **Propagación W3C** (`traceparent` / `tracestate`), la de Boot por defecto. Se aceptan además B3 al consumir, sin uso aquí.
- **Métricas OTLP apagadas** (`management.otlp.metrics.export.enabled: false`): el starter trae el registro OTLP de métricas, y las métricas se deciden en la Fase 2.
- **Logs:** Boot ya agrega `[traceId-spanId]` a cada línea de log cuando hay una traza activa, así que un log se puede llevar a su traza desde ya. El envío a Loki es de la Fase 2.

### 4. Propagación por HTTP con el builder instrumentado

| Salto | Cómo |
|---|---|
| Cliente → gateway → servicio | El servidor (Spring MVC) lee el `traceparent` entrante; el proxy de Gateway WebMVC usa el `RestClient.Builder` de Boot, así el servicio recibe el span cliente **del gateway** (no una copia de la cabecera del llamador) |
| sales → catalog | `CatalogHttp` recibe el `RestClient.Builder` de Boot (antes `RestClient.builder()` estático, sin instrumentar). Cada intento (también el reintento) es un span cliente |
| sales → Keycloak (token) | `CatalogTokenProvider`, igual. Aparece solo cuando el token no está en memoria (decisión 9) |
| invoicing → Factus | `FactusHttp`, igual: un span por intento de Resilience4j y por la petición del token. Los constructores sin builder quedan para los tests sin Spring |

Los adaptadores leen los cuerpos con sus propios `JsonMapper` (bytes), así que los convertidores del builder de Boot (con `fail-on-unknown-properties` de la API) no cambian nada.

### 5. El contexto de la traza sobrevive al outbox (decisión 2)

El problema: el evento se guarda en la transacción de la petición, pero el relay lo publica después, desde otro hilo y sin la traza. Sin hacer nada, cada mensaje empezaría una traza nueva y la venta quedaría partida en tres.

**Cómo se resuelve:**
- **Migraciones** `sales V5` / `invoicing V8`: columnas `trace_parent` y `trace_state` (`TEXT`, opcionales) en `outbox_events`, con un CHECK (`trace_state` solo junto con `trace_parent`).
  - Son NULL en los eventos anteriores, o si no había traza activa.
  - No tienen formato fijo: el valor lo escribe y lo lee el propagador, y W3C admite versiones futuras.
- **Al guardar** (`JpaSaleRepositoryAdapter.saveNew`; en invoicing, `JpaInvoiceRepositoryAdapter.save` y `JpaOutboxAdapter.append`):
  - `OutboxTraceContext` (package-private del adaptador de persistencia, copia en cada servicio) inyecta el contexto del span actual con el `Propagator` de Micrometer;
  - guarda `traceparent` y `tracestate` en la MISMA transacción que el evento.
- **Al leer:** las columnas vuelven como un mapa de cabeceras en `OutboxMessage.traceContext()`.
- **Al publicar** (`RabbitEventPublisher`, en `adapter/out/messaging`):
  1. extrae el contexto guardado y abre un span `outbox <evento>`, hijo del span que guardó el evento;
  2. envía dentro de él. La observación de `RabbitTemplate` crea su span productor como hijo y escribe el `traceparent` en las cabeceras del mensaje.
  - Un evento sin contexto se publica igual, con una traza nueva.
  - El span `outbox` muestra en la traza el retraso del relay (desde que se guardó hasta que salió).

**El único cambio en `application`:** el record `OutboxMessage` (puerto de salida) gana el campo `Map<String, String> traceContext`. Es opaco, como `payload`: el caso de uso no lo lee, solo lo pasa del outbox al publicador. Se agregó un constructor sin el campo, así que ni el caso de uso ni sus tests cambiaron.

La alternativa era un canal lateral entre adaptadores (un ThreadLocal o un registro por `eventId`): no tocaba `application`, pero escondía un acoplamiento entre persistencia y mensajería, y fallaría sin avisar.

### 6. Propagación por RabbitMQ: el consumidor es un span HIJO (D2)

- **Configuración:** `spring.rabbitmq.template.observation-enabled` y `spring.rabbitmq.listener.simple.observation-enabled` en sales e invoicing.
- **Publicar:** Spring AMQP escribe `traceparent` en las cabeceras del mensaje.
- **Consumir:** el contenedor lo lee y abre un span de consumo **hijo** del span productor. Todo lo que pasa dentro queda en la misma traza:
  - el caso de uso;
  - la emisión;
  - el guardado del resultado, con su outbox (decisión 5).

| Opción | A favor | En contra |
|---|---|---|
| **Hijo (elegida)** | UNA traza de punta a punta (el objetivo); es lo que Spring AMQP hace de fábrica; OpenTelemetry lo admite al procesar un mensaje a la vez (prefetch 1) | La traza puede durar mucho: esperas de 10 s / 1 min / 5 min, o una DLQ reprocesada horas después, quedan como huecos largos |
| Span link | Trazas cortas, una por consumo | La venta deja de verse como una traza (hay que saltar entre trazas); código propio en los dos consumidores. Tiene sentido para lotes o fan-in, que aquí no hay |

**Reintentos y DLQ:** `ConfirmedRepublisher` usa el mismo `RabbitTemplate`. Al republicar en una cola de espera o en la DLQ, el `traceparent` del mensaje pasa a ser el del span de envío, hijo del consumo en curso.
- Al vencer la espera, el reintento es un consumo nuevo dentro de la misma traza.
- En Tempo se ven los intentos fallidos, la espera entre ellos y el intento que funcionó.
- Un mensaje reprocesado desde la DLQ (shovel, ADR 0014) conserva sus cabeceras y vuelve a la traza de su venta.

### 7. Spans de base de datos (JDBC): no en esta fase (D4)

- No se agrega `datasource-micrometer`.
- Habría que filtrar las consultas del relay (una por segundo y por servicio), que sin padre serían trazas sueltas.
- Los spans HTTP y de mensajería ya muestran dónde se va el tiempo.
- Queda como mejora si una traza muestra un hueco que solo explique la base.

### 8. Ruido fuera (D6)

Con muestreo al 100 %, sin filtros Tempo se llenaría de trazas inútiles.

| Qué | Por qué | Cómo |
|---|---|---|
| Peticiones a `/actuator/**` | Los healthchecks del compose corren cada 5–10 s por servicio | `ObservationPredicate` en `ObservabilityConfiguration` (copia deliberada en los cuatro servicios, con su test). Tampoco cuentan para la métrica `http.server.requests` |
| Ejecuciones de `@Scheduled` | El relay del outbox corre cada segundo | `management.observations.enable."[tasks.scheduled]": false` |
| Spans internos de Spring Security | Filtros, autenticación y autorización: una docena de spans por petición sin información útil | `management.observations.enable."[spring.security]": false`. Un 401 o un 403 sigue viéndose en el span del servidor |

### 9. Keycloak: solo del lado del cliente (D5)

- La petición del token de sales sale como span cliente, sin costo (decisión 4).
- **No** se activa el tracing interno de Keycloak (`KC_TRACING_*`):
  - el token de `sales-service` está en memoria unos 5 minutos, así que casi ninguna venta pasa por Keycloak;
  - la validación del JWT usa el JWKS en caché;
  - exigiría opciones de build de Keycloak y agregaría trazas de su propio tráfico.
- Si hiciera falta, son tres variables en el compose (`KC_TRACING_ENABLED`, `KC_TRACING_ENDPOINT`, `KC_TRACING_SAMPLER_RATIO`).

### 10. Proveedor falso con su span (D7)

- Sin llamada HTTP, la emisión con el proveedor falso no aparecería en la traza.
- `FakeElectronicInvoiceProvider` envuelve cada emisión en una `Observation` (`invoicing.fake.provider.issue`; span `fake-provider issue invoice` o `credit-note`). Una falla simulada queda como error del span.
- Con Factus, los spans son los de sus llamadas HTTP.

### 11. Muestreo

- `management.tracing.sampling.probability: ${TRACING_SAMPLING_PROBABILITY:1.0}`: 100 % en local, configurable por variable.
- El sampler es `parent-based-trace-id-ratio`: un servicio respeta la decisión de quien inició la traza (el gateway, o el productor del mensaje), así una traza nunca queda a medias entre servicios.

### 12. Sin colector, todo funciona igual (criterio del ADR 0010)

- **Arranque:** ningún servicio tiene `depends_on: lgtm`. El exportador no se conecta al arrancar.
- **Colector caído:** el `BatchSpanProcessor` descarta los spans de cada lote (cola acotada, 2048) y deja un WARN. No bloquea peticiones ni consumos.
- **Health:** no hay indicador del colector. `/actuator/health` no cambia.
- **Prueba de humo (ADR 0016):** arranca cada imagen con la variable del endpoint apuntando a `lgtm`, que en ese proyecto no existe, y espera `UP`. Así prueba justamente este caso. El script y el override no cambiaron: `.env.example` trae las variables nuevas con `CAMBIAME`.

### 13. Tests sin el contenedor de Grafana

- **Exportador en memoria:** `TracingTestConfiguration` registra un `InMemorySpanExporter` (SDK de OpenTelemetry). Boot lo conecta al SDK como cualquier exportador: se prueba el stack REAL (propagadores, sampler, observaciones de Spring MVC, RestClient y Spring AMQP) contra PostgreSQL y RabbitMQ de Testcontainers.
- **`@AutoConfigureTracing` es necesaria:** sin ella, Boot 4 deja el tracing inactivo en los tests (ni spans ni propagación). Lo comprobamos al escribir el test del gateway. Por eso los demás tests no cambian de comportamiento.

| Test | Prueba |
|---|---|
| `SaleTracingEndToEndTest` (sales) | Un POST con `traceparent`: las dos llamadas a catalog (WireMock) llevan la misma traza; la fila del outbox guarda el contexto; el relay (otro hilo) publica con un span `outbox SaleConfirmed` hijo del que guardó y el mensaje lleva el `traceparent` del span productor. Sin `traceparent` entrante empieza una traza nueva; una fila sin contexto se publica igual |
| `InvoiceResultTracingTest` (sales) | El consumo de `InvoiceIssued` es hijo del span productor (sin links); la DLQ conserva la traza |
| `SaleConfirmedTracingTest` (invoicing) | `SaleConfirmed` → consumo (hijo) → emisión → outbox → `InvoiceIssued` en la MISMA traza; con `SIMULAR_FALLA_TEMPORAL`, el reintento cuelga del envío a la cola de espera; con `SIMULAR_CAIDA`, la DLQ conserva la traza; un mensaje inválido registra `InvoiceRequestRejected` con la traza |
| `GatewayTracingTest` (gateway) | El servicio recibe la traza del cliente con el span del gateway como padre (no la cabecera copiada); sin `traceparent`, el gateway empieza la traza |
| `OutboxTraceContextTest`, `ObservabilityConfigurationTest` | Los bordes: sin tracing, filas sin contexto, el filtro de Actuator |

### 14. Lo que NO se registra

- **Spans HTTP:** Micrometer pone método, ruta (plantilla, p. ej. `/api/v1/sales/{id}`), estado y la URL del cliente, **sin cabeceras ni cuerpos**. El `Authorization` nunca entra en un span (ADR 0018).
- **Factus:** la URL de Factus es la base pública del sandbox. Los tokens van en cabeceras o en el cuerpo, nunca en la URL, y `public_url` no se pide en ninguna llamada.
- **Spans de mensajería:** exchange, routing key y `message_id`, sin el cuerpo.

## Demostración

En el README (sección de observabilidad):
1. crear una venta con el token de `vendedor.demo` y un `traceparent` propio;
2. abrir ese trace id en Grafana → Explore → Tempo;
3. o buscarla con TraceQL: `{ resource.service.name = "gateway" && span.http.route =~ "/api/v1/sales.*" }`.

## Cómo se haría en producción

- **Piezas separadas y gestionadas:** un colector de OpenTelemetry por nodo o como gateway, que reciba OTLP y exporte a un Tempo o Jaeger con almacenamiento de objetos (S3/GCS), o a un servicio gestionado (Grafana Cloud, Honeycomb, Datadog). Grafana con SSO (el mismo Keycloak) y roles.
- **Muestreo por cola en el colector** (`tail_sampling`): guardar el 100 % de las trazas con error o lentas y un porcentaje del resto. En los servicios, `TRACING_SAMPLING_PROBABILITY=1.0` hacia el colector (decide él), o `parentbased_traceidratio` bajo si el volumen hacia el colector pesara.
- **El borde no confía en el `traceparent` del cliente:** hoy el gateway acepta el que llega (útil para la demo). En producción, el gateway empezaría una traza nueva para el tráfico externo y conservaría el del cliente como link, para que nadie elija trace ids ni fuerce el muestreo con el flag `01`.
- **TLS y autenticación en OTLP** (cabeceras `management.opentelemetry.tracing.export.otlp.headers` con un token desde un gestor de secretos), y límites de atributos.
- **Spans de JDBC** con `datasource-micrometer`, solo con padre, si hiciera falta.
- **Trazas largas:** con esperas de minutos o reprocesos de la DLQ, se podría pasar a span links en el reintento desde la DLQ, para no tener trazas de horas.

## Consecuencias

- **Positivas:**
  - una venta se sigue de punta a punta en una sola traza: gateway, sales, catalog, Keycloak (si pide token), RabbitMQ, invoicing, el proveedor, y el regreso a sales;
  - los reintentos y la DLQ quedan en la misma traza, con sus esperas visibles;
  - los logs ya llevan el trace id;
  - el dominio y los casos de uso no cambiaron;
  - todo funciona sin el colector, y está probado sin Grafana.
- **Negativas:**
  - **Un campo opaco en el puerto `OutboxMessage`** (decisión 5) y dos columnas por outbox.
  - **Copias deliberadas:** `ObservabilityConfiguration` en los cuatro servicios; `OutboxTraceContext` y `TracingTestConfiguration` en sales e invoicing.
  - **Un contenedor más y pesado** en el entorno local (unos 1,5 GB de imagen).
  - **Trazas largas** con reintentos (D2), aceptadas a cambio de una sola traza.
  - **Tres contextos de Spring más en los tests** (cada uno con sus contenedores), por `@AutoConfigureTracing`.
  - **Un WARN periódico** en los logs si el colector no está (p. ej. en la prueba de humo).
