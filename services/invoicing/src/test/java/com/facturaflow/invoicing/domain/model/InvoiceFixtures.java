package com.facturaflow.invoicing.domain.model;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Datos válidos por defecto: cada test cambia solo lo que quiere probar. */
final class InvoiceFixtures {

    static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 20);
    static final InvoiceId INVOICE_ID = new InvoiceId(UUID.fromString("7f1c2a4e-0000-4000-8000-000000000001"));
    static final String VALID_CUFE = "0123456789abcdef".repeat(6);

    private InvoiceFixtures() {
    }

    static CustomerSnapshot customer() {
        return CustomerSnapshot.company(IdentificationDocument.nit("890903938", 8), "Cliente Ejemplo S.A.S.",
                "facturacion@cliente.example");
    }

    /** Línea con un solo impuesto: {@code tax} es la tarifa de IVA. */
    static InvoiceLine line(String quantity, String unitPrice, String discount, String tax) {
        return line(quantity, unitPrice, discount, LineTax.of(TaxType.IVA, tax));
    }

    static InvoiceLine line(String quantity, String unitPrice, String discount, LineTax... taxes) {
        return new InvoiceLine("SKU-1", "Producto", Quantity.of(quantity), Money.of(unitPrice),
                DiscountRate.of(discount), List.of(taxes), UnitOfMeasure.UNIT, false);
    }

    /** Línea excluida de IVA y sin otros impuestos. */
    static InvoiceLine excludedLine(String quantity, String unitPrice) {
        return new InvoiceLine("SKU-EXC", "Producto excluido", Quantity.of(quantity), Money.of(unitPrice),
                DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, true);
    }

    static Invoice invoiceWith(List<InvoiceLine> lines) {
        return Invoice.create(INVOICE_ID, new ReferenceCode("SALE-0001"), customer(), lines, PaymentMethod.CASH,
                PaymentTerms.cash(), ISSUE_DATE, null);
    }

    static Invoice pendingInvoice() {
        return invoiceWith(List.of(line("1", "100000.00", "0", "19")));
    }

    static ElectronicDocument electronicDocument(String officialTotal) {
        return new ElectronicDocument("SETP990000001", VALID_CUFE, URI.create("https://sandbox.example/invoice.pdf"),
                OffsetDateTime.of(2026, 10, 20, 10, 30, 0, 0, ZoneOffset.ofHours(-5)), Money.of(officialTotal));
    }
}
