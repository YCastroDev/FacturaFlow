package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.GetInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.Objects;
import java.util.UUID;

public final class GetInvoiceService implements GetInvoiceUseCase {

    private final InvoiceRepository repository;

    public GetInvoiceService(InvoiceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Invoice byId(UUID invoiceId) {
        return repository.findById(new InvoiceId(invoiceId))
                .orElseThrow(() -> InvoiceNotFoundException.byId(invoiceId));
    }

    @Override
    public Invoice byReferenceCode(String referenceCode) {
        ReferenceCode code = new ReferenceCode(referenceCode);
        return repository.findByReferenceCode(code)
                .orElseThrow(() -> InvoiceNotFoundException.byReferenceCode(code.value()));
    }
}
