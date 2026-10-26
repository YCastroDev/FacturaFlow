-- V3: resultado de la facturación de la venta (ADR 0015). La venta pasa de CONFIRMED a INVOICED (con la factura) o a
-- INVOICE_FAILED (con el motivo), según los eventos de invoicing. Las restricciones repiten las reglas del dominio.

ALTER TABLE sales DROP CONSTRAINT ck_sales_status;
ALTER TABLE sales ADD CONSTRAINT ck_sales_status CHECK (status IN ('CONFIRMED', 'INVOICED', 'INVOICE_FAILED'));

-- Factura emitida (INVOICED). Número y CUFE los asigna el proveedor: sin límite propio de longitud.
ALTER TABLE sales ADD COLUMN invoice_id UUID;
ALTER TABLE sales ADD COLUMN invoice_number TEXT;
ALTER TABLE sales ADD COLUMN invoice_cufe TEXT;
ALTER TABLE sales ADD COLUMN invoiced_at TIMESTAMPTZ;
-- Fallo (INVOICE_FAILED): el motivo (máximo 1000, como el dominio) y, si la DIAN rechazó una factura, su id.
ALTER TABLE sales ADD COLUMN invoice_failure_reason VARCHAR(1000);
ALTER TABLE sales ADD COLUMN invoice_failed_at TIMESTAMPTZ;
ALTER TABLE sales ADD COLUMN rejected_invoice_id UUID;

ALTER TABLE sales ADD CONSTRAINT ck_sales_invoicing_outcome CHECK (
    (status = 'CONFIRMED'
        AND invoice_id IS NULL AND invoice_number IS NULL AND invoice_cufe IS NULL AND invoiced_at IS NULL
        AND invoice_failure_reason IS NULL AND invoice_failed_at IS NULL AND rejected_invoice_id IS NULL)
    OR (status = 'INVOICED'
        AND invoice_id IS NOT NULL AND invoice_number IS NOT NULL AND invoice_cufe IS NOT NULL
        AND invoiced_at IS NOT NULL
        AND invoice_failure_reason IS NULL AND invoice_failed_at IS NULL AND rejected_invoice_id IS NULL)
    OR (status = 'INVOICE_FAILED'
        AND invoice_id IS NULL AND invoice_number IS NULL AND invoice_cufe IS NULL AND invoiced_at IS NULL
        AND invoice_failure_reason IS NOT NULL AND invoice_failed_at IS NOT NULL));
