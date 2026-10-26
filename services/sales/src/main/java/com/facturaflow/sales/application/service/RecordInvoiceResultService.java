package com.facturaflow.sales.application.service;

import com.facturaflow.sales.application.port.in.RecordInvoiceResultCommand;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultOutcome;
import com.facturaflow.sales.application.port.in.RecordInvoiceResultUseCase;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.domain.exception.InvalidReferenceCodeException;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.InvoiceReference;
import com.facturaflow.sales.domain.model.InvoicingOutcome;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplica a la venta el resultado de su facturación (ADR 0015, D3). Las reglas, para que el estado final no dependa
 * del orden ni de las repeticiones:
 * <table>
 *   <tr><th>Venta</th><th>InvoiceIssued</th><th>InvoiceRejected / InvoiceRequestRejected</th></tr>
 *   <tr><td>CONFIRMED</td><td>→ INVOICED</td><td>→ INVOICE_FAILED</td></tr>
 *   <tr><td>INVOICED</td><td>misma factura: duplicado; otra: ignorar</td><td>ignorar (el fallo no deshace)</td></tr>
 *   <tr><td>INVOICE_FAILED</td><td>→ INVOICED (el éxito gana)</td><td>duplicado (se conserva el primer motivo)</td></tr>
 * </table>
 * Antes de aplicar se comprueba que el resultado sea de ESTA venta: invoicing publica TODAS sus facturas (también
 * las de su API REST, D1). Sin venta con ese {@code referenceCode}, o con otro documento de cliente (o, en una
 * petición rechazada, otro {@code saleId}): se ignora.
 *
 * <p>Sin transacción aquí: la lectura y el guardado (con bloqueo optimista) son operaciones del repositorio.
 */
public final class RecordInvoiceResultService implements RecordInvoiceResultUseCase {

    private final SaleRepository repository;

    public RecordInvoiceResultService(SaleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public RecordInvoiceResultOutcome record(RecordInvoiceResultCommand command) {
        Optional<Sale> found = findSale(command.referenceCode());
        if (found.isEmpty()) {
            return new RecordInvoiceResultOutcome.Ignored("No hay una venta con el código " + command.referenceCode()
                    + " (p. ej. una factura creada por la API de invoicing).");
        }
        Sale sale = found.get();
        Optional<String> foreign = notThisSale(sale, command);
        if (foreign.isPresent()) {
            return new RecordInvoiceResultOutcome.Ignored(foreign.get());
        }
        return switch (command) {
            case RecordInvoiceResultCommand.InvoiceIssued issued -> applyIssued(sale,
                    new InvoiceReference(issued.invoiceId(), issued.number(), issued.cufe()), issued.occurredAt());
            case RecordInvoiceResultCommand.InvoiceRejected rejected -> applyFailed(sale, rejected.reason(),
                    rejected.invoiceId(), rejected.occurredAt());
            case RecordInvoiceResultCommand.InvoiceRequestRejected rejected -> applyFailed(sale, rejected.reason(),
                    null, rejected.occurredAt());
        };
    }

    private RecordInvoiceResultOutcome applyIssued(Sale sale, InvoiceReference invoice, Instant at) {
        return switch (sale.status()) {
            case CONFIRMED, INVOICE_FAILED -> new RecordInvoiceResultOutcome.Applied(
                    repository.save(sale.invoiced(invoice, at)).status());
            case INVOICED -> {
                InvoiceReference current = ((InvoicingOutcome.Invoiced) sale.invoicing().orElseThrow()).invoice();
                yield current.invoiceId().equals(invoice.invoiceId())
                        ? new RecordInvoiceResultOutcome.Duplicate(sale.status())
                        : new RecordInvoiceResultOutcome.Ignored("La venta " + sale.referenceCode().value()
                                + " ya está facturada con la factura " + current.number() + "; se ignora la "
                                + invoice.number() + ".");
            }
        };
    }

    private RecordInvoiceResultOutcome applyFailed(Sale sale, String reason, UUID rejectedInvoiceId, Instant at) {
        return switch (sale.status()) {
            case CONFIRMED -> new RecordInvoiceResultOutcome.Applied(
                    repository.save(sale.invoiceFailed(reason, rejectedInvoiceId, at)).status());
            case INVOICE_FAILED -> new RecordInvoiceResultOutcome.Duplicate(sale.status());
            case INVOICED -> new RecordInvoiceResultOutcome.Ignored("La venta " + sale.referenceCode().value()
                    + " ya está facturada: un fallo tardío no deshace una factura emitida.");
        };
    }

    /** El motivo por el que el resultado NO es de esta venta, o vacío si sí lo es. */
    private static Optional<String> notThisSale(Sale sale, RecordInvoiceResultCommand command) {
        return switch (command) {
            case RecordInvoiceResultCommand.InvoiceIssued issued ->
                    otherCustomer(sale, issued.documentType(), issued.documentNumber());
            case RecordInvoiceResultCommand.InvoiceRejected rejected ->
                    otherCustomer(sale, rejected.documentType(), rejected.documentNumber());
            case RecordInvoiceResultCommand.InvoiceRequestRejected rejected ->
                    rejected.saleId() == null || rejected.saleId().equals(sale.id().value()) ? Optional.empty()
                            : Optional.of("La petición rechazada es de otra venta (" + rejected.saleId() + ").");
        };
    }

    private static Optional<String> otherCustomer(Sale sale, DocumentType type, String number) {
        CustomerSnapshot customer = sale.customer();
        boolean same = customer.documentType() == type && number != null
                && customer.documentNumber().equalsIgnoreCase(number.strip());
        return same ? Optional.empty() : Optional.of("La factura es de otro cliente (" + type + " " + number
                + ") que la venta " + sale.referenceCode().value() + ".");
    }

    private Optional<Sale> findSale(String referenceCode) {
        try {
            return repository.findByReferenceCode(new ReferenceCode(referenceCode));
        } catch (InvalidReferenceCodeException e) {
            return Optional.empty();   // un código que una venta no puede tener no es de ninguna venta
        }
    }
}
