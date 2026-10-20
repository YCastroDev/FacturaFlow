package com.facturaflow.invoicing.domain.model;

/** Ciclo de vida de una factura: PENDING -> ISSUED | REJECTED. Los estados finales no cambian. */
public enum InvoiceStatus {
    PENDING,
    ISSUED,
    REJECTED;

    public boolean canTransitionTo(InvoiceStatus target) {
        return this == PENDING && (target == ISSUED || target == REJECTED);
    }

    public boolean isFinal() {
        return this != PENDING;
    }
}
