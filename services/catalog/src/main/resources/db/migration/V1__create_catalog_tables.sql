-- V1: productos y clientes del catálogo (ADR 0011).
-- Las restricciones repiten las reglas de las entidades (defensa en profundidad); lo que un CHECK no
-- puede expresar (p. ej. "excluido de IVA sin impuesto IVA", que cruza tablas) lo validan solo ellas.
-- Nada se borra: los registros se desactivan (active = false).

-- ----- Productos -----
CREATE TABLE products (
    id               UUID          PRIMARY KEY,
    code             VARCHAR(50)   NOT NULL,
    name             VARCHAR(300)  NOT NULL,
    -- Precio unitario NETO: sin impuestos ni descuentos (como la línea de factura de invoicing).
    unit_price       NUMERIC(15,2) NOT NULL,
    unit_of_measure  VARCHAR(20)   NOT NULL,
    iva_excluded     BOOLEAN       NOT NULL,
    active           BOOLEAN       NOT NULL,
    version          BIGINT        NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_products_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT ck_products_name CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT ck_products_unit_price CHECK (unit_price > 0),
    -- Las 8 unidades que acepta el proveedor de facturación (spike 0001, ADR 0007).
    CONSTRAINT ck_products_unit_of_measure CHECK (unit_of_measure IN
        ('UNIT', 'KILOGRAM', 'GRAM', 'POUND', 'LITER', 'MILLILITER', 'GALLON', 'METER'))
);

-- Código único SIN distinguir mayúsculas: "sku-1" y "SKU-1" no conviven (se guarda como se escribió).
CREATE UNIQUE INDEX uq_products_code ON products (lower(code));

CREATE TABLE product_taxes (
    product_id  UUID         NOT NULL REFERENCES products (id),
    tax_type    VARCHAR(10)  NOT NULL,
    rate        NUMERIC(5,2) NOT NULL,
    -- Un tipo de impuesto no se repite en el mismo producto.
    CONSTRAINT pk_product_taxes PRIMARY KEY (product_id, tax_type),
    CONSTRAINT ck_product_taxes_tax_type CHECK (tax_type IN ('IVA', 'INC')),
    CONSTRAINT ck_product_taxes_rate CHECK (rate BETWEEN 0 AND 100)
);

-- ----- Clientes -----
CREATE TABLE customers (
    id                 UUID          PRIMARY KEY,
    document_type      VARCHAR(40)   NOT NULL,
    -- Numérico de 3 a 15 dígitos o alfanumérico (documentos extranjeros) de 3 a 20, en mayúsculas.
    document_number    VARCHAR(20)   NOT NULL,
    check_digit        INTEGER,
    person_type        VARCHAR(20)   NOT NULL,
    company_name       TEXT,
    names              TEXT,
    tax_scheme         VARCHAR(20)   NOT NULL,
    municipality_code  VARCHAR(5),
    address            VARCHAR(250),
    phone              VARCHAR(16),
    email              VARCHAR(254),
    active             BOOLEAN       NOT NULL,
    version            BIGINT        NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    -- Un cliente por tipo + número de documento.
    CONSTRAINT uq_customers_document UNIQUE (document_type, document_number),
    CONSTRAINT ck_customers_document_type CHECK (document_type IN
        ('CITIZENSHIP_CARD', 'NIT', 'FOREIGNER_ID_CARD', 'PASSPORT', 'IDENTITY_CARD', 'CIVIL_REGISTRY',
         'FOREIGNER_CARD', 'FOREIGN_ID_DOCUMENT', 'SPECIAL_STAY_PERMIT', 'TEMPORARY_PROTECTION_PERMIT',
         'FOREIGN_NIT')),
    CONSTRAINT ck_customers_document_number CHECK (document_number ~ '^[A-Z0-9]{3,20}$'),
    -- El DV es obligatorio para NIT y prohibido en los demás tipos; su valor lo calcula la entidad. El IS NOT
    -- NULL explícito importa: "NULL BETWEEN 0 AND 9" es NULL, y un CHECK que da NULL se cumple.
    CONSTRAINT ck_customers_check_digit CHECK (
        (document_type = 'NIT' AND check_digit IS NOT NULL AND check_digit BETWEEN 0 AND 9)
        OR (document_type <> 'NIT' AND check_digit IS NULL)),
    CONSTRAINT ck_customers_person_type CHECK (person_type IN ('NATURAL_PERSON', 'LEGAL_ENTITY')),
    CONSTRAINT ck_customers_person_name CHECK (
        (person_type = 'LEGAL_ENTITY' AND company_name IS NOT NULL AND names IS NULL)
        OR (person_type = 'NATURAL_PERSON' AND names IS NOT NULL AND company_name IS NULL)),
    CONSTRAINT ck_customers_tax_scheme CHECK (tax_scheme IN ('IVA', 'NOT_APPLICABLE')),
    CONSTRAINT ck_customers_municipality_code CHECK (municipality_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_customers_phone CHECK (phone ~ '^\+?[0-9]{7,15}$')
);

CREATE TABLE customer_fiscal_responsibilities (
    customer_id     UUID         NOT NULL REFERENCES customers (id),
    responsibility  VARCHAR(30)  NOT NULL,
    CONSTRAINT pk_customer_fiscal_responsibilities PRIMARY KEY (customer_id, responsibility),
    -- "NOT_RESPONSIBLE va sola" cruza filas: la valida la entidad.
    CONSTRAINT ck_customer_fiscal_responsibilities_value CHECK (responsibility IN
        ('LARGE_TAXPAYER', 'SELF_WITHHOLDER', 'VAT_WITHHOLDING_AGENT', 'SIMPLE_TAX_REGIME', 'NOT_RESPONSIBLE'))
);

-- Sin índices de búsqueda por texto: el listado usa ILIKE '%texto%' (recorrido completo), suficiente para un
-- catálogo de pyme. En producción: pg_trgm + índices GIN (y unaccent) — ADR 0011.
