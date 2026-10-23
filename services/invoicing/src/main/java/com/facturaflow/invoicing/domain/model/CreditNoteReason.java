package com.facturaflow.invoicing.domain.model;

/**
 * Motivo de la nota crédito (ADR 0009). Concepto propio: el código de la DIAN lo traduce el
 * adaptador del proveedor. Los ajustes de precio y los descuentos quedan fuera de alcance.
 */
public enum CreditNoteReason {
    /** Devolución de algunas líneas, o de parte de sus cantidades. */
    PARTIAL_RETURN,
    /** Anulación: acredita todo lo que aún no se ha acreditado de la factura. */
    TOTAL_ANNULMENT
}
