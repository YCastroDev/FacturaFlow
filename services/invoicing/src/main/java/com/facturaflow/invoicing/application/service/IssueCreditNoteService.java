package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.CreditNoteAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.CreditNoteNotFoundException;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteCommand;
import com.facturaflow.invoicing.application.port.in.IssueCreditNoteUseCase;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.CreditNoteStatus;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Emite una nota crédito con la misma secuencia que {@link IssueInvoiceService} (ADR 0006): leer
 * (tx 1) → llamar al proveedor (sin tx) → aplicar el resultado → guardar (tx 2). El proveedor
 * recibe también la factura, de la que salen el cliente y la referencia al documento original.
 *
 * <p>Emitir o rechazar una nota nunca aumenta lo acreditado, así que no toca la versión de la
 * factura. Una emisión simultánea de la misma nota se resuelve igual que en facturas.
 */
public final class IssueCreditNoteService implements IssueCreditNoteUseCase {

    private static final System.Logger LOG = System.getLogger(IssueCreditNoteService.class.getName());

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final ElectronicCreditNoteProvider provider;

    public IssueCreditNoteService(InvoiceRepository invoices, CreditNoteRepository creditNotes,
                                  ElectronicCreditNoteProvider provider) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.creditNotes = Objects.requireNonNull(creditNotes, "creditNotes");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public CreditNote issue(IssueCreditNoteCommand command) {
        CreditNoteId id = new CreditNoteId(command.creditNoteId());
        CreditNote creditNote = creditNotes.findById(id).orElseThrow(() -> new CreditNoteNotFoundException(id.value()));

        switch (creditNote.status()) {
            case ISSUED -> {
                return creditNote;
            }
            case REJECTED -> throw new CreditNoteAlreadyRejectedException(id.value());
            case PENDING -> {
            }
        }

        // La factura existe siempre: la base no permite una nota sin su factura (FK).
        Invoice invoice = invoices.findById(creditNote.invoiceId()).orElseThrow(() -> new IllegalStateException(
                "La nota crédito " + id + " referencia la factura inexistente " + creditNote.invoiceId()));

        // Una falla técnica sale de aquí como excepción: no se guarda nada y la nota sigue PENDING.
        IssuanceResult result = provider.issue(creditNote, invoice);
        switch (result) {
            case IssuanceResult.Issued(var document) -> creditNote.markAsIssued(document);
            case IssuanceResult.Rejected(var reason) -> creditNote.markAsRejected(reason);
        }

        try {
            return creditNotes.save(creditNote);
        } catch (CreditNoteConcurrentModificationException conflict) {
            return resolveConflict(id, result, conflict);
        }
    }

    private CreditNote resolveConflict(CreditNoteId id, IssuanceResult ours,
                                       CreditNoteConcurrentModificationException conflict) {
        CreditNote current = creditNotes.findById(id).orElseThrow(() -> conflict);
        if (ours instanceof IssuanceResult.Issued(var discarded) && current.status() == CreditNoteStatus.ISSUED) {
            String kept = current.electronicDocument().map(document -> document.number()).orElse("?");
            if (!kept.equals(discarded.number())) {
                LOG.log(Level.WARNING, "Emisión concurrente de la nota crédito {0}: se conserva el documento {1} y "
                        + "se descarta el documento {2}. Conciliar con el proveedor.", id, kept, discarded.number());
            }
            return current;
        }
        throw conflict;
    }
}
