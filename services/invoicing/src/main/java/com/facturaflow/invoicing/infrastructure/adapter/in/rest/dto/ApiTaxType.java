package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.TaxType;

/** Tipo de impuesto en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiTaxType {
    IVA,
    INC;

    public TaxType toDomain() {
        return switch (this) {
            case IVA -> TaxType.IVA;
            case INC -> TaxType.INC;
        };
    }

    public static ApiTaxType from(TaxType type) {
        return switch (type) {
            case IVA -> IVA;
            case INC -> INC;
        };
    }
}
