package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.exception.InvoiceNotIssuedException;
import com.facturaflow.invoicing.application.port.in.GetInvoicePdfUseCase;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import java.util.Objects;
import java.util.UUID;

/** Lee la factura (su propia transacción corta) y pide el PDF al proveedor fuera de la base (ADR 0006). */
public final class GetInvoicePdfService implements GetInvoicePdfUseCase {

    private final InvoiceRepository repository;
    private final ElectronicInvoiceProvider provider;

    public GetInvoicePdfService(InvoiceRepository repository, ElectronicInvoiceProvider provider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public InvoicePdf pdf(UUID invoiceId) {
        Invoice invoice = repository.findById(new InvoiceId(invoiceId))
                .orElseThrow(() -> InvoiceNotFoundException.byId(invoiceId));
        if (invoice.status() != InvoiceStatus.ISSUED) {
            throw new InvoiceNotIssuedException(invoiceId, invoice.status().name());
        }
        return provider.downloadPdf(invoice);
    }
}
