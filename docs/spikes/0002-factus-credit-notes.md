# Spike 0002. Notas crédito en el sandbox de Factus

- **Fecha:** 2026-10-22
- **Entorno:** `https://api-sandbox.factus.com.co`, la misma empresa de pruebas del [spike 0001](0001-factus-sandbox.md).
- **Objetivo:** confirmar cómo se comporta `POST /v2/credit-notes/validate` antes de diseñar el agregado `CreditNote`. La [documentación](https://developers.factus.com.co/notas-credito/crear-y-validar/) no lista los motivos ni describe la respuesta.
- **Facturas acreditadas:** dos del spike 0001, `SETP990028958` (contado) y `SETP990028959` (crédito, 2 líneas). No se emitieron facturas nuevas.
- **Notas crédito creadas:** 5, de `NC1661` a `NC1665`, todas con `reference_code` `SPIKE-1022-…` y `send_email: false`.
  - Hubo **7 envíos con `reference_code`**: las 5 creaciones, 1 reenvío de un código ya usado y 1 rechazo 422, que no crea nada.
  - Además hubo **13 probes sin `reference_code`**, que no pueden crear nada.
  - No se borró nada.

## Cómo reproducirlo

```bash
./scripts/spikes/factus-spike.sh probe /v2/credit-notes/validate                                   # campos obligatorios
./scripts/spikes/factus-spike.sh probe /v2/credit-notes/validate probes/credit-note-minimal.json   # qué exige cada ítem
./scripts/spikes/factus-spike.sh note-codes probes/credit-note-full.json 0,1,2,3,4,5,6,7,XX        # motivos válidos
./scripts/spikes/factus-spike.sh note payloads/credit-notes/partial-return-959.json SPIKE-<código> # crea una nota
./scripts/spikes/factus-spike.sh pdf NC1661 /v2/credit-notes/NC1661/download-pdf                   # PDF de la nota
./scripts/spikes/factus-spike.sh get /v2/bills/SETP990028959                                       # notas de la factura
```

El script es el del spike 0001, con las mismas salvaguardas. Además:
- `note` usa su propio tope de **8 envíos** (`output/credit-notes.log`), aparte del de facturas;
- `note-codes`, como `probe`, quita `reference_code`, así que nunca puede crear una nota.

## Resumen de sorpresas respecto a la documentación

| # | Lo que dice la documentación o lo que asumíamos | Lo que pasa en realidad | Impacto |
|---|---|---|---|
| 1 | Factus controla que no se acredite más de lo facturado | **No controla nada**: aceptó una devolución de 5 unidades sobre 2 facturadas, una nota que lleva el acumulado a 4 de 3 y una anulación sobre una factura ya devuelta en parte. `SETP990028959` (total 147.475,01) tiene hoy **304.108,33 acreditados** | **Alto (Fase 2).** La regla "nunca acreditar más de lo facturado" la garantiza **solo nuestro dominio**. |
| 2 | `customer` es opcional: se toma de la factura | **Obligatorio**: sin él da 422 `"El campo customer es obligatorio."`. Además **no se compara** con el de la factura: una nota sobre la factura de un NIT se validó con la cédula de otro cliente | El adaptador envía el cliente **de nuestra factura**; la coherencia es responsabilidad nuestra. |
| 3 | Los ítems referencian la factura | Cada ítem exige `name`, `price`, `unit_measure_code`, `standard_code` y `taxes` completos. No se cruzan con la factura: Factus calcula los totales con lo que se envía | La nota lleva una **copia** de los datos de cada línea acreditada. |
| 4 | `numbering_range_id` solo si hay varios rangos | Hay dos rangos activos de nota crédito (`390` NC y `1776` CRTE), así que **es obligatorio**. El rango de facturas (`389`) da "inválido" | **Configuración obligatoria** para las notas, a diferencia de las facturas. |
| 5 | La respuesta tiene la misma forma que la de la factura | Hay tres diferencias: trae **`cude`** (no `cufe`), **`errors` es una lista** de textos (en facturas es un mapa) y agrega `bill` y `correction_concept` | El intérprete de respuestas no puede reutilizarse tal cual. |
| 6 | — | Todas las notas traen la notificación `CBF02` "No se informó el número de la factura referenciada", **aunque** la referencia sí llegó (`bill.number` y `bill.cufe` están en la respuesta y la factura lista la nota) | Es una notificación de habilitación, no un rechazo: `is_validated: true`. |
| 7 | — | Reenviar un `reference_code` es **idempotencia ciega**, igual que en facturas | Hay que verificar la identidad de la respuesta, igual que en facturas. |
| 8 | — | La factura **no cambia**: sigue `is_validated: true` con su total original, y solo gana `related_notes.credit_notes` (número y fecha, sin montos) | Confirma la decisión C: la factura sigue ISSUED, y el saldo lo calculamos nosotros. |

## 1. Motivos (`correction_concept_code`)

Se probaron con `note-codes`, que envía el mismo esqueleto válido sin `reference_code` y cambia solo el motivo. Los nombres son los que devuelve Factus en `correction_concept.name`; los de los códigos 3 a 6 vienen del anexo técnico de la DIAN.

| Código | Resultado | Nombre | Uso en FacturaFlow |
|---|---|---|---|
| `1` | acepta | Devolución parcial de los bienes y/o no aceptación parcial del servicio | **devolución parcial** |
| `2` | acepta | Anulación de factura electrónica | **anulación total** |
| `3` | acepta | Rebaja o descuento parcial o total | fuera de alcance |
| `4` | acepta | Ajuste de precio | fuera de alcance |
| `5` | acepta | Descuento comercial por pronto pago | fuera de alcance |
| `6` | acepta | Descuento comercial por volumen de ventas | fuera de alcance |
| `0`, `7`, `XX` | **inválido** | "El campo código de concepto de corrección es inválido." | — |

- `customization_id` = `20` es "Nota Crédito que referencia una factura electrónica", el valor por defecto. El `22`, sin factura, no aplica a FacturaFlow.
- Factus **no** comprueba que el contenido corresponda al motivo: una nota de anulación (`2`) se valida igual con ítems parciales o totales. Que la anulación acredite **todo lo pendiente** lo decide nuestro dominio.

## 2. Campos obligatorios

**Cuerpo vacío** (422):

```json
{
  "correction_concept_code": ["El campo código de concepto de corrección es obligatorio."],
  "bill_number": ["El campo bill number es obligatorio."],
  "reference_code": ["El campo código de referencia es obligatorio."],
  "numbering_range_id": ["El campo id rango de numeración es obligatorio."],
  "payment_details": ["El campo detalles de pago es obligatorio."],
  "customer": ["El campo customer es obligatorio."],
  "customer.identification": ["El campo número documento de identidad es obligatorio."],
  "customer.responsibilities": ["El campo responsabilidades fiscales es obligatorio."],
  "items": ["El campo items es obligatorio."]
}
```

**Ítem con solo `code_reference` y `quantity`** (`probes/credit-note-minimal.json`): exige `items.0.name`, `items.0.price`, `items.0.unit_measure_code`, `items.0.standard_code` e `items.0.taxes`.

**Conclusión (pregunta 6):** Factus **no** toma nada de la factura. Hay que enviar el cliente, los ítems con precio, el descuento, la unidad, el código estándar y los impuestos, y los detalles de pago. Lo único que comprueba de la factura es que **exista**: con `bill_number: "SETP990000001"` responde 422 `"bill_number": ["La factura referenciada no existe."]`, y ese rechazo no consume el `reference_code`.

## 3. Devolución parcial válida: estructura real

**Petición** (`payloads/credit-notes/partial-return-959.json`): devuelve **1 de las 3 unidades** de la línea `SKU-RND` de `SETP990028959`, con los mismos precio, descuento e impuestos de la factura.

```json
{
  "reference_code": "SPIKE-1022-NCPART01",
  "correction_concept_code": "1",
  "customization_id": "20",
  "bill_number": "SETP990028959",
  "numbering_range_id": 390,
  "observation": "Spike FacturaFlow: devolución parcial (1 de 3 unidades de SKU-RND)",
  "customer": {
    "identification_document_code": "13", "identification": "1020304050", "names": "Ana María Gómez",
    "legal_organization_code": "2", "municipality_code": "05001", "email": "ana.gomez@correo.example",
    "responsibilities": [ "R-99-PN" ]
  },
  "items": [ {
    "code_reference": "SKU-RND", "name": "Producto con centavos", "quantity": "1.00", "price": "33333.33",
    "discount_rate": "7.50", "unit_measure_code": "94", "standard_code": "999",
    "taxes": [ { "code": "01", "rate": "19.00" }, { "code": "04", "rate": "8.00" } ]
  } ],
  "payment_details": [ { "payment_form": "1", "payment_method_code": "47", "amount": "39158.33" } ],
  "send_email": false
}
```

**Respuesta: HTTP 201** en 2,3 s. Es un extracto: se omitió `company`, se recortaron los bloques iguales a los de la factura y `public_url` está enmascarado.

```json
{
  "status": "Created",
  "message": "Documento con el código de referencia SPIKE-1022-NCPART01 registrado y validado con éxito",
  "data": {
    "reference_code": "SPIKE-1022-NCPART01",
    "number": "NC1661",
    "correction_concept": { "code": "1", "name": "Devolución parcial de los bienes y/o no aceptación parcial del servicio" },
    "customization_id": { "code": "20", "name": "Nota Crédito que referencia una factura electrónica." },
    "payment_details": [ { "payment_form": { "code": "1", "name": "Pago de contado" },
                           "payment_method": { "code": "47", "name": "Transferencia" },
                           "reference_code": null, "amount": "39158.33", "due_date": null } ],
    "billing_period": null,
    "send_email": false,
    "is_validated": true,
    "validated_at": "22-10-2026 03:17:42 PM",
    "errors": [
      "Regla: CBF02, Notificación: No se informo el numero de la factura referenciada",
      "Regla: RUT01, Notificación: La validación del estado del RUT próximamente estará disponible."
    ],
    "observation": "Spike FacturaFlow: devolución parcial (1 de 3 unidades de SKU-RND)",
    "created_at": "22-10-2026 03:17:41 PM",
    "customer": {
      "identification_document": { "code": "13", "name": "Cédula ciudadanía" },
      "identification": "1020304050", "dv": null, "names": "Ana María Gómez", "company": null,
      "legal_organization": { "code": "2", "name": "Persona Natural" }, "…": "igual que en la factura"
    },
    "items": [ {
      "code_reference": "SKU-RND", "name": "Producto con centavos", "quantity": "1.00",
      "discount_rate": "7.50", "discount": "2500.00", "gross_value": "30833.33",
      "taxes": [
        { "tribute": { "code": "01", "name": "IVA" }, "is_excluded": false,
          "rates": [ { "taxable_amount": "30833.33", "tax_amount": "5858.33", "rate": "19.00" } ] },
        { "tribute": { "code": "04", "name": "INC" }, "is_excluded": false,
          "rates": [ { "taxable_amount": "30833.33", "tax_amount": "2466.67", "rate": "8.00" } ] } ],
      "price": "33333.33", "total": "39158.33"
    } ],
    "totals": { "gross_amount": "30833.33", "taxable_amount": "30833.33", "tax_amount": "8325.00",
                "surcharge_amount": "0.00", "total": "39158.33" },
    "cude": "b659dc4457ab1b6809496733bfc393fae329efc39fe9072a7b619d81c1425e9313b61151d3960e00181f724424a30af5",
    "bill": {
      "reference_code": "SPIKE-1021-CRED01",
      "number": "SETP990028959",
      "cufe": "3404fc52d61f1f64d31156d5a0155be46086a2478895be6722fc99408404c10e61d8a16feaebcd326750ba581f3e866a"
    },
    "links": {
      "qr": "https://catalogo-vpfe-hab.dian.gov.co/document/searchqr?documentkey=b659dc44…a30af5",
      "public_url": "https://app-sandbox.factus.com.co/documents/credit-notes/87da4262…"
    }
  }
}
```

**Detalles relevantes:**
- **Número:** `NC1661`, el prefijo del rango 390 más un consecutivo, sin ceros a la izquierda. El rango es **compartido** con otras cuentas del sandbox (su `current` era 1640 el día anterior).
- **`cude`:** el código único del documento de una nota se llama CUDE, no CUFE, y tiene el mismo formato: 96 caracteres hexadecimales, igual al `documentkey` del QR. El `ElectronicDocument` del dominio sirve si el campo se generaliza.
- **`bill`:** devuelve el `reference_code`, el `number` y el `cufe` de la factura acreditada. Sirve para **verificar la identidad** de la nota además de su propio `reference_code`.
- **Totales:** coinciden con el redondeo `HALF_UP` por línea e impuesto del ADR 0004:
  - bruto 33.333,33;
  - descuento 7,5 % = 2.499,99975 → **2.500,00**;
  - base 30.833,33;
  - IVA 19 % = 5.858,3327 → **5.858,33**;
  - INC 8 % = 2.466,6664 → **2.466,67**;
  - total **39.158,33**.
- **`validated_at`:** el mismo formato de 12 horas sin zona (hora de Colombia) que en las facturas.
- **`errors`:** es una **lista de textos**, no un mapa como en las facturas. Con `is_validated: true` son notificaciones. `CBF02` sale siempre en habilitación (sorpresa 6).
- **`links.public_url`:** es un enlace-capacidad, igual que en las facturas, y no debe ir a los logs.
- **Pago:** `payment_details` es obligatorio. Siempre se envió `amount` igual al total de la nota. **NO VERIFICADO:** si un monto distinto da 422 como en las facturas.
- **Tiempos:** creación unos 2,3 s; reenvío 0,48 s; rechazo 422 0,6 s; PDF 1 s; consulta 0,43 s.

## 4. Devolución parcial (pregunta 3)

**Sí la acepta:** una sola línea, con una cantidad menor a la facturada (1 de 3), y la nota se validó. Lo que no se envía no se acredita: Factus no exige repetir las demás líneas.

## 5. Acreditar más de lo facturado (pregunta 4)

| Envío | Factura | Qué se acreditó | Acumulado | Resultado |
|---|---|---|---|---|
| `NC1661` | …959 | 1 de 3 `SKU-RND` | 1/3 | 201, validada |
| `NC1662` | …959 | 3 `SKU-RND` | **4/3** | **201, validada** |
| `NC1663` (anulación, `2`) | …959 | todo: 3 `SKU-RND` + 1,5 `SKU-EXC` | **7/3 y 1,5/1,5** | **201, validada** |
| `NC1664` | …958 | 5 `SKU-001` (se facturaron **2**) | **5/2** | **201, validada** |

- Factus y la DIAN de habilitación **no** controlan cantidades ni montos acreditados, ni por nota ni acumulados. Tampoco impiden anular una factura que ya tiene devoluciones.
- `SETP990028959` sigue `is_validated: true` con total 147.475,01. En `GET /v2/bills/SETP990028959` solo cambió `related_notes.credit_notes`, que lista `NC1661`, `NC1662` y `NC1663` con su número, fecha y `note_url` (otro enlace-capacidad), **sin montos**.

**Conclusión:** la regla que cruza agregados del ADR nuevo es la **única** protección. No hay un "422 de Factus" que la respalde. En producción la DIAN podría ser más estricta que en habilitación; no se puede comprobar en el sandbox.

## 6. `reference_code` repetido (pregunta 5)

| Envío | Contenido | HTTP | Tiempo | `number` | Contenido de la respuesta |
|---|---|---|---|---|---|
| 1 | 1 unidad, total 39.158,33 | 201 | 2309 ms | NC1661 | el enviado |
| 2 | **2 unidades**, total 78.316,66, otra observación | **201** | 476 ms | NC1661 | **el original**: 1 unidad, 39.158,33, la observación original |

Es la misma **idempotencia ciega** que en las facturas (spike 0001, sorpresa 1): el mismo mensaje de "registrado y validado con éxito", sin comparar el contenido. El adaptador debe verificar la respuesta igual que con las facturas: el `reference_code`, la factura referenciada (`bill.number`), la identificación del cliente y el total.

## 7. El cliente (pregunta 6)

- **Obligatorio** (sorpresa 2), en contra de lo que dice la documentación.
- **No se compara con la factura:** `NC1665` acredita `SETP990028958`, cuyo cliente es el NIT 890903938, pero se envió con la cédula 1020304050 de otra persona. Se validó, y su `customer` refleja lo enviado mientras `bill` apunta a `SETP990028958`.
- **Consecuencia:** el adaptador envía siempre el cliente de **nuestra** factura, que el agregado de la nota no duplica (lo lee de la factura), y verifica que la respuesta lo devuelva.

## 8. PDF de la nota crédito (pregunta 7)

`GET /v2/credit-notes/{number}/download-pdf` → HTTP 200, `application/json`, con la misma forma que en las facturas:

```json
{ "status": "OK", "message": "…", "data": { "file_name": "nc10007890020002600002877", "pdf_base_64_encoded": "JVBERi0xLjQK…" } }
```

El contenido decodificado ocupa 52.169 bytes y empieza con `%PDF-1.4`. Tardó 978 ms, y `file_name` tampoco trae extensión.

**Consulta:** `GET /v2/credit-notes/{number}` → 200 con la misma estructura de la validación (`cude`, `bill`, `correction_concept`, `totals`…).

## Casos no observados

- **409** "documento pendiente por enviar a la DIAN" e **`is_validated: false`:** no aparecieron (5 notas, todas validadas en la misma respuesta). La documentación sugiere "eliminar la nota y crear una nueva"; **nunca borramos nada en Factus**, así que se tratarán como en facturas, como falla técnica.
- **Rechazo por los datos de la nota más allá de la existencia de la factura:** los 422 vistos fueron de forma (campos obligatorios, motivo, rango y factura inexistente).
- **Monto de pago distinto del total de la nota:** ver la sección 3.

## Implicaciones para las fases siguientes

**Fase 2 (dominio y persistencia):**
- Motivos propios: `PARTIAL_RETURN` → `1` y `TOTAL_ANNULMENT` → `2`. Los códigos los traduce el adaptador.
- **La regla "nunca acreditar más de lo facturado" (por línea y cantidad, incluso con notas concurrentes) depende solo de nosotros** (sorpresa 1). Es el punto de diseño más importante de la fase.
- Cada línea acreditada necesita los datos de la línea de la factura: nombre, código, precio, descuento, unidad e impuestos. Se pueden leer de la factura (referencia a la línea + cantidad) o copiar a la nota. La factura es inmutable una vez ISSUED, así que leerlos de ella basta.
- Los totales de la nota se calculan con las mismas reglas que la factura. El saldo en dinero puede diferir **en centavos** de "total facturado − total acreditado" cuando una línea se acredita en varias partes, por el redondeo por línea. La regla se expresa **en cantidades**, y el monto se muestra como información.
- `ElectronicDocument` necesita un nombre neutro para el CUFE/CUDE, o un campo propio en la nota.

**Fase 3 (adaptador):**
- `numbering_range_id` de notas crédito: **configuración obligatoria** (sorpresa 4), distinta de la de facturas.
- El cliente se toma de la factura (sorpresa 2), y los ítems se arman con los datos de cada línea acreditada y su cantidad (sorpresa 3).
- Verificación de identidad: `reference_code` de la nota, `bill.number` de la factura, identificación del cliente; si solo difiere el total, WARN.
- Diferencias con el intérprete de facturas (sorpresa 5): `cude` en lugar de `cufe` y `errors` como lista. El resto (estados HTTP, 422 con un mapa de campos, `validated_at`, montos como texto, Base64 del PDF) es igual y se puede reutilizar.
- `customization_id` se envía siempre `20`.
