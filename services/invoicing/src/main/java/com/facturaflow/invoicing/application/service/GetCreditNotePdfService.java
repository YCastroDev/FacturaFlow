package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotIssuedException;
import com.facturaflow.invoicing.application.port.in.GetCreditNotePdfUseCase;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.CreditNoteStatus;
import java.util.Objects;
import java.util.UUID;

/** Lee la nota (su propia transacción corta) y pide el PDF al proveedor fuera de la base (ADR 0006). */
public final class GetCreditNotePdfService implements GetCreditNotePdfUseCase {

    private final CreditNoteRepository creditNotes;
    private final ElectronicCreditNoteProvider provider;

    public GetCreditNotePdfService(CreditNoteRepository creditNotes, ElectronicCreditNoteProvider provider) {
        this.creditNotes = Objects.requireNonNull(creditNotes, "creditNotes");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public InvoicePdf pdf(UUID creditNoteId) {
        CreditNote creditNote = creditNotes.findById(new CreditNoteId(creditNoteId))
                .orElseThrow(() -> new CreditNoteNotFoundException(creditNoteId));
        if (creditNote.status() != CreditNoteStatus.ISSUED) {
            throw new CreditNoteNotIssuedException(creditNoteId, creditNote.status().name());
        }
        return provider.downloadPdf(creditNote);
    }
}
