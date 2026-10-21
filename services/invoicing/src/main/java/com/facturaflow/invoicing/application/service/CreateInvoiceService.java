package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceUseCase;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredInvoice;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Crea facturas de forma idempotente por código de referencia (ADR 0006):
 * <ul>
 *   <li>código nuevo: crea la factura PENDING;</li>
 *   <li>código existente con la misma huella: devuelve la existente (reintento);</li>
 *   <li>código existente con otra huella: {@link ReferenceCodeConflictException}.</li>
 * </ul>
 * Si dos peticiones simultáneas pasan la consulta inicial, el UNIQUE de la base hace fallar
 * a la segunda; entonces se relee la ganadora y se aplica la misma regla.
 *
 * <p>La huella se resuelve ANTES de crear la factura: un reintento al día siguiente de una
 * venta a crédito no debe fallar porque el vencimiento ya no sea posterior a "hoy".
 */
public final class CreateInvoiceService implements CreateInvoiceUseCase {

    private final InvoiceRepository repository;
    private final Clock clock;

    public CreateInvoiceService(InvoiceRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreateInvoiceResult create(CreateInvoiceCommand command) {
        ReferenceCode referenceCode = new ReferenceCode(command.referenceCode());
        InvoiceContent content = InvoiceContentMapper.toContent(command);
        RequestHash requestHash = InvoiceRequestHasher.hash(content);

        Optional<StoredInvoice> existing = repository.findStoredByReferenceCode(referenceCode);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        Invoice invoice = Invoice.create(InvoiceId.generate(), referenceCode, content.customer(), content.lines(),
                content.paymentMethod(), content.paymentTerms(), LocalDate.now(clock), content.observation());
        try {
            return CreateInvoiceResult.created(repository.saveNew(invoice, requestHash));
        } catch (DuplicateReferenceCodeException race) {
            // Otra petición la creó entre nuestra consulta y nuestro INSERT.
            StoredInvoice winner = repository.findStoredByReferenceCode(referenceCode).orElseThrow(() -> race);
            return replayOrConflict(winner, requestHash);
        }
    }

    private static CreateInvoiceResult replayOrConflict(StoredInvoice existing, RequestHash requestHash) {
        if (existing.requestHash().equals(requestHash)) {
            return CreateInvoiceResult.replayed(existing.invoice());
        }
        throw new ReferenceCodeConflictException(existing.invoice().referenceCode().value());
    }
}
