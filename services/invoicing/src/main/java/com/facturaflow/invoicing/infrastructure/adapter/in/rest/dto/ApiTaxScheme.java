package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.TaxScheme;

/** Responsabilidad tributaria en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiTaxScheme {
    IVA,
    NOT_APPLICABLE;

    public TaxScheme toDomain() {
        return switch (this) {
            case IVA -> TaxScheme.IVA;
            case NOT_APPLICABLE -> TaxScheme.NOT_APPLICABLE;
        };
    }

    public static ApiTaxScheme from(TaxScheme scheme) {
        return switch (scheme) {
            case IVA -> IVA;
            case NOT_APPLICABLE -> NOT_APPLICABLE;
        };
    }
}
