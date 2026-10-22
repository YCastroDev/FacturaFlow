package com.facturaflow.invoicing.domain.model;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Datos válidos por defecto: cada test cambia solo lo que quiere probar. */
public final class InvoiceFixtures {

    public static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 20);
    public static final InvoiceId INVOICE_ID = new InvoiceId(UUID.fromString("7f1c2a4e-0000-4000-8000-000000000001"));
    public static final String VALID_CUFE = "0123456789abcdef".repeat(6);

    private InvoiceFixtures() {
    }

    public static final FiscalProfile FISCAL_PROFILE =
            FiscalProfile.of(TaxScheme.IVA, FiscalResponsibility.LARGE_TAXPAYER, FiscalResponsibility.SELF_WITHHOLDER);

    /** Persona jurídica responsable de IVA, sin datos de ubicación. */
    public static CustomerSnapshot customer() {
        return CustomerSnapshot.company(IdentificationDocument.nit("890903938", 8), "Cliente Ejemplo S.A.S.",
                FISCAL_PROFILE, "facturacion@cliente.example");
    }

    /** Persona natural no responsable (consumidor final típico). */
    public static CustomerSnapshot naturalPerson() {
        return CustomerSnapshot.person(IdentificationDocument.of(DocumentType.CITIZENSHIP_CARD, "1020304050"),
                "Ana María Gómez", FiscalProfile.notResponsible(), null);
    }

    /** {@link #customer()} con municipio, dirección y teléfono. */
    public static CustomerSnapshot customerWithLocation() {
        return customer().withLocation(Municipality.of("11001"), "Calle 10 # 20-30", PhoneNumber.of("+57 601 555 1234"));
    }

    /** Línea con un solo impuesto: {@code tax} es la tarifa de IVA. */
    public static InvoiceLine line(String quantity, String unitPrice, String discount, String tax) {
        return line(quantity, unitPrice, discount, LineTax.of(TaxType.IVA, tax));
    }

    public static InvoiceLine line(String quantity, String unitPrice, String discount, LineTax... taxes) {
        return new InvoiceLine("SKU-1", "Producto", Quantity.of(quantity), Money.of(unitPrice),
                DiscountRate.of(discount), List.of(taxes), UnitOfMeasure.UNIT, false);
    }

    /** Línea excluida de IVA y sin otros impuestos. */
    public static InvoiceLine excludedLine(String quantity, String unitPrice) {
        return new InvoiceLine("SKU-EXC", "Producto excluido", Quantity.of(quantity), Money.of(unitPrice),
                DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, true);
    }

    public static Invoice invoiceWith(List<InvoiceLine> lines) {
        return Invoice.create(INVOICE_ID, new ReferenceCode("SALE-0001"), customer(), lines, PaymentMethod.CASH,
                PaymentTerms.cash(), ISSUE_DATE, null);
    }

    public static Invoice pendingInvoice() {
        return invoiceWith(List.of(line("1", "100000.00", "0", "19")));
    }

    public static ElectronicDocument electronicDocument(String officialTotal) {
        return new ElectronicDocument("SETP990000001", VALID_CUFE, URI.create("https://dian.example/qr?key=1"),
                URI.create("https://sandbox.example/documents/1"), Instant.parse("2026-10-20T15:30:00Z"), Money.of(officialTotal));
    }
}
