package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.InvoiceStatus;

/** Estado de la factura en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiInvoiceStatus {
    PENDING,
    ISSUED,
    REJECTED;

    public static ApiInvoiceStatus from(InvoiceStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case ISSUED -> ISSUED;
            case REJECTED -> REJECTED;
        };
    }
}
