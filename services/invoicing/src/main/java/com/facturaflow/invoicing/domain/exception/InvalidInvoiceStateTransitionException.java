package com.facturaflow.invoicing.domain.exception;

import java.util.UUID;

public final class InvalidInvoiceStateTransitionException extends DomainException {

    public InvalidInvoiceStateTransitionException(UUID invoiceId, String currentStatus, String targetStatus) {
        super("La factura " + invoiceId + " está en estado " + currentStatus + " y no puede pasar a "
                + targetStatus + ". Solo una factura PENDING puede emitirse o rechazarse.");
    }
}
