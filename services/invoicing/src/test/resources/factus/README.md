# Respuestas de Factus para los tests (WireMock)

Salen del [spike 0001](../../../../../../docs/spikes/0001-factus-sandbox.md) contra el sandbox real.
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
