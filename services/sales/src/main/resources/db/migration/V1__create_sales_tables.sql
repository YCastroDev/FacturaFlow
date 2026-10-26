-- V1: ventas confirmadas con la copia (snapshot) de cliente y productos de catalog (ADR 0012, ADR 0003).
-- Una venta no cambia después de crearse salvo su estado (y su versión): las columnas del snapshot son
-- de solo inserción. Las restricciones repiten las reglas del dominio (defensa en profundidad).

CREATE TABLE sales (
    id                                UUID          PRIMARY KEY,
    version                           BIGINT        NOT NULL,
    reference_code                    VARCHAR(50)   NOT NULL,
    -- Huella SHA-256 de la petición normalizada (idempotencia).
    request_hash                      TEXT          NOT NULL,
    status                            VARCHAR(20)   NOT NULL,
    sale_date                         DATE          NOT NULL,
    created_at                        TIMESTAMPTZ   NOT NULL,
    payment_method                    VARCHAR(30)   NOT NULL,
    payment_type                      VARCHAR(10)   NOT NULL,
    due_date                          DATE,
    observation                       VARCHAR(250),
    -- Snapshot del cliente: lo que invoicing necesita para facturar (mismos nombres que su API).
    customer_id                       UUID          NOT NULL,
    customer_document_type            VARCHAR(40)   NOT NULL,
    customer_document_number          VARCHAR(20)   NOT NULL,
    customer_check_digit              INTEGER,
    customer_person_type              VARCHAR(20)   NOT NULL,
    customer_company_name             TEXT,
    customer_names                    TEXT,
    customer_tax_scheme               VARCHAR(20)   NOT NULL,
    customer_fiscal_responsibilities  TEXT[]        NOT NULL,
    customer_municipality_code        VARCHAR(5),
    customer_address                  VARCHAR(250),
    customer_phone                    VARCHAR(16),
    customer_email                    VARCHAR(254),
    CONSTRAINT uk_sales_reference_code UNIQUE (reference_code),
    CONSTRAINT ck_sales_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- Cada estado nuevo (INVOICED, INVOICE_FAILED en la tarea de eventos) exige una migración.
    CONSTRAINT ck_sales_status CHECK (status IN ('CONFIRMED')),
    CONSTRAINT ck_sales_payment_method CHECK (payment_method IN
        ('CASH', 'BANK_TRANSFER', 'BANK_DEPOSIT', 'DEBIT_CARD', 'CREDIT_CARD', 'CHECK')),
    -- Contado sin vencimiento; crédito con vencimiento posterior a la fecha de la venta.
    CONSTRAINT ck_sales_payment_terms CHECK (
        (payment_type = 'CASH' AND due_date IS NULL)
        OR (payment_type = 'CREDIT' AND due_date IS NOT NULL AND due_date > sale_date)),
    CONSTRAINT ck_sales_customer_person_type CHECK (customer_person_type IN ('NATURAL_PERSON', 'LEGAL_ENTITY')),
    CONSTRAINT ck_sales_customer_name CHECK (
        (customer_person_type = 'LEGAL_ENTITY' AND customer_company_name IS NOT NULL)
        OR (customer_person_type = 'NATURAL_PERSON' AND customer_names IS NOT NULL)),
    CONSTRAINT ck_sales_customer_fiscal_responsibilities CHECK (cardinality(customer_fiscal_responsibilities) >= 1)
);

-- El listado va de la más reciente a la más antigua.
CREATE INDEX ix_sales_created_at ON sales (created_at DESC, id);

CREATE TABLE sale_lines (
    id               UUID          PRIMARY KEY,
    sale_id          UUID          NOT NULL REFERENCES sales (id),
    line_index       INTEGER       NOT NULL,
    product_id       UUID          NOT NULL,
    code             VARCHAR(50)   NOT NULL,
    name             VARCHAR(300)  NOT NULL,
    quantity         NUMERIC(14,2) NOT NULL,
    unit_price       NUMERIC(18,2) NOT NULL,
    discount_rate    NUMERIC(5,2)  NOT NULL,
    unit_of_measure  VARCHAR(20)   NOT NULL,
    iva_excluded     BOOLEAN       NOT NULL,
    CONSTRAINT uk_sale_lines_order UNIQUE (sale_id, line_index),
    CONSTRAINT ck_sale_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_sale_lines_unit_price CHECK (unit_price > 0),
    CONSTRAINT ck_sale_lines_discount_rate CHECK (discount_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_sale_lines_unit_of_measure CHECK (unit_of_measure IN
        ('UNIT', 'KILOGRAM', 'GRAM', 'POUND', 'LITER', 'MILLILITER', 'GALLON', 'METER'))
);

CREATE TABLE sale_line_taxes (
    line_id     UUID          NOT NULL REFERENCES sale_lines (id),
    sort_order  INTEGER       NOT NULL,
    tax_type    VARCHAR(10)   NOT NULL,
    rate        NUMERIC(5,2)  NOT NULL,
    CONSTRAINT pk_sale_line_taxes PRIMARY KEY (line_id, sort_order),
    CONSTRAINT uk_sale_line_taxes_type UNIQUE (line_id, tax_type),
    CONSTRAINT ck_sale_line_taxes_tax_type CHECK (tax_type IN ('IVA', 'INC')),
    CONSTRAINT ck_sale_line_taxes_rate CHECK (rate BETWEEN 0 AND 100)
);
