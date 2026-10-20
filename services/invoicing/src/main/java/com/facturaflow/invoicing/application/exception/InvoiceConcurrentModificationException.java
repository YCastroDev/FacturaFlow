package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/**
 * La factura cambió (o desapareció) desde que se leyó: guardar esta copia pisaría
 * los cambios de otro proceso. Hay que volver a leerla y reintentar la operación.
 */
public final class InvoiceConcurrentModificationException extends RuntimeException {

    private final UUID invoiceId;

    public InvoiceConcurrentModificationException(UUID invoiceId, Throwable cause) {
        super("La factura " + invoiceId + " fue modificada por otro proceso desde que se leyó.", cause);
        this.invoiceId = invoiceId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }
}
