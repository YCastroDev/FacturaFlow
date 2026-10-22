-- V5: el documento electrónico guarda los enlaces reales del proveedor en vez de una "URL del PDF"
-- que Factus no ofrece (ADR 0008):
--   * edoc_qr_url: consulta del documento en el catálogo de la DIAN (lo que codifica el QR);
--   * edoc_public_url: vista pública del documento en el proveedor (enlace-capacidad).
-- El PDF ya no se guarda como enlace: se pide al proveedor (GET /api/v1/invoices/{id}/pdf).
--
-- Facturas ISSUED existentes: no hay un QR real que rellenar, así que la migración ABORTA si hay
-- alguna. Es un proyecto de portafolio (ver CLAUDE.md): la única afectada era una emisión del
-- proveedor falso en la base local, y el patrón "rellenar lo deducible" ya se demostró en la V4.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM invoices WHERE status = 'ISSUED') THEN
        RAISE EXCEPTION 'V5: hay facturas ISSUED y no tienen la URL de consulta de la DIAN (QR) que ahora es '
            'obligatoria. Elimine esas filas (en desarrollo: docker compose ... down -v) y vuelva a ejecutar la '
            'migración.';
    END IF;
END
$$;

ALTER TABLE invoices RENAME COLUMN edoc_pdf_url TO edoc_public_url;
ALTER TABLE invoices ADD COLUMN edoc_qr_url TEXT;

-- Coherencia de estado con los dos enlaces: la misma regla que Invoice.reconstitute (ADR 0005, D7)
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_state_coherence;
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_state_coherence CHECK (
    (status = 'PENDING'
        AND edoc_number IS NULL AND edoc_cufe IS NULL AND edoc_qr_url IS NULL AND edoc_public_url IS NULL
        AND edoc_validated_at IS NULL AND edoc_official_total IS NULL
        AND rejection_reason IS NULL)
    OR (status = 'ISSUED'
        AND edoc_number IS NOT NULL AND edoc_cufe IS NOT NULL AND edoc_qr_url IS NOT NULL
        AND edoc_public_url IS NOT NULL
        AND edoc_validated_at IS NOT NULL AND edoc_official_total IS NOT NULL
        AND rejection_reason IS NULL)
    OR (status = 'REJECTED'
        AND edoc_number IS NULL AND edoc_cufe IS NULL AND edoc_qr_url IS NULL AND edoc_public_url IS NULL
        AND edoc_validated_at IS NULL AND edoc_official_total IS NULL
        AND rejection_reason IS NOT NULL));
