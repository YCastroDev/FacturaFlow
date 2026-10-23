package com.facturaflow.catalog.web.dto;

/** Responsabilidad fiscal del RUT en la API; NOT_RESPONSIBLE va sola. */
public enum ApiFiscalResponsibility {
    LARGE_TAXPAYER,
    SELF_WITHHOLDER,
    VAT_WITHHOLDING_AGENT,
    SIMPLE_TAX_REGIME,
    NOT_RESPONSIBLE
}
