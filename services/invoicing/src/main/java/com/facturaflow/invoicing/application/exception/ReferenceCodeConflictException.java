package com.facturaflow.invoicing.application.exception;

/**
 * Ya existe una factura con ese código de referencia, pero creada con un contenido distinto:
 * no es un reintento de la misma petición, sino otra venta que reutiliza el código (ADR 0006).
 */
public final class ReferenceCodeConflictException extends RuntimeException {

    private final String referenceCode;

    public ReferenceCodeConflictException(String referenceCode) {
        super("Ya existe una factura con el código de referencia '" + referenceCode
                + "' y un contenido distinto. Use otro código para una venta diferente.");
        this.referenceCode = referenceCode;
    }

    public String referenceCode() {
        return referenceCode;
    }
}
