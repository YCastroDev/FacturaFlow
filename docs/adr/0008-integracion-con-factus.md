# 0008. Integración con Factus: Anti-Corruption Layer, token y mapeo de respuestas

## Estado

Aceptado — 2026-10-22. Completa el ADR 0006: agrega el adaptador real del puerto `ElectronicInvoiceProvider`, la descarga del PDF y una fila a su tabla de errores. Reemplaza la "URL del PDF" del documento electrónico por los enlaces reales.

## Contexto

Hasta ahora la emisión usaba un proveedor falso (ADR 0006). El [spike 0001](../spikes/0001-factus-sandbox.md) mostró cómo se comporta de verdad el sandbox de Factus. Los hallazgos que más pesan en el diseño:

- **Idempotencia ciega:** un `reference_code` repetido devuelve la factura existente con HTTP 201, aunque el contenido sea otro.
- **Refresh token:** rota los dos tokens y revoca los anteriores al instante.
- **`data.errors`:** trae notificaciones de la DIAN incluso en facturas validadas, así que no indica un rechazo.
- **Formatos:** las fechas vienen en 12 horas sin zona y los montos como texto.
- **Sin URL del PDF:** la respuesta solo trae `links.qr` y `links.public_url`; el PDF se pide aparte.
- **Límites:** 120 peticiones por minuto en la API y 60 en `/oauth/token`.
- **Cloudflare:** bloquea algunos User-Agent genéricos.

## Decisión

### 1. Activación y configuración

- `invoicing.provider=factus` (variable `INVOICING_PROVIDER`) activa `FactusConfiguration`.
- El proveedor falso sigue disponible y es el valor por defecto.
- Las propiedades `invoicing.factus.*` (`FactusProperties`, un record `@Validated`) solo se enlazan y validan con Factus activo. Con el falso no se piden credenciales.

| Propiedad | Variable | Valor por defecto |
|---|---|---|
| `base-url` | `FACTUS_BASE_URL` | `https://api-sandbox.factus.com.co` |
| `client-id`, `client-secret`, `username`, `password` | `FACTUS_*` | ninguno: vacío hace fallar el arranque |
| `send-email` | `FACTUS_SEND_EMAIL` | `false` |
| `numbering-range-id` | `FACTUS_NUMBERING_RANGE_ID` | vacío, que usa el rango por defecto de la cuenta |
| `connect-timeout` / `read-timeout` | — | 5 s / 30 s (la validación ante la DIAN tardó unos 2,3 s) |
| `token-refresh-margin` | — | 60 s |
| `user-agent` | — | `FacturaFlow-Invoicing/0.0.1` |

**Seguridad de la configuración:**
- Las credenciales salen **solo** de variables de entorno.
- `FactusProperties.toString()` las enmascara.
- Si falta una credencial, el error de arranque nombra la propiedad pero no muestra los demás valores (lo verifica `FactusConfigurationTest`).

### 2. Cliente HTTP

- Usa `RestClient` sobre el `HttpClient` del JDK (HTTP/1.1):
  - timeouts de conexión y lectura explícitos;
  - un User-Agent propio;
  - `Accept: application/json`.
- `FactusHttp` usa `exchange` y **nunca lanza por un código HTTP**: entrega el estado y el cuerpo crudos (`FactusResponse`) para que el intérprete decida qué significan.
- Una falla de red o un timeout se convierten en `FactusTechnicalException`.
- **`JsonMapper` propio** (`FactusJson`):
  - escribe en snake_case y omite los nulos;
  - **ignora los campos desconocidos**, al revés que la API REST (ADR 0006): Factus puede agregar campos sin romper la integración;
  - el cuerpo se serializa a bytes con este mapper, así que la configuración global de Jackson no interviene.

### 3. Token (`TokenProvider` → `InMemoryTokenProvider`)

- **Vencimiento:**
  - se lee `expires_in` de **cada** respuesta y el token se renueva `token-refresh-margin` antes de vencer;
  - el margen nunca supera la mitad de la vida del token, para no renovar en cada llamada si Factus diera una vida corta.
- **Renovación:**
  - se renueva con el refresh token y se guarda siempre el **nuevo**, porque Factus los rota;
  - si el refresh falla por cualquier motivo (por ejemplo, revocado), se vuelve a autenticar con usuario y contraseña;
  - si eso también falla, es una falla técnica (503).
- **Un solo hilo renueva:** un `ReentrantLock` con doble verificación. Leer un token vigente no toma el bloqueo. Es necesario porque, si dos hilos renovaran con el mismo refresh token, el segundo fallaría: Factus revoca el anterior al instante.
- **401:** `FactusClient` invalida el token y reintenta **una sola vez**.
  - `invalidate` solo descarta el token si sigue siendo el actual. Si otro hilo ya lo renovó, el reintento usa el nuevo sin renovar otra vez.
  - Reintentar la validación es seguro: un 401 no procesa nada, y Factus es idempotente por `reference_code`.
  - Si el reintento también recibe 401, es una falla técnica.
- **Nunca se registran tokens:** `TokenResponse` y el token interno enmascaran su `toString`, y los mensajes de error solo llevan el estado HTTP, nunca el cuerpo.

### 4. Anti-Corruption Layer

Todo vive en `infrastructure.adapter.out.provider.factus` y es package-private:

| Pieza | Responsabilidad |
|---|---|
| `FactusElectronicInvoiceProvider` | Implementa el puerto: traduce, envía e interpreta. Toda falla técnica sale como `ElectronicInvoiceProviderUnavailableException` |
| `FactusBillTranslator` | `Invoice` → `BillRequest` |
| `FactusCodes` | Tablas de equivalencia dominio → códigos DIAN, con `switch` exhaustivos |
| `FactusClient` / `FactusHttp` / `FactusTokenEndpoint` | Llamadas HTTP y autenticación |
| `FactusResponseInterpreter` | La tabla de resultados (decisión 5) |
| `FactusDtos` | Contratos JSON de Factus (records) |

**Regla 9 de ArchUnit:** ninguna clase fuera de ese paquete depende de él. Ni un DTO ni un código de Factus pueden filtrarse al resto del servicio. La configuración de Spring vive dentro del paquete, así que la regla no necesita excepciones.

**Traducción:**
- **`reference_code` = el `InvoiceId` (UUID):**
  - el `referenceCode` de la venta es nuestra clave de idempotencia, no la de Factus;
  - el UUID no se repite entre entornos ni tras resetear la base, que es justo el riesgo de la idempotencia ciega;
  - verificado: Factus acepta los 36 caracteres (factura `SETP990030361`, emitida por `FactusSandboxTest`).
- **Teléfono:** se envía normalizado por el dominio, con el `+` inicial (`+576015551234`). **Verificado:** Factus lo aceptó y la DIAN validó la factura `SETP990030364`, emitida desde Swagger con el ejemplo de contado.
- **Códigos:** los de `FactusCodes`, verificados uno a uno en el spike (sección 9). `FactusCodesTest` tiene una fila por valor del dominio.
- **Montos:**
  - el precio unitario, la cantidad y los porcentajes se envían tal cual;
  - Factus calcula los totales con el mismo redondeo que el dominio;
  - `payment_details.amount` es el total del dominio, porque Factus exige que coincida con el suyo.
- **Línea excluida de IVA:** `{"code": "01", "rate": 0, "is_excluded": true}`, más sus otros impuestos (por ejemplo, el INC).
- **Otros valores fijos:**
  - `standard_code` = `999`, el estándar de adopción del contribuyente;
  - las responsabilidades van en el orden del enum;
  - los opcionales ausentes no se envían.
- `FactusBillTranslatorTest` comprueba que las facturas de contado y de crédito producen **exactamente** las peticiones que Factus aceptó en el spike.

### 5. Tabla de resultados

| Respuesta de Factus | Resultado | Factura | Verificado en el spike |
|---|---|---|---|
| 200/201, `is_validated: true`, misma identidad | `Issued` (notificaciones de `data.errors` → log INFO con sus códigos) | ISSUED | Sí |
| 200/201, `is_validated: true`, otra identidad | falla técnica + log ERROR (decisión 6) | PENDING | Sí (el reenvío con otro contenido) |
| 200/201, `is_validated: false` | falla técnica (aún no validada por la DIAN) | PENDING | **No** |
| 200/201 con cuerpo ilegible o documento inválido | falla técnica + log ERROR | PENDING | — |
| 422 por los datos de la factura | `Rejected("Factus no validó la factura: <campo>: <mensaje>; …")`, de máximo 1000 caracteres | REJECTED | Sí |
| 422 por la configuración (`numbering_range_id`, `send_email`) | falla técnica + log ERROR que nombra la propiedad y la variable que hay que revisar (p. ej. `FACTUS_NUMBERING_RANGE_ID`) | PENDING | Sí (el mensaje) |
| 401 después del reintento | falla técnica | PENDING | Sí (el 401) |
| 409 "pendiente por enviar a la DIAN" | falla técnica | PENDING | **No** |
| 429 | falla técnica; el `Retry-After` de Factus va al log | PENDING | **No** |
| 5xx, otros códigos (403 de Cloudflare, 404…) | falla técnica | PENDING | **No** / Sí (403) |
| Timeout, conexión cortada, error de red | falla técnica | PENDING | — |

Toda falla técnica es un 503 con `Retry-After: 30`; el adaptador **nunca** devuelve `Rejected` por una falla técnica.

**Ajustes respecto a la tabla de la tarea:**
- **422 de configuración:** un 422 sobre `numbering_range_id` o `send_email` no es culpa de la factura. Marcarla REJECTED sería un estado final que no le corresponde, así que es una falla técnica.
- **Notificaciones:** `data.errors` con `is_validated: true` se registra, pero no cambia el resultado (spike, sorpresa 8).

**Nunca se borra nada en Factus:** el adaptador solo usa `POST /v2/bills/validate`, `GET /v2/bills/{number}/download-pdf` y `POST /oauth/token`.

### 6. ¿Es nuestra factura? Identidad frente a total

Por la idempotencia ciega, la respuesta podría ser de otra factura. Se separan dos casos:

- **Identidad distinta** (otro `reference_code`, u otro tipo o número de identificación del cliente): es **otra** factura.
  - No se marca emitida: es una falla técnica y la factura sigue PENDING.
  - Se registra un **ERROR** con los valores esperados y recibidos y el número del documento ajeno, para conciliarlo a mano.
  - No es `Rejected`, porque la DIAN sí validó un documento y marcarlo como rechazado lo ocultaría.
- **Misma identidad pero otro total:** es **nuestra** factura con otro redondeo.
  - Se marca emitida con el **total oficial**, que es el legal (ADR 0004, decisión B).
  - Se registra un **WARN** con los dos valores.
  - La API lo expone en `officialTotalDiscrepancy`.

### 7. Documento electrónico: enlaces en vez de "URL del PDF"

**Dominio.** `ElectronicDocument(number, cufe, qrUrl, publicUrl, validatedAt, officialTotal)`. Los dos enlaces son obligatorios, absolutos y `http(s)`.
- `qrUrl`: la consulta en el catálogo de la DIAN, lo que codifica el QR. Es un concepto de la DIAN, no de Factus.
- `publicUrl`: la vista pública en el proveedor. Es un enlace-capacidad, así que **nunca va a los logs**.

Si otro proveedor algún día no ofreciera vista pública, `publicUrl` pasaría a ser opcional; no se anticipa.

**Persistencia (migración V5).**
- Renombra `edoc_pdf_url` a `edoc_public_url`, agrega `edoc_qr_url` y recrea `ck_invoices_state_coherence` con los dos enlaces.
- **Aborta** si existe alguna factura ISSUED, porque no hay un QR real que rellenar.
- En este proyecto la única afectada era una emisión del proveedor falso en la base local. El patrón de relleno ya se demostró en la V4 (ver el principio de trabajo en `CLAUDE.md`).
- En producción se haría así:
  1. agregar `edoc_qr_url` nula;
  2. rellenarla consultando `GET /v2/bills/{number}` por cada factura ISSUED, en un job fuera de Flyway;
  3. después, en una migración posterior, endurecer el `CHECK`.

**API.** `electronicDocument.pdfUrl` se reemplaza por `qrUrl` y `publicUrl`. Es un cambio incompatible, aceptable porque la v1 aún no tiene clientes.

**Proveedor falso.**
- Genera los enlaces en `https://fake-provider.invalid/qr/<número>` y `…/invoices/<número>`.
- Su PDF es un PDF 1.4 mínimo armado a mano, que dice "DOCUMENTO FALSO - SIN VALIDEZ".
- `SIMULAR_CAIDA` también hace fallar la descarga.

### 8. PDF

- **Puerto:** `ElectronicInvoiceProvider.downloadPdf(Invoice)` devuelve `InvoicePdf(fileName, content)`.
  - Recibe la factura y no solo el documento, porque la excepción técnica lleva el id de la factura y el proveedor falso necesita la observación.
  - `InvoicePdf` normaliza el nombre a caracteres seguros para el header y le agrega `.pdf`.
- **Caso de uso:** `GetInvoicePdfUseCase` / `GetInvoicePdfService`.
  - Lee la factura en su propia transacción corta y pide el PDF **fuera** de la base (ADR 0006).
  - Lanza `InvoiceNotFoundException` → 404 o `InvoiceNotIssuedException` → 409 `invoice-not-issued`.
  - Una falla del proveedor da 503.
- **Endpoint:** `GET /api/v1/invoices/{id}/pdf` responde `application/pdf` con `Content-Disposition: attachment`.
  - Los errores se responden en Problem Details aunque el cliente pida `application/pdf`.
- **Sin caché:** el PDF se pide a Factus en cada descarga (`/v2/bills/{number}/download-pdf`, Base64).
  - El adaptador comprueba que lo decodificado empiece con `%PDF-`.
  - Guardarlo duplicaría unos 50 kB por factura sin necesidad en este proyecto.

### 9. Tests

| Test | Qué cubre |
|---|---|
| `FactusElectronicInvoiceProviderTest` (WireMock) | Toda la tabla de resultados, el 401 con renovación y un solo reintento, el refresh revocado, el token vencido renovado antes de llamar, el 429 sin reintentos, el timeout, la conexión cortada, el PDF y que ningún secreto llegue a los logs |
| `InMemoryTokenProviderTest` | Margen, rotación, respaldo con contraseña y **concurrencia**: 64 hilos sin token → una sola autenticación; 64 hilos con el token vencido → un solo refresh; 32 hilos con 401 → una sola renovación |
| `FactusBillTranslatorTest`, `FactusCodesTest` | Las peticiones y los códigos verificados en el spike |
| `FactusConfigurationTest` | La activación por propiedad, el arranque fallido sin credenciales y el `toString` enmascarado |
| `FactusSandboxTest` (`@Tag("sandbox")`) | Contra el sandbox **real**: emite una factura, la reenvía (mismo número), baja el PDF y provoca un 422 que no crea nada |

**Fixtures de WireMock.** Son las respuestas reales del spike, sanitizadas: sin el bloque `company` y con una `public_url` falsa. Los casos no observados están marcados como **NO VERIFICADOS** en `src/test/resources/factus/README.md`.

**Tests de sandbox.** Están excluidos por defecto (`excludedGroups=sandbox` en el pom). Se ejecutan así:

```bash
MVN_ENV_FILE=.env ./scripts/mvn.sh invoicing test -Dgroups=sandbox -DexcludedGroups=none
```

Docker inyecta el `.env` (`--env-file`) sin que el script lo lea.

## Limitaciones

- **Sin resiliencia todavía:** no hay reintentos (salvo el del 401), ni circuit breaker, ni limitador de peticiones.
  - El límite real es **120 peticiones/min** en la API y **60/min** en `/oauth/token`.
  - Un 429 se trata como falla técnica (503).
  - El `Retry-After` que se responde es el fijo de 30 s, no el de Factus.
  - El reintento, el circuit breaker y el `RateLimiter` llegarán juntos con **Resilience4j**, en el paso de resiliencia. Por eso no se escribió un limitador propio, que quedaría para tirar.
- **Doble escritura (ADR 0006, D4):** sigue presente. Si la DIAN valida y el `save` falla, la factura queda PENDING con un documento real en Factus.
  - Un reintento lo recupera por la idempotencia de Factus, porque el `reference_code` es el mismo UUID.
  - La solución prevista sigue siendo Outbox más una reconciliación que consulte `GET /v2/bills?filter[reference_code]=…`.
- **Identidad distinta:** se concilia a mano, guiándose por el log ERROR. No hay un estado que la marque.
- **Token en memoria por instancia:** con varias réplicas, cada una tendría su propio token.
  - No se verificó si una autenticación nueva revoca los tokens de otra sesión de la misma cuenta.
  - Con réplicas, el token pasaría a un almacén compartido (por ejemplo, Redis) con un bloqueo distribuido.
- **No verificado en el sandbox:**
  - las respuestas `is_validated: false`, 409, 429 y 5xx: sus fixtures siguen la documentación (el teléfono con `+` y el `reference_code` de 36 caracteres sí se verificaron, ver la decisión 4);
  - una línea sin impuestos que no está excluida;
  - el formato de `Retry-After`.
- **`publicUrl` y el PDF** solo dependen del id de la factura mientras la API no tenga autenticación (llegará con Keycloak).
- **`validated_at`** se interpreta con un formato fijo (`dd-MM-yyyy hh:mm:ss a`, hora de Bogotá). Si Factus lo cambia, la emisión falla técnicamente (con un ERROR), no silenciosamente.

## Alternativas consideradas

- **Enviar el `referenceCode` de la venta como `reference_code`:** si se reseteara la base o hubiera otro entorno en la misma cuenta, Factus devolvería una factura ajena sin avisar.
- **Tolerancia en el total para decidir la identidad:** el total no identifica una factura; un redondeo distinto de nuestra propia factura no debe dejarla PENDING para siempre.
- **`Rejected` ante una identidad distinta:** ocultaría que la DIAN validó un documento.
- **Limitador de peticiones propio:** se descarta porque quedaría reemplazado por el `RateLimiter` de Resilience4j.
- **Renovar el token con un hilo programado:** agrega un ciclo de vida que gestionar. La renovación perezosa con margen, junto con el reintento del 401, cubre los mismos casos.
- **Token en la base de datos:** no aporta nada con una sola instancia, y guardaría un secreto en la base.
- **WebClient o un cliente declarativo (`@HttpExchange`):** WebClient trae la pila reactiva sin necesidad. Con un cliente declarativo, el manejo del 401 y de los códigos crudos quedaría menos explícito.
- **Guardar el PDF:** duplica datos que Factus conserva. Una caché tendría sentido con mucho tráfico.
- **Migrar las facturas ISSUED con un QR inventado:** inventa datos legales.

## Consecuencias

**Positivas**
- La emisión real ante la DIAN funciona sin que el dominio ni los casos de uso cambien su contrato: `IssuanceResult` y la excepción técnica bastan.
- Toda la incertidumbre del proveedor (formatos, códigos, tokens, respuestas ambiguas) vive en un paquete aislado y probado con sus respuestas reales.
- Las fallas técnicas nunca cierran una factura: siempre se puede reintentar sin riesgo de duplicarla.

**Negativas**
- Un pico de emisiones puede chocar con el límite de Factus hasta que llegue Resilience4j.
- Hay comportamiento basado en respuestas no observadas (409, `is_validated: false`, 429, 5xx), marcado como tal.
- Cambio incompatible en la API (`pdfUrl`) y una migración que exige vaciar la base local si tiene facturas ISSUED.
