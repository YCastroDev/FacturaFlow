package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.CreditNoteReason;

/** Motivo de la nota crédito en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiCreditNoteReason {
    PARTIAL_RETURN,
    TOTAL_ANNULMENT;

    public CreditNoteReason toDomain() {
        return switch (this) {
            case PARTIAL_RETURN -> CreditNoteReason.PARTIAL_RETURN;
            case TOTAL_ANNULMENT -> CreditNoteReason.TOTAL_ANNULMENT;
        };
    }

    public static ApiCreditNoteReason from(CreditNoteReason reason) {
        return switch (reason) {
            case PARTIAL_RETURN -> PARTIAL_RETURN;
            case TOTAL_ANNULMENT -> TOTAL_ANNULMENT;
        };
    }
}
