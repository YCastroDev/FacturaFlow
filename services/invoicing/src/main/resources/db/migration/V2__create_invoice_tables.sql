-- V2: persistencia del agregado Invoice (ADR 0005).
-- Criterios:
--   * Enums del dominio como texto + CHECK con los valores válidos.
--   * Dinero NUMERIC(18,2); porcentajes NUMERIC(5,2); cantidades NUMERIC(14,2). Nunca float.
--   * VARCHAR(n) solo donde el dominio impone ese mismo límite; TEXT donde no lo impone,
--     para que la base nunca rechace un valor que el dominio considera válido.
--   * Las reglas que el dominio ya valida se repiten como CHECK (defensa en profundidad).

CREATE TABLE invoices (
    id                          UUID            PRIMARY KEY,
    version                     BIGINT          NOT NULL,
    reference_code              VARCHAR(50)     NOT NULL,
    status                      VARCHAR(20)     NOT NULL,
    issue_date                  DATE            NOT NULL,
    payment_method              VARCHAR(30)     NOT NULL,
    payment_type                VARCHAR(10)     NOT NULL,
    due_date                    DATE,
    observation                 VARCHAR(250),

    -- Snapshot del cliente al momento de la venta (ADR 0003)
    customer_document_type      VARCHAR(40)     NOT NULL,
    customer_document_number    VARCHAR(20)     NOT NULL,
    customer_check_digit        SMALLINT,
    customer_company_name       TEXT,
    customer_names              TEXT,
    customer_email              VARCHAR(254),

    -- Documento electrónico (solo cuando status = ISSUED)
    edoc_number                 TEXT,
    edoc_cufe                   VARCHAR(96),
    edoc_pdf_url                TEXT,
    edoc_validated_at           TIMESTAMPTZ,
    edoc_official_total         NUMERIC(18, 2),

    -- Motivo de rechazo (solo cuando status = REJECTED)
    rejection_reason            TEXT,

    -- Idempotencia: una venta se factura una sola vez (también garantizado por la base)
    CONSTRAINT uk_invoices_reference_code UNIQUE (reference_code),

    CONSTRAINT ck_invoices_status CHECK (status IN ('PENDING', 'ISSUED', 'REJECTED')),
    CONSTRAINT ck_invoices_payment_method CHECK (payment_method IN
        ('CASH', 'BANK_TRANSFER', 'BANK_DEPOSIT', 'DEBIT_CARD', 'CREDIT_CARD', 'CHECK')),
    CONSTRAINT ck_invoices_payment_type CHECK (payment_type IN ('CASH', 'CREDIT')),
    CONSTRAINT ck_invoices_due_date CHECK (
        (payment_type = 'CASH' AND due_date IS NULL)
        OR (payment_type = 'CREDIT' AND due_date IS NOT NULL AND due_date > issue_date)),

    CONSTRAINT ck_invoices_customer_document_type CHECK (customer_document_type IN
        ('CITIZENSHIP_CARD', 'NIT', 'FOREIGNER_ID_CARD', 'PASSPORT', 'IDENTITY_CARD', 'CIVIL_REGISTRY',
         'FOREIGNER_CARD', 'FOREIGN_ID_DOCUMENT', 'SPECIAL_STAY_PERMIT', 'TEMPORARY_PROTECTION_PERMIT',
         'FOREIGN_NIT')),
    CONSTRAINT ck_invoices_customer_check_digit CHECK (
        (customer_document_type = 'NIT' AND customer_check_digit BETWEEN 0 AND 9)
        OR (customer_document_type <> 'NIT' AND customer_check_digit IS NULL)),
    CONSTRAINT ck_invoices_customer_name CHECK (
        customer_company_name IS NOT NULL OR customer_names IS NOT NULL),

    CONSTRAINT ck_invoices_official_total CHECK (edoc_official_total >= 0),

    -- Coherencia de estado: la misma regla que Invoice.reconstitute (ADR 0005, D7)
    CONSTRAINT ck_invoices_state_coherence CHECK (
        (status = 'PENDING'
            AND edoc_number IS NULL AND edoc_cufe IS NULL AND edoc_pdf_url IS NULL
            AND edoc_validated_at IS NULL AND edoc_official_total IS NULL
            AND rejection_reason IS NULL)
        OR (status = 'ISSUED'
            AND edoc_number IS NOT NULL AND edoc_cufe IS NOT NULL AND edoc_pdf_url IS NOT NULL
            AND edoc_validated_at IS NOT NULL AND edoc_official_total IS NOT NULL
            AND rejection_reason IS NULL)
        OR (status = 'REJECTED'
            AND edoc_number IS NULL AND edoc_cufe IS NULL AND edoc_pdf_url IS NULL
            AND edoc_validated_at IS NULL AND edoc_official_total IS NULL
            AND rejection_reason IS NOT NULL))
);

CREATE TABLE invoice_lines (
    id                  UUID            PRIMARY KEY,
    invoice_id          UUID            NOT NULL,
    line_number         INTEGER         NOT NULL,
    code                VARCHAR(50)     NOT NULL,
    name                VARCHAR(300)    NOT NULL,
    quantity            NUMERIC(14, 2)  NOT NULL,
    unit_price          NUMERIC(18, 2)  NOT NULL,
    discount_rate       NUMERIC(5, 2)   NOT NULL,
    unit_of_measure     VARCHAR(20)     NOT NULL,
    iva_excluded        BOOLEAN         NOT NULL,

    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id) REFERENCES invoices (id) ON DELETE CASCADE,
    -- Orden de las líneas; su índice también sirve para buscar las líneas de una factura (FK)
    CONSTRAINT uk_invoice_lines_number UNIQUE (invoice_id, line_number),

    CONSTRAINT ck_invoice_lines_line_number CHECK (line_number >= 0),
    CONSTRAINT ck_invoice_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_invoice_lines_unit_price CHECK (unit_price > 0),
    CONSTRAINT ck_invoice_lines_discount_rate CHECK (discount_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_invoice_lines_unit_of_measure CHECK (unit_of_measure IN
        ('UNIT', 'DOZEN', 'BOX', 'PACKAGE', 'KILOGRAM', 'GRAM', 'POUND', 'LITER', 'MILLILITER', 'GALLON',
         'METER', 'CENTIMETER', 'SQUARE_METER', 'HOUR', 'DAY', 'SERVICE'))
);

CREATE TABLE invoice_line_taxes (
    line_id             UUID            NOT NULL,
    sort_order          INTEGER         NOT NULL,
    tax_type            VARCHAR(10)     NOT NULL,
    rate                NUMERIC(5, 2)   NOT NULL,

    -- Una línea no repite tipo de impuesto (misma regla que InvoiceLine)
    CONSTRAINT pk_invoice_line_taxes PRIMARY KEY (line_id, tax_type),
    CONSTRAINT fk_invoice_line_taxes_line FOREIGN KEY (line_id) REFERENCES invoice_lines (id) ON DELETE CASCADE,
    CONSTRAINT uk_invoice_line_taxes_order UNIQUE (line_id, sort_order),

    CONSTRAINT ck_invoice_line_taxes_sort_order CHECK (sort_order >= 0),
    CONSTRAINT ck_invoice_line_taxes_tax_type CHECK (tax_type IN ('IVA', 'INC')),
    CONSTRAINT ck_invoice_line_taxes_rate CHECK (rate BETWEEN 0 AND 100)
);
-- La regla "línea excluida de IVA sin impuesto IVA" cruza dos tablas: la valida el dominio.
