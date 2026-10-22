# Spike 0001. Comportamiento real del sandbox de Factus

- **Fecha:** 2026-10-21
- **Entorno:** `https://api-sandbox.factus.com.co`, empresa de pruebas "FACTUS V2" (NIT 1000789002-2)
- **Objetivo:** confirmar cómo se comporta Factus antes de diseñar el adaptador (Fase 3) y los datos fiscales del cliente (Fase 2).
- **Facturas creadas:** 4 (`SETP990028958`, `…959`, `…960` y, en la Fase 2, `…966`), todas con `reference_code` `SPIKE-…` y `send_email: false`. En total hubo 9 envíos a la validación: 2 reenvíos de un código ya usado y 3 rechazos 422, que no crean nada. No se borró nada.

## Cómo reproducirlo

```bash
./scripts/spikes/factus-spike.sh help                          # lista los pasos
./scripts/spikes/factus-spike.sh auth                          # token, refresh, revocación
./scripts/spikes/factus-spike.sh get /v2/numbering-ranges      # GET de solo lectura
./scripts/spikes/factus-spike.sh probe /v2/bills/validate probes/skeleton.json   # errores de validación
./scripts/spikes/factus-spike.sh codes                         # qué códigos de catálogo acepta
./scripts/spikes/factus-spike.sh bill payloads/cash-nit-iva.json SPIKE-<código>  # crea una factura
./scripts/spikes/factus-spike.sh pdf SETP990028958             # descarga el PDF
```

- El script corre en un contenedor `python:3.13.16-alpine3.24` (solo biblioteca estándar) y no requiere instalar nada.
- Las credenciales `FACTUS_*` las inyecta Docker desde `.env` (`--env-file`). El script nunca lee el archivo, nunca imprime secretos y enmascara los tokens (`eyJ0eX…[1028 caracteres]`).
- Salvaguardas incorporadas en el código:
  - solo usa `GET` y `POST`, así que no puede borrar nada;
  - `reference_code` debe empezar por `SPIKE-`;
  - `send_email` se fuerza a `false`;
  - hay un tope de 10 envíos (`output/bills.log`);
  - deja 1 s entre peticiones;
  - se detiene ante un 429;
  - los `probe` y `codes` quitan `reference_code`, así que **nunca pueden crear una factura**.
- Las respuestas sanitizadas quedan en `scripts/spikes/output/`, que está ignorado por Git.

## Resumen de sorpresas respecto a la documentación

| # | Lo que dice la documentación o lo que asumíamos | Lo que pasa en realidad | Impacto |
|---|---|---|---|
| 1 | Reenviar un `reference_code` devuelve la factura existente | Sí, pero **sin comparar el contenido**: con otra cantidad y otro total devuelve la factura original con HTTP 201 y el mismo mensaje de "registrado y validado con éxito" | **Alto (Fase 3).** El adaptador debe verificar que la factura devuelta es la nuestra. |
| 2 | Límite de 80 peticiones/min | Los headers dicen `X-RateLimit-Limit: 120` en la API y `60` en `/oauth/token` | Medio: configurar el límite, no fijarlo en el código. |
| 3 | Unidad 94 = unidad (códigos DIAN) | Factus solo acepta un **subconjunto**: `94`, `KGM`, `GRM`, `LBR`, `LTR`, `MLT`, `GLL` y `MTR`. Rechaza `CMT`, `MTK`, `HUR`, `DAY`, `DZN`, `BX`, `PK`, `E48` y otras 30 de la recomendación 20 de UN/ECE | **Alto (Fase 2/3).** 7 de nuestras 16 unidades no tienen código aceptado. |
| 4 | — | Cloudflare delante de Factus bloquea el User-Agent `Python-urllib/*` (403, error 1010) | Bajo: el cliente de Java pasa, pero conviene enviar un User-Agent propio. |
| 5 | v1 y v2 | Esta cuenta **solo tiene v2**. La v1 responde 403 "Versión de API no disponible para esta empresa" | El adaptador usa solo `/v2`. |
| 6 | No hay endpoints de catálogo en v2 (`/v2/measurement-units`, `/v2/tributes`, `/v2/municipalities` → 404) | Los códigos son los de la DIAN y se verificaron uno a uno con el paso `codes` | Las tablas de equivalencia se escriben a mano y se cubren con tests. |
| 7 | Refresh token | El refresh **rota** los dos tokens y **revoca al instante** el access token anterior (401) y el refresh token anterior (401 "Token has been revoked") | **Alto (Fase 3).** Ver la sección de autenticación. |
| 8 | `errors` indica errores | En una factura **validada** (`is_validated: true`), `errors` trae **notificaciones** de la DIAN (`FAJ43b`, `FAJ44b`, `RUT01`) | No confundir `errors` con un rechazo. |
| 9 | 409 "pendiente por enviar a la DIAN" e `is_validated: false` | No aparecieron en 3 facturas reales | Sus respuestas no son reales: los mocks de la Fase 3 se basarán en la documentación. |
| 10 | Vencimiento posterior a la emisión | Factus acepta un vencimiento **igual** a hoy ("posterior o igual a today") | Ninguno: nuestro dominio es más estricto. |
| 11 | `gross_amount` = valor bruto | `totals.gross_amount` es la base **después** del descuento. El `gross_value` del ítem es un valor sin sentido claro (precio unitario menos el descuento total de la línea) | Comparar solo `totals.total` y `tax_amount`; no mapear `gross_*`. |

## 1. Token: duración y refresh

**Petición:** `POST /oauth/token` como `application/x-www-form-urlencoded`.

- Con contraseña: `grant_type=password`, `client_id`, `client_secret`, `username`, `password`.
- Con refresh: `grant_type=refresh_token`, `client_id`, `client_secret`, `refresh_token`.

**Respuesta** (HTTP 200, con los tokens enmascarados):

```json
{ "token_type": "Bearer", "expires_in": 3600, "access_token": "eyJ0eX…[1028 caracteres]", "refresh_token": "def502…[778 caracteres]" }
```

**Duración y refresh:**
- `expires_in` fue **3600** tanto con la contraseña como con el refresh. No esperé una hora para confirmar el vencimiento real, así que el adaptador debe leer `expires_in` en cada respuesta y no asumir nada.
- El refresh **rota** los tokens: el access token y el refresh token nuevos son distintos.

**Revocación inmediata** (probado con `GET /v2/numbering-ranges`):

| Token usado | Resultado |
|---|---|
| access token **anterior**, después del refresh | **401** `{"status":"Unauthorized","message":"Unauthenticated."}` |
| refresh token **anterior**, reutilizado | **401** `{"error":"invalid_request","error_description":"The refresh token is invalid.","hint":"Token has been revoked","message":"The refresh token is invalid."}` |
| token inventado | 401 `Unauthenticated.` |

**Consecuencias para el `TokenProvider`:**
- Cualquier petición que esté en vuelo con el token viejo mientras otro hilo renueva recibirá 401. El "invalidar y reintentar una vez" absorbe ese caso.
- Si dos hilos renuevan a la vez con el mismo refresh token, el segundo falla. Por eso un solo hilo debe renovar, y si el refresh falla hay que volver a autenticar con contraseña.
- Hay que guardar **el refresh token nuevo** de cada respuesta.

## 2. Factura de contado válida: estructura real

**Petición** (`scripts/spikes/factus/payloads/cash-nit-iva.json`): NIT 890903938-8 persona jurídica, 2 × 85.000 con 10 % de descuento e IVA 19 %, contado y efectivo. **No** se envió `numbering_range_id`.

```json
{
  "reference_code": "SPIKE-1021-CASH01",
  "observation": "Spike FacturaFlow: contado con IVA",
  "customer": {
    "identification_document_code": "31", "identification": "890903938", "dv": "8",
    "company": "Cliente Ejemplo S.A.S.", "legal_organization_code": "1", "municipality_code": "11001",
    "address": "Calle 1 # 2-3", "email": "facturacion@cliente.example", "phone": "3001234567",
    "responsibilities": [ "R-99-PN" ]
  },
  "items": [ {
    "code_reference": "SKU-001", "name": "Teclado inalámbrico", "quantity": 2, "price": 85000.00,
    "discount_rate": 10, "unit_measure_code": "94", "standard_code": "999",
    "taxes": [ { "code": "01", "rate": 19 } ]
  } ],
  "payment_details": [ { "payment_form": "1", "payment_method_code": "10", "amount": 182070.00 } ],
  "send_email": false
}
```

**Respuesta: HTTP 201** en 2,3 s. Lo siguiente es un extracto: se omitieron `company` y bloques vacíos, y se recortó `public_url`.

```json
{
  "status": "Created",
  "message": "Documento con el código de referencia SPIKE-1021-CASH01 registrado y validado con éxito",
  "data": {
    "reference_code": "SPIKE-1021-CASH01",
    "number": "SETP990028958",
    "document_type": { "code": "01", "name": "Factura electrónica de Venta" },
    "operation_type": { "code": "10", "name": "Estándar" },
    "payment_details": [ {
      "payment_form": { "code": "1", "name": "Pago de contado" },
      "payment_method": { "code": "10", "name": "Efectivo" },
      "reference_code": null, "amount": "182070.00", "due_date": null } ],
    "send_email": false,
    "is_validated": true,
    "validated_at": "21-10-2026 01:35:11 PM",
    "errors": {
      "RUT01": "Regla: RUT01, Notificación: La validación del estado del RUT próximamente estará disponible",
      "FAJ44b": "Regla: FAJ44b, Notificación: Nit o Documento de Identificación informado No corresponde al registrado en el RUT con respecto a la razón social o nombre comercial suministrado.",
      "FAJ43b": "Regla: FAJ43b, Notificación: Nombre informado No corresponde al registrado en el RUT con respecto al Nit suministrado."
    },
    "observation": "Spike FacturaFlow: contado con IVA",
    "created_at": "21-10-2026 01:35:10 PM",
    "numbering_range": { "prefix": "SETP", "from": 990000000, "to": 995000000, "resolution_number": "18760000001",
                         "start_date": "19-01-2019", "end_date": "19-01-2030", "months": 132 },
    "customer": {
      "identification_document": { "code": "31", "name": "NIT" },
      "identification": "890903938", "dv": "8",
      "graphic_representation_name": "Cliente Ejemplo S.A.S.", "trade_name": null,
      "company": "Cliente Ejemplo S.A.S.", "names": null,
      "address": "Calle 1 # 2-3", "email": "facturacion@cliente.example", "phone": "3001234567",
      "legal_organization": { "code": "1", "name": "Persona Jurídica" },
      "tribute": { "code": "ZZ", "name": "No aplica" },
      "responsibilities": [ { "code": "R-99-PN", "name": "No responsable" } ],
      "country": { "code": "CO", "name": "Colombia" },
      "municipality": { "code": "11001", "name": "Bogotá, D.c.", "department": { "code": "11", "name": "Bogota- D.c." } }
    },
    "items": [ {
      "code_reference": "SKU-001", "name": "Teclado inalámbrico", "quantity": "2.00",
      "unit_measure": { "code": "94", "name": "unidad" },
      "standard_code": { "code": "999", "name": "Estándar de adopción del contribuyente" },
      "discount_rate": "10.00", "discount": "17000.00", "gross_value": "68000.00",
      "taxes": [ { "tribute": { "code": "01", "name": "IVA" }, "is_excluded": false,
                   "rates": [ { "taxable_amount": "153000.00", "tax_amount": "29070.00", "rate": "19.00" } ] } ],
      "withholding_taxes": [], "price": "85000.00", "total": "182070.00"
    } ],
    "taxes": [ { "tribute": { "code": "01", "name": "IVA" }, "is_excluded": false,
                 "rates": [ { "taxable_amount": "153000.00", "tax_amount": "29070.00", "rate": "19.00" } ] } ],
    "withholding_taxes": [],
    "totals": { "prepayment_amount": "0.00", "gross_amount": "153000.00", "taxable_amount": "153000.00",
                "tax_amount": "29070.00", "surcharge_amount": "0.00", "total": "182070.00" },
    "cufe": "308970cce2ea09184f4c643e52567899731562c671443c88afb59a824c152a6c4be746a05e32abd53f97e2acd1d145d1",
    "links": {
      "qr": "https://catalogo-vpfe-hab.dian.gov.co/document/searchqr?documentkey=308970cc…d145d1",
      "public_url": "https://app-sandbox.factus.com.co/documents/bills/466e850e…"
    }
  }
}
```

**Detalles relevantes:**
- **Montos:** van como **texto** con 2 decimales (`"182070.00"`); se parsean con `BigDecimal`.
- **`validated_at`:** viene como `"DD-MM-YYYY hh:mm:ss AM/PM"` (reloj de 12 horas) **sin zona horaria**. Es la hora de Colombia: el header `Date` decía `18:35:12 GMT` y `validated_at` decía `01:35:11 PM`. El adaptador debe interpretarla con `America/Bogota`.
- **`cufe`:** 96 caracteres hexadecimales, compatible con nuestro `ElectronicDocument`.
- **`links`:**
  - `qr` apunta al catálogo de la DIAN (habilitación);
  - `public_url` es la vista pública de Factus. Es un enlace-capacidad (cualquiera que lo tenga ve la factura), así que no debe ir a los logs;
  - **no hay URL directa del PDF**: el PDF se obtiene con el endpoint de la sección 8.
- **`customer.tribute`:** si no se envía, Factus pone `ZZ` "No aplica" por defecto.

## 3. Reenviar el mismo `reference_code`

| Envío | Contenido | HTTP | Tiempo | `number` | CUFE |
|---|---|---|---|---|---|
| 1 | original (cantidad 2, total 182.070) | 201 | 2315 ms | SETP990028958 | 308970cc… |
| 2 | idéntico | **201** | 545 ms | SETP990028958 | igual |
| 3 | **distinto** (cantidad 3, total 273.105) | **201** | 587 ms | SETP990028958 | igual; la respuesta sigue diciendo cantidad 2 y total 182.070 |

- Responde 201, no 200, y el mensaje es idéntico al de una creación. Lo único que distingue la repetición es el tiempo, porque no vuelve a consultar a la DIAN.
- Lo importante: **Factus ignora el contenido nuevo sin avisar.** Si alguna vez enviamos un `reference_code` que ya existe en la cuenta con otro contenido, recibiríamos un documento que **no corresponde** a nuestra factura. Por ejemplo, tras resetear la base local, o desde otro entorno que comparte la misma cuenta del sandbox. Hay que decidirlo en la Fase 3.
- Un **422 no consume** el `reference_code`: `SPIKE-1021-PAYMM01` falló con 422 y luego se creó con ese mismo código. Tampoco consume números: la factura siguiente tomó 990028960, justo después de 959.

## 4. Cuerpos de error

**401** (token inválido o revocado, en `/v2/bills/validate`):
```json
{ "status": "Unauthorized", "message": "Unauthenticated." }
```

**422 de validación de Factus** (NIT con DV incorrecto; no crea nada):
```json
{ "status": "Validation error", "message": "Error de validación",
  "data": { "message": "Error de validación",
            "errors": { "customer.dv": [ "El campo dígito de verificación no es valido" ] } } }
```
El formato es el de Laravel: `data.errors` es un mapa de **ruta del campo → lista de mensajes** (`items.0.taxes.0.code`, `payment_details.0.due_date`…).

**Otros 422 observados:**
- `"payment_details": ["La suma de todos los detalles de pago no es igual al total de la factura. Esperado: 182,070.00 - Enviado: 182,070.01"]`
- `"payment_details.0.due_date": ["El campo fecha de vencimiento debe ser una fecha posterior o igual a today."]`
- `"customer.email": ["El campo correo debe ser una dirección de correo válida.", "El campo correo debe tener un dominio válido (como \"dominio.com\")."]`
- `"numbering_range_id": ["El campo id rango de numeración es inválido."]`

**Rechazo de la DIAN:** con los datos probados no apareció. Factus valida antes casi todo lo que la DIAN rechazaría (DV, totales, fechas, códigos).

**403 de Cloudflare** (User-Agent bloqueado): es un `application/json` con `"title": "Error 1010: Access denied"` y `"error_name": "browser_signature_banned"`. No es un error de Factus.

**404 de ruta:** `{ "status": "Not Found", "message": "No se encontró la ruta o recurso solicitado." }`

**409:** no apareció.

## 5. `is_validated: false`

No apareció: las 3 facturas creadas llegaron con `is_validated: true` en la misma respuesta (unos 2,3 s). Como `errors` también trae notificaciones en las facturas validadas, la regla debe ser: **la validez la decide `is_validated`, no la presencia de `errors`.**

## 6. Totales de Factus frente a nuestro dominio

Factura `SPIKE-1021-CRED01` (`payloads/credit-cc-discount-iva-inc-excluded.json`), pensada para forzar el redondeo:
- persona natural con CC, a crédito, Medellín (`05001`);
- línea 1: 3 × 33.333,33 con 7,5 % de descuento, IVA 19 % + INC 8 %;
- línea 2: 1,5 × 20.000,01, excluida de IVA.

Se envió con `payment_details.amount` = **el total que calcula nuestro dominio** y Factus lo aceptó, lo que implica que su total coincide.

| Concepto | Nuestro dominio (ADR 0004) | Factus | ¿Igual? |
|---|---|---|---|
| L1 bruto | 99.999,99 | (no lo informa como tal) | — |
| L1 descuento | 7.500,00 | `discount` 7500.00 | ✔ |
| L1 base | 92.499,99 | `taxable_amount` 92499.99 | ✔ |
| L1 IVA 19 % | 17.575,00 | 17575.00 | ✔ |
| L1 INC 8 % | 7.400,00 | 7400.00 | ✔ |
| L1 total | 117.474,99 | 117474.99 | ✔ |
| L2 (excluida) | 30.000,02 (`HALF_UP` de 30.000,015) | `taxable_amount` 30000.02, `tax_amount` 0.00 | ✔ |
| Impuestos | 24.975,00 | `tax_amount` 24975.00 | ✔ |
| **Total** | **147.475,01** | **`total` 147475.01** | ✔ |

La factura de contado (182.070,00) también coincidió. **Conclusión:** el redondeo `HALF_UP` por línea y por impuesto del ADR 0004 coincide con el de Factus en estos casos. Igual se debe guardar el total oficial y avisar si alguna vez difiere.

**Cómo representar cada caso:**
- Línea **excluida de IVA**: `"taxes": [ { "code": "01", "rate": 0, "is_excluded": true } ]`. En la respuesta aparece con `is_excluded: true`.
- **IVA + INC** en la misma línea: dos entradas en `taxes`, `01` y `04`.
- `is_excluded` a nivel de ítem no es un campo que Factus valide.

## 7. Rangos de numeración

`GET /v2/numbering-ranges` muestra 6 rangos activos y **un solo** rango de "Factura de Venta": `id 389`, prefijo `SETP`, del 990000000 al 995000000, resolución 18760000001. Su `current` era 990028958: el sandbox es **compartido** y otras cuentas consumen el mismo rango, así que los números no son consecutivos para nosotros.

Los demás rangos son notas crédito (`CRTE`, `NC`), nota débito (`ND`), nota de ajuste (`NA`) y documento soporte (`SEDS`).

**`numbering_range_id` es opcional:** sin enviarlo, Factus usó el rango 389. Un id inexistente da 422. Propuesta: configurable y opcional, como ya estaba decidido.

## 8. Descarga del PDF

`GET /v2/bills/{number}/download-pdf` → HTTP 200, `application/json`:
```json
{ "status": "OK", "message": "…", "data": { "file_name": "fv10007890020002600029353", "pdf_base_64_encoded": "JVBERi0xLjQK…" } }
```
El contenido decodificado ocupa 51.534 bytes y empieza con `%PDF-1.4`. Tardó 854 ms. `file_name` no trae extensión.

**Consulta de facturas** (útil para una futura reconciliación):
- `GET /v2/bills/{number}` → la factura completa, con la misma estructura que la validación;
- `GET /v2/bills?filter[reference_code]=…` → lista paginada. Cada elemento trae `is_validated`, `number`, `total`, `errors`… y `api_client_name`, que es el **email de la cuenta** y no debe registrarse;
- `GET /v2/bills/show/{number}` → 404 (esta ruta no existe en v2).

## 9. Códigos de catálogo verificados

**Cómo se verificaron:** con `codes`, que envía el mismo esqueleto válido **sin `reference_code`** y cambia un solo código cada vez. "Acepta" significa que ese campo no produjo error; los códigos son los del anexo técnico de la DIAN.

**Tipo de documento** (`customer.identification_document_code`):

| Código | Documento | Nuestro `DocumentType` |
|---|---|---|
| 11 | Registro civil | `CIVIL_REGISTRY` |
| 12 | Tarjeta de identidad | `IDENTITY_CARD` |
| 13 | Cédula de ciudadanía | `CITIZENSHIP_CARD` |
| 21 | Tarjeta de extranjería | `FOREIGNER_CARD` |
| 22 | Cédula de extranjería | `FOREIGNER_ID_CARD` |
| 31 | NIT | `NIT` |
| 41 | Pasaporte | `PASSPORT` |
| 42 | Documento de identificación extranjero | `FOREIGN_ID_DOCUMENT` |
| 47 | PEP | `SPECIAL_STAY_PERMIT` |
| 48 | PPT | `TEMPORARY_PROTECTION_PERMIT` |
| 50 | NIT de otro país | `FOREIGN_NIT` |
| 91 | NUIP | (no lo tenemos) |

Todos se aceptan. Con los tipos distintos de 31 y 50, Factus exige además `names`.

**Organización jurídica** (`customer.legal_organization_code`):
- `1` = Persona Jurídica: va con `company`;
- `2` = Persona Natural: exige `names`.

**Tributo del cliente** (`customer.tribute_code`): `01` (IVA) y `ZZ` (No aplica). Rechaza `04` y `ZA`. Si se omite, el valor por defecto es `ZZ`.

**Responsabilidades fiscales** (`customer.responsibilities`, lista obligatoria):
- `O-13` gran contribuyente;
- `O-15` autorretenedor;
- `O-23` agente de retención IVA;
- `O-47` régimen simple de tributación;
- `R-99-PN` no responsable.

Un código inexistente da "El valor de responsabilidades fiscales no existe".

**Municipio** (`customer.municipality_code`): es el **código DIVIPOLA del DANE de 5 dígitos**. Se probaron `11001` (Bogotá), `05001` (Medellín) y `68679` (San Gil, el del emisor); `00000` es inválido. La respuesta devuelve además el nombre y el departamento.

**Unidad de medida** (`items.*.unit_measure_code`):

| Acepta | Nuestro `UnitOfMeasure` |
|---|---|
| `94` (unidad) | `UNIT` |
| `KGM` | `KILOGRAM` |
| `GRM` | `GRAM` |
| `LBR` | `POUND` |
| `LTR` | `LITER` |
| `MLT` | `MILLILITER` |
| `GLL` | `GALLON` |
| `MTR` | `METER` |

**Rechazadas** (48 códigos probados): `CMT`, `MTK`, `HUR`, `DAY`, `DZN`, `BX`, `PK`, `E48`, `NIU`, `C62`, `EA`, `H87`, `DZ`, `DPC`, `XBX`, `BO`, `XPK`, `XPA`, `PA`, `PR`, `SET`, `HR`, `MIN`, `MON`, `WEE`, `ANN`, `LM`, `MMT`, `KMT`, `M2`, `CMK`, `MTQ`, `TNE`, `ONZ`, `KWH`, `ZZ`, `04`, `26`, `70` y `A86`.

**Sin código aceptado** quedan `DOZEN`, `BOX`, `PACKAGE`, `CENTIMETER`, `SQUARE_METER`, `HOUR`, `DAY` y `SERVICE`. Factus parece tener una tabla propia reducida, y en v2 no hay un endpoint para listarla.

**Código estándar del ítem** (`items.*.standard_code`): `999` (estándar de adopción del contribuyente) se acepta. También `001`, `010` y `020`.

**Tributo del ítem** (`items.*.taxes.*.code`): `01` IVA, `04` INC y `03` ICA. `ZZ` es inválido aquí. Las retenciones van en `withholding_taxes` (`code`, `rate`).

**Forma de pago** (`payment_details.*.payment_form`):
- `1` contado;
- `2` crédito, que exige `due_date` en formato `YYYY-MM-DD`; la respuesta lo devuelve como `DD-MM-YYYY`.

**Medio de pago** (`payment_details.*.payment_method_code`):

| Código | Medio | Nuestro `PaymentMethod` |
|---|---|---|
| `10` | Efectivo | `CASH` |
| `20` | Cheque | `CHECK` |
| `42` | Consignación | `BANK_DEPOSIT` |
| `47` | Transferencia | `BANK_TRANSFER` |
| `48` | Tarjeta crédito | `CREDIT_CARD` |
| `49` | Tarjeta débito | `DEBIT_CARD` |
| `ZZZ` | Otro | — |

**Campos opcionales del cliente:** `address`, `phone`, `email`, `trade_name` y `municipality_code`. La factura de la persona natural se validó **sin** dirección ni teléfono.

**Cliente sin municipio, dirección ni teléfono** (verificación de la Fase 2, `payloads/cash-cc-no-location.json`): `SETP990028966` se **validó** (`is_validated: true`). La DIAN agrega la notificación `FAK08` ("El grupo deberá estar conformado al menos por el conjunto de elementos ID, CityName, CountrySubentity, … AddressLine …"), que no impide la validación. La respuesta trae `address: "No informado"` y `municipality: null`. Conclusión: el municipio puede ser **opcional** en el dominio, pero conviene informarlo para evitar la notificación.

**Otros campos de la raíz:** `operation_type` (validado; `10` = estándar por defecto), `observation` (texto), `numbering_range_id` (opcional) y `send_email` (la respuesta refleja `false`). El valor por defecto `true` de `send_email` no se probó: el script siempre envía `false`.

## 10. Límite de peticiones y tiempos

- Headers vistos: `X-RateLimit-Limit: 120` y `X-RateLimit-Remaining` en la API; `60` en `/oauth/token`. **Difieren de lo documentado (80/min)**, y además el límite es distinto por endpoint.
- No se provocó un 429 (las reglas del spike lo prohíben), así que el formato de `Retry-After` no está verificado.
- **Tiempos:**
  - creación con validación DIAN: unos 2,3 s;
  - reenvío idempotente: unos 0,55 s;
  - validación fallida (422): unos 0,45 s;
  - PDF: unos 0,85 s;
  - token: unos 0,6 s.

  Propuesta: timeout de lectura de 30 s para la validación y de 10 s para el resto.

## Implicaciones para las fases siguientes

**Fase 2 (dominio):**
- **Tipo de persona:** Factus lo exige (`legal_organization_code` 1 o 2), junto con la regla "jurídica → `company`, natural → `names`".
- **Responsabilidad tributaria:** `tribute_code` con solo **dos** valores útiles, `01` (responsable de IVA) y `ZZ` (no aplica).
- **Responsabilidades fiscales:** lista con O-13, O-15, O-23, O-47 y R-99-PN.
- **Municipio DIVIPOLA:** Factus lo acepta y lo valida.
- **Dirección y teléfono:** son opcionales también para Factus.
- **Unidades de medida:** hay que decidir qué hacer con las 8 que Factus no acepta.

**Fase 3 (adaptador):**
- **Idempotencia ciega de Factus (sorpresa 1):** hay que decidir qué `reference_code` se envía y cómo verificar la respuesta.
- **TokenProvider:** debe tener en cuenta la revocación inmediata en el refresh y la rotación del refresh token.
- **Mapeo de resultados:**
  - `is_validated: true` → `Issued`, aunque `errors` traiga notificaciones (que conviene registrar);
  - 422 de validación de Factus → no consume el `reference_code`.
- **Detalles HTTP:**
  - fechas con `America/Bogota` y formato de 12 horas;
  - montos como texto;
  - User-Agent propio;
  - solo `/v2`.
- **Casos no observados:** 409 e `is_validated: false` se simularán con WireMock según la documentación, marcados como no verificados.
