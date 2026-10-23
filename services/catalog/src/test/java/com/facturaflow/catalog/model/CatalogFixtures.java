package com.facturaflow.catalog.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Datos válidos de ejemplo para los tests. */
public final class CatalogFixtures {

    public static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    public static final Instant LATER = NOW.plusSeconds(60);

    private CatalogFixtures() {
    }

    public static ProductDetails coffee() {
        return new ProductDetails("Café molido 500 g", new BigDecimal("18500.00"), UnitOfMeasure.UNIT, false,
                List.of(ProductTax.of(TaxType.IVA, "5")));
    }

    public static ProductDetails coffee(String price) {
        return new ProductDetails("Café molido 500 g", new BigDecimal(price), UnitOfMeasure.UNIT, false,
                List.of(ProductTax.of(TaxType.IVA, "5")));
    }

    public static CustomerDocument nit() {
        return CustomerDocument.nit("890903938", 8);
    }

    public static CustomerProfile company() {
        return company("Cliente Ejemplo S.A.S.");
    }

    public static CustomerProfile company(String companyName) {
        return new CustomerProfile(PersonType.LEGAL_ENTITY, companyName, null, TaxScheme.IVA,
                EnumSet.of(FiscalResponsibility.LARGE_TAXPAYER, FiscalResponsibility.SELF_WITHHOLDER), "05001",
                "Carrera 48 # 10-45", "(604) 444-5555", "facturacion@cliente.example");
    }

    public static CustomerProfile person(String names) {
        return new CustomerProfile(PersonType.NATURAL_PERSON, null, names, TaxScheme.NOT_APPLICABLE,
                Set.of(FiscalResponsibility.NOT_RESPONSIBLE), null, null, null, null);
    }
}
