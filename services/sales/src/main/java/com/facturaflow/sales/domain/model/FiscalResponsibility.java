package com.facturaflow.sales.domain.model;

/**
 * Responsabilidades fiscales del cliente según su RUT (ADR 0007). {@link #NOT_RESPONSIBLE}
 * significa "ninguna de las anteriores", así que no se combina con las demás
 * (lo valida {@link FiscalProfile}).
 */
public enum FiscalResponsibility {
    LARGE_TAXPAYER("gran contribuyente"),
    SELF_WITHHOLDER("autorretenedor"),
    VAT_WITHHOLDING_AGENT("agente de retención de IVA"),
    SIMPLE_TAX_REGIME("régimen simple de tributación"),
    NOT_RESPONSIBLE("no responsable");

    private final String description;

    FiscalResponsibility(String description) {
        this.description = description;
    }

    /** Nombre legible en español, para mensajes. */
    public String description() {
        return description;
    }
}
