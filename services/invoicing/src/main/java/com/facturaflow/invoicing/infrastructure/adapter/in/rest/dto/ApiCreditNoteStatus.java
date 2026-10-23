package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.CreditNoteStatus;

/** Estado de la nota crédito en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiCreditNoteStatus {
    PENDING,
    ISSUED,
    REJECTED;

    public static ApiCreditNoteStatus from(CreditNoteStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case ISSUED -> ISSUED;
            case REJECTED -> REJECTED;
        };
    }
}
