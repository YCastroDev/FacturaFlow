package com.facturaflow.invoicing.application.exception;

/** Ya existe una nota crédito con ese código de referencia: es la clave de idempotencia (ADR 0009). */
public final class DuplicateCreditNoteReferenceCodeException extends RuntimeException {

    private final String referenceCode;

    public DuplicateCreditNoteReferenceCodeException(String referenceCode, Throwable cause) {
        super("Ya existe una nota crédito con el código de referencia '" + referenceCode + "'.", cause);
        this.referenceCode = referenceCode;
    }

    public String referenceCode() {
        return referenceCode;
    }
}
