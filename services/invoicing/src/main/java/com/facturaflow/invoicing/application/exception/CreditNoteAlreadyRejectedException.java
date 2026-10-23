package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/** La nota crédito fue rechazada por el proveedor: es un estado final y no se puede volver a emitir. */
public final class CreditNoteAlreadyRejectedException extends RuntimeException {

    private final UUID creditNoteId;

    public CreditNoteAlreadyRejectedException(UUID creditNoteId) {
        super("La nota crédito " + creditNoteId + " fue rechazada y no se puede volver a emitir. Sus cantidades "
                + "quedaron libres: cree una nota nueva con otro código de referencia.");
        this.creditNoteId = creditNoteId;
    }

    public UUID creditNoteId() {
        return creditNoteId;
    }
}
