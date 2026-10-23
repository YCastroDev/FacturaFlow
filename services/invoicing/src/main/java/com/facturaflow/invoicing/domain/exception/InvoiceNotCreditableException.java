package com.facturaflow.invoicing.domain.exception;

import java.util.UUID;

/**
 * Solo se acreditan facturas ISSUED (ADR 0009): una PENDING aún no existe ante la DIAN y una
 * REJECTED nunca existirá. Es un conflicto con el estado de la factura, no un dato inválido.
 */
public final class InvoiceNotCreditableException extends DomainException {

    private final UUID invoiceId;

    public InvoiceNotCreditableException(UUID invoiceId, String currentStatus) {
        super("La factura " + invoiceId + " está " + currentStatus + ": solo se pueden acreditar facturas ISSUED.");
        this.invoiceId = invoiceId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }
}
