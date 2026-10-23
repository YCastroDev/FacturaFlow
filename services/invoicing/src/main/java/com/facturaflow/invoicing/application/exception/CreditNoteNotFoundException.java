package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/** No existe una nota crédito con ese identificador. */
public final class CreditNoteNotFoundException extends RuntimeException {

    private final UUID creditNoteId;

    public CreditNoteNotFoundException(UUID creditNoteId) {
        super("No existe una nota crédito con id " + creditNoteId + ".");
        this.creditNoteId = creditNoteId;
    }

    public UUID creditNoteId() {
        return creditNoteId;
    }
}
