package com.facturaflow.invoicing.domain.model;

/**
 * Ciclo de vida de una nota crédito: PENDING -> ISSUED | REJECTED, igual que una factura. Los
 * estados finales no cambian. Una nota REJECTED no acredita nada (ADR 0009).
 */
public enum CreditNoteStatus {
    PENDING,
    ISSUED,
    REJECTED;

    public boolean canTransitionTo(CreditNoteStatus target) {
        return this == PENDING && (target == ISSUED || target == REJECTED);
    }

    /** Si la nota cuenta para el saldo de la factura: las PENDING sí, porque aún pueden emitirse. */
    public boolean countsTowardsBalance() {
        return this != REJECTED;
    }
}
