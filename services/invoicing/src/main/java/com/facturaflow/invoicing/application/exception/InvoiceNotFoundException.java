package com.facturaflow.invoicing.application.exception;

import java.util.UUID;

/** No existe una factura con ese identificador o código de referencia. */
public final class InvoiceNotFoundException extends RuntimeException {

    private InvoiceNotFoundException(String message) {
        super(message);
    }

    public static InvoiceNotFoundException byId(UUID invoiceId) {
        return new InvoiceNotFoundException("No existe una factura con id " + invoiceId + ".");
    }

    public static InvoiceNotFoundException byReferenceCode(String referenceCode) {
        return new InvoiceNotFoundException("No existe una factura con el código de referencia '"
                + referenceCode + "'.");
    }
}
