package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.util.function.Function;

/** Proveedor programable para los tests de casos de uso: se le dice qué responder y cuenta las llamadas. */
final class StubElectronicInvoiceProvider implements ElectronicInvoiceProvider {

    private Function<Invoice, IssuanceResult> behavior = invoice -> {
        throw new AssertionError("El test no programó la respuesta del proveedor");
    };
    private Function<Invoice, InvoicePdf> pdfBehavior = invoice -> {
        throw new AssertionError("El test no programó el PDF del proveedor");
    };
    private Runnable duringCall = () -> { };
    private int calls;

    void willIssue(ElectronicDocument document) {
        behavior = invoice -> new IssuanceResult.Issued(document);
    }

    void willReject(String reason) {
        behavior = invoice -> new IssuanceResult.Rejected(reason);
    }

    void willBeUnavailable() {
        behavior = invoice -> {
            throw new ElectronicInvoiceProviderUnavailableException(invoice.id().value(), "timeout de prueba", null);
        };
    }

    void willReturnPdf(InvoicePdf pdf) {
        pdfBehavior = invoice -> pdf;
    }

    void pdfWillBeUnavailable() {
        pdfBehavior = invoice -> {
            throw new ElectronicInvoiceProviderUnavailableException(invoice.id().value(), "timeout de prueba", null);
        };
    }

    /** Ejecuta {@code action} mientras "esperamos" al proveedor (p. ej. otra petición que emite en paralelo). */
    void whileCalling(Runnable action) {
        duringCall = action;
    }

    int calls() {
        return calls;
    }

    @Override
    public IssuanceResult issue(Invoice invoice) {
        calls++;
        Runnable action = duringCall;
        duringCall = () -> { };
        action.run();
        return behavior.apply(invoice);
    }

    @Override
    public InvoicePdf downloadPdf(Invoice issuedInvoice) {
        calls++;
        return pdfBehavior.apply(issuedInvoice);
    }
}
