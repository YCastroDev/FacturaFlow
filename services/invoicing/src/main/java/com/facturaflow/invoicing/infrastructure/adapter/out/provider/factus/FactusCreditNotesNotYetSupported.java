package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.Invoice;

/**
 * TEMPORAL: las notas crédito con Factus todavía no están implementadas (llegan en la fase 3 del
 * ADR 0009). Mientras tanto, con {@code invoicing.provider=factus} el servicio arranca y las
 * notas se pueden crear, pero emitirlas o pedir su PDF es una falla técnica (503): la nota sigue
 * PENDING y nada llega a Factus.
 */
final class FactusCreditNotesNotYetSupported implements ElectronicCreditNoteProvider {

    private static final String DETAIL = "las notas crédito con Factus aún no están implementadas";

    @Override
    public IssuanceResult issue(CreditNote creditNote, Invoice creditedInvoice) {
        throw ElectronicInvoiceProviderUnavailableException.forCreditNote(creditNote.id().value(), DETAIL, null);
    }

    @Override
    public InvoicePdf downloadPdf(CreditNote issuedCreditNote) {
        throw ElectronicInvoiceProviderUnavailableException.forCreditNote(issuedCreditNote.id().value(), DETAIL, null);
    }
}
