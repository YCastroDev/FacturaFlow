package com.facturaflow.sales.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Datos válidos de ejemplo para los tests. */
public final class SaleFixtures {

    public static final LocalDate SALE_DATE = LocalDate.of(2026, 10, 23);
    public static final Instant CREATED_AT = Instant.parse("2026-10-23T15:00:00Z");
    public static final SaleId SALE_ID = new SaleId(UUID.fromString("5a1e0000-0000-4000-8000-000000000001"));
    public static final UUID PRODUCT_ID = UUID.fromString("9f000000-0000-4000-8000-000000000001");
    public static final UUID CUSTOMER_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");

    private SaleFixtures() {
    }

    public static SaleLine line(String quantity, String unitPrice, String discount, String tax) {
        return line(quantity, unitPrice, discount, LineTax.of(TaxType.IVA, tax));
    }

    public static SaleLine line(String quantity, String unitPrice, String discount, LineTax... taxes) {
        return new SaleLine(PRODUCT_ID, "SKU-1", "Producto", Quantity.of(quantity), Money.of(unitPrice),
                DiscountRate.of(discount), List.of(taxes), UnitOfMeasure.UNIT, false);
    }

    /** Línea excluida de IVA y sin otros impuestos. */
    public static SaleLine excludedLine(String quantity, String unitPrice) {
        return new SaleLine(PRODUCT_ID, "SKU-EXC", "Producto excluido", Quantity.of(quantity), Money.of(unitPrice),
                DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, true);
    }

    public static CustomerSnapshot customer() {
        return new CustomerSnapshot(CUSTOMER_ID, DocumentType.NIT, "890903938", 8, PersonType.LEGAL_ENTITY,
                "Cliente Ejemplo S.A.S.", null, TaxScheme.IVA,
                EnumSet.of(FiscalResponsibility.LARGE_TAXPAYER, FiscalResponsibility.SELF_WITHHOLDER), "05001",
                "Carrera 48 # 10-45", "6044445555", "facturacion@cliente.example");
    }

    public static Sale saleWith(List<SaleLine> lines) {
        return Sale.confirm(SALE_ID, new ReferenceCode("VENTA-0001"), SALE_DATE, CREATED_AT, customer(), lines,
                PaymentMethod.CASH, PaymentTerms.cash(), null);
    }
}
