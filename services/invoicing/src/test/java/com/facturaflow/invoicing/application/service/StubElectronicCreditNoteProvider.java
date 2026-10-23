package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.util.function.Function;

/** Proveedor de notas programable para los tests de casos de uso: se le dice qué responder y cuenta las llamadas. */
final class StubElectronicCreditNoteProvider implements ElectronicCreditNoteProvider {

    private Function<CreditNote, IssuanceResult> behavior = note -> {
        throw new AssertionError("El test no programó la respuesta del proveedor");
    };
    private Function<CreditNote, InvoicePdf> pdfBehavior = note -> {
        throw new AssertionError("El test no programó el PDF del proveedor");
    };
    private Runnable duringCall = () -> { };
    private Invoice lastInvoice;
    private int calls;

    void willIssue(ElectronicDocument document) {
        behavior = note -> new IssuanceResult.Issued(document);
    }

    void willReject(String reason) {
        behavior = note -> new IssuanceResult.Rejected(reason);
    }

    void willBeUnavailable() {
        behavior = note -> {
            throw ElectronicInvoiceProviderUnavailableException.forCreditNote(note.id().value(), "timeout de prueba",
                    null);
        };
    }

    void willReturnPdf(InvoicePdf pdf) {
        pdfBehavior = note -> pdf;
    }

    /** Ejecuta {@code action} mientras "esperamos" al proveedor (p. ej. otra petición que emite en paralelo). */
    void whileCalling(Runnable action) {
        duringCall = action;
    }

    int calls() {
        return calls;
    }

    /** La factura que recibió la última emisión. */
    Invoice lastInvoice() {
        return lastInvoice;
    }

    @Override
    public IssuanceResult issue(CreditNote creditNote, Invoice creditedInvoice) {
        calls++;
        lastInvoice = creditedInvoice;
        Runnable action = duringCall;
        duringCall = () -> { };
        action.run();
        return behavior.apply(creditNote);
    }

    @Override
    public InvoicePdf downloadPdf(CreditNote issuedCreditNote) {
        calls++;
        return pdfBehavior.apply(issuedCreditNote);
    }
}
