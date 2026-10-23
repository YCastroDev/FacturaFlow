package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;

/**
 * Lo que se pide acreditar de una línea de la factura en una devolución parcial: su número
 * (desde 1, como se muestra en la factura) y la cantidad, que debe ser mayor que cero.
 */
public record CreditedQuantity(int invoiceLineNumber, Quantity quantity) {

    public CreditedQuantity {
        if (invoiceLineNumber < 1) {
            throw InvalidCreditNoteException.invalidLineNumber(invoiceLineNumber);
        }
        if (quantity == null) {
            throw InvalidCreditNoteException.missing("una cantidad en cada línea acreditada");
        }
    }
}
