package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/**
 * La nota crédito cambió (o desapareció) desde que se leyó: guardar esta copia pisaría los
 * cambios de otro proceso. Hay que volver a leerla y reintentar la operación.
 */
public final class CreditNoteConcurrentModificationException extends RuntimeException {

    private final UUID creditNoteId;

    public CreditNoteConcurrentModificationException(UUID creditNoteId, Throwable cause) {
        super("La nota crédito " + creditNoteId + " fue modificada por otro proceso desde que se leyó.", cause);
        this.creditNoteId = creditNoteId;
    }

    public UUID creditNoteId() {
        return creditNoteId;
    }
}
