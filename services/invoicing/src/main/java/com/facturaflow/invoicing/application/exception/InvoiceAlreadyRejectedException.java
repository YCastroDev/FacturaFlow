package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/** La factura fue rechazada por el proveedor: es un estado final y no se puede volver a emitir. */
public final class InvoiceAlreadyRejectedException extends RuntimeException {

    private final UUID invoiceId;

    public InvoiceAlreadyRejectedException(UUID invoiceId) {
        super("La factura " + invoiceId + " fue rechazada y no se puede volver a emitir. "
                + "Corrija los datos y cree una factura nueva con otro código de referencia.");
        this.invoiceId = invoiceId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }
}
