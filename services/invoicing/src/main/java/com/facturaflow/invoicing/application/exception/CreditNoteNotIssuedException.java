package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/** La operación exige una nota crédito ISSUED (p. ej. descargar su PDF) y la nota está en otro estado. */
public final class CreditNoteNotIssuedException extends RuntimeException {

    private final UUID creditNoteId;

    public CreditNoteNotIssuedException(UUID creditNoteId, String currentStatus) {
        super("La nota crédito " + creditNoteId + " está " + currentStatus + ": solo una nota ISSUED tiene "
                + "representación gráfica (PDF).");
        this.creditNoteId = creditNoteId;
    }

    public UUID creditNoteId() {
        return creditNoteId;
    }
}
