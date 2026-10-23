package com.facturaflow.invoicing.application.exception;

/**
 * Ya existe una nota crédito con ese código de referencia, pero creada con un contenido distinto
 * (otra factura, motivo, líneas u observación): no es un reintento de la misma petición (ADR 0009).
 */
public final class CreditNoteReferenceCodeConflictException extends RuntimeException {

    private final String referenceCode;

    public CreditNoteReferenceCodeConflictException(String referenceCode) {
        super("Ya existe una nota crédito con el código de referencia '" + referenceCode
                + "' y un contenido distinto. Use otro código para una devolución diferente.");
        this.referenceCode = referenceCode;
    }

    public String referenceCode() {
        return referenceCode;
    }
}
