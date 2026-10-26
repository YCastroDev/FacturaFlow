package com.facturaflow.sales.infrastructure.adapter.in.rest.dto;

/** Responsabilidad fiscal del RUT del cliente. */
public enum ApiFiscalResponsibility {
    LARGE_TAXPAYER,
    SELF_WITHHOLDER,
    VAT_WITHHOLDING_AGENT,
    SIMPLE_TAX_REGIME,
    NOT_RESPONSIBLE
}
