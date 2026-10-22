package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/** La operación exige una factura ISSUED (p. ej. descargar su PDF) y la factura está en otro estado. */
public final class InvoiceNotIssuedException extends RuntimeException {

    private final UUID invoiceId;

    public InvoiceNotIssuedException(UUID invoiceId, String currentStatus) {
        super("La factura " + invoiceId + " está " + currentStatus + ": solo una factura ISSUED tiene "
                + "representación gráfica (PDF).");
        this.invoiceId = invoiceId;
    }

    public UUID invoiceId() {
        return invoiceId;
    }
}
