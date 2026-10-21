-- V3: huella de la petición que creó cada factura (idempotencia, ADR 0006).
-- Repetir una creación con el mismo reference_code solo es un reintento si la huella coincide;
-- si no, es un conflicto (409).
--   * SHA-256 en hexadecimal minúscula: el largo lo fija el algoritmo, no el dominio, por eso
--     TEXT + CHECK (que exige exactamente 64 caracteres) en vez de VARCHAR(64).
--   * NOT NULL sin valor por defecto: toda factura nace de una petición. Antes de V3 no existía
--     ningún camino para crear facturas fuera de los tests, así que la tabla está vacía; si no lo
--     estuviera, esta migración fallaría de forma visible en vez de inventar huellas.
--   * No se edita: si cambia el formato de la huella, el prefijo de versión del texto canónico
--     lo distingue (ver InvoiceRequestHasher) y cualquier cambio de esquema va en una V4.

ALTER TABLE invoices ADD COLUMN request_hash TEXT NOT NULL;

ALTER TABLE invoices ADD CONSTRAINT ck_invoices_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$');
