package com.facturaflow.invoicing.application.port.in;

import com.facturaflow.invoicing.domain.model.CreditNote;
import java.util.Objects;

/**
 * Resultado de crear una nota crédito. {@code created} es {@code false} cuando la petición era un
 * reintento idéntico de una ya existente: se devuelve esa nota y no se crea otra.
 */
public record CreateCreditNoteResult(CreditNote creditNote, boolean created) {

    public CreateCreditNoteResult {
        Objects.requireNonNull(creditNote, "creditNote");
    }

    public static CreateCreditNoteResult created(CreditNote creditNote) {
        return new CreateCreditNoteResult(creditNote, true);
    }

    public static CreateCreditNoteResult replayed(CreditNote creditNote) {
        return new CreateCreditNoteResult(creditNote, false);
    }
}
