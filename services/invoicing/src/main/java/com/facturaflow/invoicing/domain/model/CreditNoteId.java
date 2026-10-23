package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;
import java.util.UUID;

/** Identidad de la nota crédito. Es también el {@code reference_code} que recibe el proveedor (ADR 0009). */
public record CreditNoteId(UUID value) {

    public CreditNoteId {
        if (value == null) {
            throw InvalidCreditNoteException.missing("un identificador");
        }
    }

    /** Genera un id nuevo. El dominio lo recibe como parámetro; quien crea la nota decide cuándo generarlo. */
    public static CreditNoteId generate() {
        return new CreditNoteId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
