package com.facturaflow.invoicing.infrastructure.adapter.in.rest.dto;

import com.facturaflow.invoicing.domain.model.FiscalResponsibility;

/** Responsabilidad fiscal en el contrato de la API (ver {@link ApiDocumentType} sobre por qué es un enum propio). */
public enum ApiFiscalResponsibility {
    LARGE_TAXPAYER,
    SELF_WITHHOLDER,
    VAT_WITHHOLDING_AGENT,
    SIMPLE_TAX_REGIME,
    NOT_RESPONSIBLE;

    public FiscalResponsibility toDomain() {
        return switch (this) {
            case LARGE_TAXPAYER -> FiscalResponsibility.LARGE_TAXPAYER;
            case SELF_WITHHOLDER -> FiscalResponsibility.SELF_WITHHOLDER;
            case VAT_WITHHOLDING_AGENT -> FiscalResponsibility.VAT_WITHHOLDING_AGENT;
            case SIMPLE_TAX_REGIME -> FiscalResponsibility.SIMPLE_TAX_REGIME;
            case NOT_RESPONSIBLE -> FiscalResponsibility.NOT_RESPONSIBLE;
        };
    }

    public static ApiFiscalResponsibility from(FiscalResponsibility responsibility) {
        return switch (responsibility) {
            case LARGE_TAXPAYER -> LARGE_TAXPAYER;
            case SELF_WITHHOLDER -> SELF_WITHHOLDER;
            case VAT_WITHHOLDING_AGENT -> VAT_WITHHOLDING_AGENT;
            case SIMPLE_TAX_REGIME -> SIMPLE_TAX_REGIME;
            case NOT_RESPONSIBLE -> NOT_RESPONSIBLE;
        };
    }
}
