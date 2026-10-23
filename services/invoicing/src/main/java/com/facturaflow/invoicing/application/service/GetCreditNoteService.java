package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.GetCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class GetCreditNoteService implements GetCreditNoteUseCase {

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;

    public GetCreditNoteService(InvoiceRepository invoices, CreditNoteRepository creditNotes) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.creditNotes = Objects.requireNonNull(creditNotes, "creditNotes");
    }

    @Override
    public CreditNote byId(UUID creditNoteId) {
        return creditNotes.findById(new CreditNoteId(creditNoteId))
                .orElseThrow(() -> new CreditNoteNotFoundException(creditNoteId));
    }

    /** Distingue "la factura no existe" (404) de "la factura no tiene notas" (lista vacía). */
    @Override
    public List<CreditNote> byInvoice(UUID invoiceId) {
        InvoiceId id = new InvoiceId(invoiceId);
        if (invoices.findById(id).isEmpty()) {
            throw InvoiceNotFoundException.byId(invoiceId);
        }
        return creditNotes.findByInvoiceId(id);
    }
}
