package com.facturaflow.invoicing.domain.model;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.ISSUE_DATE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Datos para los tests de notas crédito. La factura es la del spike 0002 ({@code SETP990028959}):
 * así los totales de las notas se comparan con los que calculó Factus.
 */
public final class CreditNoteFixtures {

    public static final InvoiceId CREDITED_INVOICE_ID =
            new InvoiceId(UUID.fromString("7f1c2a4e-0000-4000-8000-0000000000c1"));
    public static final LocalDate NOTE_DATE = ISSUE_DATE.plusDays(2);

    private CreditNoteFixtures() {
    }

    /** Línea 1: 3 x 33.333,33 con 7,5 % de descuento, IVA 19 % + INC 8 %. */
    public static InvoiceLine roundingLine() {
        return new InvoiceLine("SKU-RND", "Producto con centavos", Quantity.of("3"), Money.of("33333.33"),
                DiscountRate.of("7.5"), List.of(LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                UnitOfMeasure.UNIT, false);
    }

    /** Línea 2: 1,5 x 20.000,01 excluida de IVA. */
    public static InvoiceLine excludedRoundingLine() {
        return new InvoiceLine("SKU-EXC", "Producto excluido de IVA", Quantity.of("1.5"), Money.of("20000.01"),
                DiscountRate.NONE, List.of(), UnitOfMeasure.UNIT, true);
    }

    /** Factura PENDING de dos líneas, total 147.475,01 (igual al de Factus en el spike). */
    public static Invoice pendingInvoiceToCredit() {
        return Invoice.create(CREDITED_INVOICE_ID, new ReferenceCode("SALE-NC-1"), InvoiceFixtures.naturalPerson(),
                List.of(roundingLine(), excludedRoundingLine()), PaymentMethod.BANK_TRANSFER,
                PaymentTerms.credit(ISSUE_DATE.plusDays(30)), ISSUE_DATE, null);
    }

    /** La misma factura ya emitida, tal como la reconstruye el repositorio. */
    public static Invoice issuedInvoiceToCredit() {
        Invoice invoice = pendingInvoiceToCredit();
        invoice.markAsIssued(electronicDocument("147475.01"));
        return Invoice.reconstitute(invoice.id(), invoice.referenceCode(), invoice.customer(), invoice.lines(),
                invoice.paymentMethod(), invoice.paymentTerms(), invoice.issueDate(), null, invoice.status(),
                invoice.electronicDocument().orElseThrow(), null, 1L);
    }

    public static CreditedQuantity credit(int lineNumber, String quantity) {
        return new CreditedQuantity(lineNumber, Quantity.of(quantity));
    }

    public static CreditNote partialReturn(CreditBalance balance, String referenceCode, CreditedQuantity... lines) {
        return CreditNote.create(CreditNoteId.generate(), new ReferenceCode(referenceCode), balance,
                CreditNoteReason.PARTIAL_RETURN, List.of(lines), NOTE_DATE, null);
    }

    public static CreditNote annulment(CreditBalance balance, String referenceCode) {
        return CreditNote.create(CreditNoteId.generate(), new ReferenceCode(referenceCode), balance,
                CreditNoteReason.TOTAL_ANNULMENT, List.of(), NOTE_DATE, null);
    }

    /** Copia de la nota en otro estado (como la reconstruiría el repositorio). */
    public static CreditNote withStatus(CreditNote note, CreditNoteStatus status) {
        ElectronicDocument document = status == CreditNoteStatus.ISSUED ? creditNoteDocument(note.total()) : null;
        String rejection = status == CreditNoteStatus.REJECTED ? "Rechazo de prueba" : null;
        return CreditNote.reconstitute(note.id(), note.referenceCode(), note.invoiceId(), note.reason(), note.lines(),
                note.issueDate(), note.observation().orElse(null), status, document, rejection, 0L);
    }

    public static ElectronicDocument creditNoteDocument(Money officialTotal) {
        return new ElectronicDocument("NC1661", "b659dc4457ab1b68".repeat(6), URI.create("https://dian.example/qr?key=nc"),
                URI.create("https://sandbox.example/credit-notes/1"), Instant.parse("2026-10-22T20:17:42Z"),
                officialTotal);
    }
}
