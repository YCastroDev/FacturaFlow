-- V4: datos fiscales y de ubicación del cliente; unidades reducidas a las que acepta el proveedor (ADR 0007).
-- Criterio para las filas existentes: rellenar solo lo que se deduce sin ambigüedad y ABORTAR con un
-- mensaje explícito en lo demás, en vez de inventar datos.
--   * customer_person_type: razón social sin nombres -> LEGAL_ENTITY; nombres sin razón social ->
--     NATURAL_PERSON; con las dos (antes permitido) -> aborta.
--   * customer_tax_scheme = NOT_APPLICABLE y responsabilidades = {NOT_RESPONSIBLE}: los valores más
--     neutros (los mismos que el proveedor asume cuando no se informan).
--   * municipio, dirección y teléfono: NULL (son opcionales).
--   * unidades eliminadas del dominio: si alguna línea las usa -> aborta (no hay equivalencia segura).

-- 1. Comprobaciones que impiden migrar sin inventar datos
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM invoices WHERE customer_company_name IS NOT NULL AND customer_names IS NOT NULL) THEN
        RAISE EXCEPTION 'V4: hay facturas cuyo cliente tiene razón social Y nombres; no se puede deducir su tipo '
            'de persona. Corrija esas filas (deje solo uno de los dos campos) y vuelva a ejecutar la migración.';
    END IF;
    IF EXISTS (SELECT 1 FROM invoice_lines WHERE unit_of_measure NOT IN
               ('UNIT', 'KILOGRAM', 'GRAM', 'POUND', 'LITER', 'MILLILITER', 'GALLON', 'METER')) THEN
        RAISE EXCEPTION 'V4: hay líneas con unidades que el dominio ya no admite (DOZEN, BOX, PACKAGE, CENTIMETER, '
            'SQUARE_METER, HOUR, DAY, SERVICE); no tienen una equivalencia segura. Corrija esas filas y vuelva '
            'a ejecutar la migración.';
    END IF;
END
$$;

-- 2. Columnas nuevas (nulas mientras se rellenan)
ALTER TABLE invoices
    ADD COLUMN customer_person_type              VARCHAR(20),
    ADD COLUMN customer_tax_scheme               VARCHAR(20),
    ADD COLUMN customer_fiscal_responsibilities  TEXT[],
    ADD COLUMN customer_municipality_code        VARCHAR(5),
    ADD COLUMN customer_address                  VARCHAR(250),
    ADD COLUMN customer_phone                    VARCHAR(16);

-- 3. Relleno determinista de las filas existentes
UPDATE invoices SET
    customer_person_type = CASE WHEN customer_company_name IS NOT NULL THEN 'LEGAL_ENTITY' ELSE 'NATURAL_PERSON' END,
    customer_tax_scheme = 'NOT_APPLICABLE',
    customer_fiscal_responsibilities = ARRAY['NOT_RESPONSIBLE'];

-- 4. Restricciones (las mismas reglas que CustomerSnapshot y FiscalProfile: defensa en profundidad)
ALTER TABLE invoices
    ALTER COLUMN customer_person_type SET NOT NULL,
    ALTER COLUMN customer_tax_scheme SET NOT NULL,
    ALTER COLUMN customer_fiscal_responsibilities SET NOT NULL;

-- La regla "razón social o nombres" de V2 se reemplaza por la que depende del tipo de persona.
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_customer_name;

ALTER TABLE invoices
    ADD CONSTRAINT ck_invoices_customer_person_type CHECK (customer_person_type IN ('NATURAL_PERSON', 'LEGAL_ENTITY')),
    ADD CONSTRAINT ck_invoices_customer_person_name CHECK (
        (customer_person_type = 'LEGAL_ENTITY' AND customer_company_name IS NOT NULL AND customer_names IS NULL)
        OR (customer_person_type = 'NATURAL_PERSON' AND customer_names IS NOT NULL AND customer_company_name IS NULL)),
    ADD CONSTRAINT ck_invoices_customer_tax_scheme CHECK (customer_tax_scheme IN ('IVA', 'NOT_APPLICABLE')),
    -- No vacío, solo valores conocidos y NOT_RESPONSIBLE siempre sola. Los duplicados no se pueden
    -- comprobar en un CHECK (no admite subconsultas): los evita el dominio, que usa un conjunto.
    ADD CONSTRAINT ck_invoices_customer_fiscal_responsibilities CHECK (
        cardinality(customer_fiscal_responsibilities) >= 1
        AND customer_fiscal_responsibilities <@ ARRAY['LARGE_TAXPAYER', 'SELF_WITHHOLDER', 'VAT_WITHHOLDING_AGENT',
                                                      'SIMPLE_TAX_REGIME', 'NOT_RESPONSIBLE']::TEXT[]
        AND (NOT ('NOT_RESPONSIBLE' = ANY (customer_fiscal_responsibilities))
             OR cardinality(customer_fiscal_responsibilities) = 1)),
    ADD CONSTRAINT ck_invoices_customer_municipality_code CHECK (customer_municipality_code ~ '^[0-9]{5}$'),
    ADD CONSTRAINT ck_invoices_customer_phone CHECK (customer_phone ~ '^\+?[0-9]{7,15}$');

-- 5. Unidades de medida: solo las que acepta el proveedor
ALTER TABLE invoice_lines DROP CONSTRAINT ck_invoice_lines_unit_of_measure;
ALTER TABLE invoice_lines ADD CONSTRAINT ck_invoice_lines_unit_of_measure CHECK (unit_of_measure IN
    ('UNIT', 'KILOGRAM', 'GRAM', 'POUND', 'LITER', 'MILLILITER', 'GALLON', 'METER'));
