# 0020. Observabilidad, Fase 2: logs correlacionados en Loki, métricas de negocio y dashboards como código

## Estado

Aceptado — 2026-11-10. Fase 2 de 2 de la observabilidad; la Fase 1 (trazas) es el ADR 0019, cuyas decisiones 3 y 4 (logs JSON en Loki y dashboards como código) implementa este. Completa el ADR 0016 (filtros de la CI y una dependencia fuera de Dependabot).

## Contexto

Tras la Fase 1, una venta se ve como una sola traza en Tempo. Faltan dos cosas:

1. **Logs:** siguen en texto, en la consola de cada contenedor. Llevan el `[traceId-spanId]`, pero para leerlos hay que entrar a cada contenedor. Ni Grafana puede mostrarlos ni se puede saltar de una traza a sus logs.
2. **Métricas:** solo hay seis propias, todas técnicas (outbox y consumidores), y no salen del servicio. Nadie puede responder "¿cuántas ventas hubo hoy, cuánto se vendió, qué fracción se facturó y cuánto tarda la Saga?".

La restricción es la misma de la Fase 1: la observabilidad es infraestructura. Dominio y casos de uso no cambian, y ArchUnit sigue en verde.

## Decisión

### 1. Logs a Loki por OTLP, desde cada servicio (D1)

- **Appender:** `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`. `OpenTelemetryLogsConfiguration` (copia deliberada en los cuatro servicios, con su test) lo agrega **por código** al logger raíz y lo conecta al SDK de OpenTelemetry de Spring Boot. Cada registro sale con el `trace_id` y el `span_id` de la traza en curso, más el `service_name`.
- **Por código y no con un `logback-spring.xml`:** la configuración de Logback de Boot queda intacta, incluido el JSON de la consola (decisión 2).
- **Idempotente:** en los tests, varios contextos de Spring comparten el mismo Logback, así que el appender se agrega una sola vez.
- **Exportación solo por variable** (`MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT`, en el compose → `http://lgtm:4318/v1/logs`). Mismo criterio que las trazas: ningún valor en `application.yml`, porque con la propiedad presente Boot crearía el exportador.
- **Sin bucle:** los logs del propio SDK (`io.opentelemetry.*`) no se exportan. Con el colector caído, la falla de exportación produce un WARN, que el appender volvería a exportar y a fallar.
- **Versión fijada** en `2.28.1-alpha`, la que depende de `opentelemetry-api` 1.62.0, la misma que fija Boot 4.1.2. La última (2.33) pide la API 1.67, y Maven la habría bajado a 1.62 sin avisar. Dependabot la ignora; se sube a mano junto con Boot (nota en el ADR 0016).
- **El salto traza ↔ logs ya venía configurado en la imagen `lgtm`:**
  - el datasource de Tempo busca en Loki `{service_name=...} | trace_id = "<traza>"`;
  - el de Loki enlaza el `trace_id` de cada registro a Tempo.

| Opción | A favor | En contra |
|---|---|---|
| **Appender OTLP (elegida)** | `trace_id` y `span_id` como metadatos estructurados, sin parsear texto; el salto traza ↔ logs funciona solo; no monta nada del host | Una dependencia "alpha" con versión atada a la de Boot; hay que cortar el bucle de los logs del SDK |
| Colector leyendo los logs de Docker (`filelog`) | Cero cambios en las aplicaciones | Montar `/var/lib/docker/containers` en `lgtm` (lee los logs de TODOS los contenedores del host), reemplazar la configuración del colector de la imagen y extraer el `trace_id` con un parser propio |

### 2. Consola en JSON (ECS) solo en los contenedores (D2)

- **Cómo:** `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` en el compose activa el structured logging de Boot en formato [ECS](https://www.elastic.co/guide/en/ecs/current/index.html): `@timestamp`, `log.level`, `log.logger`, `service.name`, `message`, `traceId` y `spanId`.
- **Por qué ECS:** es un estándar que entienden Elastic, Loki y la mayoría de las herramientas, y Boot lo trae sin dependencias.
- **Solo en los contenedores:** los tests y una ejecución local siguen con el formato de texto, que se lee mejor en una terminal. JSON siempre haría ilegible la salida de `mvn test`.

### 3. Métricas por OTLP (push) al colector (D3)

- **Registro:** el OTLP de Micrometer (`micrometer-registry-otlp`, que ya venía con el starter de la Fase 1).
- **Activación:** `management.otlp.metrics.export.enabled: false` en `application.yml`; el compose lo enciende con `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=true` y `MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://lgtm:4318/v1/metrics`.
- **Paso de 10 s** en local, para que los dashboards reaccionen rápido. En producción, 30–60 s.
- **Histogramas de `http.server.requests`** (`percentiles-histogram`): el p95 se calcula en Prometheus, agregable entre instancias, en lugar de percentiles precalculados por instancia.
- **Nombres en Prometheus:** la traducción de OTLP convierte puntos en guiones bajos y agrega sufijos de unidad y de tipo (`sales.confirmed` → `sales_confirmed_total`; `sales.time_to_invoice` → `sales_time_to_invoice_milliseconds_bucket`). El servicio llega en la etiqueta `job` (y en `service_name`).

La alternativa, que Prometheus raspe `/actuator/prometheus`, exigía abrir ese endpoint, que hoy es solo para `admin` (ADR 0018), o darle un token al raspador. Con push no se abre nada.

### 4. Métricas de negocio en los adaptadores de persistencia, al confirmarse la transacción (D4)

- **Dónde:** `SaleMetrics` (sales) e `IssuanceMetrics` (invoicing), package-private, en `adapter/out/persistence`. Ahí pasan TODAS las ventas y TODAS las transiciones de estado, lleguen por la API o por un evento.
- **Cómo detecta la transición:** el adaptador lee el estado anterior de la fila antes de aplicar el nuevo, como ya hacía con el bloqueo optimista.
- **Solo al confirmarse** (`TransactionSynchronization.afterCommit`): una venta revertida por una referencia duplicada o un cambio perdido por un conflicto de versión no cuentan. Los tests lo prueban con PostgreSQL real.
- **Series en cero desde el arranque:** los resultados posibles se registran al iniciar el servicio, así un panel muestra 0 y no "sin datos".

| Opción | A favor | En contra |
|---|---|---|
| **Persistencia, `afterCommit` (elegida)** | Un solo punto por servicio; ve la transición real; nada revertido cuenta | El adaptador de persistencia conoce Micrometer (infraestructura igual) |
| Adaptadores de entrada (controlador, listeners) | Cerca del "evento" de negocio | No ven todas las transiciones; habría que repetir la lógica en cada entrada |
| Decoradores de los casos de uso | No toca adaptadores | Los resultados no traen lo necesario (p. ej. la fecha de la venta para medir la Saga) |

### 5. Las métricas (D5)

Etiquetas siempre de baja cardinalidad: **nunca** el cliente, el producto ni el `referenceCode`. Eso va en las trazas y en los logs.

| Métrica (Micrometer) | Tipo | Etiquetas | Qué mide |
|---|---|---|---|
| `sales.confirmed` | contador | `payment_method` | Ventas nuevas confirmadas (los reintentos idempotentes no cuentan) |
| `sales.amount` | distribución | `payment_method` | Total de cada venta, en COP (con impuestos y descuentos) |
| `sales.invoicing.outcome` | contador | `status` = `invoiced` / `invoice_failed` | Cómo terminó la facturación de cada venta (un fallo seguido de éxito cuenta los dos) |
| `sales.time_to_invoice` | timer (histograma) | — | De la venta confirmada al instante de la factura emitida: la Saga completa, con reintentos y DLQ |
| `invoicing.invoices.issuance` | contador | `outcome` = `issued` / `rejected` | Facturas emitidas o rechazadas por la DIAN (de las ventas y de la API) |
| `invoicing.credit_notes.issuance` | contador | `outcome` | Lo mismo, para las notas crédito |

Se conservan las técnicas: outbox (`*.outbox.pending`, `*.outbox.oldest.pending.age`), consumidores (`*.processed{outcome}`), Resilience4j, HTTP, JVM y el span del proveedor falso (`invoicing.fake.provider.issue`).

### 6. Dashboards como código (D6)

- **Formato:** JSON de Grafana en `infra/grafana/dashboards/`. `infra/grafana/provisioning/facturaflow-dashboards.yaml` los aprovisiona en la carpeta "FacturaFlow" de solo lectura (`allowUiUpdates: false`, `disableDeletion: true`): para cambiar un panel se edita el JSON en el repositorio.
- **Montaje:** los dos van montados en `lgtm` en modo `:ro`, junto a los dashboards que trae la imagen (JVM y RED de ejemplo).
- **"FacturaFlow · Negocio":**
  - indicadores del rango: ventas, monto vendido (COP), porcentaje de facturación exitosa, p95 del tiempo venta → factura;
  - series de ventas y monto por minuto según medio de pago;
  - resultado de la facturación;
  - p50 y p95 de la Saga;
  - facturas y notas crédito emitidas o rechazadas;
  - salud del flujo de eventos: pendientes en los outbox, mensajes enviados a la DLQ y antigüedad del pendiente más viejo.
- **"FacturaFlow · Servicios"**, con la variable `service`:
  - RED por servicio y ruta (peticiones, 5xx, p95);
  - circuitos de Resilience4j;
  - consumidores por resultado;
  - outbox;
  - JVM (heap, hilos);
  - un panel de logs de Loki (WARN y ERROR) cuyo `trace_id` abre la traza.
- **Verificación al escribirlos:** cada consulta se ejecutó contra el entorno real y devolvió datos. Los 5xx dan cero sin errores, como es de esperar.

La alternativa era generar el JSON desde Jsonnet/Grafonnet o el Foundation SDK: más compacto y tipado, pero suma una herramienta y un paso de build para dos dashboards.

### 7. Contrato entre el dashboard y el código (D7)

`BusinessMetricsContractTest` (sales e invoicing, copia deliberada) lee el MISMO `facturaflow-negocio.json` que carga Grafana, el mismo patrón que `EventContracts` con `docs/events/`. Para cada métrica con el prefijo del servicio:
- traduce el nombre de Prometheus al de Micrometer (quita los sufijos de unidad y de tipo);
- comprueba que exista en el `MeterRegistry`;
- comprueba también cada etiqueta con valor fijo que filtra el dashboard (`status="invoiced"`, `outcome="dead-lettered"`).

Renombrar una métrica, o un valor de etiqueta, rompe el test en vez de dejar un panel vacío sin que nadie lo note. Se comprobó cambiando `invoiced` por otro valor en el JSON: el test falló con el nombre de la métrica y la etiqueta. `infra/grafana/**` dispara los tests de sales e invoicing en la CI (ADR 0016).

### 8. Sin colector, todo funciona igual (criterio de los ADR 0010 y 0019)

- **Logs y métricas:** si el colector no está, cada lote se descarta y la petición o el consumo no esperan.
- **Ruido acotado:** el registro OTLP deja un WARN por intento (cada 10 s) y el exportador de OpenTelemetry un ERROR con su propio limitador.
- **Comprobado en el entorno real:** con `lgtm` detenido 40 s, una venta se creó y se facturó, el health siguió en `UP` y sales escribió 9 líneas en total, sin bucle (decisión 1).
- **Health:** no depende del colector.
- **Prueba de humo:** arranca cada imagen con los endpoints apuntando a un `lgtm` que no existe y espera `UP`.

### 9. Lo que NO se registra

- **Logs:** se exporta lo que la aplicación ya escribía. Las reglas existentes siguen valiendo: nunca tokens, secretos, `public_url` ni cuerpos de Factus. No se exportan los atributos del MDC.
- **Métricas:** solo etiquetas acotadas (medio de pago, estado, resultado). Ningún dato de cliente.

## Demostración

En el README (sección de observabilidad):
1. crear ventas (normales y con `SIMULAR_RECHAZO`) y ver moverse el dashboard "FacturaFlow · Negocio";
2. en "FacturaFlow · Servicios", abrir un log y saltar a su traza por el `trace_id`;
3. desde la traza en Tempo, el botón de logs muestra los registros de esa venta en los cuatro servicios.

## Cómo se haría en producción

- **Logs:**
  - el colector como agente por nodo (o los logs de Kubernetes recogidos por el colector), con Loki o un servicio gestionado y retención por política;
  - muestreo o límite de volumen para DEBUG;
  - redacción de datos personales en el colector (procesadores `transform`/`redaction`).
- **Métricas:**
  - push OTLP a un colector que las reenvíe a Mimir/Prometheus con almacenamiento remoto, paso de 30–60 s;
  - **exemplars** (enlace de un punto del histograma a una traza): quedan para cuando el registro OTLP de Micrometer los exporte;
  - **SLOs** sobre `sales.time_to_invoice` y la tasa de `invoice_failed`, con alertas (Grafana Alerting o Alertmanager) también como código.
- **Dashboards y alertas:** en un repositorio de plataforma o con el Foundation SDK, revisados en PR y desplegados por la API de Grafana (o por el operador de Grafana).
- **Métricas de negocio "de verdad":** un dashboard operativo no reemplaza la analítica. Para reportes (ventas por cliente o por producto) se consulta la base o un data warehouse alimentado por los eventos, no Prometheus.

## Consecuencias

- **Positivas:**
  - de una venta se pasa a su traza y de la traza a los logs de los cuatro servicios;
  - el negocio tiene un dashboard (ventas, monto, facturación, latencia de la Saga);
  - la operación tiene RED, circuitos, consumidores y outbox en un solo lugar;
  - los dashboards viven en el repositorio y un test protege su contrato con el código;
  - dominio y casos de uso sin cambios, y todo funciona sin el colector.
- **Negativas:**
  - **Una dependencia "alpha"** con versión atada a la de Boot, que se sube a mano.
  - **Más copias deliberadas:** `OpenTelemetryLogsConfiguration` en los cuatro servicios; `BusinessMetricsContractTest` en dos.
  - **Más volumen hacia el colector:** cada log y cada métrica cada 10 s, desde cuatro servicios.
  - **Dashboards en JSON verboso**, editados a mano o exportados desde Grafana y pegados en el repositorio.
  - **Las métricas de negocio cuentan desde el arranque de cada instancia** (contadores acumulados): los paneles usan `increase`/`rate`, que absorben los reinicios.
