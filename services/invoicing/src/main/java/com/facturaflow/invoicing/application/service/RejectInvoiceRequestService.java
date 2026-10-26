package com.facturaflow.invoicing.application.service;

import com.facturaflow.invoicing.application.port.in.RejectInvoiceRequestCommand;
import com.facturaflow.invoicing.application.port.in.RejectInvoiceRequestUseCase;
import com.facturaflow.invoicing.application.port.out.InvoiceEventStore;
import com.facturaflow.invoicing.domain.model.InvoiceEvent;
import java.util.Objects;

/** Guarda {@link InvoiceEvent.InvoiceRequestRejected} en el outbox (ADR 0015). Sin transacción aquí: la abre el store. */
public final class RejectInvoiceRequestService implements RejectInvoiceRequestUseCase {

    private final InvoiceEventStore events;

    public RejectInvoiceRequestService(InvoiceEventStore events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public void reject(RejectInvoiceRequestCommand command) {
        events.append(new InvoiceEvent.InvoiceRequestRejected(command.referenceCode(), command.sourceId(),
                command.sourceEventId(), command.reason()));
    }
}
