-- V6: notas crédito (ADR 0009). Mismos criterios que V2 (ADR 0005): enums como texto + CHECK,
-- dinero NUMERIC(18,2), cantidades NUMERIC(14,2), VARCHAR(n) solo donde el dominio fija el límite,
-- y las reglas del dominio repetidas como CHECK (defensa en profundidad).

-- 1. Nombre neutro del código único del documento (CUFE de la factura, CUDE de la nota).
--    Solo cambia el nombre: los CHECK que usan la columna la siguen automáticamente.
ALTER TABLE invoices RENAME COLUMN edoc_cufe TO edoc_unique_code;

-- 2. La nota crédito: un agregado separado que referencia su factura.
CREATE TABLE credit_notes (
    id                          UUID            PRIMARY KEY,
    version                     BIGINT          NOT NULL,
    reference_code              VARCHAR(50)     NOT NULL,
    -- Sin ON DELETE CASCADE: una factura con notas nunca se borra
    invoice_id                  UUID            NOT NULL,
    reason                      VARCHAR(20)     NOT NULL,
    status                      VARCHAR(20)     NOT NULL,
    issue_date                  DATE            NOT NULL,
    observation                 VARCHAR(250),

    -- Documento electrónico (solo cuando status = ISSUED)
    edoc_number                 TEXT,
    edoc_unique_code            VARCHAR(96),
    edoc_qr_url                 TEXT,
    edoc_public_url             TEXT,
    edoc_validated_at           TIMESTAMPTZ,
    edoc_official_total         NUMERIC(18, 2),

    -- Motivo de rechazo (solo cuando status = REJECTED)
    rejection_reason            TEXT,

    -- Idempotencia de la creación (ADR 0009), como en invoices (V3)
    request_hash                TEXT            NOT NULL,

    -- Orden de creación para listar las notas de una factura. Detalle de persistencia: el dominio no lo usa.
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT fk_credit_notes_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id),
    CONSTRAINT uk_credit_notes_reference_code UNIQUE (reference_code),

    CONSTRAINT ck_credit_notes_reason CHECK (reason IN ('PARTIAL_RETURN', 'TOTAL_ANNULMENT')),
    CONSTRAINT ck_credit_notes_status CHECK (status IN ('PENDING', 'ISSUED', 'REJECTED')),
    CONSTRAINT ck_credit_notes_official_total CHECK (edoc_official_total >= 0),
    CONSTRAINT ck_credit_notes_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),

    -- Coherencia de estado: la misma regla que CreditNote.reconstitute
    CONSTRAINT ck_credit_notes_state_coherence CHECK (
        (status = 'PENDING'
            AND edoc_number IS NULL AND edoc_unique_code IS NULL AND edoc_qr_url IS NULL
            AND edoc_public_url IS NULL AND edoc_validated_at IS NULL AND edoc_official_total IS NULL
            AND rejection_reason IS NULL)
        OR (status = 'ISSUED'
            AND edoc_number IS NOT NULL AND edoc_unique_code IS NOT NULL AND edoc_qr_url IS NOT NULL
            AND edoc_public_url IS NOT NULL AND edoc_validated_at IS NOT NULL
            AND edoc_official_total IS NOT NULL
            AND rejection_reason IS NULL)
        OR (status = 'REJECTED'
            AND edoc_number IS NULL AND edoc_unique_code IS NULL AND edoc_qr_url IS NULL
            AND edoc_public_url IS NULL AND edoc_validated_at IS NULL AND edoc_official_total IS NULL
            AND rejection_reason IS NOT NULL))
);

-- Las notas de una factura se leen juntas para calcular su saldo
CREATE INDEX ix_credit_notes_invoice ON credit_notes (invoice_id, created_at);

-- 3. Líneas acreditadas: el número de la línea de la factura (desde 1) y una copia de esa línea
--    con la cantidad acreditada (snapshot, ADR 0009).
CREATE TABLE credit_note_lines (
    id                      UUID            PRIMARY KEY,
    credit_note_id          UUID            NOT NULL,
    sort_order              INTEGER         NOT NULL,
    invoice_line_number     INTEGER         NOT NULL,
    code                    VARCHAR(50)     NOT NULL,
    name                    VARCHAR(300)    NOT NULL,
    quantity                NUMERIC(14, 2)  NOT NULL,
    unit_price              NUMERIC(18, 2)  NOT NULL,
    discount_rate           NUMERIC(5, 2)   NOT NULL,
    unit_of_measure         VARCHAR(20)     NOT NULL,
    iva_excluded            BOOLEAN         NOT NULL,

    CONSTRAINT fk_credit_note_lines_note FOREIGN KEY (credit_note_id) REFERENCES credit_notes (id)
        ON DELETE CASCADE,
    CONSTRAINT uk_credit_note_lines_order UNIQUE (credit_note_id, sort_order),
    -- Una nota no acredita dos veces la misma línea (misma regla que CreditNote)
    CONSTRAINT uk_credit_note_lines_invoice_line UNIQUE (credit_note_id, invoice_line_number),

    CONSTRAINT ck_credit_note_lines_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_credit_note_lines_invoice_line_number CHECK (invoice_line_number >= 1),
    CONSTRAINT ck_credit_note_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_credit_note_lines_unit_price CHECK (unit_price > 0),
    CONSTRAINT ck_credit_note_lines_discount_rate CHECK (discount_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_credit_note_lines_unit_of_measure CHECK (unit_of_measure IN
        ('UNIT', 'KILOGRAM', 'GRAM', 'POUND', 'LITER', 'MILLILITER', 'GALLON', 'METER'))
);

CREATE TABLE credit_note_line_taxes (
    line_id             UUID            NOT NULL,
    sort_order          INTEGER         NOT NULL,
    tax_type            VARCHAR(10)     NOT NULL,
    rate                NUMERIC(5, 2)   NOT NULL,

    CONSTRAINT pk_credit_note_line_taxes PRIMARY KEY (line_id, tax_type),
    CONSTRAINT fk_credit_note_line_taxes_line FOREIGN KEY (line_id) REFERENCES credit_note_lines (id)
        ON DELETE CASCADE,
    CONSTRAINT uk_credit_note_line_taxes_order UNIQUE (line_id, sort_order),

    CONSTRAINT ck_credit_note_line_taxes_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_credit_note_line_taxes_tax_type CHECK (tax_type IN ('IVA', 'INC')),
    CONSTRAINT ck_credit_note_line_taxes_rate CHECK (rate BETWEEN 0 AND 100)
);

-- La regla "lo acreditado nunca supera lo facturado" cruza filas de varias tablas: un CHECK no
-- puede expresarla. La garantizan el dominio (CreditBalance) y el bloqueo optimista sobre la
-- factura al insertar cada nota (ADR 0009, decisión 3).
