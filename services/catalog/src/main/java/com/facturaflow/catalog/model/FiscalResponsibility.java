package com.facturaflow.catalog.model;

/**
 * Responsabilidades fiscales del RUT (copia de invoicing). {@link #NOT_RESPONSIBLE} significa "ninguna
 * de las anteriores" y va siempre sola (lo valida {@link CustomerProfile}).
 */
public enum FiscalResponsibility {
    LARGE_TAXPAYER,
    SELF_WITHHOLDER,
    VAT_WITHHOLDING_AGENT,
    SIMPLE_TAX_REGIME,
    NOT_RESPONSIBLE
}
