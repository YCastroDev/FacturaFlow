package com.facturaflow.invoicing.domain.model;

import com.facturaflow.invoicing.domain.exception.InvalidCreditNoteException;

/**
 * Línea de una nota crédito: la línea de la factura que acredita (número desde 1) y una copia de
 * esa línea con la cantidad acreditada (ADR 0009). La copia es un snapshot, como el del cliente:
 * la factura emitida es inmutable, y así los montos salen exactamente del mismo cálculo y
 * redondeo de {@link InvoiceLine}, y la nota se basta a sí misma para enviarse al proveedor.
 */
public record CreditNoteLine(int invoiceLineNumber, InvoiceLine creditedLine) {

    public CreditNoteLine {
        if (invoiceLineNumber < 1) {
            throw InvalidCreditNoteException.invalidLineNumber(invoiceLineNumber);
        }
        if (creditedLine == null) {
            throw InvalidCreditNoteException.missing("los datos de la línea acreditada");
        }
    }

    public Quantity quantity() {
        return creditedLine.quantity();
    }
}
