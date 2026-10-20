package com.facturaflow.invoicing.application.exception;

/** Ya existe una factura para esa venta: el código de referencia es la clave de idempotencia. */
public final class DuplicateReferenceCodeException extends RuntimeException {

    private final String referenceCode;

    public DuplicateReferenceCodeException(String referenceCode, Throwable cause) {
        super("Ya existe una factura con el código de referencia '" + referenceCode + "'.", cause);
        this.referenceCode = referenceCode;
    }

    public String referenceCode() {
        return referenceCode;
    }
}
