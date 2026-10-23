package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.GetCreditBalanceUseCase;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.CreditBalance;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import java.util.Objects;
import java.util.UUID;

/** Calcula el saldo acreditable de una factura con la misma regla que valida la creación de notas. */
public final class GetCreditBalanceService implements GetCreditBalanceUseCase {

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;

    public GetCreditBalanceService(InvoiceRepository invoices, CreditNoteRepository creditNotes) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.creditNotes = Objects.requireNonNull(creditNotes, "creditNotes");
    }

    @Override
    public CreditBalance forInvoice(UUID invoiceId) {
        InvoiceId id = new InvoiceId(invoiceId);
        Invoice invoice = invoices.findById(id).orElseThrow(() -> InvoiceNotFoundException.byId(invoiceId));
        return CreditBalance.of(invoice, creditNotes.findByInvoiceId(id));
    }
}
