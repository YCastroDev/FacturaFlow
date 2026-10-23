# Respuestas de Factus para los tests (WireMock)

Salen de los spikes [0001](../../../../../../docs/spikes/0001-factus-sandbox.md) (facturas) y
[0002](../../../../../../docs/spikes/0002-factus-credit-notes.md) (notas crédito) contra el sandbox real.
`{{REFERENCE_CODE}}` y `{{PDF_BASE64}}` los reemplaza cada test.

| Archivo | Origen |
|---|---|
| `request-cash.json`, `request-credit.json` | Las peticiones que Factus **aceptó** en el spike (`cash-nit-iva`, `credit-cc-discount-iva-inc-excluded`), más `tribute_code` y `send_email`, que el traductor siempre envía. |
| `bill-validated-cash.json`, `bill-validated-credit.json` | **Reales** (201). Sin el bloque `company` y con `public_url` falsa (es un enlace-capacidad). |
| `bill-422-wrong-dv.json`, `bill-422-past-due-date.json` | **Reales** (422). |
| `bill-422-numbering-range.json` | **Real** el mensaje (spike, sección 4); el cuerpo se armó con el formato de los otros 422. |
| `pdf.json` | **Real** la estructura; el PDF lo genera el test. |
| `error-401.json`, `token-refresh-revoked.json` | **Reales** (401). |
| `bill-not-validated.json` | **NO VERIFICADO.** `is_validated: false` no apareció en el spike: es la respuesta real de contado con ese campo cambiado. |
| `bill-409-pending.json` | **NO VERIFICADO.** El 409 no apareció; el texto sigue la documentación de Factus. |
| `error-429.json` | **NO VERIFICADO.** No se provocó un 429 (reglas del spike); es el cuerpo por defecto de Laravel. |
| `error-500.json` | **NO VERIFICADO.** |
| `credit-note-request-partial.json` | La petición que el traductor arma para devolver 1 de 3 unidades de `SETP990028959`: la misma que Factus **aceptó** en el spike 0002 (`NC1661`), con el cliente y el ítem de `request-credit.json` y montos como números (verificado con un probe sin `reference_code`). |
| `credit-note-validated-partial.json`, `credit-note-validated-annulment.json` | **Reales** (201, `NC1661` y `NC1663`). Sin el bloque `company` y con `public_url` falsa. |
| `credit-note-422-unknown-bill.json` | **Real** (422 "La factura referenciada no existe."). |
| `credit-note-422-numbering-range.json` | **Real** (probe con el rango de facturas, 389), sin el error de `reference_code` que el adaptador nunca omite. |
| `credit-note-422-item.json` | **Real** el mensaje (probe del spike 0002); el cuerpo se armó con el formato de los otros 422. |
| `is_validated: false` y 409 de notas | **NO VERIFICADOS.** Los tests reutilizan la respuesta real cambiando el campo, y `bill-409-pending.json`. |
