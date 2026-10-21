package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.IssuanceResult;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * Emite una factura a través del {@link ElectronicInvoiceProvider} (ADR 0006).
 *
 * <p><b>El proveedor se llama fuera de toda transacción.</b> Este servicio no es
 * transaccional (application es Java puro, ADR 0002): cada operación del repositorio es su
 * propia transacción corta. La secuencia es leer (tx 1) → llamar al proveedor (sin tx) →
 * aplicar el resultado al agregado → guardar (tx 2). Así una llamada lenta al proveedor no
 * retiene una conexión ni bloqueos de la base.
 *
 * <p><b>Emisiones simultáneas.</b> Si otra petición guardó la factura mientras esperábamos al
 * proveedor, el bloqueo optimista hace fallar nuestro {@code save}. Se relee: si la otra la
 * dejó ISSUED y a nosotros también nos la emitieron, el resultado es equivalente y se
 * devuelve la factura guardada (nuestro documento se descarta y se registra para
 * conciliación). En cualquier otro caso se propaga el conflicto.
 */
public final class IssueInvoiceService implements IssueInvoiceUseCase {

    private static final System.Logger LOG = System.getLogger(IssueInvoiceService.class.getName());

    private final InvoiceRepository repository;
    private final ElectronicInvoiceProvider provider;

    public IssueInvoiceService(InvoiceRepository repository, ElectronicInvoiceProvider provider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public Invoice issue(IssueInvoiceCommand command) {
        InvoiceId id = new InvoiceId(command.invoiceId());
        Invoice invoice = repository.findById(id).orElseThrow(() -> InvoiceNotFoundException.byId(id.value()));

        switch (invoice.status()) {
            case ISSUED -> {
                return invoice;
            }
            case REJECTED -> throw new InvoiceAlreadyRejectedException(id.value());
            case PENDING -> {
            }
        }

        // Una falla técnica sale de aquí como excepción: no se guarda nada y la factura sigue PENDING.
        IssuanceResult result = provider.issue(invoice);
        switch (result) {
            case IssuanceResult.Issued(var document) -> invoice.markAsIssued(document);
            case IssuanceResult.Rejected(var reason) -> invoice.markAsRejected(reason);
        }

        try {
            return repository.save(invoice);
        } catch (InvoiceConcurrentModificationException conflict) {
            return resolveConflict(id, result, conflict);
        }
    }

    private Invoice resolveConflict(InvoiceId id, IssuanceResult ours, InvoiceConcurrentModificationException conflict) {
        Invoice current = repository.findById(id).orElseThrow(() -> conflict);
        if (ours instanceof IssuanceResult.Issued(var discarded) && current.status() == InvoiceStatus.ISSUED) {
            String kept = current.electronicDocument().map(document -> document.number()).orElse("?");
            if (!kept.equals(discarded.number())) {
                LOG.log(Level.WARNING, "Emisión concurrente de la factura {0}: se conserva el documento {1} y se "
                        + "descarta el documento {2}. Conciliar con el proveedor.", id, kept, discarded.number());
            }
            return current;
        }
        throw conflict;
    }
}
