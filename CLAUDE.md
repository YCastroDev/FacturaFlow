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
| Tests de arquitectura | ArchUnit 1.5.1 |
| Documentación de la API | springdoc-openapi 3.1.1 (Swagger UI) |
| Escaneo de secretos | gitleaks v8.30.1 (vía Docker) |
| Mock HTTP (tests del adaptador de Factus) | WireMock 3.13.1 (`wiremock-standalone`) |
| Resiliencia (llamadas a Factus) | Resilience4j 2.4.0 (`resilience4j-spring-boot4`, versión fijada en el pom) |

## Estructura

- `services/<servicio>/` — un proyecto Spring Boot independiente por servicio (hoy solo `invoicing`).
- `infra/` — `docker-compose.yml` y scripts de init de PostgreSQL.
- `scripts/` — utilidades (`mvn.sh`, `check-secrets.sh`).
- `.githooks/` — hook pre-commit con gitleaks.
- `docs/adr/` — Architecture Decision Records.
- `docs/spikes/` y `scripts/spikes/` — investigaciones contra sistemas externos (hallazgos y scripts reproducibles).

## Reglas de arquitectura

- Estructura hexagonal obligatoria en cada servicio (ADR 0002), bajo `com.facturaflow.<servicio>`:
  `domain/{model,exception}`, `application/{port/in,port/out,service}`, `infrastructure/{adapter/in/...,adapter/out/...,config}`.
- **Nada de anotaciones ni imports de Spring, JPA o Jackson en `domain/`.** El dominio es Java puro.
- Las dependencias apuntan hacia adentro: `infrastructure → application → domain`. `application` no conoce adaptadores concretos.
- `application/` también es Java puro: sin `@Service`, `@Transactional` ni nada de Spring. Los casos de uso se registran como beans en `infrastructure/config`.
- En `application/port/in` y `application/port/out` solo hay interfaces (puertos) y records (comandos/resultados). Las excepciones del contrato de los puertos van en `application/exception`.
- `infrastructure/adapter/in` nunca usa `infrastructure/adapter/out`: siempre pasa por un puerto de entrada.
- Sin ciclos entre paquetes.
- **Prohibido** importar código de otro servicio o acceder a la base de datos de otro servicio (ADR 0001, ADR 0003). La comunicación entre servicios es solo por HTTP o eventos.
- **Estas reglas se validan automáticamente** con ArchUnit (`src/test/java/.../architecture/HexagonalArchitectureTest.java`) y corren con `./scripts/mvn.sh <servicio> test`. Si un test de arquitectura falla, se corrige el código, no la regla; cambiar una regla requiere preguntar (y, si es una decisión nueva, un ADR).

## Reglas de seguridad

- Ninguna contraseña, token, llave o credencial en código, `application.yml`, `docker-compose.yml`, scripts ni documentación. Todo se lee de variables de entorno, sin valores por defecto que contengan secretos.
- `.env` (raíz, ignorado por Git) contiene los valores reales. Claude **puede generar o modificar `.env` para desarrollo local**, pero **nunca mostrar su contenido** (ni con `cat`, `grep`, logs, `docker compose config`, etc.) **ni copiar sus valores a otros archivos**. Para saber qué variables existen, leer `.env.example`.
- Al agregar o renombrar una variable, actualizar `.env.example` (con valor `CAMBIAME`) en el mismo cambio.
- No desactivar ni saltarse el hook de gitleaks (`--no-verify`).

## Reglas del dominio (ADR 0004)

- Conceptos propios (enums, value objects); **nunca** IDs de catálogos de Factus en `domain/`. La traducción va en el adaptador.
- El dominio no llama a `LocalDate.now()`, `Instant.now()` ni al reloj: las fechas entran como parámetro.
- Las excepciones de `domain.exception` reciben solo valores simples (String, UUID, números), nunca objetos de `domain.model`.
- Montos con `Money` (COP, 2 decimales, `HALF_UP`), redondeo por línea; `unitPrice` sin impuestos ni descuentos.
- Impuestos por línea como `List<LineTax>` (`TaxType` + `TaxRate`), sin tipos repetidos, cada uno redondeado por separado. `excluded` = "excluida de IVA" (no admite `LineTax` de tipo IVA).
- Value objects como records que se validan en su constructor; el aggregate `Invoice` sin setters.
- Tests de dominio: JUnit 5 + AssertJ puros (sin Spring), con `@DisplayName` en español como frase de negocio.
- Cliente (ADR 0007): `PersonType` con exclusión estricta (jurídica → solo `companyName`; natural → solo `names`); `FiscalProfile` obligatorio (`TaxScheme` + conjunto no vacío de `FiscalResponsibility`, `NOT_RESPONSIBLE` siempre sola); `Municipality` (DIVIPOLA, 5 dígitos), dirección y `PhoneNumber` opcionales.
- `UnitOfMeasure` solo contiene unidades que el proveedor acepta (ver spike 0001). Agregar una exige comprobarlo y una migración.

## Reglas de persistencia (ADR 0005)

- Entidades JPA solo en `infrastructure/adapter/out/persistence`, package-private, separadas del dominio; mapeo a mano en `InvoicePersistenceMapper`.
- El dominio se reconstruye con `Invoice.reconstitute(...)`, nunca saltándose sus validaciones.
- `Invoice.version()` es un token opaco: no se interpreta ni se modifica fuera del adaptador. `saveNew` (insertar, con la huella de la petición) y `save` (actualizar) devuelven una instancia nueva; usar siempre la devuelta.
- Los adaptadores no dejan escapar excepciones de Spring/Hibernate: se traducen a las de `application.exception`. Hacer `flush()` dentro del `try` para poder traducirlas.
- Cargar agregados con entity graph (sin N+1); colecciones como listas indexadas (`@OrderColumn`). Los tests verifican el número de sentencias con `Statistics`.
- Cada valor nuevo de un enum persistido exige migración (CHECK). Columnas: sin palabras reservadas; `VARCHAR(n)` solo si el dominio impone ese límite.

## Reglas de casos de uso y API REST (ADR 0006)

- Casos de uso: interfaz en `port/in` + implementación en `application/service` registrada en `UseCaseConfiguration`. Reciben comandos con tipos simples y construyen los value objects ellos mismos. La fecha sale del `Clock` inyectado (zona `invoicing.time-zone`).
- **Transacciones solo en `infrastructure/adapter/out/persistence`** (regla de ArchUnit). Nunca envolver un caso de uso en una transacción: las llamadas a sistemas externos (proveedor de facturación) van fuera de la base.
- Idempotencia de creación por `referenceCode` + huella SHA-256 de la petición normalizada (`InvoiceRequestHasher`, formato v2). Si se agrega un campo a la factura, agregarlo también a la huella y a su test parametrizado (la huella no se versiona: ADR 0007, decisión 6).
- Emisión: respuestas de negocio como `IssuanceResult` sellado (`Issued` | `Rejected`); fallas técnicas como `ElectronicInvoiceProviderUnavailableException`. Un adaptador de proveedor nunca devuelve un `Rejected` por un timeout.
- Proveedor activo según `invoicing.provider` (`fake` por defecto, `factus` para el sandbox real). El falso se controla con `SIMULAR_RECHAZO` y `SIMULAR_CAIDA` en la observación (de la factura o de la nota crédito).
- REST: DTOs y enums propios en `adapter/in/rest/dto`, con `switch` exhaustivos hacia y desde el dominio. El controlador lee el agregado, nunca lo serializa. Bean Validation solo para forma (presencia, tipos, topes anti-abuso); las reglas de negocio no se duplican (responden 422 desde el dominio).
- Errores: siempre Problem Details desde `ProblemDetailsHandler`, con `type` `urn:facturaflow:problem:<slug>`. Nunca exponer mensajes de Spring, Jackson o Hibernate ni trazas. Una excepción nueva de `application.exception` necesita su fila en la tabla del ADR 0006 y su test en el test del controlador que la expone (`InvoiceControllerTest` o `CreditNoteControllerTest`).
- Tests: casos de uso con `InMemoryInvoiceRepository` y `StubElectronicInvoiceProvider` (sin Spring); web con `@WebMvcTest` + `MockMvcTester`; punta a punta con `RestTestClient` + Testcontainers.

## Reglas de las notas crédito (ADR 0009)

- `CreditNote` es un agregado SEPARADO: referencia la factura por `InvoiceId`, con su ciclo PENDING → ISSUED | REJECTED. La factura no cambia de estado al acreditarse.
- Motivos propios (`PARTIAL_RETURN`, `TOTAL_ANNULMENT`); los códigos de la DIAN solo en el adaptador.
- El cliente de la nota es SIEMPRE el de la factura guardada; nunca viene en la petición.
- Cada línea es el número de línea de la factura (desde 1) + `InvoiceLine.withQuantity(q)`: los montos salen del mismo cálculo que la factura.
- **Regla que cruza agregados:** lo acreditado (notas PENDING + ISSUED; las REJECTED no cuentan) nunca supera lo facturado, **en cantidades por línea**, nunca en dinero. La calcula `CreditBalance`; la protege `CreditNoteRepository.saveNew`, que sube la versión de la factura solo si sigue siendo la leída (bloqueo optimista). Toda nota nueva DEBE pasar por `saveNew`. El caso de uso reintenta hasta 3 veces (422 si ya no cabe, 409 si persisten los conflictos).
- Emitir o rechazar una nota no toca la factura. No hay cancelación de notas PENDING (limitación documentada en el ADR 0009).
- `ElectronicDocument.uniqueCode` es el CUFE o el CUDE; la API usa `cufe` en facturas y `cude` en notas.
- Con Factus (ADR 0008, decisión 10): `reference_code` = `CreditNoteId`; cliente e ítems con el mismo código que la factura; pago de contado; `FACTUS_CREDIT_NOTE_NUMBERING_RANGE_ID` obligatorio. Antes de marcar una nota emitida se verifican `reference_code`, la identificación del cliente y `bill.number`. `CBF02` es una notificación, no un rechazo. Un 422 por `bill_number` es falla técnica: la factura no existe en esa cuenta.

## Reglas del adaptador de Factus (ADR 0008)

- Anti-Corruption Layer en `infrastructure/adapter/out/provider/factus`, todo package-private. **Ningún DTO, código ni cliente de Factus sale de ese paquete** (regla 9 de ArchUnit); se usa solo a través de `ElectronicInvoiceProvider`.
- Códigos de catálogo solo en `FactusCodes` (`switch` exhaustivos) y cada uno con su fila en `FactusCodesTest`. Un código nuevo exige verificarlo contra el sandbox.
- `reference_code` = `InvoiceId` (UUID). Una respuesta con otro `reference_code` o con otra identificación del cliente es otra factura: falla técnica y log ERROR, nunca `Issued`. Si solo difiere el total: `Issued` con el total oficial y log WARN.
- Resultados según la tabla del ADR 0008, la misma para facturas y notas crédito: solo un 422 por los datos del documento es `Rejected`; todo lo demás (incluido `is_validated: false`, 409, 429, 5xx, timeouts y un 422 de configuración) es falla técnica. `data.errors` con `is_validated: true` son notificaciones, no errores.
- Nunca borrar nada en Factus. Sin reintentos ni limitadores a mano: la resiliencia es la del ADR 0010 (abajo); el 401 lo sigue resolviendo `FactusClient` con un único reintento.
- **Nunca registrar** tokens, `client_secret`, contraseñas ni `public_url` (enlace-capacidad); los `toString` de propiedades y tokens enmascaran. Los mensajes de error solo llevan el estado HTTP, nunca el cuerpo.
- Tests con WireMock y las respuestas reales del spike (`src/test/resources/factus/`, con los casos no observados marcados como NO VERIFICADOS). Los de sandbox real llevan `@Tag("sandbox")` y no corren por defecto; crean facturas reales: con moderación y `send_email=false`.

## Reglas de resiliencia (ADR 0010)

- Solo en el adaptador de Factus, programática (sin anotaciones): `FactusResilience` envuelve CADA llamada HTTP en `FactusHttp.exchange` con Retry → CircuitBreaker → RateLimiter. Toda llamada nueva a Factus debe pasar por `FactusHttp`.
- Se reintenta (máx. 3 intentos, backoff con jitter) solo ante timeouts, errores de red, 5xx, 429 (si su `Retry-After` ≤ 5 s) y 409 "pendiente". NUNCA ante 422, 401 (lo maneja `FactusClient`) ni una identidad distinta (se detecta después, en el intérprete).
- Un solo circuito `factus`. Cuentan como falla: timeouts, red, 5xx y 429; el 409 y el limitador se ignoran; los rechazos de la DIAN no cuentan.
- Circuito abierto o limitador agotado → `ElectronicInvoiceProviderUnavailableException` con `reason` y `retryAfter` → 503 con ese `Retry-After`.
- El health no depende de Factus: el componente `factus` siempre es UP (detalles en `/actuator/health/providers`) y no entra en liveness/readiness.
- Los valores viven en `invoicing.factus.resilience.*` (`application.yml`); los tests del adaptador usan `FactusTestData.singleAttempt()` salvo `FactusResilienceTest`.

## Convenciones

- Código, clases, paquetes y endpoints en inglés. Documentación y ADRs en español.
- Esquema de base de datos solo con migraciones Flyway (`src/main/resources/db/migration/V<n>__<descripcion>.sql`). Nunca `ddl-auto=update`; se usa `validate`.
- Una migración ya aplicada no se edita: se crea una nueva.

## Comandos útiles (desde la raíz)

```bash
# Tests de un servicio (Maven en contenedor, Testcontainers incluido)
./scripts/mvn.sh invoicing test

# Solo los tests de arquitectura (ArchUnit, ~2 s)
./scripts/mvn.sh invoicing test -Dtest=HexagonalArchitectureTest

# Tras borrar o mover clases, limpiar target/ para no evaluar .class viejos
./scripts/mvn.sh invoicing clean test

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

# Swagger UI (API documentada con ejemplos)
# http://localhost:8081/swagger-ui.html

# Tests contra el sandbox REAL de Factus (excluidos por defecto; crean 2 facturas y 1 nota crédito por ejecución).
# Docker inyecta el .env con --env-file; nadie lo lee ni lo muestra.
MVN_ENV_FILE=.env ./scripts/mvn.sh invoicing test -Dgroups=sandbox -DexcludedGroups=none

# Levantar con Factus en vez del proveedor falso (o INVOICING_PROVIDER=factus en .env)
INVOICING_PROVIDER=factus docker compose --env-file .env -f infra/docker-compose.yml up --build -d

# Estado de la resiliencia hacia Factus (con INVOICING_PROVIDER=factus)
curl -s http://localhost:8081/actuator/health/providers
curl -s http://localhost:8081/actuator/circuitbreakers
curl -s http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.not.permitted.calls

# Escaneo de secretos de todo el repo
./scripts/check-secrets.sh
```

## Regla de trabajo

- Proyecto de portafolio sin datos ni usuarios en producción: cuando la solución robusta solo proteja datos de prueba o casos hipotéticos, prefiere la solución simple y documenta en el ADR cómo se haría en producción. Aplica rigor completo en arquitectura, modelado del dominio, tests y seguridad. Si dudas, pregúntame.
- Si una decisión técnica importante no está cubierta por un ADR, **preguntar antes de implementarla**.
- **No hacer commits** ni push; los hace el usuario.
